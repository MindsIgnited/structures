import {ConcurrencyConfig} from '@/config/ConcurrencyConfig.js'
import {LoadTestConfig} from '@/config/LoadTestConfig.js'
import {StructuresConnectionConfig} from '@/config/StructuresConnectionConfig.js'
import {nodeSdk} from '@/instrumentation.js'
import {LoadTaskGeneratorFactory} from '@/services/LoadTaskGeneratorFactory.js'
import {TaskExecutionService} from '@/services/TaskExecutionService.js'
import {formatDuration} from '@/utils/DataUtil.js'

import {WebSocket} from 'ws'

// This is required when running Continuum from node
Object.assign(global, { WebSocket})

try {

    const concurrencyConfig = ConcurrencyConfig.fromEnv()
    const structuresConfig = StructuresConnectionConfig.fromEnv()
    const loadTestConfig = LoadTestConfig.fromEnv()
    console.log('Load Generator Config:')
    concurrencyConfig.print()
    structuresConfig.print()
    loadTestConfig.print()

    const startDelaySeconds = parseInt(process.env.START_DELAY_SECONDS || '60')
    const executionOptions = {
        durationSeconds: parseInt(process.env.DURATION_SECONDS || '0'),
        reportIntervalSeconds: parseInt(process.env.REPORT_INTERVAL_SECONDS || '0'),
        logTasks: (process.env.LOG_TASKS || 'true') === 'true',
        reportFile: process.env.REPORT_FILE
    }
    console.log(`START_DELAY_SECONDS=${startDelaySeconds}`)
    console.log(`DURATION_SECONDS=${executionOptions.durationSeconds}`)
    console.log(`REPORT_INTERVAL_SECONDS=${executionOptions.reportIntervalSeconds}`)

    const taskGenerator = LoadTaskGeneratorFactory.createTaskGenerator(structuresConfig, loadTestConfig)
    const taskExecutor = new TaskExecutionService(concurrencyConfig.maxConcurrentRequests,
                                                  concurrencyConfig.maxRequestsPerSecond,
                                                  100,
                                                  taskGenerator,
                                                  executionOptions)

    process
        .on('unhandledRejection', (reason, p) => {
            console.error(reason, 'Unhandled Rejection at Promise', p)
        })
        .on('uncaughtException', async err => {
            console.error(err, 'Uncaught Exception thrown')
            await taskExecutor.stop()
            await nodeSdk.shutdown().catch(console.error)
            process.exit(1)
        }).on('SIGINT', async () => {
            console.log('SIGINT')
            await taskExecutor.stop()
            await nodeSdk.shutdown().catch(console.error)
        }).on('SIGTERM', async () => {
            console.log('SIGTERM')
            await taskExecutor.stop()
            await nodeSdk.shutdown().catch(console.error)
        })

    try {
        const start = performance.now()
        
        if (startDelaySeconds > 0) {
            console.log(`Waiting ${startDelaySeconds}s before starting tasks...`)
            await new Promise(resolve => setTimeout(resolve, startDelaySeconds * 1000))
        }
        
        await taskExecutor.start()
        console.log('Load Generator Started')

        await taskExecutor.waitForCompletion()

        console.log('Load Generator Completed')
        const end = performance.now()    // Record end time
        const duration = end - start     // Calculate the duratio
        console.log(`Load Generation took ${formatDuration(duration)}.`)

        await nodeSdk.shutdown().catch(console.error)
    } finally {
        await taskExecutor.stop()
    }
    process.exit(0)

} catch (e: any) {
    console.error(e, 'Exception thrown')
    await nodeSdk.shutdown().catch(console.error)
}

