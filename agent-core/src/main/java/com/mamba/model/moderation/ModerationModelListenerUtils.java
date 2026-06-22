package com.mamba.model.moderation;

import static com.mamba.internal.Utils.isNullOrEmpty;

import com.mamba.Internal;
import com.mamba.model.ModelProvider;
import com.mamba.model.moderation.listener.ModerationModelErrorContext;
import com.mamba.model.moderation.listener.ModerationModelListener;
import com.mamba.model.moderation.listener.ModerationModelRequestContext;
import com.mamba.model.moderation.listener.ModerationModelResponseContext;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Internal
class ModerationModelListenerUtils {

    private static final Logger LOG = LoggerFactory.getLogger(ModerationModelListenerUtils.class);

    private ModerationModelListenerUtils() {}

    static void onRequest(
            ModerationRequest moderationRequest,
            ModelProvider modelProvider,
            Map<Object, Object> attributes,
            List<ModerationModelListener> listeners) {
        if (isNullOrEmpty(listeners)) {
            return;
        }
        ModerationModelRequestContext requestContext =
                new ModerationModelRequestContext(moderationRequest, modelProvider, attributes);
        listeners.forEach(listener -> {
            try {
                listener.onRequest(requestContext);
            } catch (Exception e) {
                LOG.warn(
                        "An exception occurred during the invocation of the moderation model listener '{}'"
                                + " for model provider '{}'. This exception has been ignored.",
                        listener.getClass().getName(),
                        modelProvider,
                        e);
            }
        });
    }

    static void onResponse(
            ModerationResponse moderationResponse,
            ModerationRequest moderationRequest,
            ModelProvider modelProvider,
            Map<Object, Object> attributes,
            List<ModerationModelListener> listeners) {
        if (isNullOrEmpty(listeners)) {
            return;
        }
        ModerationModelResponseContext responseContext =
                new ModerationModelResponseContext(moderationResponse, moderationRequest, modelProvider, attributes);
        listeners.forEach(listener -> {
            try {
                listener.onResponse(responseContext);
            } catch (Exception e) {
                LOG.warn(
                        "An exception occurred during the invocation of the moderation model listener '{}'"
                                + " for model provider '{}'. This exception has been ignored.",
                        listener.getClass().getName(),
                        modelProvider,
                        e);
            }
        });
    }

    static void onError(
            Throwable error,
            ModerationRequest moderationRequest,
            ModelProvider modelProvider,
            Map<Object, Object> attributes,
            List<ModerationModelListener> listeners) {
        if (isNullOrEmpty(listeners)) {
            return;
        }
        ModerationModelErrorContext errorContext =
                new ModerationModelErrorContext(error, moderationRequest, modelProvider, attributes);
        listeners.forEach(listener -> {
            try {
                listener.onError(errorContext);
            } catch (Exception e) {
                LOG.warn(
                        "An exception occurred during the invocation of the moderation model listener '{}'"
                                + " for model provider '{}'. This exception has been ignored.",
                        listener.getClass().getName(),
                        modelProvider,
                        e);
            }
        });
    }
}
