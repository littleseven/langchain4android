package com.mamba.internal;

import com.google.gson.Gson;
import com.mamba.Internal;

import java.lang.reflect.Type;

/**
 * Gson-based JSON codec implementation for Android.
 * Replaces Jackson to avoid Java 17 API dependencies (e.g., isSealed()) and reduce APK size.
 */
@Internal
class GsonJsonCodec implements Json.JsonCodec {

    private final Gson gson = new Gson();

    @Override
    public String toJson(Object o) {
        return gson.toJson(o);
    }

    @Override
    public <T> T fromJson(String json, Class<T> type) {
        return gson.fromJson(json, type);
    }

    @Override
    public <T> T fromJson(String json, Type type) {
        return gson.fromJson(json, type);
    }
}
