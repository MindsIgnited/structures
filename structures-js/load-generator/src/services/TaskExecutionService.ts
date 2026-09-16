import {OperationStats} from '@/metrics/OperationStats.js'
import {ITaskGenerator} from '@/tasks/ITaskGenerator.js'
import PQueue from 'p-queue'
import fs from 'fs/promises'

export interface ExecutionOptions {
    /** Stop issuing new tasks after this many seconds; 0 runs the generator to exhaustion */
    durationSeconds: number
    /** Print a window of latency stats this often; 0 disables */
    reportIntervalSeconds: number
    /** Log every task as it starts */
    logTasks: boolean
    /** Write the final stats as JSON here, if set */
    reportFile?: string
}

export class TaskExecutionService {

    private static readonly MAX_LOGGED_ERRORS = 20

    private taskGenerator: ITaskGenerator
    private maxQueueDepth: number
    private queue: PQueue
    private started: boolean = false
    private completedPromise: Promise<void> | null = null
    private completeResolver: ((value: void) => void) | null = null
    private readonly options: ExecutionOptions
    private readonly stats = new OperationStats()
    private startedAt = 0
    private reportTimer: NodeJS.Timeout | null = null
    private loggedErrors = 0
    private stopRequested = false
    private stopping: Promise<void> | null = null

    constructor(concurrency: number,
                maxExecutionsPerSecond: number,
                maxQueueDepth: number,
                taskGenerator: ITaskGenerator,
                options: ExecutionOptions = {durationSeconds: 0, reportIntervalSeconds: 0, logTasks: true}) {

        this.queue = new PQueue({
                                    concurrency: concurrency,
                                    interval: 1000,
                                    intervalCap: maxExecutionsPerSecond,
                                    carryoverConcurrencyCount: true,
                                    autoStart: false
                                })
        this.maxQueueDepth = maxQueueDepth
        this.taskGenerator = taskGenerator
        this.options = options

        // Failures are recorded and logged in run(); p-queue also emits them as 'error' events, and
        // a listener there would print every one a second time, past the cap run() keeps.

        // Fires whenever the queue drains, including from clear() while stopping, so hasMoreTasks()
        // has to say no once a stop is under way or the paused queue is refilled and never idles
        this.queue.on('empty', async () => {
            let tasksAdded = false
            if(this.hasMoreTasks()){
                tasksAdded = this.enqueueTasks()
            }
            if(!tasksAdded) {
                await this.queue.onIdle() // make sure all inflight tasks are completed
                await this.stop().catch(err => console.error(err, "Error stopping Task Execution Service"))
            }
        });
    }

    public async start(): Promise<void>{
        if(!this.started) {
            this.started = true
            this.startedAt = performance.now()
            console.log('Starting Task Execution Service')
            this.completedPromise = new Promise<void>((resolve) => {
                this.completeResolver = resolve
            })
            if (this.options.reportIntervalSeconds > 0) {
                this.reportTimer = setInterval(() => this.reportWindow(), this.options.reportIntervalSeconds * 1000)
            }
            this.enqueueTasks()
            this.queue.start()
        }else{
            throw new Error('Task Execution Service already started')
        }
    }

    public async stop(): Promise<void> {
        if (this.started && !this.stopping) {
            this.stopRequested = true
            this.stopping = (async () => {
                console.log('Stopping Task Execution Service')
                if (this.reportTimer) {
                    clearInterval(this.reportTimer)
                    this.reportTimer = null
                }
                this.queue.pause()
                this.queue.clear()
                await this.queue.onIdle()
                try {
                    await this.taskGenerator.shutdown?.()
                } catch (e) {
                    console.error(e, 'Error shutting down task generator')
                }
                await this.reportTotal()
                this.started = false
                if (this.completeResolver) {
                    this.completeResolver()
                    this.completeResolver = null
                }
            })()
        }
        return this.stopping ?? Promise.resolve()
    }

    public async waitForCompletion(): Promise<void> {
        if(this.completedPromise){
            return this.completedPromise
        }else{
            throw new Error('Task Execution Service not started')
        }
    }

    public getStats(): OperationStats {
        return this.stats
    }

    private hasMoreTasks(): boolean {
        if (this.stopRequested) {
            return false
        }
        if (this.options.durationSeconds > 0
            && (performance.now() - this.startedAt) / 1000 >= this.options.durationSeconds) {
            return false
        }
        return this.taskGenerator.hasMoreTasks()
    }

    private enqueueTasks(): boolean {
        let ret = false
        let numberOfTasks = this.maxQueueDepth - this.queue.size
        for(let i = 0; i < numberOfTasks; i++) {
            if(this.hasMoreTasks()) {
                const task = this.taskGenerator.getNextTask()
                this.queue.add(() => this.run(task.name(), () => task.execute()))
                     .catch(() => {}) // recorded and logged in run()
                ret = true
            }else {
                break
            }
        }
        return ret
    }

    private async run(name: string, execute: () => Promise<void>): Promise<void> {
        if (this.options.logTasks) {
            console.log(`Executing task ${name}`)
        }
        const begin = performance.now()
        try {
            await execute()
            this.stats.record(name, performance.now() - begin, true)
        } catch (e) {
            this.stats.record(name, performance.now() - begin, false)
            if (this.loggedErrors < TaskExecutionService.MAX_LOGGED_ERRORS) {
                this.loggedErrors++
                console.error(`Task ${name} failed: ${(e as Error)?.message ?? e}`)
                if (this.loggedErrors === TaskExecutionService.MAX_LOGGED_ERRORS) {
                    console.error('Further task failures are counted but not logged')
                }
            }
            throw e
        }
    }

    private reportWindow(): void {
        const elapsed = this.stats.elapsedSeconds().toFixed(0)
        console.log(`\n--- last ${this.options.reportIntervalSeconds}s (t=${elapsed}s, queued=${this.queue.size}, running=${this.queue.pending}) ---`)
        console.log(OperationStats.format(this.stats.snapshotWindow()))
    }

    private async reportTotal(): Promise<void> {
        const total = this.stats.snapshotTotal()
        console.log(`\n=== run total (${this.stats.elapsedSeconds().toFixed(0)}s) ===`)
        console.log(OperationStats.format(total))
        if (this.options.reportFile) {
            await fs.writeFile(this.options.reportFile,
                               JSON.stringify({elapsedSeconds: this.stats.elapsedSeconds(), operations: total}, null, 2))
            console.log(`Report written to ${this.options.reportFile}`)
        }
    }

}
