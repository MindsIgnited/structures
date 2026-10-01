package org.kinotic.structures.internal.api.services.sql.elasticsearch;

import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.elasticsearch.sql.TranslateResponse;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.SimpleJsonpMapper;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.tracing.TracingPolicy;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.mutable.MutableObject;
import org.kinotic.continuum.core.api.crud.CursorPage;
import org.kinotic.continuum.core.api.crud.CursorPageable;
import org.kinotic.continuum.core.api.crud.Page;
import org.kinotic.continuum.core.api.crud.Pageable;
import org.kinotic.structures.api.config.StructuresProperties;
import org.kinotic.structures.api.domain.QueryOptions;
import org.kinotic.structures.api.domain.RawJson;
import org.kinotic.structures.auth.internal.services.DefaultCaffeineCacheFactory;
import org.kinotic.structures.api.config.ElasticConnectionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Provides access to ElasticSearch via Vertx.
 * This was done because the ElasticSearch Java client is missing functionality that we need.
 * Requests are spread round robin over every configured Elasticsearch connection, and a request that cannot reach a
 * node is retried on the next one, see {@link ElasticNodeSelector}.
 * Created by Navíd Mitchell 🤪 on 4/29/24.
 */
@Component
public class DefaultElasticVertxClient implements ElasticVertxClient {
    private static final Logger log = LoggerFactory.getLogger(DefaultElasticVertxClient.class);
    /**
     * How much longer than a named query's own request timeout we wait, so Elasticsearch can report that it timed out
     */
    private static final Duration REQUEST_TIMEOUT_GRACE = Duration.ofSeconds(5);
    private final ObjectMapper objectMapper;
    private final ElasticNodeSelector<ElasticNode> nodeSelector;
    private final WebClient webClient;
    private final Cache<String, List<ElasticColumn>> columnsCache;


    public DefaultElasticVertxClient(ObjectMapper objectMapper,
                                     StructuresProperties structuresProperties,
                                     Vertx vertx,
                                     DefaultCaffeineCacheFactory cacheFactory) {
        this.objectMapper = objectMapper;
        this.columnsCache = cacheFactory.<String, List<ElasticColumn>>newBuilder()
                .name("elasticColumnsCache")
                .expireAfterAccess(Duration.ofMinutes(35))
                .maximumSize(20_000)
                .build();

        WebClientOptions options = new WebClientOptions()
                .setConnectTimeout((int) structuresProperties.getElasticConnectionTimeout().toMillis())
                .setTcpNoDelay(true)
                .setTcpKeepAlive(true)
                .setTracingPolicy(TracingPolicy.IGNORE);

        this.webClient = WebClient.create(vertx, options, new PoolOptions().setHttp1MaxSize(500));

        Validate.notEmpty(structuresProperties.getElasticConnections(), "No Elastic connections defined");

        List<ElasticNode> nodes = new ArrayList<>();
        for(ElasticConnectionInfo elasticConnectionInfo : structuresProperties.getElasticConnections()){
            HttpRequest<Buffer> sqlQueryRequest = webClient.post(elasticConnectionInfo.getPort(),
                                                                 elasticConnectionInfo.getHost(), "/_sql")
                    // Without it a request sent on a pooled connection to a node that vanished never completes
                    .idleTimeout(structuresProperties.getElasticNamedQueryTimeout().toMillis());
            if(elasticConnectionInfo.getScheme().equalsIgnoreCase("https")){
                sqlQueryRequest.ssl(true);
            }
            if(structuresProperties.hasElasticUsernameAndPassword()){
                sqlQueryRequest.basicAuthentication(structuresProperties.getElasticUsername(),
                                                    structuresProperties.getElasticPassword());
            }
            nodes.add(new ElasticNode(elasticConnectionInfo.toHostAndPort(),
                                      sqlQueryRequest,
                                      sqlQueryRequest.copy().uri("/_sql/translate")));
        }
        this.nodeSelector = new ElasticNodeSelector<>(nodes);
    }

    @PreDestroy
    public void destroy(){
        webClient.close();
    }

    @WithSpan
    @Override
    public <T> CompletableFuture<Page<T>> querySql(String statement,
                                                   List<?> parameters,
                                                   JsonObject filter,
                                                   QueryOptions options,
                                                   Pageable pageable,
                                                   Class<T> type) {
        Integer requestTimeout = options != null ? options.getRequestTimeout() : null;
        if(requestTimeout != null && requestTimeout <= 0){
            return CompletableFuture.failedFuture(new IllegalArgumentException("requestTimeout is a number of seconds and must be greater than 0, got " + requestTimeout));
        }
        JsonObject json = new JsonObject();
        boolean foundCursor = false;
        MutableObject<String> cursorProvided = new MutableObject<>(null);
        if(pageable != null){
            if(pageable instanceof CursorPageable cursorPageable){
                if(cursorPageable.getCursor() != null) {
                    foundCursor = true;
                    cursorProvided.setValue(cursorPageable.getCursor());
                    json.put("cursor", cursorPageable.getCursor());
                }else{
                    json.put("fetch_size", pageable.getPageSize());
                }
            }else{
                return CompletableFuture.failedFuture(new IllegalArgumentException("Only CursorPageable is supported for queries containing Aggregations."));
            }
        }

        // Only add the query if we are not using a cursor
        if(!foundCursor){
            json.put("query", statement);
            if(parameters != null) {
                JsonArray paramsJson = new JsonArray();
                for(Object param : parameters){
                    paramsJson.add(param);
                }
                json.put("params", paramsJson);
            }
            if(filter != null){
                json.put("filter", filter);
            }
            if(options != null){
                if(options.getTimeZone() != null){
                    json.put("time_zone", options.getTimeZone());
                }
                if (options.getPageTimeout() != null) {
                    json.put("page_timeout", options.getPageTimeout());
                }else{
                    json.put("page_timeout", "2m");
                }
                if (requestTimeout != null) {
                    // Elasticsearch only takes a time value with its unit
                    json.put("request_timeout", requestTimeout + "s");
                }
            }
        }

        // Completed from the WebClient's handler, on a Vert.x context, so dependent stages already run there
        CompletableFuture<Page<T>> fut = new CompletableFuture<>();
        // A query given its own timeout is waited on for that long, rather than for the named query default
        Long idleTimeoutOverride = requestTimeout != null
                ? Duration.ofSeconds(requestTimeout).plus(REQUEST_TIMEOUT_GRACE).toMillis()
                : null;
        send(ElasticNode::sqlQueryRequest, json, idleTimeoutOverride)
                       .onComplete(ar -> {
                           if(ar.succeeded()){
                               if(ar.result().statusCode() == 200) {
                                   Buffer buffer = ar.result().body();
                                   if (RawJson.class.isAssignableFrom(type)) {
                                       try {
                                           @SuppressWarnings("unchecked")
                                           Page<T> page = (Page<T>) processBufferToRawJson(buffer, cursorProvided.getValue());
                                           fut.complete(page);
                                       } catch (Exception e) {
                                           fut.completeExceptionally(e);
                                       }
                                   } else if (Map.class.isAssignableFrom(type)) {
                                       try {
                                           @SuppressWarnings("unchecked")
                                           Page<T> page = (Page<T>) processBufferToMap(buffer, cursorProvided.getValue());
                                           fut.complete(page);
                                       } catch (Exception e) {
                                           fut.completeExceptionally(e);
                                       }
                                   } else {
                                       fut.completeExceptionally(new IllegalArgumentException("Type: " + type.getName() + " is not supported at this time"));
                                   }
                               }else{
                                   try {
                                       fut.completeExceptionally(convertErrorResponse(new ByteArrayInputStream(ar.result().body().getBytes())));
                                   } catch (Exception e) {
                                       fut.completeExceptionally(new IllegalStateException("Could not convert error response " + e.getMessage(), e));
                                   }
                               }
                           }else{
                               fut.completeExceptionally(ar.cause());
                           }
                       });
        return fut;
    }

    @WithSpan
    @Override
    public CompletableFuture<TranslateResponse> translateSql(String statement,
                                                             List<?> parameters){
        CompletableFuture<TranslateResponse> responseFuture = new CompletableFuture<>();
        JsonObject json = new JsonObject().put("query", statement);
        if(parameters != null) {
            JsonArray paramsJson = new JsonArray();
            for(Object param : parameters){
                paramsJson.add(param);
            }
            json.put("params", paramsJson);
        }
        send(ElasticNode::sqlTranslateRequest, json, null)
                           .onComplete(ar -> {
                               if(ar.succeeded()){
                                   InputStream input = new ByteArrayInputStream(ar.result()
                                                                                  .body()
                                                                                  .getBytes());
                                   if(ar.result().statusCode() == 200) {
                                       try {
                                           TranslateResponse translateResponse = TranslateResponse.of(builder -> {
                                               JsonpMapper mapper = SimpleJsonpMapper.INSTANCE; // We don't want to fail on unknown fields
                                               builder.withJson(mapper.jsonProvider().createParser(input), mapper);
                                               return builder;
                                           });
                                           responseFuture.complete(translateResponse);
                                       } catch (Exception e) {
                                           responseFuture.completeExceptionally(e);
                                       }
                                   }else{
                                       // Parsing the error body can itself throw; without this the future is
                                       // never completed and the caller waits on it forever
                                       try {
                                           responseFuture.completeExceptionally(convertErrorResponse(input));
                                       } catch (Exception e) {
                                           responseFuture.completeExceptionally(
                                                   new IllegalStateException("Could not convert error response "
                                                                             + e.getMessage(), e));
                                       }
                                   }
                               }else{
                                   responseFuture.completeExceptionally(ar.cause());
                               }
                           });
        return responseFuture;
    }

    /**
     * Sends the request to the next node in the rotation, moving on to the following node when one cannot be reached or
     * answers that it is unavailable. Everything this client sends is a read, so sending a request twice is harmless.
     * A request that times out is not retried, since a slow query is no reason to think the node is gone and running
     * it again elsewhere would only double the load it puts on the cluster.
     */
    private Future<HttpResponse<Buffer>> send(Function<ElasticNode, HttpRequest<Buffer>> requestForNode,
                                              JsonObject body,
                                              Long idleTimeoutOverride){
        Function<ElasticNode, HttpRequest<Buffer>> request = idleTimeoutOverride == null
                ? requestForNode
                : node -> requestForNode.apply(node).copy().idleTimeout(idleTimeoutOverride);
        Promise<HttpResponse<Buffer>> promise = Promise.promise();
        send(request, body, nodeSelector.nodesForRequest(), 0, new ArrayList<>(), promise);
        return promise.future();
    }

    private void send(Function<ElasticNode, HttpRequest<Buffer>> requestForNode,
                      JsonObject body,
                      List<ElasticNode> nodes,
                      int index,
                      List<Throwable> nodeFailures,
                      Promise<HttpResponse<Buffer>> promise){
        ElasticNode node = nodes.get(index);
        boolean hasNextNode = index + 1 < nodes.size();
        requestForNode.apply(node)
                      .sendJsonObject(body)
                      .onComplete(ar -> {
                          if(ar.succeeded()){
                              int statusCode = ar.result().statusCode();
                              if(isUnavailableStatus(statusCode)){
                                  markDead(node, "HTTP " + statusCode);
                                  if(hasNextNode){
                                      send(requestForNode, body, nodes, index + 1, nodeFailures, promise);
                                      return;
                                  }
                              }else if(nodeSelector.markAlive(node)){
                                  log.info("Elasticsearch node {} is answering again, it is back in the rotation", node.hostAndPort());
                              }
                              // The last node's error response is passed on, so the caller reports what Elasticsearch said
                              promise.complete(ar.result());
                          }else{
                              Throwable cause = ar.cause();
                              if(isNodeFailure(cause)){
                                  markDead(node, cause.toString());
                                  nodeFailures.add(cause);
                                  if(hasNextNode){
                                      send(requestForNode, body, nodes, index + 1, nodeFailures, promise);
                                      return;
                                  }
                                  if(nodeFailures.size() > 1){
                                      promise.fail(allNodesFailed(nodes, nodeFailures));
                                      return;
                                  }
                              }
                              promise.fail(cause);
                          }
                      });
    }

    private void markDead(ElasticNode node, String reason){
        if(nodeSelector.markDead(node)){
            log.warn("Elasticsearch node {} is unavailable ({}), it is out of the rotation until it recovers",
                     node.hostAndPort(), reason);
        }else{
            log.debug("Elasticsearch node {} is still unavailable ({})", node.hostAndPort(), reason);
        }
    }

    private static Exception allNodesFailed(List<ElasticNode> nodes, List<Throwable> nodeFailures){
        List<String> hosts = nodes.stream().map(ElasticNode::hostAndPort).toList();
        Throwable last = nodeFailures.getLast();
        IllegalStateException e = new IllegalStateException("No Elasticsearch node could be reached, tried " + hosts
                                                             + ", last failure: " + last, last);
        for(Throwable failure : nodeFailures.subList(0, nodeFailures.size() - 1)){
            e.addSuppressed(failure);
        }
        return e;
    }

    /**
     * The same statuses the Elasticsearch RestClient treats as the node, or a proxy in front of it, being unavailable
     */
    static boolean isUnavailableStatus(int statusCode){
        return statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    /**
     * True for failures that mean the node could not be reached or dropped the connection: refused or timed out
     * connects, unresolvable hosts, resets and TLS failures are all {@link IOException}s.
     * Timeouts waiting for a response and a full local connection pool say nothing about the node, so they are not.
     */
    static boolean isNodeFailure(Throwable cause){
        return cause instanceof IOException || cause instanceof HttpClosedException;
    }

    private Exception convertErrorResponse(InputStream input) {
        ErrorResponse errorResponse = ErrorResponse.of(builder -> {
            JsonpMapper mapper = SimpleJsonpMapper.INSTANCE; // We don't want to fail on unknown fields
            builder.withJson(mapper.jsonProvider().createParser(input), mapper);
            return builder;
        });
        ErrorCause cause = errorResponse.error();
        log.debug("Exception from Elastic SQL: {} {} \n{}", cause.type(), cause.reason(), cause.stackTrace());
        return new IllegalArgumentException("SQL " + cause.type() + " " + cause.reason());
    }

    private record ElasticNode(String hostAndPort,
                               HttpRequest<Buffer> sqlQueryRequest,
                               HttpRequest<Buffer> sqlTranslateRequest) {
    }

    private Page<Map<String, Object>> processBufferToMap(Buffer buffer, String cursorProvided) throws Exception {
        ElasticSQLResponse response = objectMapper.readValue(buffer.getBytes(), ElasticSQLResponse.class);
        List<ElasticColumn> elasticColumns = getElasticColumns(response, cursorProvided);
        List<Map<String,Object>> ret = new ArrayList<>(response.getRows().size());

        for(List<Object> row : response.getRows()){
            Map<String, Object> obj = new HashMap<>(response.getRows().size(), 1.5F);

            for(int colIdx = 0; colIdx < row.size(); colIdx++){
                obj.put(elasticColumns.get(colIdx).getName(), row.get(colIdx));
            }
            ret.add(obj);
        }
        // now store columns in cache
        if(response.getCursor() != null){
            columnsCache.put(response.getCursor(), elasticColumns);
        }

        // We only allow each to be used once
        if(cursorProvided != null){
            columnsCache.asMap().remove(cursorProvided);
        }
        return new CursorPage<>(ret, response.getCursor(), null);
    }

    private List<ElasticColumn> getElasticColumns(ElasticSQLResponse response, String cursorProvided) {
        List<ElasticColumn> elasticColumns;
        if(cursorProvided != null){
            elasticColumns = columnsCache.getIfPresent(cursorProvided);
            if(elasticColumns == null){
                throw new IllegalStateException("Cursor has expired");
            }
        }else{
            elasticColumns = response.getColumns();
        }
        return elasticColumns;
    }

    private Page<RawJson> processBufferToRawJson(Buffer buffer, String cursorProvided) throws Exception {
        ElasticSQLResponse response = objectMapper.readValue(buffer.getBytes(), ElasticSQLResponse.class);
        List<ElasticColumn> elasticColumns = getElasticColumns(response, cursorProvided);
        List<RawJson> ret = new ArrayList<>(response.getRows().size());

        for(List<Object> row : response.getRows()){

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            // Closed per row: a generator returns its buffers to Jackson's pool only on close
            try (JsonGenerator jsonGenerator = objectMapper.createGenerator(outputStream, JsonEncoding.UTF8)) {
                jsonGenerator.writeStartObject();

                for(int colIdx = 0; colIdx < row.size(); colIdx++){
                    jsonGenerator.writeName(elasticColumns.get(colIdx).getName());
                    jsonGenerator.writePOJO(row.get(colIdx));
                }
                jsonGenerator.writeEndObject();
            }
            ret.add(new RawJson(outputStream.toByteArray()));
        }

        // now store columns in cache
        if(response.getCursor() != null){
            columnsCache.put(response.getCursor(), elasticColumns);
        }

        // We only allow each to be used once
        if(cursorProvided != null){
            columnsCache.asMap().remove(cursorProvided);
        }

        return new CursorPage<>(ret, response.getCursor(), null);
    }


}
