package com.mamba.agent.data.message;

import com.mamba.agent.model.chat.ChatModel;
import com.mamba.agent.model.chat.StreamingChatModel;

/**
 * Represents a chat message.
 * Used together with {@link ChatModel} and {@link StreamingChatModel}.
 *
 * @see SystemMessage
 * @see UserMessage
 * @see AiMessage
 * @see ToolExecutionResultMessage
 * @see CustomMessage
 */
public interface ChatMessage {

    /**
     * The type of the message.
     *
     * @return the type of the message
     */
    ChatMessageType type();
}
