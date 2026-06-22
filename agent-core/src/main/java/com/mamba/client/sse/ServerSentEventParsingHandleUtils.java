package com.mamba.client.sse;

import com.mamba.Internal;
import com.mamba.model.chat.response.StreamingHandle;

/**
 * @since 1.8.0
 */
@Internal
public class ServerSentEventParsingHandleUtils {

    public static StreamingHandle toStreamingHandle(ServerSentEventParsingHandle parsingHandle) {
        return new StreamingHandle() {

            @Override
            public void cancel() {
                parsingHandle.cancel();
            }

            @Override
            public boolean isCancelled() {
                return parsingHandle.isCancelled();
            }
        };
    }
}
