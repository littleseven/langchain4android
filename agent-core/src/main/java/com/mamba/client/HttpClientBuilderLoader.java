package com.mamba.client;

import com.mamba.client.okhttp.OkHttpClientBuilderFactory;

public class HttpClientBuilderLoader {

    public static HttpClientBuilder loadHttpClientBuilder() {
        return new OkHttpClientBuilderFactory().create();
    }
}
