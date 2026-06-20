package com.mamba.model.openai.spi;

import com.mamba.Internal;
import com.mamba.model.openai.OpenAiStreamingLanguageModel;

import java.util.function.Supplier;

/**
 * A factory for building {@link OpenAiStreamingLanguageModel.OpenAiStreamingLanguageModelBuilder} instances.
 */
@Internal
public interface OpenAiStreamingLanguageModelBuilderFactory extends Supplier<OpenAiStreamingLanguageModel.OpenAiStreamingLanguageModelBuilder> {
}
