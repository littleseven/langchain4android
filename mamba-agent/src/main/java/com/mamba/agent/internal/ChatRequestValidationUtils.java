package com.mamba.agent.internal;

import com.mamba.agent.Internal;
import com.mamba.agent.agent.tool.ToolSpecification;
import com.mamba.agent.data.message.ChatMessage;
import com.mamba.agent.data.message.Content;
import com.mamba.agent.data.message.UserMessage;
import com.mamba.agent.exception.UnsupportedFeatureException;
import com.mamba.agent.model.chat.request.ChatRequestParameters;
import com.mamba.agent.model.chat.request.ResponseFormat;
import com.mamba.agent.model.chat.request.ResponseFormatType;
import com.mamba.agent.model.chat.request.ToolChoice;

import java.util.List;
import java.util.Locale;

import static com.mamba.agent.data.message.ContentType.TEXT;
import static com.mamba.agent.internal.Utils.isNullOrEmpty;
import static com.mamba.agent.model.chat.request.ToolChoice.AUTO;

@Internal
public class ChatRequestValidationUtils {

    public static void validateMessages(List<ChatMessage> messages) {
        for (ChatMessage message : messages) {
            if (message instanceof UserMessage userMessage) {
                for (Content content : userMessage.contents()) {
                    if (content.type() != TEXT) {
                        throw new UnsupportedFeatureException(String.format(
                                "Content of type %s is not supported yet by this model provider",
                                content.type().toString().toLowerCase(Locale.ROOT)));
                    }
                }
            }
        }
    }

    public static void validateParameters(ChatRequestParameters parameters) {
        String errorTemplate = "%s is not supported yet by this model provider";

        if (parameters.modelName() != null) {
            throw new UnsupportedFeatureException(String.format(errorTemplate, "'modelName' parameter"));
        }
        if (parameters.temperature() != null) {
            throw new UnsupportedFeatureException(String.format(errorTemplate, "'temperature' parameter"));
        }
        if (parameters.topP() != null) {
            throw new UnsupportedFeatureException(String.format(errorTemplate, "'topP' parameter"));
        }
        if (parameters.topK() != null) {
            throw new UnsupportedFeatureException(String.format(errorTemplate, "'topK' parameter"));
        }
        if (parameters.frequencyPenalty() != null) {
            throw new UnsupportedFeatureException(String.format(errorTemplate, "'frequencyPenalty' parameter"));
        }
        if (parameters.presencePenalty() != null) {
            throw new UnsupportedFeatureException(String.format(errorTemplate, "'presencePenalty' parameter"));
        }
        if (parameters.maxOutputTokens() != null) {
            throw new UnsupportedFeatureException(String.format(errorTemplate, "'maxOutputTokens' parameter"));
        }
        if (!parameters.stopSequences().isEmpty()) {
            throw new UnsupportedFeatureException(String.format(errorTemplate, "'stopSequences' parameter"));
        }
    }

    public static void validate(List<ToolSpecification> toolSpecifications) {
        if (!isNullOrEmpty(toolSpecifications)) {
            throw new UnsupportedFeatureException("tools are not supported yet by this model provider");
        }
    }

    public static void validate(ToolChoice toolChoice) {
        if (toolChoice != null && toolChoice != AUTO) {
            throw new UnsupportedFeatureException(String.format("%s.%s is not supported yet by this model provider",
                    ToolChoice.class.getSimpleName(), toolChoice));
        }
    }

    public static void validate(ResponseFormat responseFormat) {
        String errorTemplate = "%s is not supported yet by this model provider";
        if (responseFormat != null && responseFormat.type() == ResponseFormatType.JSON) {
            throw new UnsupportedFeatureException(String.format(errorTemplate, "JSON response format"));
        }
    }
}
