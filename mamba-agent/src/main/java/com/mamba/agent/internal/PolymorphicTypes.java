package com.mamba.agent.internal;

import static com.mamba.agent.internal.Utils.isNullOrBlank;

import com.mamba.agent.Internal;
import com.mamba.agent.exception.UnsupportedFeatureException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Detection and naming for polymorphic base types — sealed interfaces / classes.
 * Jackson annotations removed; only sealed type detection remains.
 */
@Internal
public final class PolymorphicTypes {

    /** Default discriminator property name. */
    public static final String DEFAULT_DISCRIMINATOR_PROPERTY = "type";

    private PolymorphicTypes() {}

    /**
     * Returns {@code true} if {@code type} is a polymorphic base — sealed
     * and has at least one concrete subtype discoverable.
     */
    public static boolean isPolymorphic(Class<?> type) {
        if (type == null || type.isPrimitive() || type.isEnum() || type.isArray()) {
            return false;
        }
        if (type.isSealed()) {
            return !findConcreteSubtypes(type).isEmpty();
        }
        return false;
    }

    /**
     * Concrete (instantiable) subtypes for {@code type}. Sealed hierarchies are flattened
     * recursively.
     */
    public static List<Class<?>> findConcreteSubtypes(Class<?> type) {
        Set<Class<?>> result = new LinkedHashSet<>();
        flatten(type, result);
        return new ArrayList<>(result);
    }

    private static void flatten(Class<?> type, Set<Class<?>> result) {
        if (type.isSealed()) {
            for (Class<?> permitted : type.getPermittedSubclasses()) {
                flatten(permitted, result);
            }
            return;
        }
        if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
            result.add(type);
        }
    }

    /**
     * Discriminator property name. Always returns {@link #DEFAULT_DISCRIMINATOR_PROPERTY}.
     */
    public static String discriminatorPropertyName(Class<?> baseType) {
        return DEFAULT_DISCRIMINATOR_PROPERTY;
    }

    /**
     * Discriminator value for a subtype. Returns {@link Class#getSimpleName()}.
     */
    public static String discriminatorValue(Class<?> baseType, Class<?> subtype) {
        return subtype.getSimpleName();
    }

    /**
     * No-op verification for Android (Jackson annotations removed).
     */
    public static void verifyJsonTypeInfoIsSupported(Class<?> baseType) {
        // No-op
    }
}
