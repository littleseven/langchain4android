package com.mamba.model.openai.internal.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ToolChoiceMode {

    @JsonProperty("none")
    NONE,
    @JsonProperty("auto")
    AUTO,
    @JsonProperty("required")
    REQUIRED
}
