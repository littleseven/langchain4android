package com.mamba.spi.prompt.structured;

import com.mamba.Internal;
import com.mamba.model.input.Prompt;

/**
 * Represents a factory for structured prompts.
 */
@Internal
public interface StructuredPromptFactory {

    /**
     * Converts the given structured prompt to a prompt.
     * @param structuredPrompt the structured prompt.
     * @return the prompt.
     */
    Prompt toPrompt(Object structuredPrompt);
}
