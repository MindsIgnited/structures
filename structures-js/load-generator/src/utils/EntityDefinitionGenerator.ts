import { ObjectC3Type } from '@kinotic/continuum-idl'
import { ConsoleLogger } from '@kinotic/structures-cli/dist/internal/Logger.js'
import { CodeGenerationService } from '@kinotic/structures-cli/dist/internal/CodeGenerationService.js'
import { TypescriptProjectConfig } from '@kinotic/structures-api'

export class EntityDefinitionGenerator {
    private readonly codeGenerationService: CodeGenerationService
    private readonly logger: ConsoleLogger

    constructor(
        private readonly namespace: string,
        private readonly entitiesPath: string,
        private readonly generatedPath: string
    ) {
        this.logger = new ConsoleLogger()
        this.codeGenerationService = new CodeGenerationService(namespace, '.js', this.logger)
    }

    async generateDefinitions(): Promise<Map<string, ObjectC3Type>> {
        const definitions = new Map<string, ObjectC3Type>()
        
        const namespaceConfig = new TypescriptProjectConfig()
        namespaceConfig.application = this.namespace
        namespaceConfig.fileExtensionForImports = '.js'
        namespaceConfig.validate = false
        namespaceConfig.entitiesPaths = [this.entitiesPath]
        namespaceConfig.generatedPath = this.generatedPath

        await this.codeGenerationService.generateAllEntities(
            namespaceConfig,
            true,
            async (entityInfo) => {
                definitions.set(entityInfo.entity.name.toLowerCase(), entityInfo.entity)
                this.logger.log(`Generated entity definition for ${entityInfo.entity.name}`)
            }
        )

        return definitions
    }
} 