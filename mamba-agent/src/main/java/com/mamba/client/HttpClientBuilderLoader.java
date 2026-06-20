package com.mamba.client;

import com.mamba.client.okhttp.OkHttpClientBuilderFactory;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

public class HttpClientBuilderLoader {

    public static HttpClientBuilder loadHttpClientBuilder() {
        String selectedClassName = System.getProperty("langchain4j.http.clientBuilderFactory");

        HttpClientBuilderFactory effectiveFactory = null;
        Collection<HttpClientBuilderFactory> factories = loadFactories(HttpClientBuilderFactory.class);
        for (HttpClientBuilderFactory factory : factories) {
            if (effectiveFactory != null) {
                throw new IllegalStateException(String.format(
                        "Conflict: multiple HTTP clients have been found "
                                + "in the classpath: %s. Please explicitly specify the one you wish to use using the `langchain4j.http.clientBuilderFactory` system property.",
                        factoryNames(factories)));
            } else {
                if (selectedClassName == null) {
                    effectiveFactory = factory;
                } else {
                    if (selectedClassName.equals(factory.getClass().getName())) {
                        effectiveFactory = factory;
                        break;
                    }
                }
            }
        }

        // Android fallback: ServiceLoader may fail on ART, explicitly use OkHttp
        if (effectiveFactory == null) {
            effectiveFactory = new OkHttpClientBuilderFactory();
        }

        return effectiveFactory.create();
    }

    private static Collection<HttpClientBuilderFactory> loadFactories(Class<HttpClientBuilderFactory> clazz) {
        try {
            return com.mamba.spi.ServiceHelper.loadFactories(clazz);
        } catch (Exception e) {
            // ServiceLoader may fail on Android, return empty collection
            return java.util.Collections.emptyList();
        }
    }

    private static Set<String> factoryNames(Collection<HttpClientBuilderFactory> factories) {
        return factories.stream().map(f -> f.getClass().getName()).collect(Collectors.toSet());
    }
}
