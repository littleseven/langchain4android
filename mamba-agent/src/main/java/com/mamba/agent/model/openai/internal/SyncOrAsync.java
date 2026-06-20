package com.mamba.agent.model.openai.internal;

import com.mamba.agent.http.client.SuccessfulHttpResponse;
import java.util.function.Consumer;

public interface SyncOrAsync<ResponseContent> {

    ResponseContent execute();

    default ParsedAndRawResponse<ResponseContent> executeRaw() {
        ResponseContent parsedResponse = execute();
        SuccessfulHttpResponse rawHttpResponse = null;
        return new ParsedAndRawResponse<>(parsedResponse, rawHttpResponse);
    }

    AsyncResponseHandling onResponse(Consumer<ResponseContent> responseHandler);
}
