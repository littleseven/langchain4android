package com.mamba.agent.http.client.okhttp;

import com.mamba.agent.http.client.HttpClientBuilderFactory;

public class OkHttpClientBuilderFactory implements HttpClientBuilderFactory {

    @Override
    public OkHttpClientBuilder create() {
        return OkHttpClient.builder();
    }
}
