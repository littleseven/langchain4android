package com.mamba.model.openai.spi;

import com.mamba.Internal;
import com.mamba.model.openai.OpenAiLanguageModel;

import java.util.function.Supplier;

/**
 * A factory for building {@link OpenAiLanguageModel.OpenAiLanguageModelBuilder} instances.
 */
@Internal
public interface OpenAiLanguageModelBuilderFactory extends Supplier<OpenAiLanguageModel.OpenAiLanguageModelBuilder> {
}
