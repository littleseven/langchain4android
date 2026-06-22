package com.mamba.android.slf4j;

import android.util.Log;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.Marker;

/**
 * SLF4J Logger 实现，桥接到 Android 的 android.util.Log。
 *
 * <p>将 SLF4J 的日志级别映射到 Android Log 级别：</p>
 * <ul>
 *   <li>TRACE → Log.VERBOSE</li>
 *   <li>DEBUG → Log.DEBUG</li>
 *   <li>INFO → Log.INFO</li>
 *   <li>WARN → Log.WARN</li>
 *   <li>ERROR → Log.ERROR</li>
 * </ul>
 */
class AndroidLogger extends LegacyAbstractLogger {

    private static final long serialVersionUID = 1L;
    private static final int MAX_LOG_LENGTH = 4000;

    AndroidLogger(String name) {
        this.name = name;
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return AndroidLogger.class.getName();
    }

    @Override
    protected void handleNormalizedLoggingCall(
            Level level,
            Marker marker,
            String messagePattern,
            Object[] arguments,
            Throwable throwable) {

        String formattedMessage = MessageFormatter.basicArrayFormat(messagePattern, arguments);
        if (throwable != null) {
            formattedMessage += "\n" + Log.getStackTraceString(throwable);
        }

        String tag = name;
        if (tag.length() > 23) {
            tag = tag.substring(0, 23);
        }

        int priority = levelToPriority(level);
        logLongMessage(tag, priority, formattedMessage);
    }

    private static int levelToPriority(Level level) {
        return switch (level) {
            case TRACE -> Log.VERBOSE;
            case DEBUG -> Log.DEBUG;
            case INFO -> Log.INFO;
            case WARN -> Log.WARN;
            case ERROR -> Log.ERROR;
        };
    }

    private static void logLongMessage(String tag, int priority, String message) {
        if (message.length() <= MAX_LOG_LENGTH) {
            Log.println(priority, tag, message);
            return;
        }
        int offset = 0;
        while (offset < message.length()) {
            int end = Math.min(offset + MAX_LOG_LENGTH, message.length());
            Log.println(priority, tag, message.substring(offset, end));
            offset = end;
        }
    }

    @Override
    public boolean isTraceEnabled() {
        return Log.isLoggable(name, Log.VERBOSE);
    }

    @Override
    public boolean isDebugEnabled() {
        return Log.isLoggable(name, Log.DEBUG);
    }

    @Override
    public boolean isInfoEnabled() {
        return Log.isLoggable(name, Log.INFO);
    }

    @Override
    public boolean isWarnEnabled() {
        return Log.isLoggable(name, Log.WARN);
    }

    @Override
    public boolean isErrorEnabled() {
        return Log.isLoggable(name, Log.ERROR);
    }
}
