package com.mamba.model.openai.internal;

import com.mamba.client.HttpClient;
import com.mamba.client.HttpRequest;
import com.mamba.client.SuccessfulHttpResponse;

class SyncRequestExecutor<Response> {

    private final HttpClient httpClient;
    private final HttpRequest httpRequest;
    private final Class<Response> responseClass;

    SyncRequestExecutor(HttpClient httpClient, HttpRequest httpRequest, Class<Response> responseClass) {
        this.httpClient = httpClient;
        this.httpRequest = httpRequest;
        this.responseClass = responseClass;
    }

    ParsedAndRawResponse<Response> execute() {
        SuccessfulHttpResponse rawHttpResponse = httpClient.execute(httpRequest);
        Response parsedResponse = Json.fromJson(rawHttpResponse.body(), responseClass);
        return new ParsedAndRawResponse<>(parsedResponse, rawHttpResponse);
    }
}
