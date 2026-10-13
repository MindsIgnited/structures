package org.kinotic.structures.internal.endpoints.graphql;

import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import lombok.RequiredArgsConstructor;
import org.kinotic.continuum.core.api.crud.Page;
import org.kinotic.continuum.core.api.crud.Pageable;
import org.kinotic.structures.api.domain.NamedQueriesDefinition;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.internal.api.services.StructureDAO;
import org.kinotic.structures.internal.api.services.impl.CrudServiceTemplate;
import org.kinotic.structures.internal.api.services.impl.DefaultNamedQueriesService;
import org.kinotic.structures.internal.utils.StructuresUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Refuses a structure, or a structure's named queries, whose GraphQL operation names another published structure in
 * the same application already has. Without this, one operation would replace the other in the schema, so a client
 * calling saveSyncJob could start writing to a different structure. See {@link GqlOperationNames}.
 * <p>
 * OpenAPI is not checked: its paths include the structure name and never clash, only operationIds can repeat (count
 * for OrderAdmin and the admin count for Order are both countOrderAdmin), which only affects generated clients.
 */
@Component
@RequiredArgsConstructor
public class GqlOperationNameValidator {

    private static final int PAGE_SIZE = 500;

    private final CrudServiceTemplate crudServiceTemplate;
    private final StructureDAO structureDAO;

    /**
     * Checks a structure about to be published, with the named queries already saved for it
     *
     * @param structure the structure to publish
     * @return a {@link CompletableFuture} that fails with an {@link IllegalArgumentException} naming every clash
     */
    public CompletableFuture<Void> validatePublish(Structure structure) {
        return findAllNamedQueries(structure.getApplicationId())
                .thenCompose(namedQueries -> validate(structure,
                                                      namedQueries.get(structure.getName()),
                                                      namedQueries,
                                                      "Structure " + structure.getName() + " can't be published"));
    }

    /**
     * Checks named queries about to be saved, against their own structure and the other published structures
     *
     * @param namedQueries the named queries to save
     * @return a {@link CompletableFuture} that fails with an {@link IllegalArgumentException} naming every clash
     */
    public CompletableFuture<Void> validateNamedQueries(NamedQueriesDefinition namedQueries) {
        String structureId = StructuresUtil.structureNameToId(namedQueries.getApplicationId(), namedQueries.getStructure());
        return structureDAO.findById(structureId)
                           .thenCompose(structure -> {
                               if (structure == null) {
                                   return CompletableFuture.completedFuture(null); // no operation is generated without a structure
                               }
                               return findAllNamedQueries(structure.getApplicationId())
                                       .thenCompose(allNamedQueries -> validate(structure,
                                                                                namedQueries,
                                                                                allNamedQueries,
                                                                                "Named queries for structure " + structure.getName() + " can't be saved"));
                           });
    }

    private CompletableFuture<Void> validate(Structure structure,
                                             NamedQueriesDefinition namedQueries,
                                             Map<String, NamedQueriesDefinition> allNamedQueries,
                                             String refusal) {
        List<String> clashes = new ArrayList<>();
        Map<String, String> names = GqlOperationNames.forStructure(structure, namedQueries, clashes);

        return findAll(pageable -> structureDAO.findAllPublishedForApplication(structure.getApplicationId(), pageable))
                .thenCompose(publishedStructures -> {
                    for (Structure other : publishedStructures) {
                        if (other.getId().equals(structure.getId())) {
                            continue;
                        }
                        Map<String, String> otherNames
                                = GqlOperationNames.forStructure(other, allNamedQueries.get(other.getName()), null);
                        for (Map.Entry<String, String> entry : names.entrySet()) {
                            String existing = otherNames.get(entry.getKey());
                            if (existing != null) {
                                clashes.add("the GraphQL operation %s for %s is already %s".formatted(entry.getKey(),
                                                                                                    entry.getValue(),
                                                                                                    existing));
                            }
                        }
                    }
                    if (clashes.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return CompletableFuture.failedFuture(
                            new IllegalArgumentException(refusal + ": " + String.join("; ", clashes)));
                });
    }

    /**
     * @return the named queries of every structure in the application, by structure name
     */
    private CompletableFuture<Map<String, NamedQueriesDefinition>> findAllNamedQueries(String applicationId) {
        return findAll(pageable -> crudServiceTemplate.search(DefaultNamedQueriesService.INDEX_NAME,
                                                              pageable,
                                                              NamedQueriesDefinition.class,
                                                              builder -> builder.query(q -> q.term(TermQuery.of(tq -> tq.field("applicationId")
                                                                                                                        .value(applicationId))))))
                .thenApply(definitions -> {
                    Map<String, NamedQueriesDefinition> ret = new HashMap<>();
                    for (NamedQueriesDefinition definition : definitions) {
                        ret.put(definition.getStructure(), definition);
                    }
                    return ret;
                });
    }

    private static <T> CompletableFuture<List<T>> findAll(Function<Pageable, CompletableFuture<Page<T>>> findPage) {
        return findFrom(findPage, 0, new ArrayList<>());
    }

    private static <T> CompletableFuture<List<T>> findFrom(Function<Pageable, CompletableFuture<Page<T>>> findPage,
                                                           int pageNumber,
                                                           List<T> found) {
        return findPage.apply(Pageable.create(pageNumber, PAGE_SIZE, null))
                       .thenCompose(page -> {
                           List<T> content = page.getContent() != null ? page.getContent() : List.of();
                           found.addAll(content);
                           long total = page.getTotalElements() != null ? page.getTotalElements() : found.size();
                           if (content.isEmpty() || found.size() >= total) {
                               return CompletableFuture.completedFuture(found);
                           }
                           return findFrom(findPage, pageNumber + 1, found);
                       });
    }
}
