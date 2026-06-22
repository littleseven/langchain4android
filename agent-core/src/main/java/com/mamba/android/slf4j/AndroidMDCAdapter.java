package com.mamba.android.slf4j;

import org.slf4j.spi.MDCAdapter;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Android 环境下的 MDC (Mapped Diagnostic Context) 适配器实现。
 *
 * <p>Android 不支持真正的线程本地存储，使用 ConcurrentHashMap 模拟。</p>
 */
class AndroidMDCAdapter implements MDCAdapter {

    private final ThreadLocal<Map<String, String>> threadLocalMap = ThreadLocal.withInitial(ConcurrentHashMap::new);
    private final ThreadLocal<Map<String, Deque<String>>> threadLocalDequeMap = ThreadLocal.withInitial(ConcurrentHashMap::new);

    @Override
    public void put(String key, String val) {
        threadLocalMap.get().put(key, val);
    }

    @Override
    public String get(String key) {
        return threadLocalMap.get().get(key);
    }

    @Override
    public void remove(String key) {
        threadLocalMap.get().remove(key);
    }

    @Override
    public void clear() {
        threadLocalMap.get().clear();
    }

    @Override
    public Map<String, String> getCopyOfContextMap() {
        return new ConcurrentHashMap<>(threadLocalMap.get());
    }

    @Override
    public void setContextMap(Map<String, String> contextMap) {
        threadLocalMap.set(new ConcurrentHashMap<>(contextMap));
    }

    @Override
    public void pushByKey(String key, String value) {
        threadLocalDequeMap.get().computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>()).push(value);
    }

    @Override
    public String popByKey(String key) {
        Deque<String> deque = threadLocalDequeMap.get().get(key);
        return deque != null ? deque.pop() : null;
    }

    @Override
    public Deque<String> getCopyOfDequeByKey(String key) {
        Deque<String> deque = threadLocalDequeMap.get().get(key);
        return deque != null ? new ConcurrentLinkedDeque<>(deque) : null;
    }

    @Override
    public void clearDequeByKey(String key) {
        threadLocalDequeMap.get().remove(key);
    }
}
