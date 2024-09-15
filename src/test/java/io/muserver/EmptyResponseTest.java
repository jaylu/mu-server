package io.muserver;

import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.ClientUtils;
import scaffolding.ServerUtils;

import java.io.IOException;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ClientUtils.request;

public class EmptyResponseTest {
    private MuServer server;

    @Test
    public void a204IsDefaultIfNoResponseWritten() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.headers().set("hello", "world");
            })
            .start();

        try (Response resp = ClientUtils.call(request(server.uri()))) {
            assertThat(resp.code(), is(204));
            assertThat(resp.header("Date"), is(notNullValue()));
            assertThat(resp.header("hello"), equalTo("world"));
            assertThat(resp.header("Content-Length"), is(nullValue()));
            assertThat(resp.body().bytes().length, is(0));
        }
    }

    @Test
    public void a304HasNoBody() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(304);
            })
            .start();

        try (Response resp = ClientUtils.call(request(server.uri()))) {
            assertThat(resp.code(), is(304));
            assertThat(resp.header("Date"), is(notNullValue()));
            assertThat(resp.header("Content-Length"), is(nullValue()));
            assertThat(resp.body().bytes().length, is(0));
        }
    }

    @AfterEach
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }
}