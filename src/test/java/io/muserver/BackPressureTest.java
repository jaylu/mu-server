package io.muserver;

import okhttp3.*;
import okio.BufferedSink;
import org.junit.After;
import org.junit.Test;
import scaffolding.ServerUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class BackPressureTest {

    static {
        OkHttpDebugLogging.INSTANCE.enableHttp2();
        OkHttpDebugLogging.INSTANCE.enableTaskRunner();
    }

    private MuServer server;

    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

    @Test
    public void slowServerReceivingTest() throws IOException {
        int chunkSize = 10000;
        int loops = 64000;
        server = ServerUtils.httpsServerForTest()
            .withMaxRequestSize(loops * (long) chunkSize)
            .withIdleTimeout(5, TimeUnit.MINUTES)
            .withRequestTimeout(5, TimeUnit.MINUTES)
            .addHandler(Method.POST, "/slow", (request, response, pathParams) -> {
                response.contentType("application/octet-stream");

                try (InputStream is = request.inputStream().orElseThrow(() -> new MuException("No input stream"))) {
                    byte[] buffer = new byte[chunkSize];
                    int read;
                    long received = 0;
                    AtomicInteger counter = new AtomicInteger(0);
                    while ((read = is.read(buffer)) > -1) {
                        System.out.println("reading " + counter.incrementAndGet() + " chunks");
                        received += read;
//                        Thread.sleep(100);
                    }
                    response.write("Got " + received + " bytes");
                }
            })
            .start();

        Request.Builder request = request()
            .url(server.uri().resolve("/slow").toString())
            .post(new RequestBody() {
                @Override
                public MediaType contentType() {
                    return MediaType.get("application/octet-stream");
                }

                @Override
                public void writeTo(BufferedSink bufferedSink) throws IOException {
                    byte[] ones = new byte[chunkSize];
                    Arrays.fill(ones, (byte) 1);
                    for (int i = 0; i < loops; i++) {
                        ByteBuffer src = ByteBuffer.wrap(ones);
                        bufferedSink.write(src);
                        System.out.println("sending " + i + " chunks");
                    }
                }
            });

        try (Response resp = call(request)) {
            assertThat(resp.code(), is(200));
            long l = (long) chunkSize * (long) loops;
            System.out.println(l);
            assertThat(resp.body().string(), equalTo("Got " + l + " bytes"));
        }
    }

    @Test
    public void slowClientTest() {

    }
}
