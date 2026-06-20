package com.mamba.agent.spi.data.document.splitter;

import com.mamba.agent.Internal;
import com.mamba.agent.data.document.DocumentSplitter;

/**
 * A factory for creating {@link DocumentSplitter} instances through SPI.
 * <br>
 * Available implementations: {@code RecursiveDocumentSplitterFactory}
 * in the {@code langchain4j-easy-rag} module.
 */
@Internal
public interface DocumentSplitterFactory {

    DocumentSplitter create();
}
