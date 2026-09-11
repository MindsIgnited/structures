package org.kinotic.structures.internal.endpoints.graphql;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.graphql.GraphQLHandler;
import lombok.extern.slf4j.Slf4j;

import org.kinotic.structures.auth.internal.services.DefaultCaffeineCacheFactory;
import org.kinotic.structures.internal.cache.events.CacheEvictionEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Created by Navíd Mitchell 🤪 on 11/19/24.
 */
@Slf4j
@Component
public class DefaultDelegatingGqlHandler implements DelegatingGqlHandler {

    private final AsyncLoadingCache<String, GraphQLHandler> graphQLHandlerCache;

    public DefaultDelegatingGqlHandler(GqlSchemaHandlerCacheLoader gqlSchemaHandlerCacheLoader,
                                       DefaultCaffeineCacheFactory cacheFactory) {
        graphQLHandlerCache = cacheFactory.<String, GraphQLHandler>newBuilder()
                .name("graphQLHandlerCache")
                .expireAfterAccess(Duration.ofHours(20))
                .maximumSize(2000)
                .buildAsync(gqlSchemaHandlerCacheLoader);
    }

    /**
     * Evicts the caches for a application event. This can be an change to a named
     * query or a structure.
     * 
     * @param event the event containing the structure or named query to evict the
     *              caches for
     */
    @EventListener
    public void handleCacheEviction(CacheEvictionEvent event) {

        try {

            if (event.getApplicationId() != null) {
                graphQLHandlerCache.asMap().remove(event.getApplicationId());

                log.info("Successfully completed cache eviction for entity: {}:{}:{} due to {} {} {}",
                    event.getApplicationId(), event.getStructureId(),
                    event.getNamedQueryId(),
                    event.getEvictionSourceType(), event.getEvictionOperation(),
                    event.getEvictionSource().getDisplayName());
            }

        } catch (Exception e) {
            log.error("Failed to handle cache eviction (source: {})",
                    event.getSource(), e);
        }
    }

    @Override
    public void handle(RoutingContext rc) {
        String application = rc.pathParam(GqlVerticle.APPLICATION_PATH_PARAMETER);

        Future.fromCompletionStage(graphQLHandlerCache.get(application),
                rc.vertx().getOrCreateContext())
                .onSuccess(graphQLHandler -> graphQLHandler.handle(rc))
                // Resolving the schema fails for an unknown application among other things. Without
                // this nothing ever responds and the request hangs until the client gives up; the
                // route's failure handler turns it into a response
                .onFailure(rc::fail);
    }

}
