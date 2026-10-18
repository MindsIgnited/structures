import {Structure, Structures} from '@kinotic/structures-api'
import {faker} from '@faker-js/faker'
import * as allure from 'allure-js-commons'
import axios, {AxiosInstance} from 'axios'
import {afterAll, beforeAll, describe, expect, inject, it} from 'vitest'
import {createPersonStructureIfNotExist, createSchema, initContinuumClient, shutdownContinuumClient} from '../TestHelpers.js'
import {generatePerson} from './OpenApiHelpers.js'

/**
 * Checks the OpenAPI spec from the outside: it is well formed, and every operation in it answers as documented when
 * called with the documented path parameters, content type and request body.
 */

const applicationId = 'openapi.spec'
const projectName = 'SpecProject'
const BASE_AUTH = 'Basic YWRtaW46c3RydWN0dXJlcw=='
// The tenant of the admin/structures login
const TENANT = 'kinotic'
const OPERATION_METHODS = ['get', 'put', 'post', 'delete', 'patch']

type Json = any

interface Operation {
    method: string
    path: string
    operation: Json
}

describe('OpenAPI spec', () => {
    let baseUrl: string
    let spec: Json
    let structure: Structure
    const http: AxiosInstance = axios.create({
                                                 headers: {'Authorization': BASE_AUTH},
                                                 // every status is checked by the tests
                                                 validateStatus: () => true
                                             })

    beforeAll(async () => {
        await allure.parentSuite('End To End Tests')
        await initContinuumClient()

        structure = await createPersonStructureIfNotExist(applicationId, projectName, true)
        const {namedQueriesDefinition} = await createSchema(applicationId, structure.projectId, 'PersonWithTenant')
        await Structures.getNamedQueriesService().save(namedQueriesDefinition)

        // @ts-ignore
        baseUrl = inject('STRUCTURES_OPENAPI_BASE_URL') as string
        const response = await http.get(`${baseUrl}/api-docs/${applicationId}/openapi.json`)
        expect(response.status).toBe(200)
        spec = response.data
    }, 300000)

    afterAll(async () => {
        await shutdownContinuumClient()
    }, 60000)

    function operations(): Operation[] {
        const ret: Operation[] = []
        for (const [path, pathItem] of Object.entries<Json>(spec.paths)) {
            for (const method of OPERATION_METHODS) {
                if (pathItem[method]) {
                    ret.push({method, path, operation: pathItem[method]})
                }
            }
        }
        return ret
    }

    function resolve(schema: Json): Json {
        if (schema?.$ref) {
            return spec.components.schemas[schema.$ref.substring('#/components/schemas/'.length)]
        }
        return schema
    }

    it('is well formed', () => {
        const problems: string[] = []
        const operationIds = new Map<string, number>()
        for (const {method, path, operation} of operations()) {
            const where = `${method.toUpperCase()} ${path}`
            if (!operation.operationId) {
                problems.push(`${where} has no operationId`)
            } else {
                operationIds.set(operation.operationId, (operationIds.get(operation.operationId) ?? 0) + 1)
            }
            const placeholders = [...path.matchAll(/\{([^}]+)}/g)].map(m => m[1]).sort()
            const declared = (operation.parameters ?? []).filter((p: Json) => p.in === 'path').map((p: Json) => p.name).sort()
            if (JSON.stringify(placeholders) !== JSON.stringify(declared)) {
                problems.push(`${where} declares path parameters ${declared} for ${placeholders}`)
            }
            if (!operation.responses?.['200']) {
                problems.push(`${where} documents no 200 response`)
            }
        }
        for (const [id, count] of operationIds) {
            if (count > 1) {
                problems.push(`operationId ${id} is used ${count} times`)
            }
        }
        const references = JSON.stringify(spec).matchAll(/"\$ref":"([^"]+)"/g)
        for (const [, reference] of references) {
            if (!resolve({$ref: reference})) {
                problems.push(`$ref ${reference} does not resolve`)
            }
        }
        expect(problems).toEqual([])
    })

    it('answers every operation as documented', async () => {
        const structureSchemaName = structure.name
        const query = `firstName:"Spec${faker.string.alpha(10)}"`
        const problems: string[] = []

        // Saved through the documented save-sync, so it is searchable straight away
        async function newPerson(): Promise<Json> {
            const saveSync = Object.keys(spec.paths).find(p => p.endsWith('/save-sync'))!
            const response = await http.post(`${baseUrl}${saveSync}`, generatePerson(TENANT))
            expect(response.status, JSON.stringify(response.data)).toBe(200)
            return response.data
        }

        function exampleFor(schema: Json, path: string, person: Json): Json {
            const resolved = resolve(schema)
            const name = schema?.$ref?.substring('#/components/schemas/'.length)
            if (name === 'QueryWithTenantSelection') {
                return {query, tenantSelection: [TENANT]}
            }
            if (name === 'TenantSpecificId') {
                return {entityId: person.id, tenantId: TENANT}
            }
            if (name === structureSchemaName) {
                // An update writes the saved entity again, a save writes a new one
                return path.includes('/update') ? {...person, lastName: 'Updated'} : generatePerson(TENANT)
            }
            if (resolved?.type === 'array') {
                const items = resolve(resolved.items)
                if (resolved.items?.$ref) {
                    return [exampleFor(resolved.items, path, person)]
                }
                // An array of strings is ids for find/by-ids, and the tenant selection everywhere else
                return items?.type === 'string' ? [path.endsWith('/find/by-ids') ? person.id : TENANT] : []
            }
            if (resolved?.type === 'string') {
                return 'x'
            }
            if (resolved?.properties) {
                const ret: Json = {}
                for (const [property, propertySchema] of Object.entries<Json>(resolved.properties)) {
                    ret[property] = property === 'tenantSelection' ? [TENANT] : exampleFor(propertySchema, path, person)
                }
                return ret
            }
            return undefined
        }

        // Checks a value has the documented shape: types of the documented properties, arrays, $refs
        function conforms(value: Json, schema: Json, at: string): string[] {
            const resolved = resolve(schema)
            if (!resolved || resolved.oneOf || resolved.anyOf || resolved.allOf) {
                return []
            }
            if (value === null || value === undefined) {
                return resolved.nullable || !resolved.type ? [] : [`${at} is ${value}`]
            }
            switch (resolved.type) {
                case 'array':
                    return Array.isArray(value)
                        ? value.flatMap((item, i) => conforms(item, resolved.items, `${at}[${i}]`))
                        : [`${at} is not an array`]
                case 'object':
                    if (typeof value !== 'object' || Array.isArray(value)) {
                        return [`${at} is not an object`]
                    }
                    return Object.entries<Json>(resolved.properties ?? {})
                                 .flatMap(([property, propertySchema]) => conforms(value[property], {...propertySchema, nullable: true}, `${at}.${property}`))
                case 'string':
                    return typeof value === 'string' ? [] : [`${at} is not a string`]
                case 'integer':
                    return Number.isInteger(value) ? [] : [`${at} is not an integer`]
                case 'number':
                    return typeof value === 'number' ? [] : [`${at} is not a number`]
                case 'boolean':
                    return typeof value === 'boolean' ? [] : [`${at} is not a boolean`]
                default:
                    return []
            }
        }

        const person = await newPerson()
        // Deletes run last, each on an entity of its own
        const ordered = operations().sort((a, b) => Number(a.method === 'delete') - Number(b.method === 'delete'))
        // An empty or partial spec would pass trivially, so the Sync operations and a named query must be there
        for (const suffix of ['/save-sync', '/update-sync', '/{id}/sync', '/{tenantId}/{id}/sync', '/named-query/adminCountByLastName']) {
            expect(ordered.some(o => o.path.endsWith(suffix)), `the spec documents ${suffix}`).toBe(true)
        }
        let called = 0
        for (const {method, path, operation} of ordered) {
            const where = `${method.toUpperCase()} ${path}`
            const target = method === 'delete' ? await newPerson() : person
            const url = baseUrl + path.replace('{tenantId}', TENANT).replace('{id}', encodeURIComponent(target.id))

            const content = operation.requestBody?.content ?? {}
            const contentType = Object.keys(content)[0]
            let data: Json = undefined
            if (contentType === 'text/plain') {
                data = query
            } else if (contentType) {
                data = exampleFor(content[contentType].schema, path, target)
            }

            const response = await http.request({method, url, data, headers: contentType ? {'Content-Type': contentType} : {}})
            called++
            if (response.status !== 200) {
                problems.push(`${where} answered ${response.status}: ${JSON.stringify(response.data)}`)
                continue
            }
            // A 200 documented without content has an empty body; one documented as JSON has a JSON body of that shape
            const documented = operation.responses['200']?.content?.['application/json']?.schema
            if (!documented) {
                if (response.data !== '') {
                    problems.push(`${where} is documented without a body but answered ${JSON.stringify(response.data)}`)
                }
            } else if (response.data === '' || !String(response.headers['content-type'] ?? '').includes('application/json')) {
                problems.push(`${where} is documented as JSON but answered '${response.data}' as ${response.headers['content-type']}`)
            } else {
                problems.push(...conforms(response.data, documented, where))
            }
        }
        expect(problems).toEqual([])
        expect(called).toBe(ordered.length)
        console.log(`Called ${called} documented operations`)
    }, 120000)
})
