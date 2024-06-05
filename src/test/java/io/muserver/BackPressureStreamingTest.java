package io.muserver;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import org.junit.Test;
import scaffolding.ServerUtils;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.*;

public class BackPressureStreamingTest {

    protected String getStringWithLength(int length) {
        String possibleText = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";
        StringBuilder result = new StringBuilder();
        Random rnd = new Random();
        while (result.length() < length) { // length of the random string.
            int index = (int) (rnd.nextFloat() * possibleText.length());
            result.append(possibleText.charAt(index));
        }
        return result.toString();

    }

    @Test
    public void canStreamLargeBody() throws Exception {
        int chunkCount = 10;
        int chunkSize = 128 * 1024;

        MuServer server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {

                AtomicInteger serverSent = new AtomicInteger(0);
                AsyncHandle asyncHandle = request.handleAsync();
                String chunkString = getStringWithLength(chunkSize);
                ByteBuffer byteBuffer = Mutils.toByteBuffer(chunkString);

                final DoneCallback[] doneCallback = new DoneCallback[1];

                doneCallback[0] = error -> {

                    if (serverSent.get() >= chunkCount) {
                        asyncHandle.complete();
                        return;
                    }

                    serverSent.incrementAndGet();
                    asyncHandle.write(byteBuffer, doneCallback[0]);
                    System.out.println("send more chunks: " + byteBuffer.capacity());
                };

                asyncHandle.write(byteBuffer, doneCallback[0]);
                System.out.println("send more chunks: " + byteBuffer.capacity());
                serverSent.incrementAndGet();


                return true;
            }).start();

        OkHttpClient http1Client = new OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .followRedirects(false)
            .followSslRedirects(false)
            .hostnameVerifier((hostname, session) -> true)
            .readTimeout(120, TimeUnit.SECONDS)
            .sslSocketFactory(sslContextForTesting(veryTrustingTrustManager).getSocketFactory(), veryTrustingTrustManager)
            .build();


        try (Response resp = call(http1Client, request(server.uri()))) {
            int read;
            byte[] buffer = new byte[8192];
            assert resp.body() != null;
            InputStream bs = resp.body().byteStream();
            int total = 0;
            while ((read = bs.read(buffer)) > 0) {
                total += read;
                Thread.sleep(10); // slow client will trigger the BackPressureHandler kick involve
            }
            assertThat(total, equalTo(chunkCount * chunkSize));
        }
    }
}
