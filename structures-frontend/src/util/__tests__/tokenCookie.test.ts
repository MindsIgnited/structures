import {selectToken, decodeJwtSegment} from '@/util/tokenCookie'
import type {User} from 'oidc-client-ts'
import {describe, expect, it} from 'vitest'

/**
 * Which token the frontend presents to the backend. JWT segments are base64url (RFC 7515): '-' and
 * '_' stand in for '+' and '/', and the padding is dropped. atob() understands standard base64 only
 * and throws on those two characters, so a token carrying either looked malformed, and the ID token
 * went out in its place - whose audience is the client id, which the backend then rejects.
 */

/** Encodes a JWT segment the way a provider does: base64url, unpadded */
function base64url(value: object): string {
    const bytes = new TextEncoder().encode(JSON.stringify(value))
    let binary = ''
    for (const byte of bytes) {
        binary += String.fromCharCode(byte)
    }
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function jwt(payload: object, header: object = {alg: 'RS256', typ: 'JWT'}): string {
    return `${base64url(header)}.${base64url(payload)}.c2lnbmF0dXJl`
}

const idToken = jwt({iss: 'https://idp.example.com', aud: '0oa9z8y7x6clientid', sub: 'u1'})

describe('selectToken', () => {

    it('keeps an access token whose payload needs base64url characters', () => {
        // A '?' in a claim - a redirect URL, say - encodes to '-' or '_' whenever it lands on the
        // last byte of a 3-byte group, which is about one payload in three. Bigger tokens carry
        // more such characters, so they are the ones that break.
        const accessToken = jwt({
            iss: 'https://idp.example.com',
            aud: 'api://structures',
            sub: 'uu',
            ref: 'https://app.example.com/home?tenant=acme'
        })
        expect(accessToken.split('.')[1], 'the fixture must exercise the defect').toMatch(/[-_]/)

        expect(selectToken({access_token: accessToken, id_token: idToken} as User),
               'the access token is a JWT and must be the one presented').toBe(accessToken)
    })

    it('keeps an access token carrying non-ascii claims', () => {
        const accessToken = jwt({
            iss: 'https://idp.example.com',
            aud: 'api://structures',
            sub: 'u1',
            name: 'Renée Müller',
            groups: ['Ingénierie', 'Geschäftsführung', 'Διαχειριστές']
        })

        expect(selectToken({access_token: accessToken, id_token: idToken} as User)).toBe(accessToken)
    })

    it('still falls back to the id token when the access token is opaque', () => {
        // Why the fallback exists: some providers hand back an opaque access token, not a JWT
        expect(selectToken({access_token: 'sSg8kJ2nOpaqueProviderToken', id_token: idToken} as User)).toBe(idToken)
    })

    it('keeps an opaque access token when there is no id token to fall back to', () => {
        expect(selectToken({access_token: 'sSg8kJ2nOpaqueProviderToken'} as User)).toBe('sSg8kJ2nOpaqueProviderToken')
    })
})

describe('decodeJwtSegment', () => {

    it.each([
        ['no padding to restore', {iss: 'https://idp.example.com', aud: 'api://structures', sub: 'u'}],
        ['one padding character', {iss: 'https://idp.example.com', aud: 'api://structures', sub: 'uu'}],
        ['two padding characters', {iss: 'https://idp.example.com', aud: 'api://structures', sub: 'uuu'}]
    ])('round trips a segment needing %s', (_label, payload) => {
        expect(JSON.parse(decodeJwtSegment(base64url(payload)))).toEqual(payload)
    })

    it('decodes utf-8 claims rather than one character per byte', () => {
        const payload = {name: 'Renée Müller', org: 'Ångström Systems'}

        expect(JSON.parse(decodeJwtSegment(base64url(payload)))).toEqual(payload)
    })
})
