package com.mamba.agent.model.openai.internal.spi;

import com.mamba.agent.model.openai.internal.OpenAiClient;

import java.util.function.Supplier;

@SuppressWarnings("rawtypes")
public interface OpenAiClientBuilderFactory extends Supplier<OpenAiClient.Builder> {
}
