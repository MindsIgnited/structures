import {ITask} from './ITask.js'

export interface ITaskGenerator {

    /**
     * Returns the next task to be executed
     */
    getNextTask(): ITask

    /**
     * Returns true if there are more tasks to be executed
     */
    hasMoreTasks(): boolean

    /**
     * Releases whatever the generator holds open (a Continuum connection, typically). Called when the
     * run ends before the generator ran out of tasks, which is how a duration-bounded run stops.
     */
    shutdown?(): Promise<void>

}
