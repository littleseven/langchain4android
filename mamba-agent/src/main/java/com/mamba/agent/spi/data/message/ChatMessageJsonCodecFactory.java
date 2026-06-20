package com.mamba.agent.spi.data.message;

import com.mamba.agent.Internal;
import com.mamba.agent.data.message.ChatMessageJsonCodec;

/**
 * A factory for creating {@link ChatMessageJsonCodec} objects.
 * Used for SPI.
 */
@Internal
public interface ChatMessageJsonCodecFactory {

    /**
     * Creates a new {@link ChatMessageJsonCodec} object.
     * @return the new {@link ChatMessageJsonCodec} object.
     */
    ChatMessageJsonCodec create();
}
