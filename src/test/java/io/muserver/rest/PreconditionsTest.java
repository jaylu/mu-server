package io.muserver.rest;

import io.muserver.HeaderNames;
import io.muserver.MuServer;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import java.net.URI;
import java.util.Date;

import static io.muserver.Mutils.toHttpDate;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class PreconditionsTest {
    private MuServer server;

    @Test
    public void ifModifiedSinceCanBeUsed() throws Exception {
        Date lastModified = new Date();
        @Path("samples")
        class Sample {
            @GET
            public jakarta.ws.rs.core.Response get(@Context Request jaxRequest) {
                jakarta.ws.rs.core.Response.ResponseBuilder resp = jaxRequest.evaluatePreconditions(lastModified);
                if (resp == null) {
                    resp = jakarta.ws.rs.core.Response.ok("The content");
                }
                return resp.lastModified(lastModified).build();
            }
        }
        this.server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        URI url = server.uri().resolve("/samples");
        try (Response resp = call(request(url))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("last-modified"), equalTo(toHttpDate(lastModified)));
            assertThat(resp.body().string(), equalTo("The content"));
        }

        Date oneSecondAfterLastModified = new Date(lastModified.toInstant().plusSeconds(1).toEpochMilli());
        try (Response resp = call(request(url)
            .header(HeaderNames.IF_MODIFIED_SINCE.toString(), toHttpDate(oneSecondAfterLastModified))
        )) {
            assertThat(resp.code(), equalTo(304));
            assertThat(resp.header("last-modified"), equalTo(toHttpDate(lastModified)));
            assertThat(resp.body().contentLength(), equalTo(0L));
        }

        try (Response resp = call(request(url).header(HeaderNames.IF_MODIFIED_SINCE.toString(), toHttpDate(lastModified)))) {
            assertThat(resp.code(), equalTo(304));
            assertThat(resp.header("last-modified"), equalTo(toHttpDate(lastModified)));
            assertThat(resp.body().contentLength(), equalTo(0L));
        }

        Date oneSecondBeforeLastModified = new Date(lastModified.toInstant().minusSeconds(1).toEpochMilli());
        try (Response resp = call(request(url)
            .header(HeaderNames.IF_MODIFIED_SINCE.toString(), toHttpDate(oneSecondBeforeLastModified))
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("last-modified"), equalTo(toHttpDate(lastModified)));
            assertThat(resp.body().string(), equalTo("The content"));
        }
    }


    @Test
    public void ifUnmodifiedSinceCanBeUsedForPuts() throws Exception {
        Date lastModified = new Date();
        @Path("samples")
        class Sample {
            @PUT
            public jakarta.ws.rs.core.Response get(@Context Request jaxRequest) {
                jakarta.ws.rs.core.Response.ResponseBuilder resp = jaxRequest.evaluatePreconditions(lastModified);
                if (resp == null) {
                    resp = jakarta.ws.rs.core.Response.ok("The content");
                }
                return resp.lastModified(lastModified).build();
            }
        }
        this.server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        URI url = server.uri().resolve("/samples");
        try (Response resp = call(request(url).put(somePutBody()))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("last-modified"), equalTo(toHttpDate(lastModified)));
            assertThat(resp.body().string(), equalTo("The content"));
        }

        Date oneSecondAfterLastModified = new Date(lastModified.toInstant().plusSeconds(1).toEpochMilli());
        try (Response resp = call(request(url)
            .put(somePutBody())
            .header(HeaderNames.IF_UNMODIFIED_SINCE.toString(), toHttpDate(oneSecondAfterLastModified))
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("last-modified"), equalTo(toHttpDate(lastModified)));
            assertThat(resp.body().string(), equalTo("The content"));
        }

        try (Response resp = call(request(url)
            .put(somePutBody())
            .header(HeaderNames.IF_UNMODIFIED_SINCE.toString(), toHttpDate(lastModified)))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("last-modified"), equalTo(toHttpDate(lastModified)));
            assertThat(resp.body().string(), equalTo("The content"));
        }

        Date oneSecondBeforeLastModified = new Date(lastModified.toInstant().minusSeconds(1).toEpochMilli());
        try (Response resp = call(request(url)
            .put(somePutBody())
            .header(HeaderNames.IF_UNMODIFIED_SINCE.toString(), toHttpDate(oneSecondBeforeLastModified))
        )) {
            assertThat(resp.code(), equalTo(412));
            String body = resp.body().string();
            assertThat(body, containsString("412 Precondition Failed"));
            assertThat(body, containsString("if-unmodified-since"));
        }

    }


    @Test
    public void ifNoneMatchCanBeUsedForGets() throws Exception {
        String etag = "some-etag";
        @Path("samples")
        class Sample {
            @GET
            public jakarta.ws.rs.core.Response get(@Context Request jaxRequest) {
                jakarta.ws.rs.core.Response.ResponseBuilder resp = jaxRequest.evaluatePreconditions(new EntityTag(etag));
                if (resp == null) {
                    resp = jakarta.ws.rs.core.Response.ok("The content").tag(etag);
                }
                return resp.build();
            }
        }
        this.server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        URI url = server.uri().resolve("/samples");
        try (Response resp = call(request(url))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().string(), equalTo("The content"));
        }

        try (Response resp = call(request(url)
            .addHeader(HeaderNames.IF_NONE_MATCH.toString(), "W/\"67ab43\"")
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().string(), equalTo("The content"));
        }
        try (Response resp = call(request(url)
            .addHeader(HeaderNames.IF_NONE_MATCH.toString(), "W/\"67ab43\"")
            .addHeader(HeaderNames.IF_NONE_MATCH.toString(), "\"" + etag + "\"")
        )) {
            assertThat(resp.code(), equalTo(304));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().contentLength(), equalTo(0L));
        }
    }

    @Test
    public void ifMatchCanBeUsedForPuts() throws Exception {
        String etag = "some-etag";
        @Path("samples")
        class Sample {
            @PUT
            @Consumes("*/*")
            public jakarta.ws.rs.core.Response put(@Context Request jaxRequest) {
                jakarta.ws.rs.core.Response.ResponseBuilder resp = jaxRequest.evaluatePreconditions(new EntityTag(etag));
                if (resp == null) {
                    resp = jakarta.ws.rs.core.Response.ok("The content").tag(etag);
                }
                return resp.build();
            }
        }
        this.server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        URI url = server.uri().resolve("/samples");
        try (Response resp = call(request(url).put(somePutBody()))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().string(), equalTo("The content"));
        }

        try (Response resp = call(request(url).put(somePutBody())
            .addHeader(HeaderNames.IF_MATCH.toString(), "W/\"67ab43\"")
        )) {
            assertThat(resp.code(), equalTo(412));
            String body = resp.body().string();
            assertThat(body, containsString("412 Precondition Failed"));
            assertThat(body, containsString("if-match"));
        }
        try (Response resp = call(request(url).put(somePutBody())
            .addHeader(HeaderNames.IF_MATCH.toString(), "W/\"67ab43\"")
            .addHeader(HeaderNames.IF_MATCH.toString(), "\"" + etag + "\"")
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().string(), equalTo("The content"));
        }
    }

    @Test
    public void emptyEvaluationPassesIfNoIfMatch() throws Exception {
        @Path("samples")
        class Sample {
            @PUT
            @Consumes("*/*")
            public jakarta.ws.rs.core.Response put(@Context Request jaxRequest) {
                jakarta.ws.rs.core.Response.ResponseBuilder resp = jaxRequest.evaluatePreconditions();
                if (resp == null) {
                    resp = jakarta.ws.rs.core.Response.ok("The content");
                }
                return resp.build();
            }
        }
        this.server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        URI url = server.uri().resolve("/samples");
        try (Response resp = call(request(url).put(somePutBody()))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("The content"));
        }

        try (Response resp = call(request(url).put(somePutBody())
            .addHeader(HeaderNames.IF_MATCH.toString(), "*")
        )) {
            assertThat(resp.code(), equalTo(412));
            String body = resp.body().string();
            assertThat(body, containsString("412 Precondition Failed"));
            assertThat(body, containsString("if-match"));
        }
    }


    private RequestBody somePutBody() {
        return RequestBody.create("blah", okhttp3.MediaType.get("text/plain"));
    }


    @Test
    public void eTagsAndLastModifiedCanBothBeChecked() throws Exception {
        String etag = "some-etag";
        Date lastModified = new Date();

        @Path("samples")
        class Sample {
            @GET
            public jakarta.ws.rs.core.Response get(@Context Request jaxRequest) {
                jakarta.ws.rs.core.Response.ResponseBuilder resp = jaxRequest.evaluatePreconditions(lastModified, new EntityTag(etag));
                if (resp == null) {
                    resp = jakarta.ws.rs.core.Response.ok("The content");
                }
                return resp.tag(etag).build();
            }
        }
        this.server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        URI url = server.uri().resolve("/samples");
        try (Response resp = call(request(url))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().string(), equalTo("The content"));
        }

        try (Response resp = call(request(url)
            .addHeader(HeaderNames.IF_NONE_MATCH.toString(), "W/\"67ab43\"")
            .addHeader(HeaderNames.IF_NONE_MATCH.toString(), "\"" + etag + "\"")
        )) {
            assertThat(resp.code(), equalTo(304));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().contentLength(), equalTo(0L));
        }

        Date oneSecondBeforeLastModified = new Date(lastModified.toInstant().minusSeconds(1).toEpochMilli());
        try (Response resp = call(request(url)
            .addHeader(HeaderNames.IF_NONE_MATCH.toString(), "\"" + etag + "\"")
            .addHeader(HeaderNames.IF_MODIFIED_SINCE.toString(), toHttpDate(oneSecondBeforeLastModified))
        )) {
            assertThat(resp.code(), equalTo(304));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().contentLength(), equalTo(0L));
        }
        try (Response resp = call(request(url)
            .addHeader(HeaderNames.IF_NONE_MATCH.toString(), "\"blah\"")
            .addHeader(HeaderNames.IF_MODIFIED_SINCE.toString(), toHttpDate(oneSecondBeforeLastModified))
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().string(), equalTo("The content"));
        }
        try (Response resp = call(request(url)
            .addHeader(HeaderNames.IF_MODIFIED_SINCE.toString(), toHttpDate(oneSecondBeforeLastModified))
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().string(), equalTo("The content"));
        }
    }


    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}