package com.mamba.spi.model.embedding;

import com.mamba.Internal;
import com.mamba.model.embedding.EmbeddingModel;

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
