package com.mamba.model.chat.response;

import static com.mamba.internal.ValidationUtils.ensureNotEmpty;

import com.mamba.Experimental;
import com.mamba.internal.JacocoIgnoreCoverageGenerated;

import java.util.Objects;

/**
 * @since 1.2.0
 */
@Experimental
@JacocoIgnoreCoverageGenerated
public class PartialThinking {

    private final String text;

    public PartialThinking(String text) {
        this.text = ensureNotEmpty(text, "text");
    }

    public String text() {
        return text;
    }

    @Override
    public boolean equals(final Object object) {
        if (this == object) return true;
        if (object == null || getClass() != object.getClass()) return false;
        PartialThinking that = (PartialThinking) object;
        return Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(text);
    }

    @Override
    public String toString() {
        return "PartialThinking{" + "text='" + text + '\'' + '}';
    }
}
