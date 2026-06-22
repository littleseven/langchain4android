package com.mamba.data.document;

import com.mamba.exception.LangChain4jException;

public class BlankDocumentException extends LangChain4jException {

    public BlankDocumentException() {
        super("The document is blank");
    }
}
