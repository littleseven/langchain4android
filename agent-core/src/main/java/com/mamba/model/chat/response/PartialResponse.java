package com.mamba.model.chat.response;

import static com.mamba.internal.ValidationUtils.ensureNotEmpty;

import java.util.Objects;
import com.mamba.Experimental;
import com.mamba.internal.JacocoIgnoreCoverageGenerated;

/**
 * @since 1.8.0
 */
@Experimental
@JacocoIgnoreCoverageGenerated
public class PartialResponse {

    private final String text;

    public PartialResponse(String text) {
        this.text = ensureNotEmpty(text, "text");
    }

    public String text() {
        return text;
    }

    @Override
    public boolean equals(final Object object) {
        if (this == object) return true;
        if (object == null || getClass() != object.getClass()) return false;
        PartialResponse that = (PartialResponse) object;
        return Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(text);
    }

    @Override
    public String toString() {
        return "PartialResponse{" + "text='" + text + '\'' + '}';
    }
}
