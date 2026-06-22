package com.mamba.spi.data.document.splitter;

import com.mamba.Internal;
import com.mamba.data.document.DocumentSplitter;

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
