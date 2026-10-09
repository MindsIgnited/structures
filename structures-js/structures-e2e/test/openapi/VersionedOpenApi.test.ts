import {Structure,} from '@kinotic/structures-api'
import * as allure from 'allure-js-commons'
import axios from 'axios'
import {afterAll, beforeAll, describe, expect, inject, it} from 'vitest'
import {createTestVehicle, createVehicleStructureIfNotExist, initContinuumClient, shutdownContinuumClient} from '../TestHelpers.js'
import {loadOpenAPISchema} from './OpenApiHelpers.js'


interface LocalTestContext {
    personStructure: Structure
    personWithTenantStructure: Structure
    vehicleStructure: Structure
}

const applicationId = 'openapi.versioned'
const projectName = 'TestProject'
const BASE_AUTH = 'Basic YWRtaW46c3RydWN0dXJlcw=='

const axiosInstance = axios.create({
                                       headers: {
                                           'Authorization': BASE_AUTH,
                                           'Content-Type': 'application/json'
                                       },
                                       // Let the tests look at error statuses instead of axios throwing
                                       validateStatus: () => true
                                   })

describe('Versioned OpenApi Tests', () => {

    let context: LocalTestContext = {} as LocalTestContext

    beforeAll(async () => {
        await allure.parentSuite('End To End Tests')
        await initContinuumClient()

        context.vehicleStructure = await createVehicleStructureIfNotExist(applicationId, projectName)
        expect(context.vehicleStructure).toBeDefined()

    }, 300000)

    afterAll(async () => {
        // await expect(deleteStructure(context.vehicleStructure.id as string)).resolves.toBeUndefined()
        await shutdownContinuumClient()
    }, 60000)


    it<LocalTestContext>(
        'OpenApi Schema loads',
        async () => {
            // @ts-ignore
            const schemaUrl = `${inject('STRUCTURES_OPENAPI_BASE_URL')}/api-docs/openapi.versioned/openapi.json`

            const schema = await loadOpenAPISchema(schemaUrl)

            expect(schema).toBeDefined()
            expect(schema.openapi).toBe('3.0.1')
            expect(schema.info?.title).toBe('openapi.versioned Structures API')

            const responses = (schema as any).paths['/api/openapi.versioned/vehicle/update'].post.responses
            expect(responses['409']).toBeDefined()
        }
    )

    it<LocalTestContext>(
        'Update with a stale version answers 409',
        async () => {
            const url = `${inject('STRUCTURES_OPENAPI_BASE_URL')}/api/openapi.versioned/vehicle`

            const saved = await axiosInstance.post(url, createTestVehicle())
            expect(saved.status).toBe(200)
            expect(saved.data.version).toBeDefined()

            const updated = await axiosInstance.post(`${url}/update`, {...saved.data, color: 'Grey'})
            expect(updated.status).toBe(200)
            expect(updated.data.version).not.toEqual(saved.data.version)

            // Still carries the version from before the update above
            const stale = await axiosInstance.post(`${url}/update`, {...saved.data, color: 'Blue'})
            expect(stale.status).toBe(409)
            expect(stale.data.error).toContain('version conflict')
        }
    )

})