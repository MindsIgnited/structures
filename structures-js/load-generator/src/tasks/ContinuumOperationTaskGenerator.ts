import {ITask} from './ITask.js'
import {ITaskFactory} from './ITaskFactory.js'
import {ITaskGenerator} from './ITaskGenerator.js'
import {ConnectionInfo, ContinuumSingleton} from '@kinotic/continuum-client'

class ContinuumTask implements ITask{
    private delegate: ITask
    private continuumGenerator: ContinuumOperationTaskGenerator

    constructor(delegate: ITask,
                continuumGenerator: ContinuumOperationTaskGenerator) {
        this.delegate = delegate
        this.continuumGenerator = continuumGenerator
    }

    name(): string {
        return this.delegate.name()
    }

    async execute(): Promise<void> {
        await this.continuumGenerator.awaitConnectionComplete()
        try {
            return await this.delegate.execute()
        } finally {
            // Settled, not succeeded: Disconnect waits for every task to be over, and a failed one is
            this.continuumGenerator.markTaskComplete()
        }
    }
}

/**
 * A {@link TaskGenerator} that will generate tasks that will execute on a {@link ContinuumSingleton}.
 * The first task connects, the last disconnects; every task between waits for the connection, so the
 * executor may run them concurrently. A total of {@link Number.POSITIVE_INFINITY} makes the generator
 * unbounded - the run then ends by duration, and {@link shutdown} disconnects.
 */
export class ContinuumOperationTaskGenerator implements ITaskGenerator{

    private readonly connectionInfoSupplier: () => Promise<ConnectionInfo>
    private taskFactory: ITaskFactory
    private readonly totalTasks: number
    private taskCreationsRemaining: number
    private connectIssued: boolean = false
    private disconnectIssued: boolean = false
    private disconnected: boolean = false
    private taskCompletionCount: number = 0
    private readonly tasksComplete: Promise<void>
    private resolveAllTasksComplete: ((value: void) => void) | null = null
    private readonly continuumConnected: Promise<void>
    private resolveContinuumConnected: ((value: void) => void) | null = null
    private readonly continuum: ContinuumSingleton

    constructor(connectionInfoSupplier: () => Promise<ConnectionInfo>,
                continuum: ContinuumSingleton,
                totalTasks: number,
                taskFactory: ITaskFactory) {
        this.connectionInfoSupplier = connectionInfoSupplier
        this.continuum = continuum
        this.taskFactory = taskFactory
        this.totalTasks = totalTasks
        this.taskCreationsRemaining = totalTasks
        this.tasksComplete = new Promise<void>((resolve) => {
            this.resolveAllTasksComplete = resolve
        })
        this.continuumConnected = new Promise<void>((resolve) => {
            this.resolveContinuumConnected = resolve
        })
    }

    getNextTask(): ITask {
        if(!this.connectIssued){
            this.connectIssued = true
            return {
                name: () => 'Connect Continuum',
                execute: async () => {
                    const connectionInfo = await this.connectionInfoSupplier()
                    await this.continuum.connect(connectionInfo)
                    this.resolveContinuumConnected!()
                }
            }
        }else if(this.taskCreationsRemaining > 0){
            this.taskCreationsRemaining--
            return new ContinuumTask(this.taskFactory.createTask(), this)
        }else{
            this.disconnectIssued = true
            return {
                name: () => 'Disconnect Continuum',
                execute: async () => {
                    await this.tasksComplete // Wait for all tasks to complete before disconnecting
                    await this.disconnect()
                }
            }
        }
    }

    hasMoreTasks(): boolean {
        return !this.disconnectIssued
    }

    async shutdown(): Promise<void> {
        await this.disconnect()
    }

    awaitConnectionComplete(): Promise<void> {
        return this.continuumConnected!
    }

    markTaskComplete(): void {
        this.taskCompletionCount++
        if(this.taskCompletionCount === this.totalTasks){
            if(this.resolveAllTasksComplete){
                this.resolveAllTasksComplete()
                this.resolveAllTasksComplete = null
            }
        }
    }

    private async disconnect(): Promise<void> {
        if(this.connectIssued && !this.disconnected){
            this.disconnected = true
            await this.continuum.disconnect()
        }
    }

}
