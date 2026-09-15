import {ContinuumOperationTaskGenerator} from '@/tasks/ContinuumOperationTaskGenerator.js'
import {ITask} from '@/tasks/ITask.js'
import {ConnectionInfo, ContinuumSingleton} from '@kinotic/continuum-client'
import {describe, expect, it} from 'vitest'

/**
 * A count-bounded run is Connect, N tasks, Disconnect, and Disconnect waits for the N to settle so
 * the connection is not pulled out from under them. Settle, not succeed: a task that fails is over
 * too, and a run with one failure must still disconnect and end rather than wait forever.
 */

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))

async function within<T>(promise: Promise<T>, ms: number): Promise<T | 'HUNG'> {
    return Promise.race([promise, sleep(ms).then(() => 'HUNG' as const)])
}

function fakeContinuum(): ContinuumSingleton & { disconnects: number } {
    return {
        disconnects: 0,
        async connect() { return {} as any },
        async disconnect() { this.disconnects++ }
    } as any
}

/** Runs the generator the way the executor does, every task to completion, ignoring failures */
async function drain(generator: ContinuumOperationTaskGenerator): Promise<ITask[]> {
    const tasks: ITask[] = []
    while (generator.hasMoreTasks()) {
        const task = generator.getNextTask()
        tasks.push(task)
        await task.execute().catch(() => {})
    }
    return tasks
}

describe('ContinuumOperationTaskGenerator', () => {

    it('disconnects at the end of a run in which a task failed', async () => {
        const continuum = fakeContinuum()
        let created = 0
        const generator = new ContinuumOperationTaskGenerator(async () => ({} as ConnectionInfo), continuum, 3, {
            createTask: () => {
                const n = ++created
                return {
                    name: () => `task-${n}`,
                    execute: n === 2 ? () => Promise.reject(new Error('boom')) : () => sleep(1)
                }
            }
        })

        const tasks = await within(drain(generator), 3000)

        expect(tasks, 'the run reached Disconnect instead of waiting on a task that already failed').not.toBe('HUNG')
        expect((tasks as ITask[]).map(t => t.name())).toEqual(['Connect Continuum', 'task-1', 'task-2', 'task-3', 'Disconnect Continuum'])
        expect(continuum.disconnects).toBe(1)
    })

    it('shutdown() disconnects a run that was cut short', async () => {
        const continuum = fakeContinuum()
        const generator = new ContinuumOperationTaskGenerator(async () => ({} as ConnectionInfo), continuum, Number.POSITIVE_INFINITY, {
            createTask: () => ({name: () => 'task', execute: () => sleep(1)})
        })
        await generator.getNextTask().execute() // Connect
        await generator.getNextTask().execute()

        await generator.shutdown()
        await generator.shutdown()

        expect(continuum.disconnects, 'disconnected once, however many times shutdown is called').toBe(1)
    })
})
