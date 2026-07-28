// @ts-ignore for some reason intellij is complaining about this even though esModuleInterop is enabled
import path from 'node:path'
// @ts-ignore
import os from 'node:os'
// @ts-ignore
import fs from 'node:fs'
import {StartedDockerComposeEnvironment, DockerComposeEnvironment, Wait} from 'testcontainers'
import {TestProject} from 'vitest/node.js'

/**
 * Load gradle.properties as the docker-compose environment, with
 * structuresVersion resolved to the effective image tag: an explicit
 * env override wins (CI exports a PR tag); otherwise a plain version is a
 * development build and gets -SNAPSHOT appended to mirror
 * org.kinotic.java-common-conventions.gradle. Passing everything through a
 * single withEnvironment call avoids depending on compose env-file precedence.
 */
function composeEnvironment(): Record<string, string> {
    const env: Record<string, string> = {}
    try {
        const content = fs.readFileSync(path.resolve('../../', 'gradle.properties'), 'utf8')
        for (const line of content.split('\n')) {
            const match = line.match(/^([\w.]+)=(.*)$/)
            if (match) {
                env[match[1]] = match[2].trim()
            }
        }
    } catch {
        // fall through to compose defaults
    }
    if (process.env.structuresVersion) {
        env.structuresVersion = process.env.structuresVersion
    } else if (env.structuresVersion && !env.structuresVersion.includes('-')) {
        env.structuresVersion += '-SNAPSHOT'
    }
    return env
}


let environment: StartedDockerComposeEnvironment

function isOSX_M1() {
    const arch = os.arch()
    const platform = os.platform()
    return platform === 'darwin' && arch === 'arm64'
}

// Run once before all tests
export async function setup(project: TestProject) {
    // @ts-ignore
    if(import.meta.env.VITE_USE_STRUCTURES_DOCKER === 'true') {
        console.log('Starting Structures...')

        const resolvedPath = path.resolve('../../docker-compose/')
        const files = ['compose.yml', 'compose.ek-transient.override.yml', 'compose.test.override.yml']
        if (isOSX_M1()) {
            files.push('compose.ek-m4.override.yml')
        }
        environment = await new DockerComposeEnvironment(resolvedPath, files)
            .withWaitStrategy('structures-elasticsearch', Wait.forHttp('/_cluster/health', 9200))
            .withWaitStrategy('structures-server', Wait.forHttp('/health', 9090))
            .withEnvironment(composeEnvironment())
            .up(['structures-elasticsearch', 'structures-server'])

        const container = environment.getContainer('structures-server')

        // @ts-ignore
        project.provide('STRUCTURES_HOST', container.getHost())
        // @ts-ignore
        project.provide('STRUCTURES_PORT', container.getMappedPort(58503))
        // @ts-ignore
        project.provide('STRUCTURES_OPENAPI_PORT', container.getMappedPort(8080))

        console.log('Structures started.')
    }else{
        // @ts-ignore
        project.provide('STRUCTURES_HOST', '127.0.0.1')
        // @ts-ignore
        project.provide('STRUCTURES_PORT', 58503)
        // @ts-ignore
        project.provide('STRUCTURES_OPENAPI_PORT', 8080)
        console.log('Skipping Structures setup because VITE_USE_STRUCTURES_DOCKER is false')
    }
}

// Run once after all tests
export async function teardown() {
    // @ts-ignore
    if(import.meta.env.VITE_USE_STRUCTURES_DOCKER === 'true') {
        console.log('Shutting down Structures...')
        await environment?.down()
        console.log('Structures shut down.')
    }else{
        console.log('Skipping Structures teardown because VITE_USE_STRUCTURES_DOCKER is false')
    }
}
