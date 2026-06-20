package com.mamba.agent.model.openai.spi;

import com.mamba.agent.Internal;
import com.mamba.agent.model.openai.OpenAiChatModel;

import java.util.function.Supplier;

/**
 * A factory for building {@link com.mamba.agent.model.openai.OpenAiChatModel.OpenAiChatModelBuilder} instances.
 */
@Internal
public interface OpenAiChatModelBuilderFactory extends Supplier<OpenAiChatModel.OpenAiChatModelBuilder> {
}
