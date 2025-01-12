package io.muserver.rest;

import io.muserver.AsyncSsePublisher;
import io.muserver.MuResponse;
import io.muserver.ResponseCompleteListener;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.SseEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static io.muserver.rest.JaxRSResponse.muHeadersToJaxObj;
import static java.nio.charset.StandardCharsets.UTF_8;

class JaxSseEventSinkImpl implements SseEventSink {
    private static final Logger log = LoggerFactory.getLogger(JaxSseEventSinkImpl.class);

    private final AsyncSsePublisher ssePublisher;
    private final MuResponse response;
    private final EntityProviders entityProviders;

    public JaxSseEventSinkImpl(AsyncSsePublisher ssePublisher, MuResponse response, EntityProviders entityProviders) {
        this.ssePublisher = ssePublisher;
        this.response = response;
        this.entityProviders = entityProviders;
    }

    void setResponseCompleteHandler(ResponseCompleteListener listener) {
        ssePublisher.setResponseCompleteHandler(listener);
    }

    @Override
    public boolean isClosed() {
        return ssePublisher.isClosed();
    }

    @Override
    public CompletionStage<?> send(OutboundSseEvent event) {

        CompletionStage<?> stage = null;

        try {
            if (event.isReconnectDelaySet()) {
                stage = ssePublisher.setClientReconnectTime(event.getReconnectDelay(), TimeUnit.MILLISECONDS);
            }
            if (event.getComment() != null) {
                stage = ssePublisher.sendComment(event.getComment());
            }
            Object data = event.getData();
            if (data != null) {
                MessageBodyWriter messageBodyWriter = entityProviders.selectWriter(event.getType(), event.getGenericType(),
                    JaxRSResponse.Builder.EMPTY_ANNOTATIONS, event.getMediaType());
                String dataString;
                if (data instanceof String && messageBodyWriter instanceof StringEntityProviders.StringMessageReaderWriter) {
                    dataString = (String) data;
                } else {
                    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        messageBodyWriter.writeTo(data, event.getType(), event.getGenericType(), JaxRSResponse.Builder.EMPTY_ANNOTATIONS,
                            event.getMediaType(), muHeadersToJaxObj(response.headers()), out);
                        dataString = out.toString(UTF_8);
                    }
                }
                stage = ssePublisher.send(dataString, event.getName(), event.getId());
            }
            if (stage == null) {
                throw new IllegalArgumentException("The event had nothing to send");
            }
        } catch (Throwable e) {
            if (e instanceof ServerErrorException) {
                log.warn("Server error while writing data to SSE stream", e);
            }
            CompletableFuture<?> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            stage = f;
        }
        return stage;
    }

    @Override
    public void close() {
        ssePublisher.close();
    }
}
