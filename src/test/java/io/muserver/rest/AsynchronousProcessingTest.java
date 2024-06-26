package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.Mutils;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.CompletionCallback;
import jakarta.ws.rs.container.ConnectionCallback;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import scaffolding.ClientUtils;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;
import scaffolding.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.SocketTimeoutException;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.util.stream.Collectors.toSet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertNotTimedOut;

public class AsynchronousProcessingTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private MuServer server;

    @Test
    public void canUseTheSuspendedAnnotationToGetAnAsyncResponseObject() throws Exception {
        CountDownLatch resumedLatch = new CountDownLatch(1);
        AsyncResponse[] captured = new AsyncResponse[1];
        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse ar) {
                executor.submit(() -> {
                    captured[0] = ar;
                    MuAssert.sleep(100);
                    jakarta.ws.rs.core.Response resp = jakarta.ws.rs.core.Response.status(202).entity("Suspended/cancelled/done: " + ar.isSuspended() + ar.isCancelled() + ar.isDone()).build();
                    ar.resume(resp);
                    resumedLatch.countDown();
                });
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(202));
            assertThat(resp.body().string(), equalTo("Suspended/cancelled/done: truefalsefalse"));
        }
        assertThat(captured[0].isSuspended(), is(false));
        assertThat(captured[0].isCancelled(), is(false));

        assertThat("Timed out waiting for resumption", resumedLatch.await(1, TimeUnit.MINUTES));
        assertThat(captured[0].isDone(), is(true));
    }


    @Test
    public void returningACompletionStageIsAlsoPossible() throws Exception {
        @Path("samples")
        class Sample {
            @POST
            public CompletionStage<jakarta.ws.rs.core.Response> go(InputStream requestBody) {
                CompletableFuture<jakarta.ws.rs.core.Response> cs = new CompletableFuture<>();
                executor.submit(() -> {
                    String entity;
                    try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                        MuAssert.sleep(50);
                        Mutils.copy(requestBody, os, 8192);
                        entity = os.toString("utf-8");
                    } catch (Exception ex) {
                        entity = "Error: " + ex;
                    }
                    cs.complete(jakarta.ws.rs.core.Response.status(200).entity(entity).build());
                });
                return cs;
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();
        String body = StringUtils.randomStringOfLength(68000);
        try (Response resp = call(request(server.uri().resolve("/samples")).post(
            new RequestBody() {
                @Override
                public MediaType contentType() {
                    return MediaType.get("text/plain;charset=utf-8");
                }

                @Override
                public void writeTo(BufferedSink bufferedSink) throws IOException {
                    bufferedSink.writeUtf8(body);
                    bufferedSink.flush();
                    bufferedSink.close();
                }
            }
        ))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo(body));
        }
    }


    @Test
    public void ifResumedWithExceptionThenItIsHandledNormally() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse ar) {
                executor.submit(() -> {
                    ar.resume(new BadRequestException("Bad bad bad request"));
                });
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.body().string(), equalTo("<h1>400 Bad Request</h1><p>Bad bad bad request</p>"));
        }
    }


    @Test
    public void timeoutsCanBeSent() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch afterSentLatch = new CountDownLatch(1);
        Object[] afterTimeoutResult = new Object[1];
        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse ar) {
                ar.setTimeout(10, TimeUnit.MILLISECONDS);
                assertNotTimedOut("Waiting until response finished", latch);
                afterTimeoutResult[0] = ar.resume("Hello");
                afterSentLatch.countDown();
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(503));
            assertThat(resp.body().string(), equalTo("<h1>503 Service Unavailable</h1><p>Timed out</p>"));
        }
        latch.countDown();
        assertNotTimedOut("Waiting until resume sent", afterSentLatch);
        assertThat(afterTimeoutResult[0], is(false));
    }


    @Test
    public void aCustomTimeoutHandlerCanBeUsed() throws Exception {
        class Hawk {
            String toHawker() {
                return "Oops, this is Hawkward";
            }
        }
        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse ar) {
                ar.setTimeoutHandler(asyncResponse -> asyncResponse.resume(new Hawk()));
                ar.setTimeout(10, TimeUnit.MILLISECONDS);
            }
        }
        this.server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new Sample())
                .addCustomWriter(new MessageBodyWriter<Hawk>() {
                    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
                        return type.equals(Hawk.class);
                    }

                    public void writeTo(Hawk hawk, Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
                        entityStream.write(hawk.toHawker().getBytes("UTF-8"));
                    }
                })
            )
            .start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("Oops, this is Hawkward"));
        }
    }


    @Test
    public void responsesCanBeCancelledWhichSendsA503() throws Exception {
        AtomicBoolean cancelResult = new AtomicBoolean();
        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse ar, @QueryParam("retryDate") Long retryDate, @QueryParam("retrySeconds") Integer retrySeconds) {
                executor.submit(() -> {
                    if (retrySeconds != null) {
                        cancelResult.set(ar.cancel(retrySeconds));
                    } else if (retryDate != null) {
                        cancelResult.set(ar.cancel(new Date(retryDate)));
                    } else {
                        cancelResult.set(ar.cancel());
                    }
                });
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(503));
            assertThat(resp.header("Retry-After"), is(nullValue()));
        }
        try (Response resp = call(request().url(server.uri().resolve("/samples?retrySeconds=123").toString()))) {
            assertThat(resp.code(), is(503));
            assertThat(resp.header("Retry-After"), is("123"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/samples?retryDate=1530959539077").toString()))) {
            assertThat(resp.code(), is(503));
            assertThat(resp.header("Retry-After"), is("Sat, 7 Jul 2018 10:32:19 GMT"));
        }
    }


    @Test
    public void completionCallbacksCanBeRegistered() throws Exception {
        CountDownLatch completedLatch = new CountDownLatch(1);
        CountDownLatch disconnectedLatch = new CountDownLatch(1);
        AtomicReference<Map<Class<?>, Collection<Class<?>>>> registered = new AtomicReference<>();

        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse ar, @QueryParam("retryDate") Long retryDate, @QueryParam("retrySeconds") Integer retrySeconds) {
                registered.set(ar.register(
                    (ConnectionCallback) disconnected -> disconnectedLatch.countDown(),
                    (CompletionCallback) disconnected -> completedLatch.countDown())
                );
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();
        OkHttpClient impatientClient = ClientUtils.client.newBuilder()
            .readTimeout(200, TimeUnit.MILLISECONDS)
            .build();
        try (Response ignored = impatientClient.newCall(request().url(server.uri().resolve("/samples").toString()).build()).execute()) {
            Assert.fail("This test expected a client timeout");
        } catch (SocketTimeoutException te) {
            assertNotTimedOut("waiting for disconnect callback", disconnectedLatch);
            assertNotTimedOut("waiting for completed callback", completedLatch);
        }

        assertThat(registered.get().values().stream().flatMap(Collection::stream).collect(toSet()),
            containsInAnyOrder(ConnectionCallback.class, CompletionCallback.class));
    }

    @Test
    public void ifExceptionThrownAfterAsyncStartedButBeforeAsyncInvokedThenSomethingHappens() throws IOException {
        AtomicBoolean methodCalled = new AtomicBoolean(false);
        @Path("samples")
        class Sample {
            @POST
            public void echo(@Suspended AsyncResponse ar, int input) {
                methodCalled.set(true);
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request()
            .post(RequestBody.create("", MediaType.parse("text/plain")))
            .url(server.uri().resolve("/samples").toString())
        )) {
            assertThat(resp.code(), equalTo(400));
            assertThat(resp.body().string(), containsString("400 Bad Request"));
        }
        assertThat("Invalid request, but method was called", methodCalled.get(), is(false));
    }

    @After
    public void stop() {
        MuAssert.stopAndCheck(server);
    }

}
