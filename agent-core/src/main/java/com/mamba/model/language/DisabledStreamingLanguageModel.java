package com.mamba.model.language;

import com.mamba.model.ModelDisabledException;
import com.mamba.model.StreamingResponseHandler;
import com.mamba.model.input.Prompt;

/**
 * A {@link StreamingLanguageModel} which throws a {@link ModelDisabledException} for all of its methods
 * <p>
 *     This could be used in tests, or in libraries that extend this one to conditionally enable or disable functionality.
 * </p>
 */
public class DisabledStreamingLanguageModel implements StreamingLanguageModel {
    @Override
    public void generate(String prompt, StreamingResponseHandler<String> handler) {
        throw new ModelDisabledException("StreamingLanguageModel is disabled");
    }

    @Override
    public void generate(Prompt prompt, StreamingResponseHandler<String> handler) {
        throw new ModelDisabledException("StreamingLanguageModel is disabled");
    }
}
