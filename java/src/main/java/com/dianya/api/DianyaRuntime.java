package com.dianya.api;

/**
 * SDK 运行时生命周期管理。
 *
 * <p>负责初始化与关闭 JNI 层对应的 Tokio Runtime，
 * 在调用 {@link TranscribeApi} 等其他 API 前需要先调用 {@link #initialize()}。</p>
 */
public final class DianyaRuntime {

    static {
        System.loadLibrary("dianyaapi_jni");
    }

    private DianyaRuntime() {
        throw new AssertionError("No instances.");
    }

    /**
     * 初始化底层 Tokio Runtime。多次调用安全（幂等）。
     */
    public static void initialize() {
        nativeInitialize();
    }

    /**
     * 关闭底层 Runtime，释放资源。非必须，但建议在应用退出时调用。
     */
    public static void shutdown() {
        nativeShutdown();
    }

    private static native void nativeInitialize();

    private static native void nativeShutdown();
}
