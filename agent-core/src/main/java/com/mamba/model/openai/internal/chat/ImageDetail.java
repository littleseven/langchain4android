package com.mamba.model.openai.internal.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ImageDetail {

    @JsonProperty("low")
    LOW,
    @JsonProperty("high")
    HIGH,
    @JsonProperty("auto")
    AUTO
}
