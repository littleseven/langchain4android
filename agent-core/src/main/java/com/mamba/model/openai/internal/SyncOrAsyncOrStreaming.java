package com.mamba.model.openai.internal;

import java.util.function.Consumer;
import com.mamba.client.sse.ServerSentEvent;

public interface SyncOrAsyncOrStreaming<ResponseContent> extends SyncOrAsync<ResponseContent> {

    StreamingResponseHandling onPartialResponse(Consumer<ResponseContent> partialResponseHandler);

    default StreamingResponseHandling onRawPartialResponse(Consumer<ParsedAndRawResponse<ResponseContent>> handler) {
        ServerSentEvent rawEvent = null;
        return onPartialResponse(parsedResponse -> handler.accept(new ParsedAndRawResponse<>(parsedResponse, rawEvent)));
    }
}
