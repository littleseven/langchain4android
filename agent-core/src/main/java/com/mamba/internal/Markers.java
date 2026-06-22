package com.mamba.internal;

import com.mamba.Internal;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

@Internal
public class Markers {

    private Markers() {}

    public static final Marker SENSITIVE = MarkerFactory.getMarker("SENSITIVE");
}
