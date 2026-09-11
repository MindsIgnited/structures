import { Structures, IEntityService } from '@kinotic/structures-api'
import { WebSocket } from 'ws'
import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { createVehicleStructure, createTestVehicles } from '../TestHelpers'
import { Vehicle } from '../domain/Vehicle'
import { K8sTestHelper } from './k8s-helper'
import {
    getPodPlacements,
    segmentPod,
    healPod,
    healPodOrThrow,
    restartPod,
    getPodLogs,
    type PodPlacement
} from './segmentation-utils'

Object.assign(global, { WebSocket })

/**
 * Validates how the cluster behaves when one node ends up isolated in its own single node cluster.
 *
 * This has been seen once in production, most likely a pod restarting during a node rollover and
 * coming up when the Ignite headless service reported no other endpoints. Ignite treats that as a
 * healthy new cluster: no EVT_NODE_SEGMENTED fires, the failure handler never halts the JVM, and the
 * pod keeps serving against isolated state. Auto restart is deliberately off, so the whole value of
 * that situation is that the monitor reports it loudly enough to alert on and that the isolated pod
 * keeps serving its own traffic until somebody restarts it.
 *
 * Three properties are asserted, in the order they matter operationally:
 *   1. an isolated pod still accepts and completes real requests. This is what continuum's local
 *      delivery preference buys - service RPC that would otherwise round robin onto nodes the pod
 *      can no longer reach stays local instead.
 *   2. the observer reports the condition, so there is something to build a monitor on.
 *   3. restarting the pod rejoins the running cluster, so the manual remediation actually works.
 *
 * Requires the KinD cluster from ./dev-tools/kind/kind-cluster.sh with 3 replicas, and runs only
 * when K8S_TEST_ENABLED=true.
 */
describe('K8s Cluster Segmentation Tests', () => {
    let k8s: K8sTestHelper
    let placements: PodPlacement[] = []
    let target: PodPlacement | undefined
    const context = process.env.K8S_CONTEXT || 'kind-structures-cluster'
    const namespace = process.env.K8S_NAMESPACE || 'default'
    const labelSelector = process.env.K8S_LABEL_SELECTOR || 'app=structures'

    beforeAll(async () => {
        // vitest runs test files in parallel processes and K8sTestHelper port forwards to a fixed
        // local range, so each k8s file needs its own base or two runs fight over the same ports and
        // the connection drops mid test
        process.env.K8S_STARTING_LOCAL_PORT = process.env.K8S_STARTING_LOCAL_PORT || '58531'
        k8s = new K8sTestHelper()
        if (!k8s.isEnabled()) {
            console.log('K8s tests disabled. Set K8S_TEST_ENABLED=true to run these tests.')
            return
        }
        if (!(await k8s.isClusterAccessible())) {
            throw new Error('Kubernetes cluster is not accessible')
        }
        await k8s.discoverPods()
        placements = getPodPlacements(context, namespace, labelSelector)
        expect(placements.length).toBeGreaterThanOrEqual(3)
    }, 180000)

    afterAll(async () => {
        if (!k8s.isEnabled()) {
            return
        }
        // Unconditional: a failed assertion must never leave a KinD node firewalled
        if (target) {
            healPod(target.node)
        }
        await k8s.stopPortForwards()
    }, 120000)

    it('keeps serving traffic while isolated, reports the condition, and rejoins on restart', async () => {
        if (!k8s.isEnabled()) {
            console.log('Test skipped: K8s tests not enabled')
            return
        }

        const applicationId = 'k8sseg-' + Date.now()
        const structureName = 'vehicle'

        // Step 1: create a structure while the cluster is whole, so the isolated pod already knows it
        console.log('Step 1 - creating structure on a healthy cluster')
        await k8s.connectToPod(0)
        const savedStructure = await createVehicleStructure(applicationId, 'SegmentationTest')
        expect(savedStructure).toBeDefined()
        await k8s.disconnectFromPod()

        // Step 2: isolate the last pod and restart it so it comes up alone
        target = placements[placements.length - 1]
        const peers = placements.filter(p => p.name !== target!.name)
        console.log(`Step 2 - isolating ${target.name} on node ${target.node} from ${peers.map(p => p.name).join(', ')}`)
        segmentPod(target, peers)
        restartPod(context, namespace, labelSelector, target.name, placements.length)

        // The pod name changes on restart, so re-read placements and re-point the helper
        placements = getPodPlacements(context, namespace, labelSelector)
        const restarted = placements.find(p => p.node === target!.node && !peers.some(peer => peer.name === p.name))
        // The rules are tied to the node they were written on, so a replacement scheduled elsewhere is
        // not isolated at all. Nothing in the Deployment pins it there, so this is checked rather than
        // assumed: a rejoined pod must not be mistaken for an isolated one.
        expect(restarted,
               `replacement should be scheduled on ${target!.node}; anywhere else and the isolation `
               + 'rules do not apply to it')
            .toBeDefined()
        target = restarted!
        await k8s.discoverPods()
        const targetIndex = k8s.getPodNames().indexOf(target.name)
        expect(targetIndex, 'restarted pod should be in the helper pod list').toBeGreaterThanOrEqual(0)

        // Step 3: the isolated pod must still accept and complete real work.
        // Without continuum preferring local delivery most of these would be dispatched to peers it
        // cannot reach, so sustained success here is the property under test rather than a smoke check.
        console.log(`Step 3 - driving traffic against the isolated pod ${target.name}`)
        await k8s.connectToPod(targetIndex)
        const entityService: IEntityService<Vehicle> = Structures.createEntityService(applicationId, structureName)
        const vehicles = createTestVehicles(10)
        for (const vehicle of vehicles) {
            const saved = await entityService.save(vehicle)
            expect(saved).toBeDefined()
            const retrieved = await entityService.findById(saved.id!)
            expect(retrieved?.id).toBe(saved.id)
        }
        await k8s.disconnectFromPod()

        // Step 4: the observer has to surface it, or there is nothing to alert on.
        // Thresholds are shortened in dev-tools/kind/config/structures-server/values.yaml; production
        // waits 5m to warn and 15m to escalate so a slow start is never reported as a split.
        console.log('Step 4 - waiting for the observer to report the isolated topology')
        const report = await waitFor(
            () => {
                const logs = getPodLogs(context, namespace, target!.name)
                // "not yet reached" is the still-forming warning and must not satisfy this
                return /below the minimum|never reached the minimum/i.test(logs) ? logs : null
            },
            180000,
            5000
        )
        expect(report, 'observer should report the isolated topology').not.toBeNull()

        // Step 5: peers are still a majority (2 of 3), so they must stay healthy and must not
        // report a split of their own. A monitor that fires on the healthy side is not usable.
        console.log('Step 5 - checking the surviving pods stay healthy and quiet')
        for (const peer of peers) {
            const peerIndex = k8s.getPodNames().indexOf(peer.name)
            if (peerIndex < 0) {
                continue
            }
            await k8s.connectToPod(peerIndex)
            const peerService: IEntityService<Vehicle> = Structures.createEntityService(applicationId, structureName)
            const saved = await peerService.save(createTestVehicles(1)[0])
            expect(saved).toBeDefined()
            await k8s.disconnectFromPod()

            expect(
                /never reached the minimum/i.test(getPodLogs(context, namespace, peer.name)),
                `${peer.name} is in a 2 node majority and must not report a split`
            ).toBe(false)
        }

        // Step 6: the documented remediation is a restart. It has to actually rejoin.
        console.log('Step 6 - healing the network and restarting to rejoin')
        // Load bearing here, unlike the afterAll cleanup: the pod cannot rejoin while the rules stand
        healPodOrThrow(target.node)
        restartPod(context, namespace, labelSelector, target.name, placements.length)

        placements = getPodPlacements(context, namespace, labelSelector)
        const rejoined = placements.find(p => p.node === target!.node && !peers.some(peer => peer.name === p.name))
        expect(rejoined, 'rejoined pod should be discoverable').toBeDefined()
        target = rejoined!

        const armed = await waitFor(
            () => {
                const logs = getPodLogs(context, namespace, target!.name)
                // Anchored: "reached the minimum" on its own also matches the warning a pod logs
                // while it is still isolated, which would pass this on a pod that never rejoined
                return /Cluster observer armed|topology reached the minimum/i.test(logs) ? logs : null
            },
            180000,
            5000
        )
        expect(armed, 'restarted pod should rejoin and arm the observer').not.toBeNull()

        // And it serves traffic again as a member of the whole cluster
        await k8s.discoverPods()
        const rejoinedIndex = k8s.getPodNames().indexOf(target.name)
        if (rejoinedIndex >= 0) {
            await k8s.connectToPod(rejoinedIndex)
            const rejoinedService: IEntityService<Vehicle> = Structures.createEntityService(applicationId, structureName)
            expect(await rejoinedService.save(createTestVehicles(1)[0])).toBeDefined()
            await k8s.disconnectFromPod()
        }
    }, 900000)
})

/**
 * Poll until the supplier returns a non-null value or the budget runs out. Returns null on timeout
 * rather than throwing, so the caller's expect() reports what was actually being waited for.
 */
async function waitFor<T>(supplier: () => T | null, timeoutMs: number, intervalMs: number): Promise<T | null> {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
        const value = supplier()
        if (value !== null) {
            return value
        }
        await new Promise(resolve => setTimeout(resolve, intervalMs))
    }
    return null
}
