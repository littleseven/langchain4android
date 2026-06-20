package com.mamba.agent.spi.prompt.structured;

import com.mamba.agent.Internal;
import com.mamba.agent.model.input.Prompt;

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
