package com.mamba.agent.http.client.sse;

import com.mamba.agent.Internal;
import com.mamba.agent.exception.UnsupportedFeatureException;

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
