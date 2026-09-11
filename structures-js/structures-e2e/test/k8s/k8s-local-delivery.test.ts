import { Continuum } from '@kinotic/continuum-client'
import { WebSocket } from 'ws'
import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { K8sTestHelper } from './k8s-helper'
import { countInPodLogs } from './segmentation-utils'

Object.assign(global, { WebSocket })

/**
 * Asserts that a pod which receives a request serves it itself rather than dispatching it to another
 * node in the cluster.
 *
 * Service RPC travels over the clustered event bus addressed to the service's base resource, and the
 * point to point selector round robins across every node registered for that address. On a three node
 * cluster that sent roughly two thirds of a pod's own calls to a peer for no reason. Continuum now
 * sets localOnly on service sends whose base resource is hosted locally, so those calls stay put.
 *
 * The segmentation test covers this indirectly - an isolated pod keeps working, so its calls must be
 * local - but only while its peers are unreachable. This asserts the preference is active during
 * normal operation on a whole, healthy cluster, which is the case that actually runs in production.
 *
 * ClusterInfo.localNodeId reports the Ignite node that executed the call rather than the node the
 * caller connected to, which is what makes the difference observable from a client.
 */
describe('K8s Local Delivery Tests', () => {
    let k8s: K8sTestHelper
    const CALLS_PER_POD = 25
    const CLUSTER_INFO_SERVICE = 'org.kinotic.structures.api.services.cluster.ClusterInfoService'
    // Logged by DefaultClusterInfoService on the node that runs the call. Needs the TRACE level set
    // in dev-tools/kind/config/structures-server/values.yaml
    const EXECUTION_MARKER = 'Returning cluster info'
    const context = process.env.K8S_CONTEXT || 'kind-structures-cluster'
    const namespace = process.env.K8S_NAMESPACE || 'default'

    beforeAll(async () => {
        k8s = new K8sTestHelper()
        if (!k8s.isEnabled()) {
            console.log('K8s tests disabled. Set K8S_TEST_ENABLED=true to run these tests.')
            return
        }
        if (!(await k8s.isClusterAccessible())) {
            throw new Error('Kubernetes cluster is not accessible')
        }
        await k8s.discoverPods()
    }, 180000)

    afterAll(async () => {
        if (!k8s.isEnabled()) {
            return
        }
        await k8s.disconnectFromPod()
        await k8s.stopPortForwards()
    }, 60000)

    it('serves every request on the pod that received it', async () => {
        if (!k8s.isEnabled()) {
            console.log('Test skipped: K8s tests not enabled')
            return
        }

        const podNames = k8s.getPodNames()
        expect(podNames.length).toBeGreaterThanOrEqual(3)

        const servingNodeByPod = new Map<string, string>()

        for (let podIndex = 0; podIndex < podNames.length; podIndex++) {
            // Attribute the work from the servers themselves, not just from what the response says
            const executionsBefore = new Map(
                podNames.map(name => [name, countInPodLogs(context, namespace, name, EXECUTION_MARKER)])
            )

            await k8s.connectToPod(podIndex)
            const proxy = Continuum.serviceProxy(CLUSTER_INFO_SERVICE)

            const servingNodes = new Set<string>()
            for (let call = 0; call < CALLS_PER_POD; call++) {
                const info = await proxy.invoke('getClusterInfo', [])
                expect(info, 'cluster info should be returned').toBeDefined()

                // Whole cluster, so a local result is a real preference rather than the only option
                expect(
                    info.serverNodeCount,
                    'cluster must be whole for this assertion to mean anything'
                ).toBe(podNames.length)

                expect(info.localNodeId, 'serving node should be identified').toBeTruthy()
                servingNodes.add(info.localNodeId)
            }

            // Round robin across n nodes would scatter these; landing on one node every time is the
            // property under test. At 25 calls across 3 nodes, doing this by chance is about 1 in 10^12.
            expect(
                Array.from(servingNodes),
                `${podNames[podIndex]} should serve all ${CALLS_PER_POD} of its own calls locally`
            ).toHaveLength(1)

            const servingNode = servingNodes.values().next().value as string
            console.log(`${podNames[podIndex]} served all ${CALLS_PER_POD} calls on node ${servingNode}`)
            servingNodeByPod.set(podNames[podIndex], servingNode)

            await k8s.disconnectFromPod()

            // Same claim, independently evidenced: the pod that was called logs every execution and
            // no other pod logs any. A payload can only report where it was built; this is the record
            // of who did the work.
            for (const name of podNames) {
                const executed = countInPodLogs(context, namespace, name, EXECUTION_MARKER)
                                 - (executionsBefore.get(name) ?? 0)
                if (name === podNames[podIndex]) {
                    expect(executed, `${name} should have executed all ${CALLS_PER_POD} of its own calls`)
                        .toBeGreaterThanOrEqual(CALLS_PER_POD)
                } else {
                    expect(executed, `${name} should not have executed any of ${podNames[podIndex]}'s calls`)
                        .toBe(0)
                }
            }
        }

        // Each pod pinning to a different node is what distinguishes "served locally" from
        // "the whole cluster happens to route everything to one node"
        const distinctNodes = new Set(servingNodeByPod.values())
        expect(
            distinctNodes.size,
            'each pod should serve on its own node, not share one'
        ).toBe(podNames.length)
    }, 600000)
})
