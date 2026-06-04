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
