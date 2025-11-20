package com.dianya.api;

import org.jetbrains.annotations.NotNull;

/**
 * SDK 抛出的统一异常类型，包含一个错误代码和详细信息。
 * <p>错误码与 Rust 端的 {@code common::Error} 对应：</p>
 * <ul>
 *     <li>{@code WS_ERROR}：WebSocket 相关错误</li>
 *     <li>{@code HTTP_ERROR}：HTTP 请求失败</li>
 *     <li>{@code SERVER_ERROR}：服务端返回错误消息</li>
 *     <li>{@code INVALID_INPUT}：请求参数校验失败</li>
 *     <li>{@code INVALID_RESPONSE}：服务端响应解析失败</li>
 *     <li>{@code INVALID_TOKEN}：鉴权 token 无效</li>
 *     <li>{@code INVALID_API_KEY}：API Key 无效</li>
 *     <li>{@code JSON_ERROR}：JSON 序列化/反序列化异常</li>
 *     <li>{@code OTHER_ERROR}：其他错误</li>
 *     <li>{@code JNI_ERROR}：JNI 层调用失败</li>
 *     <li>{@code UNEXPECTED_ERROR}：未分类的异常</li>
 * </ul>
 */
public final class DianyaException extends RuntimeException {
    private final @NotNull Code code;

    public DianyaException(@NotNull Code code, @NotNull String message) {
        super(message);
        this.code = code;
    }

    /** 返回错误代码（如 WS_ERROR、HTTP_ERROR 等）。 */
    public @NotNull Code getCode() {
        return code;
    }

    public enum Code {
        WS_ERROR,
        HTTP_ERROR,
        SERVER_ERROR,
        INVALID_INPUT,
        INVALID_RESPONSE,
        INVALID_TOKEN,
        INVALID_API_KEY,
        JSON_ERROR,
        OTHER_ERROR,
        JNI_ERROR,
        UNEXPECTED_ERROR
    }
}

