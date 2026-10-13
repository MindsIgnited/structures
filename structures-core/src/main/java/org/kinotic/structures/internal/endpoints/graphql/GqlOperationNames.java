package org.kinotic.structures.internal.endpoints.graphql;

import org.apache.commons.text.WordUtils;
import org.kinotic.continuum.idl.api.schema.FunctionDefinition;
import org.kinotic.structures.api.domain.NamedQueriesDefinition;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.api.domain.idl.decorators.QueryDecorator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The GraphQL operation names Structures generates for a structure: each is an operation followed by the structure
 * name, so two structures in an application can produce the same name (saveSync for Job and save for SyncJob are both
 * saveSyncJob). {@link GqlOperationNameValidator} uses these to refuse a structure or named query whose names another
 * structure already has.
 * <p>
 * These must match what {@link DefaultGqlOperationDefinitionService} and
 * {@link NamedQueryGqlOperationDefinitionCacheLoader} generate; GqlOperationNameTests checks them against a schema.
 */
public final class GqlOperationNames {

    /**
     * The built-in operations, each named the operation followed by the structure name.
     * GraphQL keeps queries and mutations apart, but they are treated as one set here to keep the check simple.
     */
    static final List<String> OPERATIONS = List.of("bulkSave",
                                                   "bulkUpdate",
                                                   "count",
                                                   "delete",
                                                   "deleteSync",
                                                   "findAll",
                                                   "findAllWithCursor",
                                                   "findById",
                                                   "save",
                                                   "saveSync",
                                                   "search",
                                                   "searchWithCursor",
                                                   "sync",
                                                   "update",
                                                   "updateSync");

    private GqlOperationNames() {
    }

    /**
     * Returns every GraphQL operation name generated for a structure and its named queries, with what each one is for
     *
     * @param structure    the structure
     * @param namedQueries the structure's named queries, or null if it has none
     * @param clashes      receives a message for each named query whose name a built-in operation or an earlier
     *                     named query of the structure already has, or null to ignore them
     * @return each generated name, mapped to a description of what it is for; a clashing name keeps the first one
     */
    public static Map<String, String> forStructure(Structure structure,
                                                   NamedQueriesDefinition namedQueries,
                                                   List<String> clashes) {
        String structureName = WordUtils.capitalize(structure.getName());
        Map<String, String> ret = new LinkedHashMap<>();

        for (String operation : OPERATIONS) {
            ret.put(operation + structureName, "the " + operation + " operation of structure " + structure.getName());
        }

        if (namedQueries != null && namedQueries.getNamedQueries() != null) {
            for (FunctionDefinition query : namedQueries.getNamedQueries()) {
                if (query.findDecorator(QueryDecorator.class) == null) {
                    continue; // no operation is generated without a query
                }
                // A query returning a page gets a cursor and an offset definition, but both are given this field name
                String name = query.getName() + structureName;
                String description = "named query " + query.getName() + " of structure " + structure.getName();
                String existing = ret.putIfAbsent(name, description);
                if (existing != null && clashes != null) {
                    clashes.add("the GraphQL operation %s for %s is already %s".formatted(name, description, existing));
                }
            }
        }
        return ret;
    }
}
