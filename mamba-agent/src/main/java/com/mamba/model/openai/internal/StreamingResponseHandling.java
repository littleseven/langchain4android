package com.mamba.model.openai.internal;

public interface StreamingResponseHandling extends AsyncResponseHandling {

    StreamingCompletionHandling onComplete(Runnable streamingCompletionCallback);
}
