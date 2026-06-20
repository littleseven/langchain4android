package com.mamba.agent.http.client.sse;

import static com.mamba.agent.internal.ValidationUtils.ensureNotNull;

import com.mamba.agent.Experimental;

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
