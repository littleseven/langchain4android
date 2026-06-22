package com.mamba.model.catalog;

import com.mamba.Experimental;
import com.mamba.model.ModelProvider;

import java.util.List;

/**
 * Represents a service that can discover available models from an LLM provider.
 * This allows customers to list available models and their capabilities without
 * needing to go to individual AI provider websites.
 *
 * <p>Similar to {@link com.mamba.model.chat.ChatModel} and
 * {@link com.mamba.model.chat.StreamingChatModel}, each provider should
 * implement this interface to enable model listing.
 *
 * <p>Example usage:
 * <pre>{@code
 * OpenAiModelCatalog catalog = OpenAiModelCatalog.builder()
 *     .apiKey(apiKey)
 *     .build();
 *
 * List<ModelDescription> models = catalog.listModels();
 *
 * }</pre>
 *
 * @see ModelDescription
 * @since 1.10.0
 */
@Experimental
public interface ModelCatalog {

    /**
     * Retrieves a list of available models from the provider.
     *
     * @return A list of model descriptions
     * @throws RuntimeException if the listing operation fails
     */
    List<ModelDescription> listModels();

    /**
     * Returns the provider for this catalog service.
     *
     * @return The model provider
     */
    ModelProvider provider();
}
