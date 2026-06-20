package com.mamba.agent.spi.agent.tool;

import com.mamba.agent.Internal;
import com.mamba.agent.agent.tool.ToolSpecificationJsonCodec;

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
