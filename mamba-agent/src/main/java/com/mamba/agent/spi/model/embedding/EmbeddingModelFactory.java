package com.mamba.agent.spi.model.embedding;

import com.mamba.agent.Internal;
import com.mamba.agent.model.embedding.EmbeddingModel;

/**
 * A factory for creating {@link EmbeddingModel} instances through SPI.
 * <br>
 * For the "Easy RAG", import {@code langchain4j-easy-rag} module,
 * which contains a {@code EmbeddingModelFactory} implementation.
 */
@Internal
public interface EmbeddingModelFactory {

    EmbeddingModel create();
}
