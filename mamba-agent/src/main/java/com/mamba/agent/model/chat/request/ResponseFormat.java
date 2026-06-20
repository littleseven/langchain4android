package com.mamba.agent.model.chat.request;

import com.mamba.agent.internal.JacocoIgnoreCoverageGenerated;
import com.mamba.agent.model.chat.request.json.JsonSchema;

import java.util.Objects;

import static com.mamba.agent.internal.ValidationUtils.ensureNotNull;

public class ResponseFormat {

    public static final ResponseFormat TEXT = ResponseFormat.builder().type(ResponseFormatType.TEXT).build();
    public static final ResponseFormat JSON = ResponseFormat.builder().type(ResponseFormatType.JSON).build();

    private final ResponseFormatType type;
    private final JsonSchema jsonSchema;

    private ResponseFormat(Builder builder) {
        this.type = ensureNotNull(builder.type, "type");
        this.jsonSchema = builder.jsonSchema;
        if (jsonSchema != null && type != ResponseFormatType.JSON) {
            throw new IllegalStateException("JsonSchema can be specified only when type=JSON");
        }
    }

    public ResponseFormatType type() {
        return type;
    }

    public JsonSchema jsonSchema() {
        return jsonSchema;
    }

    @Override
    @JacocoIgnoreCoverageGenerated
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ResponseFormat that = (ResponseFormat) o;
        return Objects.equals(this.type, that.type)
                && Objects.equals(this.jsonSchema, that.jsonSchema);
    }

    @Override
    @JacocoIgnoreCoverageGenerated
    public int hashCode() {
        return Objects.hash(type, jsonSchema);
    }

    @Override
    @JacocoIgnoreCoverageGenerated
    public String toString() {
        return "ResponseFormat {" +
                " type = " + type +
                ", jsonSchema = " + jsonSchema +
                " }";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private ResponseFormatType type;
        private JsonSchema jsonSchema;

        public Builder type(ResponseFormatType type) {
            this.type = type;
            return this;
        }

        public Builder jsonSchema(JsonSchema jsonSchema) {
            this.jsonSchema = jsonSchema;
            return this;
        }

        public ResponseFormat build() {
            return new ResponseFormat(this);
        }
    }
}
