package com.mamba.client.sse;

import static com.mamba.internal.ValidationUtils.ensureNotNull;

import java.io.InputStream;

/**
 * @since 1.8.0
 */
public class DefaultServerSentEventParsingHandle implements ServerSentEventParsingHandle {

    private final InputStream inputStream;
    private volatile boolean isCancelled;

    public DefaultServerSentEventParsingHandle(InputStream inputStream) {
        this.inputStream = ensureNotNull(inputStream, "inputStream");
    }

    @Override
    public void cancel() {
        isCancelled = true;
        try {
            inputStream.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean isCancelled() {
        return isCancelled;
    }
}
