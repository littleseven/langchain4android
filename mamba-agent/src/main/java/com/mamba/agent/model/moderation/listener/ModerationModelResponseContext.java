package com.mamba.agent.model.moderation.listener;

import com.mamba.agent.model.ModelProvider;
import com.mamba.agent.model.moderation.ModerationRequest;
import com.mamba.agent.model.moderation.ModerationResponse;

import java.util.Map;

import static com.mamba.agent.internal.ValidationUtils.ensureNotNull;

/**
 * The moderation model response context.
 * It contains {@link ModerationResponse}, corresponding {@link ModerationRequest}, {@link ModelProvider} and attributes.
 * The attributes can be used to pass data between methods of a {@link ModerationModelListener}
 * or between multiple {@link ModerationModelListener}s.
 */
public class ModerationModelResponseContext {

    private final ModerationResponse moderationResponse;
    private final ModerationRequest moderationRequest;
    private final ModelProvider modelProvider;
    private final Map<Object, Object> attributes;

    /**
     * Creates a new {@link ModerationModelResponseContext}.
     *
     * @param moderationResponse the moderation response.
     * @param moderationRequest  the moderation request.
     * @param modelProvider      the model provider.
     * @param attributes         the attributes map.
     */
    public ModerationModelResponseContext(
            ModerationResponse moderationResponse,
            ModerationRequest moderationRequest,
            ModelProvider modelProvider,
            Map<Object, Object> attributes) {
        this.moderationResponse = ensureNotNull(moderationResponse, "moderationResponse");
        this.moderationRequest = ensureNotNull(moderationRequest, "moderationRequest");
        this.modelProvider = ensureNotNull(modelProvider, "modelProvider");
        this.attributes = ensureNotNull(attributes, "attributes");
    }

    /**
     * @return The moderation response.
     */
    public ModerationResponse moderationResponse() {
        return moderationResponse;
    }

    /**
     * @return The moderation request.
     */
    public ModerationRequest moderationRequest() {
        return moderationRequest;
    }

    /**
     * @return The model provider.
     */
    public ModelProvider modelProvider() {
        return modelProvider;
    }

    /**
     * @return The attributes map. It can be used to pass data between methods of a {@link ModerationModelListener}
     * or between multiple {@link ModerationModelListener}s.
     */
    public Map<Object, Object> attributes() {
        return attributes;
    }

    @Override
    public String toString() {
        return "ModerationModelResponseContext{" + "moderationResponse="
                + moderationResponse + ", moderationRequest="
                + moderationRequest + ", modelProvider="
                + modelProvider + ", attributes="
                + attributes + '}';
    }
}
