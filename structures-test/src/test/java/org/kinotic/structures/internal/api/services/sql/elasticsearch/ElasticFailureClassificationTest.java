package org.kinotic.structures.internal.api.services.sql.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslHandshakeTimeoutException;
import io.vertx.core.http.HttpClosedException;
import org.junit.jupiter.api.Test;

/**
 * Which failures take an Elasticsearch node out of the rotation and which only move the request on. Here, in the
 * client's own package, so the helpers it pins can stay package-private.
 */
class ElasticFailureClassificationTest {

    @Test
    void noConnectionCouldBeMadeSoTheNodeIsBenched() {
        assertTrue(DefaultElasticVertxClient.isConnectFailure(new ConnectException("Connection refused")));
        assertTrue(DefaultElasticVertxClient.isConnectFailure(new NoRouteToHostException("No route to host")));
        assertTrue(DefaultElasticVertxClient.isConnectFailure(new UnknownHostException("es-node")));
        assertTrue(DefaultElasticVertxClient.isConnectFailure(new SSLHandshakeException("bad certificate")));
        assertTrue(DefaultElasticVertxClient.isConnectFailure(new SslHandshakeTimeoutException("handshake timed out")));
        assertTrue(DefaultElasticVertxClient.isConnectFailure(new NotSslRecordException("not an SSL/TLS record")));
    }

    @Test
    void anEstablishedConnectionBrokeSoTheRequestMovesOnAndTheNodeStays() {
        SSLException midStream = new SSLException("closing inbound before receiving peer's close_notify");
        assertFalse(DefaultElasticVertxClient.isConnectFailure(midStream));
        assertTrue(DefaultElasticVertxClient.isConnectionLost(midStream));
        assertTrue(DefaultElasticVertxClient.isConnectionLost(new SocketException("Connection reset")));
        assertTrue(DefaultElasticVertxClient.isConnectionLost(new HttpClosedException("Connection was closed")));
    }
}
