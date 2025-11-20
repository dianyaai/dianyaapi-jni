package com.dianya.api;

import com.google.gson.annotations.SerializedName;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * WebSocket 实时转写客户端。
 *
 * <p>封装 Rust {@code TranscribeWs} 的 JNI 接口，提供简单的 Java API：</p>
 * <ul>
 *     <li>{@link #start()} / {@link #stop()} 控制连接生命周期；</li>
 *     <li>{@link #sendBinary(byte[])} 发送 PCM 等二进制音频数据；</li>
     <li>{@link #sendText(String)} 发送自定义文本消息；</li>
     <li>{@link #readNext(long)} 拉取服务端推送的 JSON 文本消息。</li>
 * </ul>
 *
 * <p>使用前请确保调用 {@link DianyaRuntime#initialize()} 初始化底层运行时。</p>
 */
public final class TranscribeStream implements AutoCloseable {

    private static final long NO_TIMEOUT = -1L;

    static {
        System.loadLibrary("dianyaapi_jni");
    }

    private long nativeHandle;
    private boolean started;

    /**
     * @param sessionId 来自 {@link TranscribeStream#createSession(ModelType, String)} 的 session id
     */
    public TranscribeStream(@NotNull String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be empty.");
        }
        this.nativeHandle = nativeCreate(sessionId);
    }

    // region Session

    @NotNull
    /**
     * 创建实时转写会话。
     *
     * @param model Speed/Quality
     * @param token Bearer token
     */
    public static SessionCreateResponse createSession(@NotNull ModelType model, @NotNull String token) {
        String json = nativeCreateSession(model.alias, token);
        return Utils.fromJson(json, SessionCreateResponse.class);
    }

    @NotNull
    public static SessionCloseResponse closeSession(
            @NotNull String taskId,
            @NotNull String token,
            long timeoutSeconds
    ) {
        String json = nativeCloseSession(taskId, token, timeoutSeconds);
        return Utils.fromJson(json, SessionCloseResponse.class);
    }

    // endregion

    public synchronized void start() {
        ensureHandle();
        if (started) {
            return;
        }
        nativeStart(nativeHandle);
        started = true;
    }

    public synchronized void stop() {
        if (nativeHandle == 0L || !started) {
            return;
        }
        nativeStop(nativeHandle);
        started = false;
    }

    public synchronized void sendBinary(@NotNull byte[] payload) {
        ensureHandle();
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty.");
        }
        nativeSendBinary(nativeHandle, payload);
    }

    public synchronized void sendText(@NotNull String message) {
        ensureHandle();
        if (message == null) {
            throw new IllegalArgumentException("message must not be null.");
        }
        nativeSendText(nativeHandle, message);
    }

    public @Nullable String readNext() {
        return readNext(NO_TIMEOUT);
    }

    public @Nullable String readNext(long timeoutMillis) {
        ensureHandle();
        return nativeRead(nativeHandle, timeoutMillis);
    }

    public synchronized boolean isStarted() {
        return started;
    }

    @Override
    public synchronized void close() {
        try {
            stop();
        } finally {
            if (nativeHandle != 0L) {
                nativeDestroy(nativeHandle);
                nativeHandle = 0L;
            }
        }
    }

    public static final class SessionCreateResponse {
        @SerializedName("task_id")
        public String taskId;
        @SerializedName("suth_session_id")
        public String sessionId;
        @SerializedName("usage_id")
        public String usageId;
        @SerializedName("max_time")
        public int maxTime;
    }

    public static final class SessionCloseResponse {
        public String status;
        public Integer duration;
        @SerializedName("error_code")
        public Integer errorCode;
        public String message;
    }

    private void ensureHandle() {
        if (nativeHandle == 0L) {
            throw new IllegalStateException("TranscribeStream has been closed.");
        }
    }

    private static native String nativeCreateSession(String model, String token);

    private static native String nativeCloseSession(String taskId, String token, long timeoutSeconds);

    private static native long nativeCreate(String sessionId);

    private static native void nativeDestroy(long handle);

    private static native void nativeStart(long handle);

    private static native void nativeStop(long handle);

    private static native void nativeSendBinary(long handle, byte[] payload);

    private static native void nativeSendText(long handle, String message);

    private static native String nativeRead(long handle, long timeoutMillis);
}
