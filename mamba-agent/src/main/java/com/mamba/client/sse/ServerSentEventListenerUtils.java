package com.mamba.client.sse;

import com.mamba.Internal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Internal
public class ServerSentEventListenerUtils {

    private static final Logger log = LoggerFactory.getLogger(ServerSentEventListenerUtils.class);

    public static void ignoringExceptions(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            log.warn(
                    "An exception occurred during the invocation of the SSE listener. "
                            + "This exception has been ignored.",
                    e);
        }
    }
}
