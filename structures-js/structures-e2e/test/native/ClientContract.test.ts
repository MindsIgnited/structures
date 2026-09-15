import {ContinuumError, ContinuumSingleton, Pageable} from '@kinotic/continuum-client'
import {Structures} from '@kinotic/structures-api'
import * as allure from 'allure-js-commons'
import {createRequire} from 'node:module'
import fs from 'node:fs'
import path from 'node:path'
import {afterAll, beforeAll, describe, expect, inject, it} from 'vitest'
import {WebSocket} from 'ws'
import {initContinuumClient, shutdownContinuumClient} from '../TestHelpers.js'

// This is required when running Continuum from node
Object.assign(global, { WebSocket})

/**
 * What structures relies on from the continuum client, pinned from the consumer's side. Each case is a
 * problem met while moving structures to continuum-client 3.0.0, written so that the wrong client, or
 * the right client wired in wrongly, fails here with a message that says what went wrong rather than
 * somewhere deep in an entity test.
 */
describe('Continuum client contract', () => {

    beforeAll(async () => {
        await allure.suite('Typescript Client')
        await allure.subSuite('Client Contract')
        await initContinuumClient()
    }, 300000)

    afterAll(async () => {
        await shutdownContinuumClient()
    })

    it('structures-api admits the client version this project installs', () => {
        // structures-api declares the client as a peer. A range that does not admit the installed
        // version makes pnpm install a second client for structures-api alone, and every service call
        // then fails with "You must call connect on the event bus": connect() was called on one
        // instance, the proxies live on the other. Caught here rather than by every test in the suite.
        const client = packageJsonOf('@kinotic/continuum-client')
        const api = packageJsonOf('@kinotic/structures-api')
        const range: string = api.peerDependencies?.['@kinotic/continuum-client'] ?? ''
        const admitted = range.split('||').map(r => r.trim()).some(r => {
            const caret = r.match(/^\^(\d+)\./)
            return caret ? caret[1] === String(client.version.split('.')[0]) : false
        })
        expect(admitted,
               `@kinotic/structures-api peer range "${range}" must admit the installed @kinotic/continuum-client ${client.version}, `
               + 'or pnpm installs a second client for structures-api and every service call fails')
            .toBe(true)
    })

    it('structures-api proxies use the connection this project opened', async () => {
        // The runtime half of the same guarantee: one client instance between this project and
        // structures-api, so a connection made here is the one the services send on
        const page = await Structures.getApplicationService().findAll(Pageable.create(0, 10))
        expect(page).toBeDefined()
        expect(page.content).toBeDefined()
    })

    it('a refused login is a typed error carrying the server\'s reason', {timeout: 60000}, async () => {
        // Before 3.0.0 connect() rejected with a bare string, and the UI wrapped it as
        // new Error(reason). Callers branch on the type and show the message; both have to hold.
        const continuum = new ContinuumSingleton()
        let refusal: unknown = null
        try {
            await continuum.connect({...connectionInfo(), connectHeaders: {login: 'admin', passcode: 'not-the-password'}})
        } catch (e) {
            refusal = e
        } finally {
            await continuum.disconnect().catch(() => undefined)
        }
        expect(refusal, 'a refused login rejects connect()').not.toBeNull()
        expect(refusal, 'with a ContinuumError, not a string').toBeInstanceOf(ContinuumError)
        expect((refusal as Error).name, 'typed as the server refusing the connection').toBe('ConnectionRefusedError')
        expect((refusal as Error).message, 'carrying the server\'s own reason, readable').toMatch(/authenticat/i)
        expect((refusal as Error).message).not.toMatch(/\\c/)
    })

    it('a request on a connection the caller closed is refused at once, with a typed error', {timeout: 60000}, async () => {
        // Not left waiting, and not a bare Error either: the UI decides what to do by type
        const continuum = new ContinuumSingleton()
        await continuum.connect(connectionInfo())
        await continuum.disconnect()
        const proxy = continuum.serviceProxy('org.kinotic.structures.api.services.ApplicationService')
        await expect(Promise.race([
            proxy.invoke('findAll', [Pageable.create(0, 1)]),
            new Promise((_, reject) => setTimeout(() => reject(new Error('the request was left waiting')), 5000))
        ])).rejects.toBeInstanceOf(ContinuumError)
    })

    it('a fatal connection error is observable, and the connection is already down when it is', {timeout: 60000}, async () => {
        // The UI's cue to end its session. Provoked here by the one thing a test can make the server
        // do on demand: refuse a frame it will not accept, which ends the connection with an ERROR.
        const continuum = new ContinuumSingleton()
        await continuum.connect(connectionInfo())
        const reported = new Promise<{error: Error, activeWhenReported: boolean}>(resolve =>
            continuum.eventBus.fatalErrors.subscribe(error =>
                resolve({error, activeWhenReported: continuum.eventBus.isConnectionActive()})))
        // A service request whose reply-to is not a valid address is one the gateway answers by
        // ending the connection (a missing reply-to it lets through - that is a gateway FIXME)
        const {Event, EventConstants} = await import('@kinotic/continuum-client')
        const bad = new Event(EventConstants.SERVICE_DESTINATION_PREFIX + 'org.kinotic.structures.api.services.ApplicationService/findAll')
        bad.setHeader(EventConstants.REPLY_TO_HEADER, '')
        bad.setHeader(EventConstants.CONTENT_TYPE_HEADER, EventConstants.CONTENT_JSON)
        bad.setDataString('[]')
        continuum.eventBus.send(bad)
        const {error, activeWhenReported} = await Promise.race([
            reported,
            new Promise<never>((_, reject) => setTimeout(() => reject(new Error('fatalErrors never emitted')), 15000))
        ])
        expect(error).toBeInstanceOf(ContinuumError)
        expect(activeWhenReported, 'reported once the connection is already down, so connect() may be called from the handler').toBe(false)
        expect(continuum.eventBus.isConnectionActive()).toBe(false)
    })
})

function connectionInfo() {
    return {
        // @ts-ignore
        host: inject('STRUCTURES_HOST') as string,
        // @ts-ignore
        port: inject('STRUCTURES_PORT') as number,
        // @ts-ignore
        useSSL: inject('STRUCTURES_USE_SSL') === true,
        maxConnectionAttempts: 3,
        connectHeaders: {login: 'admin', passcode: 'structures'}
    }
}

/** The package.json of an installed package, found from its resolved entry: exports maps hide the file itself */
function packageJsonOf(name: string): any {
    const require = createRequire(import.meta.url)
    let dir = path.dirname(require.resolve(name))
    for (let i = 0; i < 6; i++) {
        const candidate = path.join(dir, 'package.json')
        if (fs.existsSync(candidate)) {
            const parsed = JSON.parse(fs.readFileSync(candidate, 'utf8'))
            if (parsed.name === name) {
                return parsed
            }
        }
        dir = path.dirname(dir)
    }
    throw new Error(`package.json of ${name} not found from ${require.resolve(name)}`)
}
