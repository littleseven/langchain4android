package com.mamba.model.chat.response;

import com.mamba.Experimental;
import com.mamba.internal.JacocoIgnoreCoverageGenerated;

import static com.mamba.internal.ValidationUtils.ensureNotNull;

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
