package com.mamba.agent.model.chat.request;

import com.mamba.agent.model.chat.ChatModel;

/**
 * Specifies how {@link ChatModel} should use tools.
 */
public enum ToolChoice {

    /**
     * The chat model can choose whether to use tools, which ones to use, and how many.
     */
    AUTO,

    /**
     * The chat model is required to use one or more tools.
     */
    REQUIRED,

    /**
     * The chat model cannot use tools
     */
    NONE,
}
