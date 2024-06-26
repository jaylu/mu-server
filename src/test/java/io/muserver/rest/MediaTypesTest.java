package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import java.io.IOException;
import java.util.List;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class MediaTypesTest {

    private MuServer server;

    @Test
    public void blah() throws IOException {

        @Path("pictures")
        class Widget {

            @GET
            @Produces({"image/jpeg, image/gif ", " image/png"})
            public String all() {
                return ";)";
            }

            @GET
            @Produces("application/json")
            public String json() {
                return "[]";
            }
        }

        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Widget())).start();

        assertSelected("/pictures", asList("image/gif"), ";)");
        assertSelected("/pictures", asList("image/jpeg"), ";)");
        assertSelected("/pictures", asList("image/png"), ";)");
        assertSelected("/pictures", asList("image/png", "application/text"), ";)");
        assertNotSelected("/pictures", asList("text/plain"));
        assertSelected("/pictures", asList("application/json"), "[]");

    }

    @Test
    public void canHandleChromeAcceptHeader() throws IOException {
        @Path("things")
        class Widget {
            @GET
            @Produces("text/plain")
            public String json() {
                return "[]";
            }
        }

        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Widget())).start();

        try (Response resp = call(request()
            .url(server.uri().resolve("/things").toString())
            .header("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8" ))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), is("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("[]"));
        }

    }

    @Test
    public void canHandleFirefox61AcceptHeader() throws IOException {
        @Path("things")
        class Widget {
            @GET
            @Produces("text/plain")
            public String json() {
                return "[]";
            }
        }

        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Widget())).start();

        try (Response resp = call(request()
            .url(server.uri().resolve("/things").toString())
            .header("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" ))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), is("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("[]"));
        }
    }

    @Test
    public void producesOnClassCanBeUsedAsDefault() {
        @Path("things")
        @Produces("application/json")
        class Widget {
            @GET
            public String json() {
                return "[]";
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Widget())).start();
        try (Response resp = call(request().url(server.uri().resolve("/things").toString()))) {
            assertThat(resp.header("Content-Type"), is("application/json"));
        }
    }

    @Test
    public void invalidAcceptHeadersReturn400() throws IOException {
        @Path("things")
        class Widget {
            @GET
            @Produces("application/json")
            public String json() {
                return "[]";
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Widget())).start();
        try (Response resp = call(request().url(server.uri().resolve("/things").toString())
        .header("accept", "text")
        )) {
            assertThat(resp.code(), is(400));
            assertThat(resp.header("Content-Type"), is("text/html;charset=utf-8"));
            assertThat(resp.body().string(), containsString("Media types must be in the format &#x27;type&#x2F;subtype&#x27;; this is inavlid: &#x27;text&#x27;"));
        }

    }

    @Test
    public void ifReturnTypeIsStringThenDefaultsToTextPlain() {
        @Path("things")
        class Widget {

            @GET
            @Path("string")
            public String getString() {
                return "[]";
            }
            @GET
            @Path("int")
            public int getInt() {
                return 42;
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Widget())).start();
        try (Response resp = call(request().url(server.uri().resolve("/things/string").toString()))) {
            assertThat(resp.header("Content-Type"), is("text/plain;charset=utf-8"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/things/int").toString()))) {
            assertThat(resp.header("Content-Type"), is("text/plain;charset=utf-8"));
        }
    }

    private void assertSelected(String path, List<String> accept, String expectedBody) throws IOException {
        Request.Builder rb = request()
            .url(server.uri().resolve(path).toString());
        for (String s : accept) {
            rb.addHeader("Accept", s);
        }
        try (Response resp = call(rb)) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo(expectedBody));
        }
    }

    private void assertNotSelected(String path, List<String> accept) {
        Request.Builder rb = request()
            .url(server.uri().resolve(path).toString());
        for (String s : accept) {
            rb.addHeader("Accept", s);
        }
        try (Response resp = call(rb)) {
            assertThat(resp.code(), is(406));
        }
    }


    @After
    public void stop() {
        MuAssert.stopAndCheck(server);
    }

}
