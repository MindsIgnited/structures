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
}

/**
 * Pod name, pod IP and hosting KinD node for every structures pod, in the order kubectl returns them.
 */
export function getPodPlacements(context: string, namespace: string, labelSelector: string): PodPlacement[] {
    const output = kubectl(
        context,
        `get pods -n ${namespace} -l ${labelSelector} ` +
        `-o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.podIP}{" "}{.spec.nodeName}{"\\n"}{end}'`
    )
    return output
        .split('\n')
        .map(line => line.trim())
        .filter(line => line.length > 0)
        .map(line => {
            const [name, ip, node] = line.split(/\s+/)
            return { name, ip, node }
        })
}

/**
 * Drop Ignite discovery and communication traffic between one pod and its peers, in both directions.
 *
 * Rules are tagged with a comment so {@link healPod} can remove exactly what was added and nothing
 * else, which matters because a failed test must not leave a KinD node quietly firewalled.
 */
export function segmentPod(target: PodPlacement, peers: PodPlacement[]): void {
    for (const peer of peers) {
        for (const port of IGNITE_PORTS) {
            for (const rule of [
                `-s ${target.ip} -d ${peer.ip} -p tcp --dport ${port}`,
                `-s ${peer.ip} -d ${target.ip} -p tcp --dport ${port}`
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
    // Delete by rule text rather than by index: indexes shift as rules are removed
    let remaining = listSegmentationRules(node)
    while (remaining.length > 0) {
        for (const rule of remaining) {
            dockerExec(node, `iptables -D ${rule.replace(/^-A /, '')}`)
        }
        remaining = listSegmentationRules(node)
    }
}

function listSegmentationRules(node: string): string[] {
    const output = dockerExec(node, `iptables -S FORWARD`)
    return output
        .split('\n')
        .map(line => line.trim())
        .filter(line => line.includes(CHAIN_COMMENT))
}

/**
 * Restart a pod and wait for its replacement to report Ready.
 */
export function restartPod(context: string, namespace: string, podName: string, timeoutSeconds = 300): void {
    kubectl(context, `delete pod ${podName} -n ${namespace} --wait=true --timeout=${timeoutSeconds}s`)
    kubectl(
        context,
        `wait --for=condition=Ready pod -n ${namespace} -l app=structures --timeout=${timeoutSeconds}s`
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
