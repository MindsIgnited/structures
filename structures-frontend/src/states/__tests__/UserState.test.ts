import {ContinuumError} from '@kinotic/continuum-client'
import {beforeEach, describe, expect, it, vi} from 'vitest'

/**
 * What the user state owes the rest of the UI about the connection underneath it. Each case is a
 * problem met while moving to continuum-client 3.0.0: connect() rejects with typed errors the UI must
 * not mangle, and a connection the server ends is reported once on fatalErrors, which the UI must
 * treat as the end of the session rather than carry on as if logged in over a dead connection.
 */

// Declared inside vi.hoisted so the mock factory below, which vitest hoists above every import,
// can see them
const mocks = vi.hoisted(() => {
    // A hot observable with just enough surface for the state's subscription
    const observers: ((value: Error) => void)[] = []
    const fatalErrors = {
        subscribe(next: (value: Error) => void) {
            observers.push(next)
            return {unsubscribe: () => { observers.splice(observers.indexOf(next), 1) }}
        },
        next(value: Error) {
            for (const observer of [...observers]) observer(value)
        }
    }
    const link = {active: false}
    return {
        fatalErrors,
        link,
        connect: vi.fn(),
        disconnect: vi.fn(async () => { link.active = false })
    }
})

vi.mock('@kinotic/continuum-client', async (importOriginal) => {
    const actual = await importOriginal<typeof import('@kinotic/continuum-client')>()
    return {
        ...actual,
        Continuum: {
            connect: mocks.connect,
            disconnect: mocks.disconnect,
            eventBus: {
                fatalErrors: mocks.fatalErrors,
                isConnectionActive: () => mocks.link.active,
                isConnected: () => mocks.link.active
            }
        }
    }
})
vi.mock('@/util/OidcSessionManager', () => ({oidcSessionManager: {cleanup: vi.fn(async () => undefined), initialize: vi.fn(async () => undefined)}}))
vi.mock('@/util/config', () => ({configService: {getOidcProviderByName: vi.fn(async () => null)}}))
vi.mock('@/util/tokenCookie', () => ({selectToken: () => 'token', writeTokenCookie: vi.fn()}))
vi.mock('@/pages/login/OidcConfiguration', () => ({createUserManagerSettings: vi.fn(async () => ({}))}))

import {UserState} from '../IUserState'

const connectedInfo = {sessionId: 'session-1', replyToId: 'reply-1', participant: {id: 'admin', roles: ['ADMIN'], metadata: new Map()}}

describe('UserState and the connection underneath it', () => {

    beforeEach(() => {
        mocks.link.active = false
        mocks.connect.mockReset()
        mocks.disconnect.mockClear()
    })

    it('a refused login surfaces the server\'s reason as the error message', async () => {
        // connect() rejects with a typed error. Wrapped as new Error(reason) that becomes
        // "ConnectionRefusedError: ..." - the type's name in front of what the login page shows.
        const refusal = new ContinuumError('Could not authenticate with the given credentials')
        refusal.name = 'ConnectionRefusedError'
        mocks.connect.mockRejectedValueOnce(refusal)
        const state = new UserState()

        let thrown: unknown = null
        try {
            await state.authenticate('admin', 'wrong')
        } catch (e) {
            thrown = e
        }
        expect(thrown).toBeInstanceOf(Error)
        expect((thrown as Error).message, 'the message shown to the user is the server\'s reason, nothing prepended')
            .toBe('Could not authenticate with the given credentials')
        expect(state.isAuthenticated()).toBe(false)
        expect(state.isAccessDenied()).toBe(true)
    })

    it('a fatal connection error ends the session', async () => {
        // The server ended the connection: a reconnect it refused, or attempts exhausted. The state
        // must stop claiming an authenticated session it no longer has, so the router sends the user
        // to log in again rather than leaving every page to fail one request at a time.
        mocks.connect.mockImplementationOnce(async () => { mocks.link.active = true; return connectedInfo })
        const state = new UserState()
        await state.authenticate('admin', 'structures')
        expect(state.isAuthenticated()).toBe(true)

        mocks.link.active = false
        mocks.fatalErrors.next(Object.assign(new ContinuumError('Could not authenticate with the given Session id'), {name: 'ConnectionRefusedError'}))
        await Promise.resolve()

        expect(state.isAuthenticated(), 'the session is over once the connection is').toBe(false)
    })

    it('logging out is not fatal', async () => {
        // A close the user asked for must not be reported back as a lost session
        mocks.connect.mockImplementationOnce(async () => { mocks.link.active = true; return connectedInfo })
        const state = new UserState()
        await state.authenticate('admin', 'structures')
        await state.logout()
        expect(mocks.disconnect).toHaveBeenCalled()
        expect(state.isAuthenticated()).toBe(false)
        expect(state.isAccessDenied()).toBe(false)
    })
})
