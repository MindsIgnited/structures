import {Page, Pageable} from '@kinotic/continuum-client'
import {ArrayC3Type, FunctionDefinition, LongC3Type, ObjectC3Type, StringC3Type} from '@kinotic/continuum-idl'
import {
    IEntityService,
    NamedQueriesDefinition,
    PageableC3Type,
    PageC3Type,
    QueryDecorator,
    Structure,
    Structures
} from '@kinotic/structures-api'
import * as allure from 'allure-js-commons'
import {afterAll, afterEach, beforeAll, beforeEach, describe, expect, it} from 'vitest'
import {WebSocket} from 'ws'
import {Person} from '../domain/Person.js'
import {
    createPersonStructureIfNotExist,
    createTestPeople,
    createTestPeopleAndVerify,
    deleteStructure,
    generateRandomString,
    initContinuumClient,
    shutdownContinuumClient,
} from '../TestHelpers.js'

Object.assign(global, { WebSocket})

interface LocalTestContext {
    structure: Structure
    applicationIdUsed: string
    projectIdUsed: string
    entityService: IEntityService<Person>
}

const COUNT_BY_LAST_NAME_PAGE = 'countPeopleByLastNamePage'

interface CountByLastName {
    count: number
    lastName: string
}

async function saveCountByLastNamePageQuery({entityService, applicationIdUsed, projectIdUsed}: LocalTestContext): Promise<void> {
    const query = new QueryDecorator(`SELECT COUNT(firstName) as count, lastName FROM "struct_${entityService.structureId}" GROUP BY lastName`)
    const namedQuery = new FunctionDefinition(COUNT_BY_LAST_NAME_PAGE, [query])
    namedQuery.addParameter('pageable', new PageableC3Type())
    namedQuery.returnType = new PageC3Type(new ObjectC3Type('CountByLastName', applicationIdUsed)
                                               .addProperty("count", new LongC3Type())
                                               .addProperty("lastName", new StringC3Type()))
    await Structures.getNamedQueriesService().save(new NamedQueriesDefinition(entityService.structureId,
                                                                              applicationIdUsed,
                                                                              projectIdUsed,
                                                                              entityService.structureName,
                                                                              [namedQuery]))
}

/**
 * Creates people named Last0, Last1, ... so grouping by last name gives one group per person
 */
async function createPeopleWithDistinctLastNames(entityService: IEntityService<Person>, numberToCreate: number): Promise<void> {
    const people: Person[] = createTestPeople(numberToCreate)
    people.forEach((person, i) => person.lastName = `Last${i}`)
    await expect(entityService.bulkSave(people)).resolves.toBeNull()
    await expect(entityService.syncIndex()).resolves.toBeNull()
}

/**
 * Fetches the count by last name query one page at a time, following each cursor by hand, and records
 * the shape the server returned: how many rows each page had and whether it carried a cursor
 */
async function pageShapes(entityService: IEntityService<Person>, pageSize: number): Promise<{rows: number, cursor: boolean}[]> {
    const ret: {rows: number, cursor: boolean}[] = []
    let cursor: string | null = null
    do {
        const page: Page<CountByLastName> = await entityService.namedQueryPage<CountByLastName>(COUNT_BY_LAST_NAME_PAGE,
                                                                                               [],
                                                                                               Pageable.createWithCursor(cursor, pageSize))
        ret.push({rows: page.content?.length ?? 0, cursor: page.cursor !== null && page.cursor !== undefined})
        cursor = page.cursor ?? null
    } while (cursor !== null && ret.length < 10)
    return ret
}

/**
 * Iterates the count by last name query with for await, returning the last names each yielded page held
 */
async function iteratedLastNames(entityService: IEntityService<Person>, pageSize: number): Promise<string[][]> {
    const ret: string[][] = []
    const firstPage = await entityService.namedQueryPage<CountByLastName>(COUNT_BY_LAST_NAME_PAGE,
                                                                          [],
                                                                          Pageable.createWithCursor(null, pageSize))
    for await (const page of firstPage) {
        ret.push((page.content ?? []).map(row => row.lastName))
    }
    return ret
}

describe('End To End Tests', () => {

    beforeAll(async () => {
        await allure.suite('Typescript Client')
        await allure.subSuite('Named Query Tests')
        await initContinuumClient()
    }, 300000)

    afterAll(async () => {
        await shutdownContinuumClient()
    }, 60000)

    beforeEach<LocalTestContext>(async (context) => {
        context.applicationIdUsed = generateRandomString(10)
        context.projectIdUsed = generateRandomString(5)
        context.structure = await createPersonStructureIfNotExist(context.applicationIdUsed, context.projectIdUsed)
        expect(context.structure).toBeDefined()
        context.entityService = Structures.createEntityService(context.structure.applicationId, context.structure.name)
        expect(context.entityService).toBeDefined()
    })

    afterEach<LocalTestContext>(async (context) => {
        await expect(deleteStructure(context.structure.id as string)).resolves.toBeUndefined()
        await expect(Structures.getStructureService().syncIndex()).resolves.toBeNull()
        await Structures.getProjectService().deleteById(context.structure.projectId)
        await expect(Structures.getProjectService().syncIndex()).resolves.toBeNull()
        await Structures.getApplicationService().deleteById(context.structure.applicationId)
    })


    it<LocalTestContext>(
        'Aggregate Test',
        async ({entityService, applicationIdUsed, projectIdUsed}) => {
            // Create people
            await createTestPeopleAndVerify(entityService, 100)

            const structureId = entityService.structureId

            const query = new QueryDecorator(`SELECT COUNT(firstName) as count FROM "struct_${structureId}"`)
            const namedQuery = new FunctionDefinition('countAllPeople', [query])
            namedQuery.returnType = new ArrayC3Type(new ObjectC3Type('PeopleCount', applicationIdUsed)
                                                        .addProperty("count", new LongC3Type()))

            const namedQueriesDefinition = new NamedQueriesDefinition(structureId,
                                                                      applicationIdUsed,
                                                                      projectIdUsed,
                                                                      entityService.structureName,
                                                                      [namedQuery])


            const namedQueriesService = Structures.getNamedQueriesService()
            await namedQueriesService.save(namedQueriesDefinition)

            const countResult: any = await entityService.namedQuery('countAllPeople', [])
            expect(countResult).toBeDefined()
            expect(countResult).toHaveLength(1)
            expect(countResult[0]).toBeDefined()
            expect(countResult[0].count).toBe(100)
        }
    )

    it<LocalTestContext>(
        'Aggregate With Parameter Test',
        async ({entityService, applicationIdUsed, projectIdUsed}) => {
            // Create people
            await createTestPeopleAndVerify(entityService, 100)

            const structureId = entityService.structureId

            const query = new QueryDecorator(`SELECT COUNT(firstName) as count, lastName FROM "struct_${structureId}" WHERE lastName = ? GROUP BY lastName`)
            const namedQuery = new FunctionDefinition('countPeopleByLastNameWithLastName', [query])
            namedQuery.addParameter('lastName', new StringC3Type())
            const contentType = new ObjectC3Type('CountByLastName', applicationIdUsed)
                .addProperty("count", new LongC3Type())
                .addProperty("lastName", new StringC3Type())
            namedQuery.returnType = new ArrayC3Type(contentType)

            const namedQueriesDefinition = new NamedQueriesDefinition(structureId,
                                                                      applicationIdUsed,
                                                                      projectIdUsed,
                                                                      entityService.structureName,
                                                                      [namedQuery])


            const namedQueriesService = Structures.getNamedQueriesService()
            await namedQueriesService.save(namedQueriesDefinition)

            const countResult: any = await entityService.namedQuery('countPeopleByLastNameWithLastName',
                                                                    [{key: 'lastName', value: 'Doe'}])

            expect(countResult).toBeDefined()
            expect(countResult).toHaveLength(1)
            expect(countResult[0]).toBeDefined()
            expect(countResult[0].count).toBe(50)
        }
    )

    it<LocalTestContext>(
        'Aggregate Pageable Test',
        async (context) => {
            // 100 people over two last names, so two groups
            await createTestPeopleAndVerify(context.entityService, 100)
            await saveCountByLastNamePageQuery(context)

            expect(await pageShapes(context.entityService, 1)).toEqual([{rows: 1, cursor: true},
                                                                        {rows: 1, cursor: true},
                                                                        {rows: 0, cursor: false}])
        }
    )

    it<LocalTestContext>(
        'Aggregate Iterate Uneven Pages Test',
        async (context) => {
            await createPeopleWithDistinctLastNames(context.entityService, 5)
            await saveCountByLastNamePageQuery(context)

            // The case the iterator must handle: the short last page has content but no cursor
            expect(await pageShapes(context.entityService, 2)).toEqual([{rows: 2, cursor: true},
                                                                        {rows: 2, cursor: true},
                                                                        {rows: 1, cursor: false}])
            expect(await iteratedLastNames(context.entityService, 2)).toEqual([['Last0', 'Last1'],
                                                                               ['Last2', 'Last3'],
                                                                               ['Last4']])
        }
    )

    it<LocalTestContext>(
        'Aggregate Iterate Even Pages Test',
        async (context) => {
            await createPeopleWithDistinctLastNames(context.entityService, 4)
            await saveCountByLastNamePageQuery(context)

            // A full last page keeps its cursor and the server ends on an empty page, which the
            // iterator must not yield
            expect(await pageShapes(context.entityService, 2)).toEqual([{rows: 2, cursor: true},
                                                                        {rows: 2, cursor: true},
                                                                        {rows: 0, cursor: false}])
            expect(await iteratedLastNames(context.entityService, 2)).toEqual([['Last0', 'Last1'],
                                                                               ['Last2', 'Last3']])
        }
    )

    it<LocalTestContext>(
        'Test Save Multiple',
        async ({entityService, applicationIdUsed, projectIdUsed}) => {
            const structureId = entityService.structureId
            const namedQueriesService = Structures.getNamedQueriesService()

            const query = new QueryDecorator(`SELECT COUNT(firstName) as count FROM "struct_${structureId}"`)
            const namedQuery = new FunctionDefinition('countAllPeople', [query])
            namedQuery.returnType = new ArrayC3Type(new ObjectC3Type('PeopleCount', applicationIdUsed)
                                                        .addProperty("count", new LongC3Type()))


            const query2 = new QueryDecorator(`SELECT COUNT(firstName) as count, lastName FROM "struct_${structureId}" WHERE lastName = ? GROUP BY lastName`)
            const namedQuery2 = new FunctionDefinition('countPeopleByLastNameWithLastName', [query2])
            namedQuery2.addParameter('lastName', new StringC3Type())
            const contentType2 = new ObjectC3Type('CountByLastName', applicationIdUsed)
                .addProperty("count", new LongC3Type())
                .addProperty("lastName", new StringC3Type())
            namedQuery2.returnType = new ArrayC3Type(contentType2)


            const query3 = new QueryDecorator(`SELECT COUNT(firstName) as count, lastName FROM "struct_${structureId}" GROUP BY lastName`)
            const namedQuery3 = new FunctionDefinition('countPeopleByLastNamePage', [query3])
            namedQuery3.addParameter('pageable', new PageableC3Type())
            const contentType3 = new ObjectC3Type('CountByLastName', applicationIdUsed)
                .addProperty("count", new LongC3Type())
                .addProperty("lastName", new StringC3Type())
            namedQuery3.returnType = new PageC3Type(contentType3)

            // Save the named queries
            const namedQueriesDefinition = new NamedQueriesDefinition(structureId,
                                                                      applicationIdUsed,
                                                                      projectIdUsed,
                                                                      entityService.structureName,
                                                                      [namedQuery, namedQuery2, namedQuery3])
            await namedQueriesService.save(namedQueriesDefinition)
        }
    )


})
