import { execSync } from 'child_process'

/**
 * Utilities for reproducing the split brain condition Ignite cannot detect on its own.
 *
 * The production trigger is a pod restarting during a node rollover, coming up when the Ignite
 * headless service reports no other endpoints, and forming its own single node cluster. Ignite
 * considers that a healthy new cluster, so no EVT_NODE_SEGMENTED fires, the failure handler never
 * halts the JVM, and the pod serves traffic against isolated state until somebody notices.
 *
 * Here that state is reached a different way: Ignite traffic to and from the pod is dropped at the
 * KinD node hosting it, so on restart it cannot reach its peers and forms its own cluster. The
 * trigger differs from production, the resulting state - a running, serving, singleton cluster that
 * believes it is healthy - is the same, and that state is what the tests assert on.
 *
 * Dropping happens on the KinD node container rather than inside the pod because the pod has no
 * NET_ADMIN, and via iptables rather than a NetworkPolicy because KinD's default CNI (kindnet)
 * does not enforce NetworkPolicy.
 */

const IGNITE_PORTS = [47500, 47100]
const CHAIN_COMMENT = 'structures-segmentation-test'

function kubectl(context: string, args: string): string {
    return execSync(`kubectl --context ${context} ${args}`, { encoding: 'utf-8' }).trim()
}

function dockerExec(node: string, command: string): string {
    return execSync(`docker exec ${node} ${command}`, { encoding: 'utf-8' }).trim()
}

export interface PodPlacement {
    name: string
    ip: string
    node: string
    /** Pod CIDR of the hosting node. Rules are written against this rather than the pod IP,
     *  because a restarted pod comes back with a new IP and IP based rules would silently miss it. */
    podCidr: string
}

/**
 * Pod name, pod IP and hosting KinD node for every structures pod that is Running and Ready, in the
 * order kubectl returns them. Pending, crash looping and terminating pods are excluded - the last of
 * those by deletionTimestamp, since a pod keeps phase Running and ready true until its container
 * actually stops - so callers counting these are counting replicas that can actually serve.
 */
export function getPodPlacements(context: string, namespace: string, labelSelector: string): PodPlacement[] {
    const cidrByNode = getNodePodCidrs(context)
    const output = kubectl(
        context,
        `get pods -n ${namespace} -l ${labelSelector} ` +
        `--field-selector=status.phase=Running ` +
        `-o jsonpath='{range .items[?(@.status.containerStatuses[0].ready==true)]}` +
        `{.metadata.name}{" "}{.status.podIP}{" "}{.spec.nodeName}{" "}` +
        `{.metadata.deletionTimestamp}{"\\n"}{end}'`
    )
    return output
        .split('\n')
        .map(line => line.trim())
        .filter(line => line.length > 0)
        .map(line => line.split(/\s+/))
        // A terminating pod keeps phase Running and ready true for the whole grace period, so it has
        // to be excluded explicitly or a replica on its way out still counts as serving
        .filter(parts => parts.length < 4 || !parts[3])
        .map(([name, ip, node]) => ({ name, ip, node, podCidr: cidrByNode.get(node) ?? '' }))
}

/** Pod CIDR for each node, which is what segmentation rules are written against. */
export function getNodePodCidrs(context: string): Map<string, string> {
    const output = kubectl(
        context,
        `get nodes -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.spec.podCIDR}{"\\n"}{end}'`
    )
    const result = new Map<string, string>()
    for (const line of output.split('\n')) {
        const [node, cidr] = line.trim().split(/\s+/)
        if (node && cidr) {
            result.set(node, cidr)
        }
    }
    return result
}

/**
 * Drop Ignite discovery and communication traffic between one pod's node and its peers' nodes, in
 * both directions.
 *
 * Rules match on node pod CIDRs rather than pod IPs on purpose: the pod under test is restarted while
 * segmented and comes back with a different IP, so IP based rules would stop matching exactly when
 * they are needed and the pod would quietly rejoin.
 *
 * Rules are tagged with a comment so {@link healPod} can remove exactly what was added and nothing
 * else, which matters because a failed test must not leave a KinD node quietly firewalled.
 */
export function segmentPod(target: PodPlacement, peers: PodPlacement[]): void {
    const peerCidrs = [...new Set(peers.map(peer => peer.podCidr).filter(cidr => cidr.length > 0))]
    if (!target.podCidr || peerCidrs.length === 0) {
        throw new Error('Cannot segment without pod CIDRs; getPodPlacements did not resolve them')
    }
    // Rules are per node, so a peer sharing the target's node would be cut off by the same rules and
    // the test would be measuring two isolated pods rather than one
    if (peerCidrs.includes(target.podCidr)) {
        throw new Error(`Cannot isolate ${target.name}: a peer shares node ${target.node}, `
                        + 'so segmenting by node CIDR would isolate that peer too')
    }
    for (const peerCidr of peerCidrs) {
        for (const port of IGNITE_PORTS) {
            for (const rule of [
                `-s ${target.podCidr} -d ${peerCidr} -p tcp --dport ${port}`,
                `-s ${peerCidr} -d ${target.podCidr} -p tcp --dport ${port}`
            ]) {
                dockerExec(
                    target.node,
                    `iptables -I FORWARD 1 ${rule} -m comment --comment ${CHAIN_COMMENT} -j DROP`
                )
            }
        }
    }
}

/**
 * Remove every rule this suite added on the given node. Safe to call when nothing was added, so it
 * belongs in an unconditional afterEach/afterAll.
 */
export function healPod(node: string): void {
    healPodInternal(node)
}

/**
 * Heal and confirm it worked. Step 6 of the segmentation test depends on the rules actually being
 * gone; without this a failed delete surfaces 180 seconds later as "the pod never rejoined", pointing
 * at the observer rather than at the firewall that is still up.
 */
export function healPodOrThrow(node: string): void {
    healPodInternal(node)
    const stuck = listSegmentationRules(node)
    if (stuck.length > 0) {
        throw new Error(`Failed to remove ${stuck.length} segmentation rule(s) from ${node}; `
                        + 'the pod cannot rejoin while they are in place')
    }
}

function healPodInternal(node: string): void {
    // Delete by rule text rather than by index: indexes shift as rules are removed.
    // Nothing here may throw: this runs in an unconditional afterAll, and an exception escaping it
    // would leave the node firewalled for every later run, which is the state it exists to prevent.
    for (let pass = 0; pass < 10; pass++) {
        const remaining = listSegmentationRules(node)
        if (remaining.length === 0) {
            return
        }
        for (const rule of remaining) {
            try {
                dockerExec(node, `iptables -D ${rule.replace(/^-A /, '')}`)
            } catch (error) {
                console.error(`[heal] could not delete rule on ${node}: ${rule}`, error)
            }
        }
    }
    const stuck = listSegmentationRules(node)
    if (stuck.length > 0) {
        console.error(`[heal] ${stuck.length} rule(s) left on ${node}; remove them with `
                      + `"docker exec ${node} iptables -D FORWARD <rule>" before running again`)
    }
}

function listSegmentationRules(node: string): string[] {
    let output: string
    try {
        output = dockerExec(node, `iptables -S FORWARD`)
    } catch {
        return []
    }
    return output
        .split('\n')
        .map(line => line.trim())
        .filter(line => line.includes(CHAIN_COMMENT))
}

/**
 * Restart a pod and wait for its replacement to report Ready.
 */
export function restartPod(context: string,
                           namespace: string,
                           labelSelector: string,
                           podName: string,
                           expectedReplicas: number,
                           timeoutSeconds = 300): void {
    kubectl(context, `delete pod ${podName} -n ${namespace} --wait=true --timeout=${timeoutSeconds}s`)

    // "kubectl wait" only waits on pods matching at the moment it runs, so calling it before the
    // ReplicaSet has created the replacement would wait on the survivors and return success
    const deadline = Date.now() + timeoutSeconds * 1000
    while (Date.now() < deadline) {
        const count = kubectl(context, `get pods -n ${namespace} -l ${labelSelector} --no-headers`)
            .split('\n')
            .filter(line => line.trim().length > 0)
            .length
        if (count >= expectedReplicas) {
            break
        }
        execSync('sleep 2')
    }

    kubectl(
        context,
        `wait --for=condition=Ready pod -n ${namespace} -l ${labelSelector} --timeout=${timeoutSeconds}s`
    )
}

/**
 * Logs for a pod. Reads the whole log rather than tailing, because the observer reports the
 * condition once and then only hourly, so the line under test can be well behind the tail.
 */
export function getPodLogs(context: string, namespace: string, podName: string): string {
    try {
        return execSync(
            `kubectl --context ${context} logs ${podName} -n ${namespace} --tail=-1`,
            { encoding: 'utf-8', maxBuffer: 64 * 1024 * 1024 }
        )
    } catch {
        return ''
    }
}

/**
 * How many Ignite server nodes a pod currently sees, read from the observer's own reporting rather
 * than from the Kubernetes API, so it reflects what the node believes rather than what is deployed.
 * Returns null when the log carries no topology line yet.
 */
export function getObservedServerNodes(logs: string): number | null {
    const matches = [...logs.matchAll(/server topology reached (\d+) nodes|topology (?:has been at|dropped to) (\d+) nodes|Server topology is (\d+) node/gi)]
    if (matches.length === 0) {
        return null
    }
    const last = matches[matches.length - 1]
    const value = last[1] ?? last[2] ?? last[3]
    return value ? parseInt(value) : null
}

/**
 * How many times a marker appears in a pod's log. Used to attribute work to the node that actually
 * performed it, from the server's own record rather than from whatever the response claims.
 */
export function countInPodLogs(context: string, namespace: string, podName: string, marker: string): number {
    const logs = getPodLogs(context, namespace, podName)
    if (logs.length === 0) {
        return 0
    }
    return logs.split('\n').filter(line => line.includes(marker)).length
}
