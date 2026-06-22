package com.mamba.client.sse;

import com.mamba.Internal;
import com.mamba.exception.UnsupportedFeatureException;

/**
 * @since 1.8.0
 */
@Internal
public class CancellationUnsupportedHandle implements ServerSentEventParsingHandle {

    @Override
    public void cancel() {
        throw new UnsupportedFeatureException("Streaming cancellation is not supported when calling "
                + "ServerSentEventListener.onEvent(ServerSentEvent). Please call "
                + "ServerSentEventListener.onEvent(ServerSentEvent, ServerSentEventContext) instead.");
    }

    @Override
    public boolean isCancelled() {
        return false;
    }
}
