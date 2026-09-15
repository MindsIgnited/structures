import {ITask} from './ITask.js'
import {ITaskGenerator} from './ITaskGenerator.js'
import {generatePeople} from '@/utils/DataUtil.js'

export interface OpenApiMixedConfig {
    /** Where the OpenAPI endpoints answer, up to and excluding `/api/...` */
    baseUrl: string
    applicationId: string
    structureName: string
    tenantId: string
    /** People per bulk save */
    batchSize: number
    /** Page size for find-all and search */
    pageSize: number
    searchText: string
    /** How many operations to issue; {@link Number.POSITIVE_INFINITY} for a duration-bounded run */
    totalOperations: number
}

/**
 * A mixed read/write workload over the OpenAPI endpoints of one structure, weighted the way an
 * application would use them: single saves and reads by id dominate, with pages, searches, counts
 * and the occasional bulk save. Ids returned by saves feed the reads, so the reads hit real rows.
 */
export class OpenApiMixedTaskGenerator implements ITaskGenerator {

    private static readonly WEIGHTS: Array<[string, number]> = [
        ['OpenAPI save', 25],
        ['OpenAPI bulk save', 5],
        ['OpenAPI find by id', 25],
        ['OpenAPI find all', 15],
        ['OpenAPI search', 20],
        ['OpenAPI count', 10]
    ]
    private static readonly MAX_REMEMBERED_IDS = 10000

    private readonly config: OpenApiMixedConfig
    private readonly entityUrl: string
    private readonly headers: Record<string, string>
    private readonly knownIds: string[] = []
    private remaining: number

    constructor(config: OpenApiMixedConfig) {
        this.config = config
        this.remaining = config.totalOperations
        this.entityUrl = `${config.baseUrl.replace(/\/$/, '')}/api/${config.applicationId}/${config.structureName.toLowerCase()}`
        this.headers = {
            'Authorization': 'Basic ' + Buffer.from('admin:structures').toString('base64'),
            'Content-Type': 'application/json',
            'tenantId': config.tenantId
        }
    }

    getNextTask(): ITask {
        this.remaining--
        let name = this.pick()
        if (name === 'OpenAPI find by id' && this.knownIds.length === 0) {
            name = 'OpenAPI save' // nothing to read yet
        }
        return {
            name: () => name,
            execute: () => this.execute(name)
        }
    }

    hasMoreTasks(): boolean {
        return this.remaining > 0
    }

    private pick(): string {
        const total = OpenApiMixedTaskGenerator.WEIGHTS.reduce((sum, [, w]) => sum + w, 0)
        let roll = Math.random() * total
        for (const [name, weight] of OpenApiMixedTaskGenerator.WEIGHTS) {
            roll -= weight
            if (roll < 0) {
                return name
            }
        }
        return OpenApiMixedTaskGenerator.WEIGHTS[0][0]
    }

    private async execute(name: string): Promise<void> {
        switch (name) {
            case 'OpenAPI save': {
                const person = generatePeople(1)[0]
                const saved = await this.request<{ id: string }>('POST', this.entityUrl, JSON.stringify(person))
                this.remember(saved.id ?? person.id)
                return
            }
            case 'OpenAPI bulk save': {
                const people = generatePeople(this.config.batchSize)
                await this.request('POST', `${this.entityUrl}/bulk`, JSON.stringify(people), false)
                for (const person of people) {
                    this.remember(person.id)
                }
                return
            }
            case 'OpenAPI find by id': {
                const id = this.knownIds[Math.floor(Math.random() * this.knownIds.length)]
                await this.request('GET', `${this.entityUrl}/${encodeURIComponent(id)}`)
                return
            }
            case 'OpenAPI find all': {
                await this.request('GET', `${this.entityUrl}?page=0&size=${this.config.pageSize}`)
                return
            }
            case 'OpenAPI search': {
                await this.request('POST', `${this.entityUrl}/search?page=0&size=${this.config.pageSize}`,
                                   this.config.searchText, true, 'text/plain')
                return
            }
            case 'OpenAPI count': {
                await this.request('GET', `${this.entityUrl}/count/all`)
                return
            }
            default:
                throw new Error(`Unknown operation ${name}`)
        }
    }

    private remember(id: string): void {
        if (this.knownIds.length >= OpenApiMixedTaskGenerator.MAX_REMEMBERED_IDS) {
            this.knownIds[Math.floor(Math.random() * this.knownIds.length)] = id
        } else {
            this.knownIds.push(id)
        }
    }

    private async request<T = any>(method: string,
                                   url: string,
                                   body?: string,
                                   parse: boolean = true,
                                   contentType: string = 'application/json'): Promise<T> {
        const response = await fetch(url, {
            method,
            headers: {...this.headers, 'Content-Type': contentType},
            body
        })
        const text = await response.text()
        if (!response.ok) {
            throw new Error(`${method} ${url} -> ${response.status}: ${text.slice(0, 200)}`)
        }
        return (parse && text.length > 0 ? JSON.parse(text) : undefined) as T
    }
}
