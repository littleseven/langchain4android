package com.mamba.internal;

import com.mamba.Internal;
import com.mamba.tool.ToolSpecification;

@Internal
public class ToolSpecificationUtils {

    private ToolSpecificationUtils() {}

    /**
     * Resolves whether a tool should use strict schema enforcement,
     * falling back to the model-level default when no per-tool value is set.
     */
    public static boolean isEffectivelyStrict(ToolSpecification toolSpecification, boolean modelLevelStrict) {
        return toolSpecification.strict() != null ? toolSpecification.strict() : modelLevelStrict;
    }
}
