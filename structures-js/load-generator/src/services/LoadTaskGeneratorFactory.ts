import {LoadTestConfig} from '@/config/LoadTestConfig.js'
import {StructuresConnectionConfig} from '@/config/StructuresConnectionConfig.js'
import { CreateComplexStructuresTaskGenerator } from '@/tasks/schema/CreateComplexStructuresTaskGenerator'
import {CreatePersonStructureTaskGenerator} from '@/tasks/schema/CreatePersonStructureTaskGenerator.js'
import {OpenApiMixedTaskGenerator} from '@/tasks/OpenApiMixedTaskGenerator.js'
import {FindTaskGenerator} from '@/tasks/FindTaskGenerator.js'
import {ITaskGenerator} from '@/tasks/ITaskGenerator.js'
import {ITaskGeneratorFactory} from '@/tasks/ITaskGeneratorFactory.js'
import {MultiTenantFindTaskGenerator} from '@/tasks/MultiTenantFindTaskGenerator.js'
import {MultiTenantSearchTaskGenerator} from '@/tasks/MultiTenantSearchTaskGenerator.js'
import {MultiTenantTaskGeneratorDelegator, TenantId} from '@/tasks/MultiTenantTaskGeneratorDelegator.js'
import {SaveTaskGenerator} from '@/tasks/SaveTaskGenerator.js'
import {SearchPeopleTaskGenerator} from '@/tasks/SearchPeopleTaskGenerator.js'
import {ConnectionInfo} from '@kinotic/continuum-client'
import {generateDeterministicId} from '@/utils/DataUtil.js'


export class LoadTaskGeneratorFactory {

    public static createTaskGenerator(structuresConfig: StructuresConnectionConfig,
                                      loadTestConfig: LoadTestConfig): ITaskGenerator | never {

        if(loadTestConfig.testName === 'bulkLoadSmall') {

            const peopleGenFactory: ITaskGeneratorFactory<TenantId>
                      = (tenantId) => {
                return new SaveTaskGenerator(
                    this.createConnectionInfo(tenantId, structuresConfig),
                    1000,
                    1000)
            }

            return new MultiTenantTaskGeneratorDelegator(loadTestConfig.beginTenantIdNumber,
                                                         loadTestConfig.numberOfTenants,
                                                         peopleGenFactory)

        }else if(loadTestConfig.testName === 'bulkLoadMedium') {

            const peopleGenFactory: ITaskGeneratorFactory<TenantId>
                      = (tenantId) => {
                return new SaveTaskGenerator(
                    this.createConnectionInfo(tenantId, structuresConfig),
                    2000,
                    10000)
            }

            return new MultiTenantTaskGeneratorDelegator(loadTestConfig.beginTenantIdNumber,
                                                         loadTestConfig.numberOfTenants,
                                                         peopleGenFactory)

        }else if(loadTestConfig.testName === 'bulkLoadLarge') {

            const peopleGenFactory: ITaskGeneratorFactory<TenantId>
                      = (tenantId) => {
                return new SaveTaskGenerator(
                    this.createConnectionInfo(tenantId, structuresConfig),
                    5000,
                    50000)
            }

            return new MultiTenantTaskGeneratorDelegator(loadTestConfig.beginTenantIdNumber,
                                                         loadTestConfig.numberOfTenants,
                                                         peopleGenFactory)

        }else if(loadTestConfig.testName === 'search') {

            const peopleGenFactory: ITaskGeneratorFactory<TenantId>
                      = (tenantId) => {
                return new SearchPeopleTaskGenerator(
                    this.createConnectionInfo(tenantId, structuresConfig),1, 'firstName: John', 100)
            }

            return new MultiTenantTaskGeneratorDelegator(loadTestConfig.beginTenantIdNumber,
                                                         loadTestConfig.numberOfTenants,
                                                         peopleGenFactory)

        }else if(loadTestConfig.testName === 'searchMultiTenantSmall') {

            // Tenant used during connection does not matter for this test
            return new MultiTenantSearchTaskGenerator(this.createConnectionInfo('kinotic', structuresConfig),
                                                      100,
                                                      'firstName: John',
                                                      100,
                                                      loadTestConfig.numberOfTenants)

        }else if(loadTestConfig.testName === 'searchMultiTenantLarge') {

            // Tenant used during connection does not matter for this test
            return new MultiTenantSearchTaskGenerator(this.createConnectionInfo('kinotic', structuresConfig),
                                                      1000,
                                                      'firstName: John',
                                                      100,
                                                      loadTestConfig.numberOfTenants)

        }else if(loadTestConfig.testName === 'findAll') {

            const peopleGenFactory: ITaskGeneratorFactory<TenantId>
                      = (tenantId) => {
                return new FindTaskGenerator(
                    this.createConnectionInfo(tenantId, structuresConfig),1, 100)
            }

            return new MultiTenantTaskGeneratorDelegator(loadTestConfig.beginTenantIdNumber,
                                                         loadTestConfig.numberOfTenants,
                                                         peopleGenFactory)

        }else if(loadTestConfig.testName === 'findAllMultiTenantSmall') {

            // Tenant used during connection does not matter for this test
            return new MultiTenantFindTaskGenerator(this.createConnectionInfo('kinotic', structuresConfig),
                                                    100,
                                                    100,
                                                    loadTestConfig.numberOfTenants)

        }else if(loadTestConfig.testName === 'findAllMultiTenantLarge') {

            // Tenant used during connection does not matter for this test
            return new MultiTenantFindTaskGenerator(this.createConnectionInfo('kinotic', structuresConfig),
                                                    1000,
                                                    100,
                                                    loadTestConfig.numberOfTenants)
        } else if(loadTestConfig.testName === 'generateComplexStructures'){

            return new CreateComplexStructuresTaskGenerator(this.createConnectionInfo('kinotic', structuresConfig))

        } else if(loadTestConfig.testName === 'createPersonStructure'){

            return new CreatePersonStructureTaskGenerator(this.createConnectionInfo('kinotic', structuresConfig))

        // The sustained tests never run out of tasks: they run until DURATION_SECONDS, on one tenant
        } else if(loadTestConfig.testName === 'sustainedBulkSave'){

            return new SaveTaskGenerator(this.createConnectionInfo(this.tenantId(loadTestConfig), structuresConfig),
                                         parseInt(process.env.BATCH_SIZE || '200'),
                                         Number.POSITIVE_INFINITY)

        } else if(loadTestConfig.testName === 'sustainedSearch'){

            return new SearchPeopleTaskGenerator(this.createConnectionInfo(this.tenantId(loadTestConfig), structuresConfig),
                                                 Number.POSITIVE_INFINITY,
                                                 process.env.SEARCH_TEXT || 'firstName: John',
                                                 parseInt(process.env.PAGE_SIZE || '100'))

        } else if(loadTestConfig.testName === 'sustainedFindAll'){

            return new FindTaskGenerator(this.createConnectionInfo(this.tenantId(loadTestConfig), structuresConfig),
                                         Number.POSITIVE_INFINITY,
                                         parseInt(process.env.PAGE_SIZE || '100'))

        } else if(loadTestConfig.testName === 'openApiMixed'){

            const baseUrl = process.env.STRUCTURES_OPENAPI_BASE_URL
            if(!baseUrl){
                throw new Error('STRUCTURES_OPENAPI_BASE_URL environment variable is required for openApiMixed')
            }
            return new OpenApiMixedTaskGenerator({
                baseUrl,
                applicationId: CreatePersonStructureTaskGenerator.APPLICATION_ID,
                structureName: CreatePersonStructureTaskGenerator.STRUCTURE_NAME,
                tenantId: this.tenantId(loadTestConfig),
                batchSize: parseInt(process.env.BATCH_SIZE || '200'),
                pageSize: parseInt(process.env.PAGE_SIZE || '50'),
                searchText: process.env.SEARCH_TEXT || 'firstName: John',
                totalOperations: Number.POSITIVE_INFINITY
            })

        }else {
            throw new Error(`Unsupported test name: ${loadTestConfig.testName}`)
        }
    }

    /** The sustained tests use a single tenant: the one BEGIN_TENANT_ID_NUMBER maps to, or kinotic */
    private static tenantId(loadTestConfig: LoadTestConfig): string {
        return process.env.TENANT_ID || (loadTestConfig.beginTenantIdNumber > 0
                                         ? generateDeterministicId(loadTestConfig.beginTenantIdNumber)
                                         : 'kinotic')
    }

    private static createConnectionInfo(tenantId: string,
                                        structuresConfig: StructuresConnectionConfig):() => Promise<ConnectionInfo> {
        return  async () => {
            return {
                host                 : structuresConfig.structuresHost,
                port                 : structuresConfig.structuresPort,
                useSSL               : structuresConfig.stucturesUseSsl,
                maxConnectionAttempts: 5,
                connectHeaders       : {
                    login   : 'admin',
                    passcode: 'structures',
                    tenantId: tenantId
                }
            }
        }
    }

}
