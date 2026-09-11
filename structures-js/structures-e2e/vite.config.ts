import {resolve} from 'path'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig(
    {
        plugins: [vue()],
        resolve:{
            alias:{
                '@' : resolve(__dirname, 'src')
            },
            dedupe: [
                '@kinotic/continuum-client',
                '@kinotic/continuum-idl',
                'reflect-metadata',
                'rxjs'
            ],
        },
        test: {
            // The k8s tests drive one shared cluster and mutate it: the segmentation test isolates and
            // restarts a pod while the local delivery test asserts the cluster is whole. This is run
            // wide rather than per file, so enabling the k8s tests serialises every file in the run,
            // docker backed ones included. That is the point - they would otherwise overlap - but it
            // does make a full run slower.
            fileParallelism: process.env.K8S_TEST_ENABLED !== 'true',
            globalSetup: './test/setup.ts',
            setupFiles: ["allure-vitest/setup"],
            reporters: [
                "verbose",
                [
                    "allure-vitest/reporter",
                    {
                        resultsDir: "allure-results",
                    },
                ],
            ],
        }
    }
)
