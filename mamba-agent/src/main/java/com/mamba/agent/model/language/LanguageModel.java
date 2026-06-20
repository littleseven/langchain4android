package com.mamba.agent.model.language;

import com.mamba.agent.model.chat.ChatModel;
import com.mamba.agent.model.input.Prompt;
import com.mamba.agent.model.output.Response;

/**
 * Represents a language model that has a simple text interface (as opposed to a chat interface).
 * It is recommended to use the {@link ChatModel} instead,
 * as it offers more features.
 */
public interface LanguageModel {

    /**
     * Generate a response to the given prompt.
     *
     * @param prompt the prompt.
     * @return the response.
     */
    Response<String> generate(String prompt);

    /**
     * Generate a response to the given prompt.
     *
     * @param prompt the prompt.
     * @return the response.
     */
    default Response<String> generate(Prompt prompt) {
        return generate(prompt.text());
    }
}
