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
import io.vertx.core.http.ClientResolverConfig;
import io.vertx.core.http.HttpClientConfig;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.tracing.TracingPolicy;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientConfig;
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
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
    /**
     * Pooled connections are retired after this long, so a host name that now resolves elsewhere is followed within
     * this time, even while its old address still answers
     */
    private static final Duration CONNECTION_MAX_LIFETIME = Duration.ofMinutes(5);
    /**
     * How long the kernel lets data we sent go unacknowledged before closing the connection. A node that is gone stops
     * acknowledging at once, while a live one acknowledges within milliseconds however long its query runs, so this
     * ends a request stuck on a dead connection without ever cutting off a slow query.
     */
    private static final int TCP_USER_TIMEOUT_MILLIS = 20_000;
    // Keepalive probes on a quiet connection, so one whose node is gone is closed in ~20s (TCP_USER_TIMEOUT caps it)
    // rather than ~2 hours
    private static final int TCP_KEEPALIVE_IDLE_SECONDS = 10;
    private static final int TCP_KEEPALIVE_INTERVAL_SECONDS = 5;
    private static final int TCP_KEEPALIVE_COUNT = 3;
    /**
     * How long the HTTP client keeps the addresses it resolved for a host. Vert.x otherwise reuses them for up to five
     * minutes regardless of the DNS TTL, so a node that moved would be tried at its old address. Its pooled connections
     * outlive a refresh, and lookups go through the DNS cache, which honours the TTL, so the DNS TTL is what governs.
     */
    private static final Duration RESOLVED_ADDRESS_MAX_AGE = Duration.ofSeconds(1);
    private final ObjectMapper objectMapper;
    private final ElasticNodeSelector<ElasticNode> nodeSelector;
    private final WebClient webClient;
    private final long namedQueryTimeoutMillis;
    private final long connectionTimeoutNanos;
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
        // These four only take effect on a native transport (epoll or io_uring); NIO ignores them
        options.setTcpUserTimeout(TCP_USER_TIMEOUT_MILLIS)
               .setTcpKeepAliveIdleSeconds(TCP_KEEPALIVE_IDLE_SECONDS)
               .setTcpKeepAliveIntervalSeconds(TCP_KEEPALIVE_INTERVAL_SECONDS)
               .setTcpKeepAliveCount(TCP_KEEPALIVE_COUNT);

        // The resolver settings are only on the newer (and still @Unstable) HttpClientConfig, so the options are
        // converted to it rather than rewritten, keeping everything set above
        WebClientConfig config = new WebClientConfig(new HttpClientConfig(options));
        config.setResolverConfig(new ClientResolverConfig()
                                         .setMaxKeepAlive(RESOLVED_ADDRESS_MAX_AGE)
                                         .setKeepAliveTimeout(RESOLVED_ADDRESS_MAX_AGE));
        this.webClient = WebClient.create(vertx, config, new PoolOptions()
                .setHttp1MaxSize(500)
                .setMaxLifetime((int) CONNECTION_MAX_LIFETIME.toSeconds())
                .setMaxLifetimeUnit(TimeUnit.SECONDS));
        this.connectionTimeoutNanos = structuresProperties.getElasticConnectionTimeout().toNanos();
        this.namedQueryTimeoutMillis = structuresProperties.getElasticNamedQueryTimeout().toMillis();
        if(!vertx.isNativeTransportEnabled()){
            log.info("Vert.x runs without a native transport, so a connection to an Elasticsearch node that vanished without closing it is only noticed when the named query times out. Cause: {}",
                     vertx.unavailableNativeTransportCause() != null ? vertx.unavailableNativeTransportCause().toString() : "not requested");
        }

        Validate.notEmpty(structuresProperties.getElasticConnections(), "No Elastic connections defined");

        List<ElasticNode> nodes = new ArrayList<>();
        for(ElasticConnectionInfo elasticConnectionInfo : structuresProperties.getElasticConnections()){
            HttpRequest<Buffer> sqlQueryRequest = webClient.post(elasticConnectionInfo.getPort(),
                                                                 elasticConnectionInfo.getHost(), "/_sql")
                    // Without it a request sent on a pooled connection to a node that vanished never completes
                    .idleTimeout(namedQueryTimeoutMillis);
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
            }
        }
        if (requestTimeout != null) {
            // Every page, so Elasticsearch gives up on a page when we do. It only takes a time value with its unit
            json.put("request_timeout", requestTimeout + "s");
        }

        // Completed from the WebClient's handler, on a Vert.x context, so dependent stages already run there
        CompletableFuture<Page<T>> fut = new CompletableFuture<>();
        // A query given a longer timeout of its own is waited on for that long. A shorter one never cuts the wait,
        // since Elasticsearch only bounds the search on each shard, not the work that combines the results
        long requestTimeoutWait = requestTimeout != null
                ? Duration.ofSeconds(requestTimeout).plus(REQUEST_TIMEOUT_GRACE).toMillis()
                : 0;
        Long idleTimeoutOverride = requestTimeoutWait > namedQueryTimeoutMillis ? requestTimeoutWait : null;
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
     * Sends the request to the next node in the rotation, moving on to the following node when one cannot be reached
     * or the connection drops. Everything this client sends is a read, so sending a request twice is harmless.
     * Only a node that cannot be connected to is taken out of the rotation: a dropped connection may just be an idle
     * one a proxy closed, and a node that is really gone refuses the next connection anyway.
     * A connection that drops only after the query has had time to start running is not retried either, since a proxy
     * may have cut off a slow query, unless the kernel gave up on a peer that stopped acknowledging altogether.
     * A request that times out is not sent elsewhere, since a slow query is no reason to think the node is gone and
     * running it again would only double the load it puts on the cluster. A connection whose node vanished without
     * closing it is ended by the kernel instead, see {@link #TCP_USER_TIMEOUT_MILLIS}.
     */
    private Future<HttpResponse<Buffer>> send(Function<ElasticNode, HttpRequest<Buffer>> requestForNode,
                                              JsonObject body,
                                              Long idleTimeoutOverride){
        Function<ElasticNode, HttpRequest<Buffer>> request = idleTimeoutOverride == null
                ? requestForNode
                : node -> requestForNode.apply(node).copy().idleTimeout(idleTimeoutOverride);
        Attempt attempt = new Attempt(request, body, nodeSelector.nodesForRequest());
        attempt.send(0);
        return attempt.promise.future();
    }

    /**
     * One request making its way through the nodes it was handed
     */
    private final class Attempt {
        private final Function<ElasticNode, HttpRequest<Buffer>> requestForNode;
        private final JsonObject body;
        private final List<ElasticNode> nodes;
        private final List<Throwable> nodeFailures = new ArrayList<>();
        private final Promise<HttpResponse<Buffer>> promise = Promise.promise();

        private Attempt(Function<ElasticNode, HttpRequest<Buffer>> requestForNode,
                        JsonObject body,
                        List<ElasticNode> nodes) {
            this.requestForNode = requestForNode;
            this.body = body;
            this.nodes = nodes;
        }

        private void send(int index){
            ElasticNode node = nodes.get(index);
            long sentAt = nodeSelector.now();
            requestForNode.apply(node)
                          .sendJsonObject(body)
                          .onComplete(ar -> {
                              if(ar.succeeded()){
                                  HttpResponse<Buffer> response = ar.result();
                                  if(isProxyUnavailable(response)){
                                      Exception failure = unavailable(node, response);
                                      markDead(node, failure.getMessage());
                                      tryNextNode(index, failure);
                                  }else{
                                      if(nodeSelector.markAlive(node, sentAt)){
                                          log.info("Elasticsearch node {} is answering again, it is back in the rotation",
                                                   node.hostAndPort());
                                      }
                                      // Includes Elasticsearch's own errors, which the caller reports as it sent them
                                      promise.complete(response);
                                  }
                              }else{
                                  Throwable cause = ar.cause();
                                  if(isConnectFailure(cause)){
                                      markDead(node, cause.toString());
                                      tryNextNode(index, cause);
                                  }else if(isConnectionLost(cause)
                                          && (nodeSelector.now() - sentAt < connectionTimeoutNanos || isPeerUnresponsive(cause))){
                                      log.debug("Connection to Elasticsearch node {} was lost ({}), trying the next node",
                                                node.hostAndPort(), cause.toString());
                                      tryNextNode(index, cause);
                                  }else{
                                      promise.fail(cause);
                                  }
                              }
                          });
        }

        private void tryNextNode(int index, Throwable failure){
            nodeFailures.add(failure);
            if(index + 1 < nodes.size()){
                send(index + 1);
            }else if(nodeFailures.size() > 1){
                promise.fail(allNodesFailed(nodes, nodeFailures));
            }else{
                promise.fail(failure);
            }
        }
    }

    private void markDead(ElasticNode node, String reason){
        if(nodeSelector.markDead(node)){
            log.warn("Elasticsearch node {} is unavailable ({}), it is out of the rotation until it recovers",
                     node.hostAndPort(), reason);
        }else{
            log.debug("Elasticsearch node {} is still unavailable ({})", node.hostAndPort(), reason);
        }
    }

    private static Exception unavailable(ElasticNode node, HttpResponse<Buffer> response){
        return new IllegalStateException("Elasticsearch node " + node.hostAndPort() + " is unavailable, it answered HTTP "
                                         + response.statusCode());
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
     * True when a proxy or load balancer in front of the node says the node is unavailable.
     * A 502 or 503 carrying an Elasticsearch error was sent by Elasticsearch itself, such as unavailable shards or a
     * cluster block, which every node would answer the same way, so it is the query's answer rather than a dead node.
     * A 504 means a proxy gave up waiting, which is a slow query rather than a dead node, so it is not one either.
     */
    static boolean isProxyUnavailable(HttpResponse<Buffer> response){
        int statusCode = response.statusCode();
        return (statusCode == 502 || statusCode == 503) && !isElasticsearchError(response.body());
    }

    private static boolean isElasticsearchError(Buffer body){
        if(body == null || body.length() == 0){
            return false;
        }
        try {
            return body.toJsonObject().getValue("error") instanceof JsonObject error && error.containsKey("type");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * True when no connection to the node could be made: refused or timed out connects, addresses with no route to them
     * (how an address that is gone usually shows), host names that do not resolve (including DNS timeouts, which Netty
     * reports as an {@link UnknownHostException}) and failed TLS handshakes
     */
    public static boolean isConnectFailure(Throwable cause){
        return cause instanceof ConnectException
                || cause instanceof NoRouteToHostException
                || cause instanceof UnknownHostException
                || cause instanceof SSLHandshakeException;
    }

    /**
     * True when an established connection closed or reset before the response arrived.
     * Timeouts waiting for a response and a full local connection pool say nothing about the node, so they are not.
     */
    public static boolean isConnectionLost(Throwable cause){
        return cause instanceof IOException || cause instanceof HttpClosedException;
    }

    /**
     * True when the kernel closed the connection because the peer stopped acknowledging, via TCP_USER_TIMEOUT or failed
     * keepalive probes. The peer never took the request, or is gone, so sending it elsewhere is safe however late.
     * Both the JDK and Netty's native transport report ETIMEDOUT with this message.
     */
    public static boolean isPeerUnresponsive(Throwable cause){
        return cause instanceof IOException && cause.getMessage() != null && cause.getMessage().contains("Connection timed out");
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
