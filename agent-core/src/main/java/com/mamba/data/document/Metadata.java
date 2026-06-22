package com.mamba.data.document;

import static com.mamba.internal.Exceptions.illegalArgument;
import static com.mamba.internal.Exceptions.runtime;
import static com.mamba.internal.ValidationUtils.ensureNotBlank;
import static com.mamba.internal.ValidationUtils.ensureNotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class Metadata {

    private static final Set<Class<?>> SUPPORTED_VALUE_TYPES = new LinkedHashSet<>();

    static {
        SUPPORTED_VALUE_TYPES.add(String.class);
        SUPPORTED_VALUE_TYPES.add(UUID.class);
        SUPPORTED_VALUE_TYPES.add(int.class);
        SUPPORTED_VALUE_TYPES.add(Integer.class);
        SUPPORTED_VALUE_TYPES.add(long.class);
        SUPPORTED_VALUE_TYPES.add(Long.class);
        SUPPORTED_VALUE_TYPES.add(float.class);
        SUPPORTED_VALUE_TYPES.add(Float.class);
        SUPPORTED_VALUE_TYPES.add(double.class);
        SUPPORTED_VALUE_TYPES.add(Double.class);
    }

    private final Map<String, Object> metadata;

    public Metadata() {
        this.metadata = new HashMap<>();
    }

    public Metadata(Map<String, ?> metadata) {
        validate(metadata);
        this.metadata = new HashMap<>(metadata);
    }

    private static void validate(Map<String, ?> metadata) {
        ensureNotNull(metadata, "metadata").forEach((key, value) -> {
            validate(key, value);
            if (!SUPPORTED_VALUE_TYPES.contains(value.getClass())) {
                throw illegalArgument(
                        "The metadata key '%s' has the value '%s', which is of the unsupported type '%s'. "
                                + "Currently, the supported types are: %s",
                        key, value, value.getClass().getName(), SUPPORTED_VALUE_TYPES);
            }
        });
    }

    private static void validate(String key, Object value) {
        ensureNotBlank(key, "The metadata key with the value '" + value + "'");
        ensureNotNull(value, "The metadata value for the key '" + key + "'");
    }

    public String getString(String key) {
        if (!containsKey(key)) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof String string) {
            return string;
        }
        throw runtime(
                "Metadata entry with the key '%s' has a value of '%s' and type '%s'. "
                        + "It cannot be returned as a String.",
                key, value, value.getClass().getName());
    }

    public UUID getUUID(String key) {
        if (!containsKey(key)) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof UUID iD) {
            return iD;
        }
        if (value instanceof String string) {
            return UUID.fromString(string);
        }
        throw runtime(
                "Metadata entry with the key '%s' has a value of '%s' and type '%s'. "
                        + "It cannot be returned as a UUID.",
                key, value, value.getClass().getName());
    }

    public Integer getInteger(String key) {
        if (!containsKey(key)) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof String) {
            return Integer.parseInt(value.toString());
        } else if (value instanceof Number number) {
            return number.intValue();
        }
        throw runtime(
                "Metadata entry with the key '%s' has a value of '%s' and type '%s'. "
                        + "It cannot be returned as an Integer.",
                key, value, value.getClass().getName());
    }

    public Long getLong(String key) {
        if (!containsKey(key)) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof String) {
            return Long.parseLong(value.toString());
        } else if (value instanceof Number number) {
            return number.longValue();
        }
        throw runtime(
                "Metadata entry with the key '%s' has a value of '%s' and type '%s'. "
                        + "It cannot be returned as a Long.",
                key, value, value.getClass().getName());
    }

    public Float getFloat(String key) {
        if (!containsKey(key)) {
            return null;
        }
        final var value = metadata.get(key);
        if (value instanceof String str) {
            return Float.parseFloat(str);
        } else if (value instanceof Number number) {
            return number.floatValue();
        }
        throw runtime(
                "Metadata entry with the key '%s' has a value of '%s' and type '%s'. "
                        + "It cannot be returned as a Float.",
                key, value, value.getClass().getName());
    }

    public Double getDouble(String key) {
        if (!containsKey(key)) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof String) {
            return Double.parseDouble(value.toString());
        } else if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw runtime(
                "Metadata entry with the key '%s' has a value of '%s' and type '%s'. "
                        + "It cannot be returned as a Double.",
                key, value, value.getClass().getName());
    }

    public boolean containsKey(String key) {
        return metadata.containsKey(key);
    }

    public Metadata put(String key, String value) {
        validate(key, value);
        this.metadata.put(key, value);
        return this;
    }

    public Metadata put(String key, UUID value) {
        validate(key, value);
        this.metadata.put(key, value);
        return this;
    }

    public Metadata put(String key, int value) {
        validate(key, value);
        this.metadata.put(key, value);
        return this;
    }

    public Metadata put(String key, long value) {
        validate(key, value);
        this.metadata.put(key, value);
        return this;
    }

    public Metadata put(String key, float value) {
        validate(key, value);
        this.metadata.put(key, value);
        return this;
    }

    public Metadata put(String key, double value) {
        validate(key, value);
        this.metadata.put(key, value);
        return this;
    }

    public Metadata putAll(Map<String, Object> metadata) {
        validate(metadata);
        this.metadata.putAll(metadata);
        return this;
    }

    public Metadata remove(String key) {
        this.metadata.remove(key);
        return this;
    }

    public Metadata copy() {
        return new Metadata(metadata);
    }

    public Map<String, Object> toMap() {
        return new HashMap<>(metadata);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Metadata that = (Metadata) o;
        return Objects.equals(this.metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(metadata);
    }

    @Override
    public String toString() {
        return "Metadata {" + " metadata = " + metadata + " }";
    }

    public static Metadata from(String key, String value) {
        return new Metadata().put(key, value);
    }

    public static Metadata from(Map<String, ?> metadata) {
        return new Metadata(metadata);
    }

    public static Metadata metadata(String key, String value) {
        return from(key, value);
    }

    public Metadata merge(Metadata another) {
        if (another == null || another.metadata.isEmpty()) {
            return this.copy();
        }
        final var thisMap = this.toMap();
        final var anotherMap = another.toMap();
        final var commonKeys = new HashSet<>(thisMap.keySet());
        commonKeys.retainAll(anotherMap.keySet());
        if (!commonKeys.isEmpty()) {
            throw illegalArgument("Metadata keys are not unique. Common keys: %s", commonKeys);
        }
        final var mergedMap = new HashMap<>(thisMap);
        mergedMap.putAll(anotherMap);
        return Metadata.from(mergedMap);
    }
}
