package io.muserver;

import io.muserver.rest.PathMatch;
import io.muserver.rest.UriPattern;
import jakarta.ws.rs.core.PathSegment;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ClientUtils;
import scaffolding.ServerUtils;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.muserver.ContextHandlerBuilder.context;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ClientUtils.request;

public class RoutesTest {
    private final RequestCounter requestCounter = new RequestCounter();
    private MuServer server;

    @Test
    public void canRouteToExactMatches() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/blah", requestCounter)
            .start();

        assertThat(call(Method.GET, "/bla"), is(404));
        assertThat(call(Method.GET, "/blahd"), is(404));
        assertThat(call(Method.DELETE, "/blah"), is(404));
        assertThat(requestCounter.count.get(), is(0));

        assertThat(call(Method.GET, "/blah/"), is(200)); // should this be 404?
        assertThat(call(Method.GET, "/blah"), is(200));
        assertThat(call(Method.GET, "/blah?oh=yeah"), is(200));
        assertThat(call(Method.GET, "/blah#hi"), is(200));
        assertThat(requestCounter.count.get(), is(4));
    }

    @Test
    public void canRouteToSlash() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", requestCounter)
            .start();
        assertThat(call(Method.GET, "/"), is(200)); // should this be 404?
    }

    @Test
    public void jaxrsTemplatesCanBeUsed() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/blah/{id : [0-9]+}/ha",
                (request, response, pathParams) -> response.write(pathParams.get("id")))
            .start();

        assertThat(call(Method.GET, "/blah/1/ha/ooh"), is(404));
        assertThat(call(Method.GET, "/ooh/blah/1/ha"), is(404));
        assertThat(call(Method.GET, "/blah/a/ha"), is(404));
        assertThat(call(Method.DELETE, "/blah/10/ha"), is(404));

        assertThat(call(Method.GET, "/blah/12345/ha"), is(200));
        assertThat(call(Method.GET, "/blah/12345/ha?oh=yeah"), is(200));
        assertThat(call(Method.GET, "/blah/12345/ha#hi"), is(200));

        assertThat(respBody(Method.GET, "/blah/12345/ha?oh=yeah"),
            equalTo("12345"));
    }

    @Test
    public void pathParametersAreUrlDecoded() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/blah ha/{name}/ha",
                (request, response, pathParams) -> response.write(pathParams.get("name")))
            .start();
        assertThat(respBody(Method.GET, "/blah%20ha/hello%20goodbye/ha"),
            equalTo("hello goodbye"));
    }

    @Test
    public void matrixParametersAreIgnoredButCanBeRetrieved() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(context("context")
                .addHandler(Method.GET, "/blah ha/{name}/{ha}",
                    (request, response, pathParams) -> {
                        PathMatch matcher = UriPattern.uriTemplateToRegex("/context/blah%20ha/{name}/{ha}").matcher(request.uri());
                        PathSegment lastSegment = matcher.segments().get("ha");
                        response.write(lastSegment.getPath() + " - " + lastSegment.getMatrixParameters().getFirst("h i"));
                    })
            )
            .start();
        assertThat(respBody(Method.GET, "/context/blah%20ha;h%20i=h%20i;a=b/hello%20goodbye/ha%20ha;h%20i=h%20i;a=b"),
            equalTo("ha ha - h i"));
    }

    private int call(Method method, String path) {
        try (Response resp = ClientUtils.call(request()
            .method(method.name(), null)
            .url(server.uri().resolve(path).toString()))) {
            return resp.code();
        }
    }

    private String respBody(Method method, String path) throws IOException {
        try (Response resp = ClientUtils.call(request()
            .method(method.name(), null)
            .url(server.uri().resolve(path).toString()))) {
            return resp.body().string();
        }
    }

    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

    private static class RequestCounter implements RouteHandler {
        final AtomicInteger count = new AtomicInteger();
        public void handle(MuRequest request, MuResponse response, Map<String, String> pathParams) throws Exception {
            count.incrementAndGet();
        }
    }
}