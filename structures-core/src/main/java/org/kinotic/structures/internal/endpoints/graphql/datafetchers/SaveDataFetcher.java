package org.kinotic.structures.internal.endpoints.graphql.datafetchers;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.vertx.ext.web.RoutingContext;
import org.kinotic.structures.api.domain.EntityContext;
import org.kinotic.structures.api.services.EntitiesService;
import org.kinotic.structures.internal.endpoints.openapi.RoutingContextToEntityContextAdapter;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Created by Navíd Mitchell 🤪 on 4/18/23.
 */
@SuppressWarnings("rawtypes")
public class SaveDataFetcher implements DataFetcher<CompletableFuture<Map>> {

    private final String structureId;
    private final EntitiesService entitiesService;
    private final boolean sync;

    /**
     * @param sync true to refresh before returning, so the change is searchable straight away
     */
    public SaveDataFetcher(String structureId, EntitiesService entitiesService, boolean sync) {
        this.structureId = structureId;
        this.entitiesService = entitiesService;
        this.sync = sync;
    }

    @Override
    public CompletableFuture<Map> get(DataFetchingEnvironment environment) throws Exception {
        RoutingContext rc = environment.getGraphQlContext().get(RoutingContext.class);
        Objects.requireNonNull(rc);
        EntityContext ec = new RoutingContextToEntityContextAdapter(rc);

        Map entity = environment.getArgument("input");

        return sync ? entitiesService.saveSync(structureId, entity, ec) : entitiesService.save(structureId, entity, ec);
    }
}
