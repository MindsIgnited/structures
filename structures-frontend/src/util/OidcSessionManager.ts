import { User, UserManager } from 'oidc-client-ts'
import { createUserManagerSettings } from '@/pages/login/OidcConfiguration'
import { writeTokenCookie } from '@/util/tokenCookie'

/**
 * OidcSessionManager maintains a persistent UserManager instance with event listeners
 * to handle automatic token refresh via oidc-client-ts's automaticSilentRenew feature.
 * 
 * When tokens are refreshed, it updates the cookies so that:
 * - Playgrounds can use the fresh token
 * - Page refresh can reconnect with the stored token
 */
class OidcSessionManager {
    private userManager: UserManager | null = null
    private onRefreshFailed: (() => void) | null = null
    private boundHandlers: {
        userLoaded: ((user: User) => void) | null
        silentRenewError: ((error: Error) => void) | null
        accessTokenExpired: (() => void) | null
    } = {
        userLoaded: null,
        silentRenewError: null,
        accessTokenExpired: null
    }
    
    /**
     * Initialize with a provider after OIDC login.
     * Sets up event listeners for automatic token refresh.
     */
    async initialize(
        provider: string,
        onRefreshFailed: () => void
    ): Promise<void> {
        await this.cleanup()
        
        const settings = await createUserManagerSettings(provider)
        this.userManager = new UserManager(settings)
        this.onRefreshFailed = onRefreshFailed
        
        // Create bound handlers so we can properly remove them later
        this.boundHandlers.userLoaded = (user: User) => {
            console.log('Token refreshed automatically')
            writeTokenCookie(user)
        }
        
        this.boundHandlers.silentRenewError = (error: Error) => {
            console.error('Silent renew failed:', error)
            this.onRefreshFailed?.()
        }
        
        this.boundHandlers.accessTokenExpired = () => {
            console.warn('Access token expired')
            this.onRefreshFailed?.()
        }
        
        // Listen for token refresh events
        this.userManager.events.addUserLoaded(this.boundHandlers.userLoaded)
        this.userManager.events.addSilentRenewError(this.boundHandlers.silentRenewError)
        this.userManager.events.addAccessTokenExpired(this.boundHandlers.accessTokenExpired)
    }
    
    /**
     * Cleanup on logout - removes event listeners and clears state
     */
    async cleanup(): Promise<void> {
        if (this.userManager) {
            // Remove event listeners using the bound handlers
            if (this.boundHandlers.userLoaded) {
                this.userManager.events.removeUserLoaded(this.boundHandlers.userLoaded)
            }
            if (this.boundHandlers.silentRenewError) {
                this.userManager.events.removeSilentRenewError(this.boundHandlers.silentRenewError)
            }
            if (this.boundHandlers.accessTokenExpired) {
                this.userManager.events.removeAccessTokenExpired(this.boundHandlers.accessTokenExpired)
            }
            
            // Stop any pending silent renew
            this.userManager.stopSilentRenew()
            
            this.userManager = null
        }
        
        this.boundHandlers = {
            userLoaded: null,
            silentRenewError: null,
            accessTokenExpired: null
        }
        this.onRefreshFailed = null
    }
    
    /**
     * Get the UserManager for signin operations
     */
    getUserManager(): UserManager | null {
        return this.userManager
    }
    
}

export const oidcSessionManager = new OidcSessionManager()
