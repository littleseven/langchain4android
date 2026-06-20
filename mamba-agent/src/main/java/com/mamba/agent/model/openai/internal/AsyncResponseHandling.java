package com.mamba.agent.model.openai.internal;

import java.util.function.Consumer;

public interface AsyncResponseHandling {

    ErrorHandling onError(Consumer<Throwable> errorHandler);

    ErrorHandling ignoreErrors();
}
