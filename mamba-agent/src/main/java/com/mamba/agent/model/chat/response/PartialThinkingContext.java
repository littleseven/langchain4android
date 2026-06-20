package com.mamba.agent.model.chat.response;

import com.mamba.agent.Experimental;
import com.mamba.agent.internal.JacocoIgnoreCoverageGenerated;

import static com.mamba.agent.internal.ValidationUtils.ensureNotNull;

/**
 * @since 1.8.0
 */
@Experimental
@JacocoIgnoreCoverageGenerated
public class PartialThinkingContext {

    private final StreamingHandle streamingHandle;

    public PartialThinkingContext(StreamingHandle streamingHandle) {
        this.streamingHandle = ensureNotNull(streamingHandle, "streamingHandle");
    }

    public StreamingHandle streamingHandle() {
        return streamingHandle;
    }
}
