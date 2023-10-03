package scaffolding;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

import java.io.IOException;
import java.io.InterruptedIOException;

public class SlowBodySender extends RequestBody {

    private final int messagesToSend;
    private final int millis;
    private volatile IOException writeException = null;

    public SlowBodySender(int messagesToSend) {
        this(messagesToSend, 70);
    }

    public SlowBodySender(int messagesToSend, int millisBetweenSends) {
        this.messagesToSend = messagesToSend;
        this.millis = millisBetweenSends;
    }

    @Override
    public MediaType contentType() {
        return MediaType.parse("text/plain");
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        for (int i = 0; i < messagesToSend; i++) {
            try {
                sink.writeUtf8("Loop " + i + "\n");
                sink.flush();
            } catch (IOException e) {
                writeException = e;
                throw e;
            }
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                throw new InterruptedIOException("Interupted");
            }
        }
    }

    public IOException writeException() {
        return writeException;
    }
}
