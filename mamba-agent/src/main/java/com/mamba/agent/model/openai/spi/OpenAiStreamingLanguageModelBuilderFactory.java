package com.mamba.agent.model.openai.spi;

import com.mamba.agent.Internal;
import com.mamba.agent.model.openai.OpenAiStreamingLanguageModel;

import java.util.function.Supplier;

/**
 * A factory for building {@link OpenAiStreamingLanguageModel.OpenAiStreamingLanguageModelBuilder} instances.
 */
@Internal
public interface OpenAiStreamingLanguageModelBuilderFactory extends Supplier<OpenAiStreamingLanguageModel.OpenAiStreamingLanguageModelBuilder> {
}
