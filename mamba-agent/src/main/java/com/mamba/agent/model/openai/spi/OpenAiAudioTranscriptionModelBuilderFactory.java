package com.mamba.agent.model.openai.spi;

import com.mamba.agent.Internal;
import com.mamba.agent.model.openai.OpenAiAudioTranscriptionModel;

import java.util.function.Supplier;

/**
 * A factory for building {@link OpenAiAudioTranscriptionModel.Builder} instances.
 */
@Internal
public interface OpenAiAudioTranscriptionModelBuilderFactory extends Supplier<OpenAiAudioTranscriptionModel.Builder> {}
