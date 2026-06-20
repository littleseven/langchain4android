package com.mamba.agent.model.openai.spi;

import com.mamba.agent.Internal;
import com.mamba.agent.model.openai.OpenAiStreamingChatModel;

import java.util.function.Supplier;

/**
 * A factory for building {@link OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder} instances.
 */
@Internal
public interface OpenAiStreamingChatModelBuilderFactory extends Supplier<OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder> {
}
