package com.mamba.agent.model.openai.spi;

import com.mamba.agent.Internal;
import com.mamba.agent.model.openai.OpenAiLanguageModel;

import java.util.function.Supplier;

/**
 * A factory for building {@link OpenAiLanguageModel.OpenAiLanguageModelBuilder} instances.
 */
@Internal
public interface OpenAiLanguageModelBuilderFactory extends Supplier<OpenAiLanguageModel.OpenAiLanguageModelBuilder> {
}
