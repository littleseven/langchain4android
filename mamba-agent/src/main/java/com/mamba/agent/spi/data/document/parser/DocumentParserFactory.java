package com.mamba.agent.spi.data.document.parser;

import com.mamba.agent.Internal;
import com.mamba.agent.data.document.DocumentParser;

/**
 * A factory for creating {@link DocumentParser} instances through SPI.
 * <br>
 * Available implementations: {@code ApacheTikaDocumentParserFactory}
 * in the {@code langchain4j-document-parser-apache-tika} module.
 * For the "Easy RAG", import {@code langchain4j-easy-rag} module.
 */
@Internal
public interface DocumentParserFactory {

    DocumentParser create();
}
