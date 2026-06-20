package com.mamba.agent.model.catalog;

import com.mamba.agent.Experimental;
import com.mamba.agent.model.audio.AudioTranscriptionModel;
import com.mamba.agent.model.chat.ChatModel;
import com.mamba.agent.model.chat.StreamingChatModel;
import com.mamba.agent.model.embedding.EmbeddingModel;
import com.mamba.agent.model.image.ImageModel;
import com.mamba.agent.model.moderation.ModerationModel;
import com.mamba.agent.model.scoring.ScoringModel;

/**
 * Represents the type/category of a model.
 *
 * @since 1.10.0
 */
@Experimental
public enum ModelType {

    /**
     * Chat/conversational models (e.g., GPT-5, Claude, etc.).
     * Can be used with {@link ChatModel} or {@link StreamingChatModel}.
     */
    CHAT,

    /**
     * Text embedding models for vector representations.
     * Can be used with {@link EmbeddingModel}.
     */
    EMBEDDING,

    /**
     * Image generation models (e.g., DALL-E, Stable Diffusion).
     * Can be used with {@link ImageModel}.
     */
    IMAGE_GENERATION,

    /**
     * Audio transcription models (speech-to-text).
     * Can be used with {@link AudioTranscriptionModel}.
     */
    AUDIO_TRANSCRIPTION,

    /**
     * Audio generation models (text-to-speech).
     */
    AUDIO_GENERATION,

    /**
     * Content moderation models.
     * Can be used with {@link ModerationModel}.
     */
    MODERATION,

    /**
     * Document scoring or re-ranking models.
     * Can be used with {@link ScoringModel}.
     */
    SCORING,

    /**
     * Other or unclassified model types.
     */
    OTHER
}
