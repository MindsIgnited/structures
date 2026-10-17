package org.kinotic.structures.internal.endpoints.graphql;

import graphql.language.OperationDefinition;
import org.apache.commons.text.WordUtils;
import org.kinotic.continuum.idl.api.schema.FunctionDefinition;
import org.kinotic.structures.api.domain.NamedQueriesDefinition;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.api.domain.idl.decorators.QueryDecorator;
import org.kinotic.structures.internal.api.services.sql.SqlQueryType;
import org.kinotic.structures.internal.utils.QueryUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The GraphQL operation names Structures generates for a structure: each is an operation followed by the structure
 * name, so two structures in an application can produce the same name (saveSync for Job and save for SyncJob are both
 * saveSyncJob). {@link GqlOperationNameValidator} uses these to refuse a structure or named query whose names another
 * structure already has. GraphQL keeps queries and mutations apart, so a name only clashes with one of the same type.
 * <p>
 * Built-in operations are named their {@link GqlOperationDefinition#getOperationName()} followed by the structure name,
 * and named queries their query name followed by the structure name, as
 * {@link NamedQueryGqlOperationDefinitionCacheLoader} does. GqlOperationNameTests checks these against a schema.
 */
public final class GqlOperationNames {

    /**
     * A generated operation: GraphQL keeps query and mutation names apart, so both parts identify it
     */
    public record OperationName(OperationDefinition.Operation type, String name) {
        @Override
        public String toString() {
            return "GraphQL " + type.name().toLowerCase() + " " + name;
        }
    }

    private GqlOperationNames() {
    }

    /**
     * Returns every GraphQL operation generated for a structure and its named queries, with what each one is for
     *
     * @param builtInOperations the built-in operations generated for every structure, including the Sync ones
     * @param structure         the structure
     * @param namedQueries      the structure's named queries, or null if it has none
     * @param clashes           receives a message for each named query whose operation a built-in operation or an
     *                          earlier named query of the structure already has, or null to ignore them
     * @return each generated operation, mapped to a description of what it is for; a clash keeps the first one
     */
    public static Map<OperationName, String> forStructure(List<GqlOperationDefinition> builtInOperations,
                                                          Structure structure,
                                                          NamedQueriesDefinition namedQueries,
                                                          List<String> clashes) {
        String structureName = WordUtils.capitalize(structure.getName());
        Map<OperationName, String> ret = new LinkedHashMap<>();

        for (GqlOperationDefinition operation : builtInOperations) {
            ret.put(new OperationName(operation.getOperationType(), operation.getOperationName() + structureName),
                    "the " + operation.getOperationName() + " operation of structure " + structure.getName());
        }

        if (namedQueries != null && namedQueries.getNamedQueries() != null) {
            for (FunctionDefinition query : namedQueries.getNamedQueries()) {
                OperationDefinition.Operation type = operationType(query);
                if (type == null) {
                    continue; // no operation is generated for it
                }
                // A query returning a page gets a cursor and an offset definition, but both are given this field name
                OperationName name = new OperationName(type, query.getName() + structureName);
                String description = "named query " + query.getName() + " of structure " + structure.getName();
                String existing = ret.putIfAbsent(name, description);
                if (existing != null && clashes != null) {
                    clashes.add("the %s for %s is already %s".formatted(name, description, existing));
                }
            }
        }
        return ret;
    }

    /**
     * @return the type of operation generated for a named query, or null if none is, as for a query without statements
     */
    private static OperationDefinition.Operation operationType(FunctionDefinition query) {
        QueryDecorator queryDecorator = query.findDecorator(QueryDecorator.class);
        if (queryDecorator == null || queryDecorator.getStatements() == null) {
            return null;
        }
        SqlQueryType queryType;
        try {
            queryType = QueryUtils.determineQueryType(queryDecorator.getStatements());
        } catch (IllegalArgumentException e) {
            return null; // an unsupported statement fails elsewhere, and generates no operation
        }
        return NamedQueryGqlOperationDefinitionCacheLoader.getGqlOperationType(queryType);
    }
}
