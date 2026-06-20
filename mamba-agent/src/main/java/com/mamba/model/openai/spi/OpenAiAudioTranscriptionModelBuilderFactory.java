package com.mamba.model.openai.spi;

import com.mamba.Internal;
import com.mamba.model.openai.OpenAiAudioTranscriptionModel;

import java.util.function.Supplier;

/**
 * A factory for building {@link OpenAiAudioTranscriptionModel.Builder} instances.
 */
@Internal
public interface OpenAiAudioTranscriptionModelBuilderFactory extends Supplier<OpenAiAudioTranscriptionModel.Builder> {}
