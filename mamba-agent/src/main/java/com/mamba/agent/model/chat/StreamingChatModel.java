package com.mamba.agent.model.chat;

import com.mamba.agent.data.message.ChatMessage;
import com.mamba.agent.data.message.UserMessage;
import com.mamba.agent.model.ModelProvider;
import com.mamba.agent.model.chat.listener.ChatModelListener;
import com.mamba.agent.model.chat.request.ChatRequest;
import com.mamba.agent.model.chat.request.ChatRequestParameters;
import com.mamba.agent.model.chat.request.DefaultChatRequestParameters;
import com.mamba.agent.model.chat.response.ChatResponse;
import com.mamba.agent.model.chat.response.CompleteToolCall;
import com.mamba.agent.model.chat.response.PartialResponse;
import com.mamba.agent.model.chat.response.PartialResponseContext;
import com.mamba.agent.model.chat.response.PartialThinking;
import com.mamba.agent.model.chat.response.PartialThinkingContext;
import com.mamba.agent.model.chat.response.PartialToolCall;
import com.mamba.agent.model.chat.response.PartialToolCallContext;
import com.mamba.agent.model.chat.response.StreamingChatResponseHandler;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.mamba.agent.internal.Utils.getOrDefault;
import static com.mamba.agent.model.ModelProvider.OTHER;
import static com.mamba.agent.model.chat.ChatModelListenerUtils.onRequest;
import static com.mamba.agent.model.chat.ChatModelListenerUtils.onResponse;

/**
 * Represents a language model that has a chat API and can stream a response one token at a time.
 *
 * @see ChatModel
 */
public interface StreamingChatModel {

    /**
     * This is the main API to interact with the chat model.
     *
     * @param request a {@link ChatRequest}, containing all the inputs to the LLM
     * @param handler a {@link StreamingChatResponseHandler} that will handle streaming response from the LLM
     */
    default void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        chat(request, ChatRequestOptions.EMPTY, handler);
    }

    /**
     * Sends a streaming chat request with additional invocation options.
     *
     * @param request a {@link ChatRequest}, containing all the inputs to the LLM
     * @param options a {@link ChatRequestOptions} carrying listener attributes and other per-call metadata
     * @param handler a {@link StreamingChatResponseHandler} that will handle streaming response from the LLM
     * @since 1.13.0
     */
    default void chat(ChatRequest request, ChatRequestOptions options, StreamingChatResponseHandler handler) {

        ChatRequest finalChatRequest = ChatRequest.builder()
                .messages(request.messages())
                .parameters(defaultRequestParameters().overrideWith(request.parameters()))
                .build();

        ChatRequestOptions effectiveOptions = getOrDefault(options, ChatRequestOptions.EMPTY);

        List<ChatModelListener> listeners = listeners();
        Map<Object, Object> attributes = new ConcurrentHashMap<>(effectiveOptions.listenerAttributes());

        StreamingChatResponseHandler observingHandler = new StreamingChatResponseHandler() {

            @Override
            public void onPartialResponse(String partialResponse) {
                handler.onPartialResponse(partialResponse);
            }

            @Override
            public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                handler.onPartialResponse(partialResponse, context);
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                handler.onPartialThinking(partialThinking);
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
                handler.onPartialThinking(partialThinking, context);
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall) {
                handler.onPartialToolCall(partialToolCall);
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
                handler.onPartialToolCall(partialToolCall, context);
            }

            @Override
            public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                handler.onCompleteToolCall(completeToolCall);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                onResponse(completeResponse, finalChatRequest, provider(), attributes, listeners);
                handler.onCompleteResponse(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                ChatModelListenerUtils.onError(error, finalChatRequest, provider(), attributes, listeners);
                handler.onError(error);
            }
        };

        onRequest(finalChatRequest, provider(), attributes, listeners);
        doChat(finalChatRequest, observingHandler);
    }

    default void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        throw new RuntimeException("Not implemented");
    }

    default ChatRequestParameters defaultRequestParameters() {
        return DefaultChatRequestParameters.EMPTY;
    }

    default List<ChatModelListener> listeners() {
        return List.of();
    }

    default ModelProvider provider() {
        return OTHER;
    }

    default void chat(String userMessage, StreamingChatResponseHandler handler) {

        ChatRequest chatRequest =
                ChatRequest.builder().messages(UserMessage.from(userMessage)).build();

        chat(chatRequest, handler);
    }

    default void chat(List<ChatMessage> messages, StreamingChatResponseHandler handler) {

        ChatRequest chatRequest = ChatRequest.builder().messages(messages).build();

        chat(chatRequest, handler);
    }

    default Set<Capability> supportedCapabilities() {
        return Set.of();
    }
}
