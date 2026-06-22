package com.mamba.spi.json;

import com.mamba.Internal;
import com.mamba.internal.Json;

/**
 * A factory for creating {@link Json.JsonCodec} instances through SPI.
 */
@Internal
public interface JsonCodecFactory {

    /**
     * Create a new {@link Json.JsonCodec}.
     * @return the new {@link Json.JsonCodec}.
     */
    Json.JsonCodec create();
}
