package com.mamba.agent.model.moderation;

import com.mamba.agent.data.message.ChatMessage;
import com.mamba.agent.data.segment.TextSegment;
import com.mamba.agent.model.ModelDisabledException;
import com.mamba.agent.model.input.Prompt;
import com.mamba.agent.model.output.Response;
import java.util.List;

/**
 * A {@link ModerationModel} which throws a {@link ModelDisabledException} for all of its methods
 * <p>
 *     This could be used in tests, or in libraries that extend this one to conditionally enable or disable functionality.
 * </p>
 */
public class DisabledModerationModel implements ModerationModel {
    @Override
    public Response<Moderation> moderate(String text) {
        throw new ModelDisabledException("ModerationModel is disabled");
    }

    @Override
    public Response<Moderation> moderate(Prompt prompt) {
        throw new ModelDisabledException("ModerationModel is disabled");
    }

    @Override
    public Response<Moderation> moderate(ChatMessage message) {
        throw new ModelDisabledException("ModerationModel is disabled");
    }

    @Override
    public Response<Moderation> moderate(List<ChatMessage> messages) {
        throw new ModelDisabledException("ModerationModel is disabled");
    }

    @Override
    public Response<Moderation> moderate(TextSegment textSegment) {
        throw new ModelDisabledException("ModerationModel is disabled");
    }

    @Override
    public ModerationResponse doModerate(ModerationRequest moderationRequest) {
        throw new ModelDisabledException("ModerationModel is disabled");
    }
}
