/**
 * Latency and outcome bookkeeping for load-test operations, keyed by task name. Every operation is
 * recorded once; a window snapshot reports and resets what arrived since the previous snapshot, the
 * total snapshot reports everything since the run started.
 */
export interface OperationSnapshot {
    name: string
    count: number
    errors: number
    perSecond: number
    meanMs: number
    p50Ms: number
    p90Ms: number
    p95Ms: number
    p99Ms: number
    maxMs: number
}

interface Bucket {
    latencies: number[]
    errors: number
}

export class OperationStats {
    private readonly window = new Map<string, Bucket>()
    private readonly total = new Map<string, Bucket>()
    private windowStart = performance.now()
    private readonly start = performance.now()

    public record(name: string, latencyMs: number, ok: boolean): void {
        for (const bucket of [this.bucket(this.window, name), this.bucket(this.total, name)]) {
            bucket.latencies.push(latencyMs)
            if (!ok) {
                bucket.errors++
            }
        }
    }

    /** Operations since the last window snapshot; resets the window. */
    public snapshotWindow(): OperationSnapshot[] {
        const now = performance.now()
        const snapshot = OperationStats.summarize(this.window, (now - this.windowStart) / 1000)
        this.window.clear()
        this.windowStart = now
        return snapshot
    }

    /** Every operation since the run started. */
    public snapshotTotal(): OperationSnapshot[] {
        return OperationStats.summarize(this.total, (performance.now() - this.start) / 1000)
    }

    public elapsedSeconds(): number {
        return (performance.now() - this.start) / 1000
    }

    public static format(snapshots: OperationSnapshot[]): string {
        const header = ['operation', 'count', 'errors', 'ops/s', 'mean', 'p50', 'p90', 'p95', 'p99', 'max']
        const rows = snapshots.map(s => [s.name, s.count, s.errors, s.perSecond.toFixed(1),
                                         ms(s.meanMs), ms(s.p50Ms), ms(s.p90Ms), ms(s.p95Ms), ms(s.p99Ms), ms(s.maxMs)]
            .map(String))
        const widths = header.map((h, i) => Math.max(h.length, ...rows.map(r => r[i].length)))
        const line = (cells: string[]) => cells.map((c, i) => i === 0 ? c.padEnd(widths[i]) : c.padStart(widths[i])).join('  ')
        return [line(header), ...rows.map(line)].join('\n')
    }

    private bucket(map: Map<string, Bucket>, name: string): Bucket {
        let bucket = map.get(name)
        if (!bucket) {
            bucket = {latencies: [], errors: 0}
            map.set(name, bucket)
        }
        return bucket
    }

    private static summarize(map: Map<string, Bucket>, seconds: number): OperationSnapshot[] {
        const result: OperationSnapshot[] = []
        for (const [name, bucket] of map) {
            const sorted = [...bucket.latencies].sort((a, b) => a - b)
            const count = sorted.length
            result.push({
                name,
                count,
                errors: bucket.errors,
                perSecond: seconds > 0 ? count / seconds : 0,
                meanMs: count ? sorted.reduce((a, b) => a + b, 0) / count : 0,
                p50Ms: percentile(sorted, 0.50),
                p90Ms: percentile(sorted, 0.90),
                p95Ms: percentile(sorted, 0.95),
                p99Ms: percentile(sorted, 0.99),
                maxMs: count ? sorted[count - 1] : 0
            })
        }
        return result.sort((a, b) => a.name.localeCompare(b.name))
    }
}

function percentile(sorted: number[], p: number): number {
    if (sorted.length === 0) {
        return 0
    }
    const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil(p * sorted.length) - 1))
    return sorted[index]
}

function ms(value: number): string {
    return value >= 1000 ? (value / 1000).toFixed(2) + 's' : value.toFixed(0) + 'ms'
}
