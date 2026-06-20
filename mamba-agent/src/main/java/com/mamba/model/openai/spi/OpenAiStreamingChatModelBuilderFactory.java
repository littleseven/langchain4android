package com.mamba.model.openai.spi;

import com.mamba.Internal;
import com.mamba.model.openai.OpenAiStreamingChatModel;

import java.util.function.Supplier;

/**
 * A factory for building {@link OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder} instances.
 */
@Internal
public interface OpenAiStreamingChatModelBuilderFactory extends Supplier<OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder> {
}
