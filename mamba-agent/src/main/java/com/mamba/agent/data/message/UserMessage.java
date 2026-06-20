package com.mamba.agent.data.message;

import static com.mamba.agent.data.message.ChatMessageType.USER;
import static com.mamba.agent.internal.Exceptions.runtime;
import static com.mamba.agent.internal.Utils.copy;
import static com.mamba.agent.internal.Utils.mutableCopy;
import static com.mamba.agent.internal.Utils.quoted;
import static com.mamba.agent.internal.ValidationUtils.ensureNotEmpty;
import static java.util.Arrays.asList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.mamba.agent.Experimental;
// import com.mamba.agent.memory.ChatMemory; // memory module removed

public class UserMessage implements ChatMessage {

    private final String name;
    private final List<Content> contents;
    private final Map<String, Object> attributes;

    public UserMessage(Builder builder) {
        this.name = builder.name;
        this.contents = copy(ensureNotEmpty(builder.contents, "contents"));
        this.attributes = mutableCopy(builder.attributes);
    }

    public UserMessage(String text) {
        this(TextContent.from(text));
    }

    public UserMessage(String name, String text) {
        this(name, TextContent.from(text));
    }

    public UserMessage(Content... contents) {
        this(asList(contents));
    }

    public UserMessage(String name, Content... contents) {
        this(name, asList(contents));
    }

    public UserMessage(List<Content> contents) {
        this.name = null;
        this.contents = copy(ensureNotEmpty(contents, "contents"));
        this.attributes = new HashMap<>();
    }

    public UserMessage(String name, List<Content> contents) {
        this.name = name;
        this.contents = copy(ensureNotEmpty(contents, "contents"));
        this.attributes = new HashMap<>();
    }

    public String name() {
        return name;
    }

    public List<Content> contents() {
        return contents;
    }

    public String singleText() {
        if (hasSingleText()) {
            return ((TextContent) contents.get(0)).text();
        } else {
            throw runtime("Expecting single text content, but got: " + contents);
        }
    }

    public boolean hasSingleText() {
        return contents.size() == 1 && contents.get(0) instanceof TextContent;
    }

    @Experimental
    public Map<String, Object> attributes() {
        return attributes;
    }

    @Experimental
    public <T> T attribute(String key, Class<T> type) {
        return (T) attributes.get(key);
    }

    @Override
    public ChatMessageType type() {
        return USER;
    }

    public Builder toBuilder() {
        return builder()
                .name(name)
                .contents(mutableCopy(contents))
                .attributes(attributes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserMessage that = (UserMessage) o;
        return Objects.equals(this.name, that.name)
                && Objects.equals(this.contents, that.contents)
                && Objects.equals(this.attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, contents, attributes);
    }

    @Override
    public String toString() {
        return "UserMessage {" +
                " name = " + quoted(name) +
                ", contents = " + contents +
                ", attributes = " + attributes +
                " }";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private String name;
        private List<Content> contents;
        private Map<String, Object> attributes;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder contents(List<Content> contents) {
            this.contents = contents;
            return this;
        }

        public Builder addContent(Content content) {
            if (this.contents == null) {
                this.contents = new ArrayList<>();
            }
            this.contents.add(content);
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            this.attributes = attributes;
            return this;
        }

        public UserMessage build() {
            return new UserMessage(this);
        }
    }

    public static UserMessage from(String text) {
        return new UserMessage(text);
    }

    public static UserMessage from(String name, String text) {
        return new UserMessage(name, text);
    }

    public static UserMessage from(Content... contents) {
        return new UserMessage(contents);
    }

    public static UserMessage from(String name, Content... contents) {
        return new UserMessage(name, contents);
    }

    public static UserMessage from(List<Content> contents) {
        return new UserMessage(contents);
    }

    public static UserMessage from(String name, List<Content> contents) {
        return new UserMessage(name, contents);
    }

    public static UserMessage userMessage(String text) {
        return from(text);
    }

    public static UserMessage userMessage(String name, String text) {
        return from(name, text);
    }

    public static UserMessage userMessage(Content... contents) {
        return from(contents);
    }

    public static UserMessage userMessage(String name, Content... contents) {
        return from(name, contents);
    }

    public static UserMessage userMessage(List<Content> contents) {
        return from(contents);
    }

    public static UserMessage userMessage(String name, List<Content> contents) {
        return from(name, contents);
    }

    public static Optional<UserMessage> findLast(Collection<ChatMessage> messages) {
        return messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .reduce((first, second) -> second);
    }

    public static List<ChatMessage> replaceLast(List<ChatMessage> messages, UserMessage replacement) {
        if (replacement == null) {
            return messages;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage existing) {
                if (existing.equals(replacement)) {
                    return messages;
                }
                List<ChatMessage> result = new ArrayList<>(messages);
                result.set(i, replacement);
                return result;
            }
        }
        return messages;
    }
}
