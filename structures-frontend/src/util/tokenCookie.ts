import Cookies from 'js-cookie'
import { User } from 'oidc-client-ts'
import { createDebug } from '@/util/debug'

const debug = createDebug('token-cookie')

/**
 * Decode one segment of a JWT to its JSON text.
 *
 * Segments are base64url (RFC 7515, §2): '-' and '_' stand in for '+' and '/', and the trailing
 * '=' padding is dropped. {@link atob} accepts standard base64 only and throws on those two
 * characters, so the segment is translated back and repadded first. Which payloads carry them is a
 * matter of alignment - a '?' in a claim encodes to one whenever it lands on the last byte of a
 * three-byte group, as does much non-ascii text - so a token either has them or does not, and the
 * more claims it carries the likelier it does.
 *
 * The bytes behind a segment are UTF-8, which atob() alone would leave as one character per byte.
 */
export function decodeJwtSegment(segment: string): string {
    const base64 = segment.replace(/-/g, '+').replace(/_/g, '/')
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=')
    const bytes = Uint8Array.from(atob(padded), character => character.charCodeAt(0))
    return new TextDecoder().decode(bytes)
}

/**
 * Check if a token is a structurally valid JWT
 */
function isValidJWT(token: string): boolean {
    try {
        const parts = token.split('.')
        if (parts.length !== 3) {
            return false
        }

        const header = JSON.parse(decodeJwtSegment(parts[0]))
        const payload = JSON.parse(decodeJwtSegment(parts[1]))

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
