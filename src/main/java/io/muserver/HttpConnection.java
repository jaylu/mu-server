package io.muserver;

import javax.net.ssl.TrustManager;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * A connection between a server and a client.
 */
public interface HttpConnection {

    /**
     * The HTTP protocol for the request.
     * @return A string such as <code>HTTP/1.1</code> or <code>HTTP/2</code>
     */
    String protocol();

    /**
     * @return <code>true</code> if the connnection is secured over HTTPS, otherwise <code>false</code>
     */
    boolean isHttps();

    /**
     * Gets the HTTPS protocol, for example "TLSv1.2" or "TLSv1.3"
     * @return The HTTPS protocol being used, or <code>null</code> if this connection is not over HTTPS.
     */
    String httpsProtocol();

    /**
     * @return The HTTPS cipher used on this connection, or <code>null</code> if this connection is not over HTTPS.
     */
    String cipher();

    /**
     * @return The time that this connection was established.
     */
    Instant startTime();

    /**
     * @return The socket address of the client.
     */
    InetSocketAddress remoteAddress();

    /**
     * @return The number of completed requests on this connection.
     */
    long completedRequests();

    /**
     * @return The number of requests received that were not valid HTTP messages.
     */
    long invalidHttpRequests();

    /**
     * @return The number of requests rejected because the executor passed to {@link MuServerBuilder#withHandlerExecutor(ExecutorService)}
     * rejected a new response.
     */
    long rejectedDueToOverload();

    /**
     * @return A readonly connection of requests that are in progress on this connection
     */
    Set<MuRequest> activeRequests();

    /**
     * The websockets on this connection.
     * <p>Note that in Mu Server websockets are only on HTTP/1.1 connections and there is a 1:1 mapping between
     * a websocket and an HTTP Connection. This means the returned set is either empty or has a size of 1.</p>
     * @return A readonly set of active websockets being used on this connection
     */
    Set<MuWebSocket> activeWebsockets();

    /**
     * @return The server that this connection belongs to
     */
    MuServer server();

    /**
     * Gets the TLS certificate the client sent.
     * <p>The returned certificate will be {@link Optional#empty()} when:</p>
     * <ul>
     *     <li>The client did not send a certificate, or</li>
     *     <li>The client sent a certificate that failed verification with the client trust manager, or</li>
     *     <li>No client trust manager was set with {@link HttpsConfigBuilder#withClientCertificateTrustManager(TrustManager)}, or</li>
     *     <li>The request was not sent over HTTPS</li>
     * </ul>
     * @return The client certificate, or <code>empty</code> if no certificate is available
     */
    Optional<Certificate> clientCertificate();

    /**
     * Gets information from a proxy that uses the <a href="https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt">HA
     * Proxy protocol</a>.
     * <p>Note: this is only available if enabled via {@link MuServerBuilder#withHAProxyProtocolEnabled(boolean)}</p>
     * @return Information from a proxy about the source client, or {@link Optional#empty()} if none specified.
     */
    Optional<ProxiedConnectionInfo> proxyInfo();

}
