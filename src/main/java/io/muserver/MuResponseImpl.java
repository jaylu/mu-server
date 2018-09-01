package io.muserver;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.concurrent.Future;
import java.util.zip.GZIPOutputStream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

class MuResponseImpl implements MuResponse {
    private static final Logger log = LoggerFactory.getLogger(MuResponseImpl.class);

    private enum OutputState {
        NOTHING, FULL_SENT, STREAMING, STREAMING_COMPLETE
    }

    private final WritableByteChannel channel;
    private final MuRequestImpl request;
    private final boolean isKeepAlive;
    private int status = 200;
    private final MuHeaders headers = new MuHeaders();
    private OutputState state = OutputState.NOTHING;
    private final ResponseGenerator rg;
    private PrintWriter writer;
    private OutputStream outputStream;
    private long bytesStreamed = 0;
    private boolean chunkResponse;
    private final MuStatsImpl2 stats;
    private final ServerSettings settings;
    private final boolean isHead;

    MuResponseImpl(WritableByteChannel channel, MuRequestImpl request, boolean isKeepAlive, MuStatsImpl2 stats, ServerSettings settings) {
        this.channel = channel;
        this.request = request;
        isHead = request.method() == Method.HEAD;
        this.isKeepAlive = isKeepAlive;
        this.stats = stats;
        this.settings = settings;
        rg = new ResponseGenerator(HttpVersion.HTTP_1_1);
        headers.set(HeaderNames.DATE.toString(), Mutils.toHttpDate(new Date()));
    }

    Charset charset() {
        String contentType = headers.get(HeaderNames.CONTENT_TYPE);
        if (contentType == null) {
            return UTF_8;
        }
        // TODO: parse the charset
        return UTF_8;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public void status(int value) {
        this.status = value;
    }

    @Override
    @Deprecated
    public Future<Void> writeAsync(String text) {
        throw new MuException("Deprecated");
    }

    @Override
    public void write(String text) {
        if (state != OutputState.NOTHING) {
            throw new IllegalStateException("MuResponse.write(String) can only be called once. To send text in multiple chunks" +
                " use MuResponse.sendChunk(String) instead.");
        }
        ByteBuffer toSend = charset().encode(text);
        headers.set(HeaderNames.CONTENT_LENGTH, toSend.remaining());
        try {
            writeBytes(rg.writeHeader(status, headers));
            // TODO combine into a single write if not HEAD?
            if (!isHead) {
                writeBytes(toSend);
            }
        } catch (IOException e) {
            throw new MuException("Exception while sending to the client. They have probably disconnected.", e);
        }
        state = OutputState.FULL_SENT;
    }


    void complete(boolean forceDisconnect) {
        boolean shouldDisconnect = forceDisconnect || !isKeepAlive || headers.containsValue(HeaderNames.CONNECTION, HeaderValues.CLOSE, true);
        try {
            if (channel.isOpen()) {
                if (state == OutputState.NOTHING) {

                    if (!isHead || !(headers().contains(HeaderNames.CONTENT_LENGTH))) {
                        headers.set(HeaderNames.CONTENT_LENGTH, 0);
                    }
                    if (shouldDisconnect) {
                        headers.add(HeaderNames.CONNECTION, HeaderValues.CLOSE);
                    }
                    ByteBuffer byteBuffer = rg.writeHeader(status, headers);
                    throwIfFinished();
                    int expected = byteBuffer.remaining();
                    int written = channel.write(byteBuffer);
                    bytesStreamed += written;
                    if (written != expected) {
                        log.warn("Sent " + written + " bytes but expected to send " + expected);
                    }

                } else if (state == OutputState.STREAMING) {
                    if (writer != null) {
                        writer.close();
                    }
                    if (outputStream != null) {
                        outputStream.close();
                    }

                    if (chunkResponse && !isHead) {
                        sendChunkEnd();
                    }
                    state = OutputState.STREAMING_COMPLETE;
                }

                if (!isHead && (headers().contains(HeaderNames.CONTENT_LENGTH))) {
                    long declaredLength = Long.parseLong(headers.get(HeaderNames.CONTENT_LENGTH));
                    long actualLength = this.bytesStreamed;
                    if (declaredLength != actualLength) {
//                        shouldDisconnect = true;
//                        log.warn("Declared length " + declaredLength + " doesn't equal actual length " + actualLength + " for " + request);
                    }
                }

            }

        } catch (Exception e) {
            shouldDisconnect = true;
            throw new MuException("Error while completing response", e);
        } finally {
            stats.onRequestEnded(request);
            if (shouldDisconnect) {
                try {
                    channel.close();
                } catch (IOException e) {
                    log.info("Error closing response to client", e);
                }
            }
        }
    }


    private void writeBytes(ByteBuffer bytes) throws IOException {
        throwIfFinished();
        int expected = bytes.remaining();
        int written = channel.write(bytes);
        bytesStreamed += written;
        stats.incrementBytesSent(written);
        if (written != expected) {
            log.warn("Sent " + written + " bytes but expected to send " + expected);
        }
    }


    private void throwIfFinished() {
        if (state == OutputState.FULL_SENT || state == OutputState.STREAMING_COMPLETE) {
            throw new IllegalStateException("Cannot write data as response has already completed");
        }
    }

    @Override
    public void sendChunk(String text) {
        byte[] bytes = text.getBytes(charset());
        try {
            sendBodyData(bytes, 0, bytes.length);
        } catch (IOException e) {
            throw new MuException("Exception while sending to the client. They have probably disconnected.", e);
        }
    }

    void sendBodyData(byte[] data, int off, int len) throws IOException {
        if (state == OutputState.NOTHING) {
            startStreaming();
        }

        ByteBuffer bb;
        if (chunkResponse) {
            byte[] size = Integer.toHexString(len).getBytes(US_ASCII);
            bb = ByteBuffer.allocate(len + size.length + 4);
            bb.put(size)
                .put((byte) '\r')
                .put((byte) '\n')
                .put(data, off, len)
                .put((byte) '\r')
                .put((byte) '\n')
                .flip();
        } else {
            bb = ByteBuffer.wrap(data, off, len);
        }
        writeBytes(bb);
    }

    void sendBodyData(ByteBuffer data) throws IOException {
        if (state == OutputState.NOTHING) {
            startStreaming();
        }

        ByteBuffer bb;
        if (chunkResponse) {
            int len = data.remaining();
            byte[] size = Integer.toHexString(len).getBytes(US_ASCII);
            bb = ByteBuffer.allocate(len + size.length + 4);
            bb.put(size)
                .put((byte) '\r')
                .put((byte) '\n')
                .put(data)
                .put((byte) '\r')
                .put((byte) '\n')
                .flip();
        } else {
            bb = data;
        }
        writeBytes(bb);
    }

    private static final byte[] LAST_CHUNK = new byte[]{'0', '\r', '\n', '\r', '\n'};

    private void sendChunkEnd() throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(LAST_CHUNK);
        throwIfFinished();
        int expected = bytes.remaining();
        int written = channel.write(bytes);
        bytesStreamed += written;
        if (written != expected) {
            log.warn("Sent " + written + " bytes but expected to send " + expected);
        }
    }


    public void redirect(String newLocation) {
        redirect(URI.create(newLocation));
    }

    public void redirect(URI newLocation) {
        URI absoluteUrl = request.uri().resolve(newLocation);
        status(302);
        headers().set(HeaderNames.LOCATION, absoluteUrl.toString());
        headers().set(HeaderNames.CONTENT_LENGTH, "0");
    }

    @Override
    public Headers headers() {
        return headers;
    }

    @Override
    public void contentType(CharSequence contentType) {
        headers.set(HeaderNames.CONTENT_TYPE, contentType);
    }

    public void addCookie(Cookie cookie) {
        headers.add(HeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie.nettyCookie));
    }

    private void startStreaming() throws IOException {
        if (state != OutputState.NOTHING) {
            throw new IllegalStateException("Cannot start streaming when state is " + state);
        }
        state = OutputState.STREAMING;
        if (headers.contains(HeaderNames.CONTENT_LENGTH)) {
            chunkResponse = false;
        } else {
            headers.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            chunkResponse = true;
        }
        writeBytes(rg.writeHeader(status, headers));
    }

    public OutputStream outputStream() {
        if (this.outputStream == null) {
            boolean shouldGzip = settings.compressionSettings.shouldGzip(
                headers.getLong(HeaderNames.CONTENT_LENGTH.toString(), Long.MAX_VALUE),
                headers.get(HeaderNames.CONTENT_TYPE), request.headers().get(HeaderNames.ACCEPT_ENCODING));
            if (shouldGzip) {
                headers.remove(HeaderNames.CONTENT_LENGTH);
                headers.add(HeaderNames.CONTENT_ENCODING, HeaderValues.GZIP);
            }
            OutputStream outputStream;
            try {
                startStreaming();
                outputStream = new ResponseOutputStream(this);
                if (shouldGzip && !isHead) {
                    outputStream = new GZIPOutputStream(outputStream, true);
                }
            } catch (IOException e) {
                throw new MuException("Exception while sending to the client. They have probably disconnected.", e);
            }
            this.outputStream = outputStream;
        }
        return this.outputStream;
    }

    public PrintWriter writer() {
        if (this.writer == null) {
            OutputStreamWriter os = new OutputStreamWriter(outputStream(), charset());
            this.writer = new PrintWriter(os);
        }
        return this.writer;
    }

    @Override
    public boolean hasStartedSendingData() {
        return false;
    }
}
