package org.kinotic.structures.internal.api.services.sql;

import lombok.RequiredArgsConstructor;
import org.kinotic.continuum.idl.api.schema.FunctionDefinition;
import org.kinotic.structures.api.config.StructuresProperties;
import org.kinotic.structures.api.domain.NamedQueriesDefinition;
import org.kinotic.structures.api.domain.NamedQueryOperation;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.api.domain.idl.decorators.QueryDecorator;
import org.kinotic.structures.api.services.security.AuthorizationService;
import org.kinotic.structures.api.services.security.AuthorizationServiceFactory;
import org.kinotic.structures.internal.api.services.sql.elasticsearch.ElasticVertxClient;
import org.kinotic.structures.internal.api.services.sql.executors.AggregateQueryExecutor;
import org.kinotic.structures.internal.api.services.sql.executors.ParameterProcessorExecutor;
import org.kinotic.structures.internal.api.services.sql.executors.PreAuthorizationExecutor;
import org.kinotic.structures.internal.api.services.sql.executors.QueryExecutor;
import org.kinotic.structures.internal.utils.QueryUtils;
import org.springframework.stereotype.Component;

/**
 * Created by Navíd Mitchell 🤪 on 4/28/24.
 */
@Component
@RequiredArgsConstructor
public class DefaultQueryExecutorFactory implements QueryExecutorFactory {

    private final ElasticVertxClient elasticVertxClient;
    private final StructuresProperties structuresProperties;
    private final AuthorizationServiceFactory authorizationServiceFactory;

    public QueryExecutor createQueryExecutor(Structure structure,
                                             String queryName,
                                             NamedQueriesDefinition namedQueriesDefinition){
        FunctionDefinition namedQuery = null;
        for(FunctionDefinition queries : namedQueriesDefinition.getNamedQueries()){
            if(queries.getName().equals(queryName)){
                namedQuery = queries;
                break;
            }
        }
        if(namedQuery == null){
            throw new IllegalArgumentException("No query found with name " + queryName);
        }

        String statement = validateNamedQuery(namedQuery);
        QueryExecutor queryExecutor = new AggregateQueryExecutor(structure,
                                                                 elasticVertxClient,
                                                                 statement,
                                                                 structuresProperties);
        AuthorizationService<NamedQueryOperation> authorizationService =
                authorizationServiceFactory.createNamedQueryAuthorizationService(namedQuery).join();
        return new ParameterProcessorExecutor(structure,
                namedQuery,
                new PreAuthorizationExecutor(authorizationService, queryExecutor));
    }

    /**
     * Checks that a named query can be run, so saving one that can't is refused instead of every call to it failing.
     * Only a single SELECT statement with an aggregate (such as COUNT or SUM) is supported so far.
     * This naive approach to handling queries will be improved as we do more R&D on advanced approaches.
     *
     * @param namedQuery the named query
     * @return the query's statement, trimmed
     * @throws IllegalArgumentException if the named query can't be run
     */
    public static String validateNamedQuery(FunctionDefinition namedQuery) {
        QueryDecorator queryDecorator = namedQuery.findDecorator(QueryDecorator.class);
        if(queryDecorator == null
                || queryDecorator.getStatements() == null
                || queryDecorator.getStatements().isBlank()){
            throw new IllegalArgumentException("Named query " + namedQuery.getName() + " has no query defined");
        }

        String[] statements = queryDecorator.getStatements().split(";");
        if(statements.length != 1){
            throw new IllegalArgumentException("Named query " + namedQuery.getName() + " has multiple statements, which aren't supported yet");
        }

        String statement = statements[0].trim();
        SqlQueryType queryType;
        try {
            queryType = QueryUtils.determineQueryType(statement);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Named query " + namedQuery.getName() + " has an unsupported statement: " + statement, e);
        }
        if(queryType != SqlQueryType.AGGREGATE){
            String what = queryType == SqlQueryType.SELECT ? "a SELECT without an aggregate" : "a " + queryType + " statement";
            throw new IllegalArgumentException("Named query " + namedQuery.getName() + " is " + what
                                                       + ", which isn't supported yet. Only a SELECT with an aggregate, such as COUNT or SUM, is.");
        }
        return statement;
    }
}
