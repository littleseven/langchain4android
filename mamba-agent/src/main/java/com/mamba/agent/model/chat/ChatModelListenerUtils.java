package com.mamba.agent.model.chat;

import static com.mamba.agent.internal.Utils.isNullOrEmpty;

import com.mamba.agent.Internal;
import com.mamba.agent.model.ModelProvider;
import com.mamba.agent.model.chat.listener.ChatModelErrorContext;
import com.mamba.agent.model.chat.listener.ChatModelListener;
import com.mamba.agent.model.chat.listener.ChatModelRequestContext;
import com.mamba.agent.model.chat.listener.ChatModelResponseContext;
import com.mamba.agent.model.chat.request.ChatRequest;
import com.mamba.agent.model.chat.response.ChatResponse;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Internal
class ChatModelListenerUtils {

    private static final Logger LOG = LoggerFactory.getLogger(ChatModelListenerUtils.class);

    private ChatModelListenerUtils() {}

    static void onRequest(
            ChatRequest chatRequest,
            ModelProvider modelProvider,
            Map<Object, Object> attributes,
            List<ChatModelListener> listeners) {
        if (isNullOrEmpty(listeners)) {
            return;
        }
        ChatModelRequestContext requestContext = new ChatModelRequestContext(chatRequest, modelProvider, attributes);
        listeners.forEach(listener -> {
            try {
                listener.onRequest(requestContext);
            } catch (Exception e) {
                LOG.warn(
                        "An exception occurred during the invocation of the chat model listener. "
                                + "This exception has been ignored.",
                        e);
            }
        });
    }

    static void onResponse(
            ChatResponse chatResponse,
            ChatRequest chatRequest,
            ModelProvider modelProvider,
            Map<Object, Object> attributes,
            List<ChatModelListener> listeners) {
        if (isNullOrEmpty(listeners)) {
            return;
        }
        ChatModelResponseContext responseContext =
                new ChatModelResponseContext(chatResponse, chatRequest, modelProvider, attributes);
        listeners.forEach(listener -> {
            try {
                listener.onResponse(responseContext);
            } catch (Exception e) {
                LOG.warn(
                        "An exception occurred during the invocation of the chat model listener. "
                                + "This exception has been ignored.",
                        e);
            }
        });
    }

    static void onError(
            Throwable error,
            ChatRequest chatRequest,
            ModelProvider modelProvider,
            Map<Object, Object> attributes,
            List<ChatModelListener> listeners) {
        if (isNullOrEmpty(listeners)) {
            return;
        }
        ChatModelErrorContext errorContext = new ChatModelErrorContext(error, chatRequest, modelProvider, attributes);
        listeners.forEach(listener -> {
            try {
                listener.onError(errorContext);
            } catch (Exception e) {
                LOG.warn(
                        "An exception occurred during the invocation of the chat model listener. "
                                + "This exception has been ignored.",
                        e);
            }
        });
    }
}
