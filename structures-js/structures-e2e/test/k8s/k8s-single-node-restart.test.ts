import { Continuum } from '@kinotic/continuum-client'
import { WebSocket } from 'ws'
import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import {
    getPodPlacements,
    scaleDeployment,
    deletePod
} from './segmentation-utils'

Object.assign(global, { WebSocket })

/**
 * Reproduces what happens to a connected client when the only server instance restarts.
 *
 * This is the small deployment case, and it is worse than losing one node of many: the sticky session
 * lives only in the Ignite cache on that one instance, so a restart wipes it, and the client's reconnect
 * arrives with a session id the new instance has never seen. Observed in production as a client that
 * hangs, with or without a request in flight, and never recovers.
 *
 * The assertions state what a client is entitled to expect, so this is red until the client is fixed
 * and stays as the check that it remains fixed. Each stage names the failure it isolates:
 *
 *   1. a request in flight when the instance dies must settle - reject - within a bounded time, not
 *      wait forever for a reply the dead instance can no longer send.
 *   2. the client must reconnect on its own once the instance is back, without the application
 *      calling disconnect and connect, which it has no way of knowing to do.
 *   3. a fresh request after the restart must succeed.
 *
 * Runs against the KinD cluster scaled to one replica, through the ingress at structures.local so the
 * reconnect has a stable address that survives the pod being replaced. Needs the mkcert root trusted:
 *   NODE_EXTRA_CA_CERTS="$(mkcert -CAROOT)/rootCA.pem"
 * and STRUCTURES_ECHO_SERVICE_ENABLED=true on the deployment, which the KinD values set.
 */
describe('K8s Single Node Restart Tests', () => {
    const context = process.env.K8S_CONTEXT || 'kind-structures-cluster'
    const namespace = process.env.K8S_NAMESPACE || 'default'
    const labelSelector = process.env.K8S_LABEL_SELECTOR || 'app=structures'
    const deployment = process.env.K8S_DEPLOYMENT || 'structures-server'
    const ingressHost = process.env.K8S_INGRESS_HOST || 'structures.local'
    const originalReplicas = parseInt(process.env.K8S_REPLICA_COUNT || '3')
    const enabled = process.env.K8S_TEST_ENABLED === 'true'

    const ECHO_SERVICE = 'org.kinotic.structures.api.services.echo.EchoService'
    // Long enough that the pod is gone well before the reply is due, short enough to stay under
    // the server side cap
    const IN_FLIGHT_DELAY_MS = 30_000
    // How long a request may legitimately take to settle once its instance is gone. Generous: it
    // covers heartbeat detection plus reconnect. Waiting past this is the hang.
    const SETTLE_BUDGET_MS = 180_000
    const RECOVERY_BUDGET_MS = 180_000

    beforeAll(async () => {
        if (!enabled) {
            console.log('K8s tests disabled. Set K8S_TEST_ENABLED=true to run these tests.')
            return
        }
        console.log(`Scaling ${deployment} to 1 replica for the single node case`)
        scaleDeployment(context, namespace, labelSelector, deployment, 1)
    }, 400_000)

    afterAll(async () => {
        if (!enabled) {
            return
        }
        try { await Continuum.disconnect(true) } catch { /* may already be dead, that is the point */ }
        console.log(`Restoring ${deployment} to ${originalReplicas} replicas`)
        scaleDeployment(context, namespace, labelSelector, deployment, originalReplicas)
    }, 400_000)

    it('does not hang an in-flight request and recovers when the only instance restarts', async () => {
        if (!enabled) {
            console.log('Test skipped: K8s tests not enabled')
            return
        }

        const [only] = getPodPlacements(context, namespace, labelSelector)
        expect(only, 'exactly one pod should be serving').toBeDefined()

        // Default connection: sticky session on, static credentials, which is how both structures
        // clients connect today
        console.log(`Connecting through ${ingressHost} to the single instance ${only.name}`)
        const connected = await Continuum.connect({
            host: ingressHost, port: 443, useSSL: true, maxConnectionAttempts: 5,
            connectHeaders: { login: 'admin', passcode: 'structures' }
        })
        expect(connected.sessionId, 'a sticky session should be established').toBeTruthy()

        const echo = Continuum.serviceProxy(ECHO_SERVICE)
        const before = await echo.invoke('echo', ['before-restart'])
        expect(before.instanceId, 'baseline call should be served').toBeTruthy()

        // Stage 1: put a request on the wire that the instance will hold, then take the instance away
        console.log(`Holding a ${IN_FLIGHT_DELAY_MS}ms request open, then deleting ${only.name}`)
        let settledAs: 'resolved' | 'rejected' | null = null
        let settledWith: unknown = null
        const inFlight = echo.invoke('echoAfter', ['in-flight', IN_FLIGHT_DELAY_MS])
            .then(v => { settledAs = 'resolved'; settledWith = v },
                  e => { settledAs = 'rejected'; settledWith = e })

        await sleep(2_000)
        deletePod(context, namespace, only.name)
        const deletedAt = Date.now()

        await Promise.race([inFlight, sleep(SETTLE_BUDGET_MS)])
        const settleMs = Date.now() - deletedAt

        expect(settledAs,
               `an in-flight request must settle within ${SETTLE_BUDGET_MS}ms of its instance dying; `
               + 'still pending means the client is waiting forever for a reply nothing can send '
               + '(no request timeout, and pending requests are not failed on reconnect)')
            .not.toBeNull()
        // A resolution here would mean the reply outran the pod deletion; report it rather than pass
        expect(settledAs,
               `the in-flight request resolved with ${JSON.stringify(settledWith)} - the pod was not gone `
               + 'before it replied, so the scenario was not exercised; raise IN_FLIGHT_DELAY_MS or check '
               + 'the delete took effect')
            .toBe('rejected')
        console.log(`In-flight request rejected after ${settleMs}ms: ${(settledWith as Error)?.message}`)

        // Stage 2: the instance comes back. The client must come back with it, unprompted.
        console.log('Waiting for the replacement pod, then for the client to reconnect on its own')
        scaleDeployment(context, namespace, labelSelector, deployment, 1)
        const replacement = getPodPlacements(context, namespace, labelSelector)[0]
        expect(replacement?.name, 'a replacement pod should be running').not.toBe(only.name)

        const recoveredAt = await waitUntil(() => Continuum.eventBus.isConnected(), RECOVERY_BUDGET_MS)
        expect(recoveredAt,
               `client should be connected again within ${RECOVERY_BUDGET_MS}ms of the instance returning; `
               + 'still disconnected means the reconnect presented a session id the new instance had never '
               + 'seen, was refused, and the client deactivated itself instead of re-authenticating')
            .not.toBeNull()

        // Stage 3: and it must actually work
        const after = await echo.invoke('echo', ['after-restart'])
        expect(after.message).toBe('after-restart')
        expect(after.instanceId, 'the call should be served by the new instance').not.toBe(before.instanceId)
        console.log(`Recovered: served by new instance ${after.instanceId}`)
    }, 900_000)
})

function sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms))
}

/** Resolve with the elapsed ms when the predicate becomes true, or null if the budget runs out. */
async function waitUntil(predicate: () => boolean, budgetMs: number, intervalMs = 2_000): Promise<number | null> {
    const start = Date.now()
    while (Date.now() - start < budgetMs) {
        if (predicate()) {
            return Date.now() - start
        }
        await sleep(intervalMs)
    }
    return null
}
