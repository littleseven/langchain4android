package com.mamba.model.openai.spi;

import com.mamba.Internal;
import com.mamba.model.openai.OpenAiChatModel;

import java.util.function.Supplier;

/**
 * A factory for building {@link com.mamba.model.openai.OpenAiChatModel.OpenAiChatModelBuilder} instances.
 */
@Internal
public interface OpenAiChatModelBuilderFactory extends Supplier<OpenAiChatModel.OpenAiChatModelBuilder> {
}
