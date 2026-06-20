package com.mamba.model.chat;

import com.mamba.model.ModelDisabledException;
import com.mamba.model.chat.request.ChatRequest;
import com.mamba.model.chat.response.StreamingChatResponseHandler;

/**
 * A {@link StreamingChatModel} which throws a {@link ModelDisabledException} for all of its methods
 * <p>
 * This could be used in tests, or in libraries that extend this one to conditionally enable or disable functionality.
 * </p>
 */
public class DisabledStreamingChatModel implements StreamingChatModel {

    @Override
    public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        throw new ModelDisabledException("StreamingChatModel is disabled");
    }
}
