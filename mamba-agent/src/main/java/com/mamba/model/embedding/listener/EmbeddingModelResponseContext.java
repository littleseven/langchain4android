package com.mamba.model.embedding.listener;

import static com.mamba.internal.Utils.copy;
import static com.mamba.internal.ValidationUtils.ensureNotNull;

import com.mamba.Experimental;
import com.mamba.data.embedding.Embedding;
import com.mamba.data.segment.TextSegment;
import com.mamba.model.embedding.EmbeddingModel;
import com.mamba.model.output.Response;
import java.util.List;
import java.util.Map;

/**
 * The embedding model response context.
 * It contains the {@link Response}, corresponding input, the {@link EmbeddingModel} and attributes.
 * The attributes can be used to pass data between methods of an {@link EmbeddingModelListener}
 * or between multiple {@link EmbeddingModelListener}s.
 *
 * @since 1.11.0
 */
@Experimental
public class EmbeddingModelResponseContext {

    private final Response<List<Embedding>> response;
    private final List<TextSegment> textSegments;
    private final EmbeddingModel embeddingModel;
    private final Map<Object, Object> attributes;

    public EmbeddingModelResponseContext(Builder builder) {
        this.response = ensureNotNull(builder.response, "response");
        this.textSegments = copy(ensureNotNull(builder.textSegments, "textSegments"));
        this.embeddingModel = ensureNotNull(builder.embeddingModel, "embeddingModel");
        this.attributes = ensureNotNull(builder.attributes, "attributes");
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link EmbeddingModelResponseContext}.
     *
     * @since 1.11.0
     */
    @Experimental
    public static class Builder {

        private Response<List<Embedding>> response;
        private List<TextSegment> textSegments;
        private EmbeddingModel embeddingModel;
        private Map<Object, Object> attributes;

        Builder() {}

        public Builder response(Response<List<Embedding>> response) {
            this.response = response;
            return this;
        }

        public Builder textSegments(List<TextSegment> textSegments) {
            this.textSegments = textSegments;
            return this;
        }

        public Builder embeddingModel(EmbeddingModel embeddingModel) {
            this.embeddingModel = embeddingModel;
            return this;
        }

        public Builder attributes(Map<Object, Object> attributes) {
            this.attributes = attributes;
            return this;
        }

        public EmbeddingModelResponseContext build() {
            return new EmbeddingModelResponseContext(this);
        }
    }

    public Response<List<Embedding>> response() {
        return response;
    }

    public List<TextSegment> textSegments() {
        return textSegments;
    }

    public EmbeddingModel embeddingModel() {
        return embeddingModel;
    }

    /**
     * @return The attributes map. It can be used to pass data between methods of an {@link EmbeddingModelListener}
     * or between multiple {@link EmbeddingModelListener}s.
     */
    public Map<Object, Object> attributes() {
        return attributes;
    }
}
