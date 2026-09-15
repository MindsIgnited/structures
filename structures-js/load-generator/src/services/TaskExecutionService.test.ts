import {TaskExecutionService} from '@/services/TaskExecutionService.js'
import {ITaskGenerator} from '@/tasks/ITaskGenerator.js'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

/**
 * How a run ends. A sustained run is unbounded and stops by duration or by signal, so stop() is
 * called while the generator still has tasks and some are in flight; a count-bounded run ends when
 * the generator runs dry. Either way stop() must return, the generator must be shut down and
 * waitForCompletion() must resolve, or the process hangs with no report written.
 */

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))

/** Resolves to the outcome, or to 'HUNG' if the promise is still pending after the timeout */
async function within<T>(promise: Promise<T>, ms: number): Promise<T | 'HUNG'> {
    return Promise.race([promise, sleep(ms).then(() => 'HUNG' as const)])
}

function unboundedGenerator(taskMs: number): ITaskGenerator & { shutdowns: number } {
    return {
        shutdowns: 0,
        getNextTask: () => ({name: () => 'task', execute: () => sleep(taskMs)}),
        hasMoreTasks: () => true,
        async shutdown() { this.shutdowns++ }
    }
}

function boundedGenerator(tasks: Array<() => Promise<void>>): ITaskGenerator {
    let next = 0
    return {
        getNextTask: () => {
            const execute = tasks[next++]
            return {name: () => `task-${next}`, execute}
        },
        hasMoreTasks: () => next < tasks.length
    }
}

describe('TaskExecutionService stopping', () => {

    const quiet = {durationSeconds: 0, reportIntervalSeconds: 0, logTasks: false}
    let errorSpy: ReturnType<typeof vi.spyOn>
    let logSpy: ReturnType<typeof vi.spyOn>

    beforeEach(() => {
        errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
        logSpy = vi.spyOn(console, 'log').mockImplementation(() => {})
    })

    afterEach(() => {
        errorSpy.mockRestore()
        logSpy.mockRestore()
    })

    it('stop() returns while tasks are in flight and the generator still has more', async () => {
        // What SIGINT does to a sustained run: the queue is paused and cleared, but the in-flight
        // tasks finish, and finishing empties the queue, which must not refill it once stopping
        const generator = unboundedGenerator(30)
        const service = new TaskExecutionService(4, 1000, 100, generator, quiet)
        await service.start()
        await sleep(150)

        expect(await within(service.stop(), 3000), 'stop() resolved').not.toBe('HUNG')
        expect(generator.shutdowns, 'the generator was shut down once').toBe(1)
        expect(await within(service.waitForCompletion(), 1000), 'completion resolved').not.toBe('HUNG')
    })

    it('a bounded run whose generator runs dry stops on its own', async () => {
        const service = new TaskExecutionService(2, 1000, 100, boundedGenerator([() => sleep(10), () => sleep(10), () => sleep(10)]), quiet)
        await service.start()

        expect(await within(service.waitForCompletion(), 3000)).not.toBe('HUNG')
        const total = service.getStats().snapshotTotal()
        expect(total.reduce((sum, s) => sum + s.count, 0)).toBe(3)
    })

    it('a failed task is recorded once and logged once', async () => {
        const failure = new Error('boom')
        const service = new TaskExecutionService(1, 1000, 100, boundedGenerator([() => Promise.reject(failure), () => sleep(5)]), quiet)
        await service.start()
        expect(await within(service.waitForCompletion(), 3000)).not.toBe('HUNG')

        const total = service.getStats().snapshotTotal()
        expect(total.reduce((sum, s) => sum + s.errors, 0), 'one error in the stats').toBe(1)
        const failureLogs = errorSpy.mock.calls.filter(call => call.some(arg => String(arg).includes('boom') || arg === failure))
        expect(failureLogs.length, 'the failure is logged by run() alone, not a second time by the queue').toBe(1)
    })
})
