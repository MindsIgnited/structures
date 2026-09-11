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
        project.provide('STRUCTURES_USE_SSL', false)
        // @ts-ignore
        project.provide('STRUCTURES_OPENAPI_BASE_URL',
                        `http://${container.getHost()}:${container.getMappedPort(8080)}`)

        console.log('Structures started.')
    }else{
        // Point the suite at an already running deployment. Defaults keep the previous behaviour of
        // assuming a local server on the standard ports; STRUCTURES_E2E_* target something else, such
        // as the KinD cluster through its ingress:
        //   STRUCTURES_E2E_HOST=structures.local STRUCTURES_E2E_PORT=443 STRUCTURES_E2E_USE_SSL=true \
        //   STRUCTURES_E2E_OPENAPI_BASE_URL=http://127.0.0.1:18080
        // The OpenAPI override is required for that case, not optional: see the note below.
        // For the KinD ingress the certificate is issued by the local mkcert CA, so node needs
        //   NODE_EXTRA_CA_CERTS="$(mkcert -CAROOT)/rootCA.pem"
        const host = process.env.STRUCTURES_E2E_HOST || '127.0.0.1'
        const port = parseInt(process.env.STRUCTURES_E2E_PORT || '58503')
        const openApiPort = parseInt(process.env.STRUCTURES_E2E_OPENAPI_PORT || '8080')
        const useSSL = process.env.STRUCTURES_E2E_USE_SSL === 'true'

        // @ts-ignore
        project.provide('STRUCTURES_HOST', host)
        // @ts-ignore
        project.provide('STRUCTURES_PORT', port)
        // @ts-ignore
        project.provide('STRUCTURES_USE_SSL', useSSL)
        // STRUCTURES_E2E_OPENAPI_BASE_URL is separate from the STOMP target on purpose: the KinD
        // ingress routes /api and /graphql but not /api-docs, which falls through to the UI's catch
        // all and answers 200 with index.html, so the OpenAPI tests have to be pointed straight at a
        // pod even while the rest of the suite goes through nginx
        const openApiBaseUrl = process.env.STRUCTURES_E2E_OPENAPI_BASE_URL
            || `${useSSL ? 'https' : 'http'}://${host}`
               + `${(useSSL && openApiPort === 443) || (!useSSL && openApiPort === 80)
                    ? '' : ':' + openApiPort}`
        // @ts-ignore
        project.provide('STRUCTURES_OPENAPI_BASE_URL', openApiBaseUrl)
        console.log(`Skipping Structures setup because VITE_USE_STRUCTURES_DOCKER is false; `
                    + `targeting ${useSSL ? 'https' : 'http'}://${host}:${port}`)
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
