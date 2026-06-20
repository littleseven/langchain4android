package com.mamba.spi.agent.tool;

import com.mamba.Internal;
import com.mamba.tool.ToolSpecificationJsonCodec;

/**
 * A factory for creating {@link ToolSpecificationJsonCodec} instances through SPI.
 */
@Internal
public interface ToolSpecificationJsonCodecFactory {

    /**
     * Create a new {@link ToolSpecificationJsonCodec}.
     *
     * @return the new {@link ToolSpecificationJsonCodec}.
     */
    ToolSpecificationJsonCodec create();
}
