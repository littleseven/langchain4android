package com.mamba.android.slf4j;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * SLF4J 2.0+ ServiceProvider 实现，将 SLF4J 日志桥接到 Android {@link android.util.Log}。
 *
 * <p>通过在 {@code META-INF/services/org.slf4j.spi.SLF4JServiceProvider} 中注册本类，
 * 自动解决 Android 环境下 "SLF4J: No SLF4J providers were found" 的警告。</p>
 */
public class AndroidSLF4JServiceProvider implements SLF4JServiceProvider {

    /**
     * SLF4J API 版本要求，必须与编译时使用的 slf4j-api 版本一致。
     */
    private static final String REQUESTED_API_VERSION = "2.0.16";

    private final ILoggerFactory loggerFactory = new AndroidLoggerFactory();
    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final MDCAdapter mdcAdapter = new AndroidMDCAdapter();

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return REQUESTED_API_VERSION;
    }

    @Override
    public void initialize() {
        // 无需初始化
    }
}
