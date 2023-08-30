package io.muserver;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scaffolding.MuAssert;

import javax.net.ssl.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;

public class MuServer2Test {

    private static final Logger log = LoggerFactory.getLogger(MuServer2Test.class);
    private MuServer server;

    @Test
    public void canStartAndStopHttp() throws Exception {
        var s = "Hello ".repeat(1000);
        for (int i = 0; i < 2; i++) {
            int finalI = i;
            MuServerBuilder muServerBuilder = MuServerBuilder.muServer()
                .withHttpPort(10000) // todo reuse same port and make this work
                .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                    response.write("Hello " + s + finalI);
                });
            // todo reuse same port and make this work
            var server = muServerBuilder.start();
            log.info("Started at " + server.uri());

            try (var resp = call(request(server.uri().resolve("/blah")))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("Hello " + s + i));
            }
            server.stop();
            try (var resp = call(request(server.uri().resolve("/blah")))) {
                Assert.fail("Should not work");
            } catch (Exception ex) {
            }
        }
    }

    @Test
    public void clientsCanInitiateTlsShutdowns() throws Exception {
        MuServerBuilder muServerBuilder = httpsServer();
        server = muServerBuilder.start();
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[0], new TrustManager[] {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] x509Certificates, String s) {
                }
                public void checkServerTrusted(X509Certificate[] x509Certificates, String s) {
                }
                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
            }},
            new SecureRandom());
        HttpsURLConnection conn = (HttpsURLConnection) server.uri().toURL().openConnection();
        conn.setSSLSocketFactory(ctx.getSocketFactory());
        conn.setHostnameVerifier((arg0, arg1) -> true);
        conn.setConnectTimeout(5000);
        conn.connect();
        assertEventually(() -> server.activeConnections(), hasSize(1));
        HttpConnection httpConnection = server.activeConnections().stream().findAny().get();
        System.out.println("httpConnection = " + httpConnection);
        conn.disconnect();
        assertEventually(() -> server.activeConnections(), empty());

    }



    @Test
    public void serverCanIntitiateShutdownOnTLS() throws Exception {
        MuServerBuilder muServerBuilder = httpsServer()
            .withHttpsPort(0)
            .addHandler((request, response) -> true);
        var server = muServerBuilder.start();
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[0], new TrustManager[] {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] x509Certificates, String s) {
                }
                public void checkServerTrusted(X509Certificate[] x509Certificates, String s) {
                }
                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
            }},
            new SecureRandom());
        HttpsURLConnection conn = (HttpsURLConnection) server.uri().toURL().openConnection();
        conn.setSSLSocketFactory(ctx.getSocketFactory());
        conn.setHostnameVerifier((arg0, arg1) -> true);
        conn.setConnectTimeout(5000);
        conn.connect();
        assertEventually(server::activeConnections, hasSize(1));


        server.stop(5, TimeUnit.SECONDS);
        InputStream inputStream = conn.getInputStream();
        inputStream.read();

        assertEventually(server::activeConnections, empty());

    }


    @Test
    public void serverCanInitiateGracefulShutdown() throws Exception {
        var events = new ConcurrentLinkedQueue<String>() {
            @Override
            public boolean add(String s) {
                log.info("Test event: " + s);
                return super.add(s);
            }
        };
        var serverStoppedLatch = new CountDownLatch(1);

        MuServerBuilder muServerBuilder = MuServerBuilder.muServer()
            .withHttpPort(0)
            .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                events.add("Writing response");
                response.write("Hello\r\n");
            });
        var server = muServerBuilder.start();

        Socket clientConnection = new Socket(server.uri().getHost(), server.uri().getPort());
        // Get the input and output streams
        PrintWriter out = new PrintWriter(clientConnection.getOutputStream(), true);
        out.print("GET /blah HTTP/1.1\r\n");
        out.print("Host: " + server.uri().getAuthority() + "\r\n");
        out.print("\r\n");
        out.flush();

        var latch = new CountDownLatch(1);

        new Thread(() -> {
            try {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientConnection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("Client read line = " + line);
                        if (line.equals("Hello")) {
                            events.add("Got line: " + line);
                            new Thread(() -> {
                                try {
                                    Thread.sleep(500);
                                } catch (InterruptedException e) {
                                }
                                events.add("Stop initiated");
                                server.stop();
                                serverStoppedLatch.countDown();
                            }).start();
                        }
                    }
                    events.add("Got EOF");
                }
                out.close();
                clientConnection.close();
                events.add("Closed reader");
            } catch (IOException e) {
                events.add("Exception reading: " + e.getMessage());
            } finally {
                latch.countDown();
            }
        }).start();


        MuAssert.assertNotTimedOut("Waiting for client EOF", latch);
        MuAssert.assertNotTimedOut("Waiting for server stop", serverStoppedLatch);
        assertThat("Actual events: " + events, events, contains("Writing response", "Got line: Hello", "Stop initiated", "Got EOF", "Closed reader"));
        assertThat(server.activeConnections(), empty());
    }

    @Test
    public void clientCanInitiateGracefulShutdown() throws Exception {
        var events = new ConcurrentLinkedQueue<String>() {
            @Override
            public boolean add(String s) {
                log.info("Test event: " + s);
                return super.add(s);
            }
        };

        MuServerBuilder muServerBuilder = MuServerBuilder.muServer()
            .withHttpPort(0)
            .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                events.add("Writing response");
                response.write("Hello\r\n");
            });
        server = muServerBuilder.start();

        Socket clientConnection = new Socket(server.uri().getHost(), server.uri().getPort());
        // Get the input and output streams
        PrintWriter out = new PrintWriter(clientConnection.getOutputStream(), true);
        out.print("GET /blah HTTP/1.1\r\n");
        out.print("Host: " + server.uri().getAuthority() + "\r\n");
        out.print("\r\n");
        out.flush();

        var latch = new CountDownLatch(1);

        new Thread(() -> {
            try {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientConnection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("Client read line = " + line);
                        if (line.equals("Hello")) {
                            events.add("Got line: " + line);
                            out.close();
                            events.add("Closed output stream");
                        }
                    }
                    events.add("Got EOF");
                }
                clientConnection.close();
                events.add("Closed reader");
            } catch (IOException e) {
                events.add("Exception reading");
            } finally {
                latch.countDown();
            }
        }).start();


        assertEventually(server::activeConnections, empty());
        MuAssert.assertNotTimedOut("Waiting for client EOF", latch);
        assertThat("Actual events: " + events, events, contains("Writing response", "Got line: Hello", "Closed output stream", "Exception reading"));
        assertThat(server.activeConnections(), empty());
    }


    @Test
    public void canStartAndStopHttps() throws Exception {
        for (int i = 0; i < 1; i++) {
            int finalI = i;
            MuServerBuilder muServerBuilder = MuServerBuilder.muServer()
                .withHttpsPort(0)
                .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                    response.write("Hello " + finalI);
                });
            var server = muServerBuilder.start();
            log.info("Started at " + server.uri());

            try (var resp = call(request(server.uri().resolve("/blah")))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("Hello " + i));
            }
            server.stop();
        }
    }

    @Test
    public void tls12Available() throws Exception {
        var theCipher = "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384";
        MuServerBuilder muServerBuilder = httpsServer()
            .withHttpsConfig(HttpsConfigBuilder.httpsConfig()
                .withProtocols("TLSv1.2")
                .withCipherFilter((supportedCiphers, defaultCiphers) -> {
                    return List.of(theCipher);
                })
            )
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                HttpConnection con = request.connection();
                response.write(con.isHttps() + " " + con.httpsProtocol() + " " + con.cipher());
            });
        server = muServerBuilder.start();
        try (var resp = call(request(server.uri().resolve("/")))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("true TLSv1.2 " + theCipher));
        }
    }

    @Test
    public void canGetServerInfo() throws Exception {
        var theCipher = "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384";
        MuServerBuilder muServerBuilder = httpsServer()
            .withHttpsConfig(HttpsConfigBuilder.httpsConfig()
                .withProtocols("TLSv1.2")
                .withCipherFilter((supportedCiphers, defaultCiphers) -> List.of(theCipher))
            );
        server = muServerBuilder.start();
        assertThat(server.sslInfo().providerName(), not(nullValue()));
        assertThat(server.sslInfo().ciphers(), contains(theCipher));
        assertThat(server.sslInfo().protocols(), contains("TLSv1.2"));
        assertThat(server.sslInfo().certificates(), hasSize(1));
    }


    @Test
    public void ifNoCommonCiphersThenItDoesNotLoad() throws Exception {
        var theCipher = "TLS_AES_128_GCM_SHA256";
        MuServerBuilder muServerBuilder = httpsServer()
            .withHttpsConfig(HttpsConfigBuilder.httpsConfig()
                .withProtocols("TLSv1.2")
                .withCipherFilter((supportedCiphers, defaultCiphers) -> List.of(theCipher))
            );
        server = muServerBuilder.start();
        assertThrows(UncheckedIOException.class, () -> {
            try (var ignored = call(request(server.uri().resolve("/")))) {
            }
        });
        assertThat(server.stats().failedToConnect(), equalTo(1L));
    }

    @Test
    public void tls13Available() throws Exception {
        AtomicReference<String> theCipher = new AtomicReference<>();
        MuServerBuilder muServerBuilder = httpsServer()
            .withHttpsConfig(HttpsConfigBuilder.httpsConfig()
                .withProtocols("TLSv1.2", "TLSv1.3")
                .withCipherFilter((supportedCiphers, defaultCiphers) -> {
                    theCipher.set(defaultCiphers.get(0));
                    return List.of(theCipher.get());
                })
            )
            .addHandler(Method.GET, "/", new RouteHandler() {
                @Override
                public void handle(MuRequest request, MuResponse response, Map<String, String> pathParams) throws Exception {
                    HttpConnection con = request.connection();
                    response.write(con.isHttps() + " " + con.httpsProtocol() + " " + con.cipher());
                }
            });
        server = muServerBuilder.start();
        try (var resp = call(request(server.uri().resolve("/")))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("true TLSv1.3 " + theCipher.get()));
        }
    }


    @Test
    public void canChunk() throws Exception {
        MuServerBuilder muServerBuilder = MuServerBuilder.muServer()
            .withHttpsPort(0)
            .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                response.headers().set(HeaderNames.TRAILER, "server-timing");
                response.sendChunk("Hello");
                response.sendChunk(" ");
                response.sendChunk("world");
                response.trailers().set(HeaderNames.SERVER_TIMING, new ParameterizedHeaderWithValue("total", Map.of("dur", "123.4")));
            });
        server = muServerBuilder.start();
        log.info("Started at " + server.uri());

        try (var resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Hello world"));
            assertThat(resp.trailers().get("server-timing"), nullValue());
        }

//        try (var resp = call(request(server.uri().resolve("/blah"))
//            .header("TE", "trailers")
//        )) {
//            assertThat(resp.code(), equalTo(200));
//            assertThat(resp.body().string(), equalTo("Hello world"));
//            assertThat(resp.trailers().get("server-timing"), equalTo("total;dur=123.4"));
//        }
    }

    @Test
    public void hmm() {
        Charset charset = StandardCharsets.UTF_8;
        var os = new ByteArrayOutputStream();
        OutputStreamWriter osw = new OutputStreamWriter(os, charset);
        var writer = new PrintWriter(osw, false);
        writer.write("Hello");
        writer.close();

    }

    @Test
    public void canWriteChunksToOutputStreamWithoutFlushing() throws Exception {
        MuServerBuilder muServerBuilder = MuServerBuilder.muServer()
            .withHttpsPort(0)
            .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                response.writer().write("Hello");
            });
        server = muServerBuilder.start();
        log.info("Started at " + server.uri());

        try (var resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.headers("content-type"), contains("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("Hello"));
        }

    }


    @Test
    public void canWriteChunksToOutputStream() throws Exception {
        MuServerBuilder muServerBuilder = MuServerBuilder.muServer()
            .withHttpsPort(0)
            .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                response.headers().set(HeaderNames.TRAILER, "server-timing");
                response.writer().write("Hello");
                response.writer().flush();
                response.writer().write(" ");
                response.writer().flush();
                response.writer().write("world");
                response.trailers().set(HeaderNames.SERVER_TIMING, new ParameterizedHeaderWithValue("total", Map.of("dur", "123.4")));
            });
        server = muServerBuilder.start();
        log.info("Started at " + server.uri());

        try (var resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.headers("content-type"), contains("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("Hello world"));
            assertThat(resp.trailers().get("server-timing"), nullValue());
        }

        try (var resp = call(request(server.uri().resolve("/blah"))
            .header("TE", "trailers")
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.headers("content-type"), contains("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("Hello world"));
            assertThat(resp.trailers().get("server-timing"), equalTo("total;dur=123.4"));
        }

    }

    @Test
    public void canWriteFixedLengthToOutputStream() throws Exception {
        MuServerBuilder muServerBuilder = MuServerBuilder.muServer()
            .withHttpsPort(0)
            .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                response.headers().set(HeaderNames.TRAILER, "server-timing");
                response.headers().set(HeaderNames.CONTENT_LENGTH, 11);
                response.writer().write("Hello");
                response.writer().flush();
                response.writer().write(" ");
                response.writer().flush();
                response.writer().write("world");
                response.trailers().set(HeaderNames.SERVER_TIMING, new ParameterizedHeaderWithValue("total", Map.of("dur", "123.4")));
            });
        server = muServerBuilder.start();
        log.info("Started at " + server.uri());

        try (var resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.headers("content-type"), contains("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("Hello world"));
            assertThat(resp.trailers().get("server-timing"), nullValue());
        }

        try (var resp = call(request(server.uri().resolve("/blah"))
            .header("TE", "trailers")
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.headers("content-type"), contains("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("Hello world"));
            assertThat(resp.trailers().get("server-timing"), nullValue());
        }

    }

    @After
    public void stopIt() {
        if (server != null) {
            server.stop();
        }
    }

}