package com.mamba.agent.model.input;

import com.mamba.agent.Internal;

/**
 * Factory for prompt templates.
 */
@Internal
public interface PromptTemplateFactory {

    Template create(Input input);

    interface Template {
        String render(java.util.Map<String, Object> variables);
    }

    class Input {
        public final String template;

        public Input(String template) {
            this.template = template;
        }
    }
}
