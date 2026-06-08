package com.k2fsa.sherpa.mnn

class OnlineStream(var ptr: Long = 0) {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
        acceptWaveform(ptr, samples, sampleRate)

    fun inputFinished() = inputFinished(ptr)

/**
 * [P0-3] 不使用 finalize()，改为显式 release()。
 * 业务层应在 try/finally 或 use {} 中调用 release() 确保资源释放。
 */
fun release() {
    if (ptr != 0L) {
        delete(ptr)
        ptr = 0
    }
}

    fun use(block: (OnlineStream) -> Unit) {
        try {
            block(this)
        } finally {
            release()
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun inputFinished(ptr: Long)
    private external fun delete(ptr: Long)

    companion object {
        init {
            // sherpa-mnn-jni.so 由 SherpaMnnAsrEngine 通过 dlopen(RTLD_GLOBAL) 加载
            // 以解决 libMNN_Express.so 符号可见性问题。
        }
    }
}
