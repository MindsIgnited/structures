import { Page, Pageable } from '@kinotic/continuum-client'
import { FunctionDefinition, LongC3Type, ObjectC3Type, StringC3Type } from '@kinotic/continuum-idl'
import {
    IEntityService,
    NamedQueriesDefinition,
    PageableC3Type,
    PageC3Type,
    QueryDecorator,
    Structure,
    Structures
} from '@kinotic/structures-api'
import { ChildProcess, spawn } from 'node:child_process'
import { writeFileSync } from 'node:fs'
import { createInterface } from 'node:readline'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { WebSocket } from 'ws'
import { Person } from '../domain/Person.js'
import { createPersonStructureIfNotExist, createTestPeople, deleteStructure, generateRandomString } from '../TestHelpers.js'
import { K8sTestHelper } from './k8s-helper'
import { getPodIpAsync, kubectlGetAsync, restartStatefulPodAsync } from './segmentation-utils'

Object.assign(global, { WebSocket })

/**
 * Pages a named query, cursors included, from several workers while the coordinating Elasticsearch node restarts,
 * twice. That is the production incident behind this test: a coordinator came back on a new IP after patching and
 * every named query failed, because the client behind them only used the first configured connection.
 *
 * It asserts what a deployment is entitled to expect when one of its Elasticsearch nodes restarts:
 *   1. no named query fails, outside or (within a configurable budget) around each restart
 *   2. latency stays bounded, so failover costs at most a connect timeout, not a query timeout
 *   3. the server took the node out of the rotation and brought it back at its new address, so the run really
 *      exercised failover rather than never touching the restarted node
 *
 * Needs the KinD cluster deployed with `kind-cluster.sh deploy --with-es-coordinator`, which adds the coordinating
 * node and gives structures-server one connection per Elasticsearch node, coordinator first. Runs only with
 * K8S_TEST_ENABLED=true; it takes about 7 minutes. Tunables: CHAOS_WORKERS, CHAOS_STEADY_SECONDS,
 * CHAOS_RESTART_ERROR_BUDGET, CHAOS_P95_BOUND_MS and CHAOS_REPORT (a JSON file of per 5s buckets).
 */
describe('K8s Named Query Chaos Tests', () => {
    const k8s = new K8sTestHelper()
    const context = process.env.K8S_CONTEXT || 'kind-structures-cluster'
    const namespace = process.env.K8S_NAMESPACE || 'default'
    const enabled = k8s.isEnabled()

    const COORDINATOR_POD = process.env.CHAOS_ES_POD || 'elasticsearch-coordinating-0'
    const COORDINATOR_HOST = 'elasticsearch-coordinating-0.elasticsearch-coordinating-headless'
    const WORKERS = parseInt(process.env.CHAOS_WORKERS || '10')
    const WARMUP_SECONDS = 30
    // Long enough after the node is back for its one minute back-off to expire and the revival to land
    const STEADY_SECONDS = parseInt(process.env.CHAOS_STEADY_SECONDS || '120')
    // Failed iterations allowed while a restart is under way; none are allowed outside one
    const RESTART_ERROR_BUDGET = parseInt(process.env.CHAOS_RESTART_ERROR_BUDGET || '0')
    // An iteration is five page requests; a failover costs at most a connect timeout on top
    const P95_BOUND_MS = parseInt(process.env.CHAOS_P95_BOUND_MS || '2000')
    const REPORT_FILE = process.env.CHAOS_REPORT
    // How long after a restarted node is Ready its window stays open
    const WINDOW_GRACE_MS = 15_000

    const QUERY = 'countPeopleByLastNamePage'
    const PEOPLE = 200
    const PAGE_SIZE = 50

    let structure: Structure | null = null
    let entityService: IEntityService<Person>
    // Server log lines about the coordinating node, streamed while the test runs. Read afterwards they may be
    // gone: the KinD deployment logs at TRACE and the kubelet rotates each pod's log every few seconds
    const coordinatorLogLines: string[] = []
    const logStreams: ChildProcess[] = []

    beforeAll(async () => {
        if (!enabled) {
            console.log('K8s tests disabled. Set K8S_TEST_ENABLED=true to run these tests.')
            return
        }
        expect(await k8s.isClusterAccessible(), 'the KinD cluster should be reachable').toBe(true)
        await kubectlGetAsync(context, `pod ${COORDINATOR_POD} -n ${namespace}`).catch(() => {
            throw new Error(`No ${COORDINATOR_POD} pod: deploy with "kind-cluster.sh deploy --with-es-coordinator"`)
        })
        const firstConnection = await kubectlGetAsync(context,
            `configmap structures-server -n ${namespace} -o jsonpath={.data.STRUCTURES_ELASTICCONNECTIONS_0_HOST}`)
        expect(firstConnection, 'structures-server should list the coordinating node as its first connection; '
                                + 'deploy with "kind-cluster.sh deploy --with-es-coordinator"').toBe(COORDINATOR_HOST)

        await k8s.discoverPods()
        for (const pod of k8s.getPodNames()) {
            const stream = spawn('kubectl', ['--context', context, 'logs', '-f', '--since=1s', '-n', namespace, pod])
            createInterface({ input: stream.stdout! }).on('line', line => {
                if (line.includes(COORDINATOR_HOST)) {
                    coordinatorLogLines.push(`${pod} ${line}`)
                }
            })
            logStreams.push(stream)
        }
        await k8s.connectToPod(0)

        const applicationId = 'chaos' + generateRandomString(6)
        structure = await createPersonStructureIfNotExist(applicationId, 'chaos' + generateRandomString(4))
        entityService = Structures.createEntityService(structure.applicationId, structure.name)

        const people = createTestPeople(PEOPLE)
        people.forEach((person, i) => person.lastName = `Last${i}`)
        await entityService.bulkSave(people)
        await entityService.syncIndex()

        const query = new QueryDecorator(`SELECT COUNT(firstName) as count, lastName FROM "struct_${entityService.structureId}" GROUP BY lastName`)
        const namedQuery = new FunctionDefinition(QUERY, [query])
        namedQuery.addParameter('pageable', new PageableC3Type())
        namedQuery.returnType = new PageC3Type(new ObjectC3Type('CountByLastName', applicationId)
                                                   .addProperty('count', new LongC3Type())
                                                   .addProperty('lastName', new StringC3Type()))
        await Structures.getNamedQueriesService().save(new NamedQueriesDefinition(entityService.structureId,
                                                                                  applicationId,
                                                                                  structure.projectId,
                                                                                  entityService.structureName,
                                                                                  [namedQuery]))
    }, 300_000)

    afterAll(async () => {
        if (!enabled) {
            return
        }
        try {
            if (structure) {
                await deleteStructure(structure.id as string)
                await Structures.getStructureService().syncIndex()
                await Structures.getProjectService().deleteById(structure.projectId)
                await Structures.getProjectService().syncIndex()
                await Structures.getApplicationService().deleteById(structure.applicationId)
            }
        } finally {
            logStreams.forEach(stream => stream.kill())
            await k8s.disconnectFromPod()
            await k8s.stopPortForwards()
        }
    }, 120_000)

    it('keeps named queries working while the coordinating Elasticsearch node restarts', async () => {
        if (!enabled) {
            return
        }

        interface Iteration { at: number, ms: number, error?: string }
        const iterations: Iteration[] = []
        const windows: { from: number, to: number }[] = []
        const start = Date.now()

        // One iteration pages through every group, following each cursor
        const iterate = async () => {
            const began = Date.now()
            try {
                let cursor: string | null = null
                let rows = 0
                let pages = 0
                do {
                    const page: Page<{ count: number, lastName: string }> =
                        await entityService.namedQueryPage(QUERY, [], Pageable.createWithCursor(cursor, PAGE_SIZE))
                    rows += page.content?.length ?? 0
                    cursor = page.cursor ?? null
                    pages++
                } while (cursor !== null && pages < 20)
                if (rows !== PEOPLE) {
                    throw new Error(`expected ${PEOPLE} groups, got ${rows}`)
                }
                iterations.push({ at: began, ms: Date.now() - began })
            } catch (e: any) {
                iterations.push({ at: began, ms: Date.now() - began, error: String(e?.message ?? e).slice(0, 160) })
            }
        }

        let running = true
        const workers = Array.from({ length: WORKERS }, async () => {
            while (running) {
                await iterate()
            }
        })

        let reported = 0
        const report = setInterval(() => {
            const until = Math.floor((Date.now() - start) / 5000) * 5000
            for (let t = reported; t < until; t += 5000) {
                const bucket = iterations.filter(i => i.at - start >= t && i.at - start < t + 5000)
                const failed = bucket.filter(i => i.error)
                const sorted = bucket.map(i => i.ms).sort((a, b) => a - b)
                const p95 = sorted.length ? sorted[Math.floor(sorted.length * 0.95)] : 0
                console.log(`[chaos] t=${t / 1000}s ok=${bucket.length - failed.length} failed=${failed.length} p95=${p95}ms`
                            + (failed.length ? ` ${failed[0].error}` : ''))
            }
            reported = until
        }, 1000)

        const sleep = (seconds: number) => new Promise(resolve => setTimeout(resolve, seconds * 1000))
        try {
            await sleep(WARMUP_SECONDS)
            for (let restart = 1; restart <= 2; restart++) {
                const ipBefore = await getPodIpAsync(context, namespace, COORDINATOR_POD)
                const from = Date.now()
                const tookMs = await restartStatefulPodAsync(context, namespace, COORDINATOR_POD)
                windows.push({ from, to: Date.now() + WINDOW_GRACE_MS })
                const ipAfter = await getPodIpAsync(context, namespace, COORDINATOR_POD)
                console.log(`[chaos] restart ${restart}: ${COORDINATOR_POD} back in ${Math.round(tookMs / 1000)}s, `
                            + `IP ${ipBefore} -> ${ipAfter}`)
                await sleep(STEADY_SECONDS)
            }
        } finally {
            running = false
            await Promise.all(workers)
            clearInterval(report)
        }

        const inWindow = (i: Iteration) => windows.some(w => i.at >= w.from && i.at <= w.to)
        const failed = iterations.filter(i => i.error)
        const failedOutside = failed.filter(i => !inWindow(i))
        const failedInside = failed.filter(inWindow)
        const sorted = iterations.map(i => i.ms).sort((a, b) => a - b)
        const p95 = sorted[Math.floor(sorted.length * 0.95)]
        console.log(`[chaos] ${iterations.length} iterations, ${failed.length} failed `
                    + `(${failedInside.length} during a restart), p95 ${p95}ms, max ${sorted[sorted.length - 1]}ms`)
        if (REPORT_FILE) {
            writeFileSync(REPORT_FILE, JSON.stringify({ windows, iterations }, null, 2))
        }

        const benched = coordinatorLogLines.filter(l => l.includes('out of the rotation')).length
        const revived = coordinatorLogLines.filter(l => l.includes('back in the rotation')).length
        coordinatorLogLines.filter(l => l.includes(' WARN ') || l.includes(' INFO '))
                           .forEach(l => console.log(`[chaos] log: ${l.slice(0, 260)}`))

        expect(iterations.length, 'named queries should have run throughout').toBeGreaterThan(WORKERS * 10)
        expect(failedOutside.map(i => i.error), 'no named query should fail outside a restart').toEqual([])
        expect(failedInside.length, `failed iterations during restarts: ${JSON.stringify(failedInside.map(i => i.error))}`)
            .toBeLessThanOrEqual(RESTART_ERROR_BUDGET)
        expect(p95, 'p95 iteration latency').toBeLessThanOrEqual(P95_BOUND_MS)
        expect(benched, 'the restarted node should have been taken out of the rotation').toBeGreaterThan(0)
        expect(revived, 'the restarted node should have been brought back at its new address').toBeGreaterThan(0)
    }, 900_000)
})
