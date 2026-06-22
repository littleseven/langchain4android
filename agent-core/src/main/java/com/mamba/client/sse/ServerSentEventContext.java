package com.mamba.client.sse;

import static com.mamba.internal.ValidationUtils.ensureNotNull;

import com.mamba.Experimental;

/**
 * @since 1.8.0
 */
@Experimental
public class ServerSentEventContext {

    private final ServerSentEventParsingHandle parsingHandle;

    public ServerSentEventContext(ServerSentEventParsingHandle parsingHandle) {
        this.parsingHandle = ensureNotNull(parsingHandle, "parsingHandle");
    }

    public ServerSentEventParsingHandle parsingHandle() {
        return parsingHandle;
    }
}
