import Cookies from 'js-cookie'
import { User } from 'oidc-client-ts'
import { createDebug } from '@/util/debug'

const debug = createDebug('token-cookie')

/**
 * Check if a token is a structurally valid JWT
 */
function isValidJWT(token: string): boolean {
    try {
        const parts = token.split('.')
        if (parts.length !== 3) {
            return false
        }

        const header = JSON.parse(atob(parts[0]))
        const payload = JSON.parse(atob(parts[1]))

        return !!(header.alg && payload.iss && payload.aud)
    } catch {
        return false
    }
}

/**
 * Pick the token to present to the backend. Some providers (like Microsoft social
 * login) return opaque access tokens; in that case fall back to the ID token.
 */
export function selectToken(user: User): string {
    if (user.access_token && !isValidJWT(user.access_token)) {
        debug('Access token is not a valid JWT, using ID token')
        return user.id_token || user.access_token
    }
    return user.access_token
}

/**
 * Write the token cookie used by the GraphQL/OpenAPI playgrounds.
 * The session itself is persisted by the oidc-client-ts user store, not this cookie.
 */
export function writeTokenCookie(user: User): void {
    Cookies.set('token', selectToken(user), {
        sameSite: 'strict',
        secure: window.location.protocol === 'https:',
        expires: new Date(user.expires_at! * 1000)
    })
}
