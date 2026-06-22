package com.mamba.client.okhttp;

import com.mamba.client.HttpClientBuilderFactory;

public class OkHttpClientBuilderFactory implements HttpClientBuilderFactory {

    @Override
    public OkHttpClientBuilder create() {
        return OkHttpClient.builder();
    }
}
