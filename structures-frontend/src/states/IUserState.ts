import { ConnectedInfo, ConnectionInfo, Continuum } from '@kinotic/continuum-client'
import { reactive } from 'vue'
import Cookies from 'js-cookie'
import { User, UserManager } from 'oidc-client-ts'
import { createDebug } from '@/util/debug'

const debug = createDebug('user-state');
import { oidcSessionManager } from '@/util/OidcSessionManager'
import { configService } from '@/util/config'
import { createConnectionInfo } from '../util/helpers'
import { selectToken, writeTokenCookie } from '@/util/tokenCookie'
import { createUserManagerSettings } from '@/pages/login/OidcConfiguration'

// Deliberately outside the 'oidc.' prefix so AuthenticationManager.clearOidcState()
// does not wipe it during error recovery. The provider name is not a secret; it is
// needed to rebuild the UserManager when restoring a session after a page refresh.
const AUTH_PROVIDER_KEY = 'auth.provider'

// Prefix used by the oidc-client-ts WebStorageStateStore for the persisted user
const OIDC_USER_KEY_PREFIX = 'oidc.user:'

/**
 * Thrown when the frontend role gate rejects an otherwise valid token. Lets the
 * restore path tell a definitive denial apart from a transient failure.
 */
class FrontendRoleDeniedError extends Error {}

export interface IUserState {
    connectedInfo: ConnectedInfo | null
    oidcUser: User | null

    isAccessDenied(): boolean
    isAuthenticated(): boolean
    authenticate(login: string, passcode: string): Promise<void>
    handleOidcLogin(user: User, provider: string): Promise<void>
    restoreSession(): Promise<boolean>
    logout(): Promise<void>
}

export class UserState implements IUserState {
    public connectedInfo: ConnectedInfo | null = null
    public oidcUser: User | null = null
    private authenticated: boolean = false
    private accessDenied: boolean = false
    private restorePromise: Promise<boolean> | null = null

    public async authenticate(login: string, passcode: string): Promise<void> {
        try {
            await Continuum.disconnect()
        } catch (error) {
            debug('No existing connection to disconnect')
        }

        const connectionInfo: ConnectionInfo = createConnectionInfo()
        connectionInfo.connectHeaders = {
            login,
            passcode
        }

        try {
            this.connectedInfo = await Continuum.connect(connectionInfo)
            this.authenticated = true
            this.accessDenied = false
            // Note: We intentionally do NOT store basic auth credentials in cookies
            // This is more secure - users must re-login on page refresh
        } catch (reason: any) {
            this.accessDenied = true
            if (reason) {
                throw new Error(reason)
            } else {
                throw new Error('Credentials invalid')
            }
        }
    }

    public async handleOidcLogin(user: User, provider: string): Promise<void> {
        try {
            await Continuum.disconnect()
        } catch (error) {
            debug('No existing connection to disconnect')
        }

        try {
            await this.establishConnection(user, provider)
        } catch (reason: any) {
            this.accessDenied = true
            if (reason instanceof Error) {
                throw reason
            } else if (reason) {
                throw new Error(reason)
            } else {
                throw new Error('OIDC authentication failed')
            }
        }
    }

    /**
     * Attempt to restore an OIDC session persisted by oidc-client-ts in localStorage.
     * Called by the router guard on page refresh before redirecting to login.
     * Resolves true if a session was restored and the Continuum connection
     * re-established; never rejects, so the guard always reaches a decision.
     */
    public restoreSession(): Promise<boolean> {
        if (!this.restorePromise) {
            this.restorePromise = this.doRestoreSession()
                .catch(error => {
                    debug('Session restore failed unexpectedly: %O', error)
                    return false
                })
                .finally(() => {
                    this.restorePromise = null
                })
        }
        return this.restorePromise
    }

    private async doRestoreSession(): Promise<boolean> {
        if (this.isAuthenticated()) {
            return true
        }

        const provider = localStorage.getItem(AUTH_PROVIDER_KEY)
        if (!provider) {
            return false
        }

        try {
            // Temporary manager just to read/renew the persisted user. Renewal and
            // session monitoring stay off so its timers never race the long-lived
            // oidcSessionManager that takes over once the session is established.
            const settings = await createUserManagerSettings(provider)
            const userManager = new UserManager({
                ...settings,
                automaticSilentRenew: false,
                monitorSession: false
            })

            let user = await userManager.getUser()
            if (!user) {
                this.clearPersistedSession()
                return false
            }

            if (user.expired) {
                if (!user.refresh_token) {
                    debug('Persisted OIDC session expired with no refresh token')
                    this.clearPersistedSession()
                    return false
                }
                debug('Persisted OIDC session expired, attempting silent renew')
                user = await userManager.signinSilent()
                if (!user) {
                    return false
                }
            }

            // The backend is the source of truth: only treat the session as
            // restored once Continuum.connect accepts the token.
            await this.establishConnection(user, provider)
            debug('OIDC session restored for provider %s', provider)
            return true
        } catch (error) {
            debug('Session restore failed: %O', error)
            // Only discard the persisted session on a definitive denial. Transient
            // failures (backend restarting, network blip) keep it so the next
            // navigation can retry the restore.
            if (error instanceof FrontendRoleDeniedError) {
                this.clearPersistedSession()
            }
            return false
        }
    }

    /**
     * Connect to Continuum with the user's token, enforce the frontend role gate,
     * and persist what is needed to survive a page refresh.
     * Shared by the initial OIDC login and session restore paths.
     */
    private async establishConnection(user: User, provider: string): Promise<void> {
        const connectionInfo: ConnectionInfo = createConnectionInfo()
        connectionInfo.connectHeaders = {
            Authorization: `Bearer ${selectToken(user)}`
        }

        this.connectedInfo = await Continuum.connect(connectionInfo)

        try {
            // Frontend role gate. The backend has already validated the token; this is a
            // UI-only admission check using frontEndRoles configured on the OIDC provider.
            const providerConfig = await configService.getOidcProviderByName(provider)
            if (providerConfig?.frontEndRoles && providerConfig.frontEndRoles.length > 0) {
                const userRoles = this.connectedInfo.participant.roles ?? []
                const hasRequiredRole = providerConfig.frontEndRoles.some(r => userRoles.includes(r))
                if (!hasRequiredRole) {
                    this.accessDenied = true
                    throw new FrontendRoleDeniedError(`User does not have any required frontend role. Required one of: ${providerConfig.frontEndRoles.join(', ')}`)
                }
            }

            // Initialize automatic token refresh before marking the session
            // authenticated so a failure here leaves no half-authenticated state
            await oidcSessionManager.initialize(provider, async () => {
                console.warn('Token refresh failed, logging out')
                await this.logout()
            })
        } catch (error) {
            try { await Continuum.disconnect() } catch { /* best effort */ }
            this.connectedInfo = null
            throw error
        }

        this.authenticated = true
        this.accessDenied = false
        this.oidcUser = user

        // Best-effort persistence: a storage failure must not tear down the
        // established session, it only costs restore-on-refresh.
        try {
            localStorage.setItem(AUTH_PROVIDER_KEY, provider)
            // The token cookie is only for the GraphQL/OpenAPI playgrounds; the session
            // itself is restored from the oidc-client-ts user store in localStorage.
            writeTokenCookie(user)
        } catch (error) {
            debug('Failed to persist session state: %O', error)
        }
    }

    /**
     * Remove the persisted OIDC user and provider so a page refresh cannot restore
     * the session. Deletes the oidc-client-ts store entries by key prefix so no
     * async config load is needed and a config failure cannot strand tokens.
     */
    private clearPersistedSession(): void {
        try {
            const keysToRemove: string[] = []
            for (let i = 0; i < localStorage.length; i++) {
                const key = localStorage.key(i)
                if (key && key.startsWith(OIDC_USER_KEY_PREFIX)) {
                    keysToRemove.push(key)
                }
            }
            keysToRemove.forEach(key => localStorage.removeItem(key))
            localStorage.removeItem(AUTH_PROVIDER_KEY)
        } catch (error) {
            debug('Failed to clear persisted session: %O', error)
        }
    }

    public async logout(): Promise<void> {
        // Cleanup OIDC session manager first
        await oidcSessionManager.cleanup()

        if (this.connectedInfo) {
            try {
                await Continuum.disconnect()
            } catch (error) {
                debug('Error disconnecting from Continuum: %O', error)
            }
        }

        this.clearPersistedSession()

        Cookies.remove('token')
        // Legacy cleanup: refresh tokens are no longer written to cookies
        Cookies.remove('oidc_refresh_token')

        this.connectedInfo = null
        this.oidcUser = null
        this.authenticated = false
        this.accessDenied = false
    }

    public isAccessDenied(): boolean {
        return this.accessDenied
    }

    public isAuthenticated(): boolean {
        // Check if we have an active Continuum connection
        return this.authenticated && this.connectedInfo !== null
    }
}

export const USER_STATE: IUserState = reactive(new UserState())
