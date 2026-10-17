package org.kinotic.structures.internal.endpoints.graphql;

import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import org.kinotic.structures.api.domain.NamedQueriesDefinition;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.internal.api.services.StructureDAO;
import org.kinotic.structures.internal.api.services.impl.CrudServiceTemplate;
import org.kinotic.structures.internal.api.services.impl.DefaultNamedQueriesService;
import org.kinotic.structures.internal.utils.PagingUtils;
import org.kinotic.structures.internal.utils.StructuresUtil;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Refuses a structure, or a structure's named queries, whose GraphQL operation names another published structure in
 * the same application already has. Without this, one operation would replace the other in the schema, so a client
 * calling saveSyncJob could start writing to a different structure. See {@link GqlOperationNames}.
 * <p>
 * OpenAPI is not checked: its paths include the structure name and never clash, only operationIds can repeat (count
 * for OrderAdmin and the admin count for Order are both countOrderAdmin), which only affects generated clients.
 * <p>
 * The check runs before the publish or save and takes no lock, so two clashing structures published at the same moment
 * can both pass. Publishing is rare enough that this is accepted; if it happens, the schema keeps the non-Sync
 * operation for a clashing Sync one (see GqlSchemaHandlerCacheLoader), and unpublishing either structure resolves it.
 */
@Component
public class GqlOperationNameValidator {

    private static final int PAGE_SIZE = 500;

    private final CrudServiceTemplate crudServiceTemplate;
    private final GqlOperationDefinitionService gqlOperationDefinitionService;
    private final StructureDAO structureDAO;

    /**
     * @param gqlOperationDefinitionService lazy, since it depends on the entity and named query services, which
     *                                      depend on this
     */
    public GqlOperationNameValidator(CrudServiceTemplate crudServiceTemplate,
                                     @Lazy GqlOperationDefinitionService gqlOperationDefinitionService,
                                     StructureDAO structureDAO) {
        this.crudServiceTemplate = crudServiceTemplate;
        this.gqlOperationDefinitionService = gqlOperationDefinitionService;
        this.structureDAO = structureDAO;
    }

    /**
     * Checks a structure about to be published, with the named queries already saved for it. Every operation it
     * generates is checked, so republishing after an unpublish is refused if a clashing structure was published since.
     *
     * @param structure the structure to publish
     * @return a {@link CompletableFuture} that fails with an {@link IllegalArgumentException} naming every clash
     */
    public CompletableFuture<Void> validatePublish(Structure structure) {
        return findAllNamedQueries(structure.getApplicationId())
                .thenCompose(namedQueries -> validate(structure,
                                                      namedQueries.get(structure.getName()),
                                                      false,
                                                      namedQueries,
                                                      "Structure " + structure.getName() + " can't be published"));
    }

    /**
     * Checks named queries about to be saved, against their own structure and the other published structures.
     * Only the named queries' operations are checked, since a save doesn't change the structure's built-in ones.
     *
     * @param namedQueries the named queries to save
     * @return a {@link CompletableFuture} that fails with an {@link IllegalArgumentException} naming every clash
     */
    public CompletableFuture<Void> validateNamedQueries(NamedQueriesDefinition namedQueries) {
        String structureId = StructuresUtil.structureNameToId(namedQueries.getApplicationId(), namedQueries.getStructure());
        return structureDAO.findById(structureId)
                           .thenCompose(structure -> {
                               // Named queries are found by the exact structure name, so with another case they
                               // generate no operations, and nothing is generated without a structure yet either.
                               // Publishing the structure checks the named queries saved for it.
                               if (structure == null || !structure.getName().equals(namedQueries.getStructure())) {
                                   return CompletableFuture.completedFuture(null);
                               }
                               return findAllNamedQueries(structure.getApplicationId())
                                       .thenCompose(allNamedQueries -> validate(structure,
                                                                                namedQueries,
                                                                                true,
                                                                                allNamedQueries,
                                                                                "Named queries for structure " + structure.getName() + " can't be saved"));
                           });
    }

    /**
     * @param onlyNamedQueries true to check only the operations of the named queries, not the built-in ones
     */
    private CompletableFuture<Void> validate(Structure structure,
                                             NamedQueriesDefinition namedQueries,
                                             boolean onlyNamedQueries,
                                             Map<String, NamedQueriesDefinition> allNamedQueries,
                                             String refusal) {
        List<GqlOperationDefinition> builtInOperations = builtInOperations();
        List<String> clashes = new ArrayList<>();
        Map<GqlOperationNames.OperationName, String> names
                = GqlOperationNames.forStructure(builtInOperations, structure, namedQueries, clashes);
        if (onlyNamedQueries) {
            names.keySet().removeAll(GqlOperationNames.forStructure(builtInOperations, structure, null, null).keySet());
        }

        return PagingUtils.findAll(pageable -> structureDAO.findAllPublishedForApplication(structure.getApplicationId(), pageable),
                                   "id",
                                   PAGE_SIZE)
                          .thenCompose(publishedStructures -> {
                              for (Structure other : publishedStructures) {
                                  if (other.getId().equals(structure.getId())) {
                                      continue;
                                  }
                                  Map<GqlOperationNames.OperationName, String> otherNames
                                          = GqlOperationNames.forStructure(builtInOperations,
                                                                           other,
                                                                           allNamedQueries.get(other.getName()),
                                                                           null);
                                  for (Map.Entry<GqlOperationNames.OperationName, String> entry : names.entrySet()) {
                                      String existing = otherNames.get(entry.getKey());
                                      if (existing != null) {
                                          clashes.add("the %s for %s is already %s".formatted(entry.getKey(),
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

    private List<GqlOperationDefinition> builtInOperations() {
        List<GqlOperationDefinition> ret = new ArrayList<>(gqlOperationDefinitionService.getBuiltInOperationDefinitions());
        ret.addAll(gqlOperationDefinitionService.getSyncOperationDefinitions());
        return ret;
    }

    /**
     * @return the named queries of every structure in the application, by structure name
     */
    private CompletableFuture<Map<String, NamedQueriesDefinition>> findAllNamedQueries(String applicationId) {
        return PagingUtils.findAll(pageable -> crudServiceTemplate.search(DefaultNamedQueriesService.INDEX_NAME,
                                                                          pageable,
                                                                          NamedQueriesDefinition.class,
                                                                          builder -> builder.query(q -> q.term(TermQuery.of(tq -> tq.field("applicationId")
                                                                                                                                    .value(applicationId))))),
                                   "id",
                                   PAGE_SIZE)
                          .thenApply(definitions -> {
                              Map<String, NamedQueriesDefinition> ret = new HashMap<>();
                              for (NamedQueriesDefinition definition : definitions) {
                                  ret.put(definition.getStructure(), definition);
                              }
                              return ret;
                          });
    }
}
