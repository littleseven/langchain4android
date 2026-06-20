package com.mamba.agent.model.chat;

import com.mamba.agent.model.ModelDisabledException;
import com.mamba.agent.model.chat.request.ChatRequest;
import com.mamba.agent.model.chat.response.ChatResponse;

/**
 * A {@link ChatModel} which throws a {@link ModelDisabledException} for all of its methods
 * <p>
 * This could be used in tests, or in libraries that extend this one to conditionally enable or disable functionality.
 * </p>
 */
public class DisabledChatModel implements ChatModel {

    @Override
    public ChatResponse doChat(final ChatRequest chatRequest) {
        throw new ModelDisabledException("ChatModel is disabled");
    }
}
