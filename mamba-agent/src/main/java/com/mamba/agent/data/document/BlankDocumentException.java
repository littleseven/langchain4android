package com.mamba.agent.data.document;

import com.mamba.agent.exception.LangChain4jException;

public class BlankDocumentException extends LangChain4jException {

    public BlankDocumentException() {
        super("The document is blank");
    }
}
