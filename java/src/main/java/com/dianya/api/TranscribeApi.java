package com.dianya.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * JNI 层对外暴露的 Java API。所有 public 方法都会返回强类型的业务对象，
 * 内部通过 Gson 解析由 native 层返回的 JSON 字符串。
 *
 * <p>注意：
 * <ul>
 *     <li>调用前必须执行 {@link DianyaRuntime#initialize()}；</li>
 *     <li>使用完成后可在合适时机调用 {@link DianyaRuntime#shutdown()} 释放底层资源；</li>
 *     <li>所有方法均可能抛出 {@link RuntimeException}，请在业务层做好捕获。</li>
 * </ul>
 * </p>
 */
/**
 * Java 层对外暴露的同步 API，封装了 JNI 本地调用以及 JSON 解析。
 * 所有方法都要求先调用 {@link DianyaRuntime#initialize()} 初始化底层 Tokio runtime。
 */
public final class TranscribeApi {
    static {
        System.loadLibrary("dianyaapi_jni");
    }

    private TranscribeApi() {
        throw new AssertionError("No instances.");
    }

    // region Native method declarations

    private static native String nativeUpload(
            String filePath,
            boolean transcribeOnly,
            boolean shortAsr,
            String model,
            String token
    );

    private static native String nativeStatus(
            @Nullable String taskId,
            @Nullable String shareId,
            String token
    );

    private static native String nativeCallback(String payloadJson, String token);

    private static native String nativeGetShareLink(String taskId, int expirationDays, String token);

    private static native String nativeCreateSummary(String utterancesJson, String token);

    private static native byte[] nativeExport(
            String taskId,
            String exportType,
            String exportFormat,
            String token
    );

    private static native String nativeTranslateText(String text, String language, String token);

    private static native String nativeTranslateUtterances(String utterancesJson, String language, String token);

    private static native String nativeTranslateTranscribe(String taskId, String language, String token);

    // endregion

    // region Upload

    @NotNull
    public static UploadResponse upload(
            @NotNull String filePath,
            boolean transcribeOnly,
            boolean shortAsr,
            @NotNull ModelType model,
            @NotNull String token
    ) {
        String json = nativeUpload(filePath, transcribeOnly, shortAsr, model.alias, token);
        return Utils.fromJson(json, UploadResponse.class);
    }

    // endregion

    // region Status & Callback

    @NotNull
    public static StatusResponse status(
            @Nullable String taskId,
            @Nullable String shareId,
            @NotNull String token
    ) {
        String json = nativeStatus(taskId, shareId, token);
        return Utils.fromJson(json, StatusResponse.class);
    }

    /**
     * 转发服务端回调。
     *
     * @param request 已序列化的回调对象
     * @param token   Bearer token
     * @return 回调处理结果
     */
    @NotNull
    public static CallbackResponse callback(@NotNull CallbackRequest request, @NotNull String token) {
        String payload = Utils.GSON.toJson(request);
        String json = nativeCallback(payload, token);
        return Utils.fromJson(json, CallbackResponse.class);
    }

    // endregion

    // region Share & Summary

    @NotNull
    public static ShareLinkResponse getShareLink(
            @NotNull String taskId,
            int expirationDays,
            @NotNull String token
    ) {
        String json = nativeGetShareLink(taskId, expirationDays, token);
        return Utils.fromJson(json, ShareLinkResponse.class);
    }

    @NotNull
    public static SummaryCreateResponse createSummary(
            @NotNull List<Utterance> utterances,
            @NotNull String token
    ) {
        UtterancesWrapper wrapper = new UtterancesWrapper(utterances);
        String payload = Utils.GSON.toJson(wrapper);
        String json = nativeCreateSummary(payload, token);
        return Utils.fromJson(json, SummaryCreateResponse.class);
    }

    // endregion

    // region Export

    @NotNull
    public static byte[] export(
            @NotNull String taskId,
            @NotNull ExportType type,
            @NotNull ExportFormat format,
            @NotNull String token
    ) {
        byte[] bytes = nativeExport(taskId, type.alias, format.alias, token);
        return bytes == null ? new byte[0] : bytes;
    }

    // endregion

    // region Translate

    @NotNull
    public static TextTranslator translateText(
            @NotNull String text,
            @NotNull Language language,
            @NotNull String token
    ) {
        String json = nativeTranslateText(text, language.alias, token);
        return Utils.fromJson(json, TextTranslator.class);
    }

    @NotNull
    public static UtteranceTranslator translateUtterances(
            @NotNull List<Utterance> utterances,
            @NotNull Language language,
            @NotNull String token
    ) {
        UtterancesWrapper wrapper = new UtterancesWrapper(utterances);
        String payload = Utils.GSON.toJson(wrapper);
        String json = nativeTranslateUtterances(payload, language.alias, token);
        return Utils.fromJson(json, UtteranceTranslator.class);
    }

    @NotNull
    public static TranscribeTranslator translateTranscribe(
            @NotNull String taskId,
            @NotNull Language language,
            @NotNull String token
    ) {
        String json = nativeTranslateTranscribe(taskId, language.alias, token);
        return Utils.fromJson(json, TranscribeTranslator.class);
    }

    // endregion

    // region Helpers

    private static final class UtterancesWrapper {
        @SerializedName("utterances")
        final List<Utterance> utterances;

        UtterancesWrapper(List<Utterance> utterances) {
            this.utterances = utterances;
        }
    }

    // endregion

    // region Data models

    public static final class UploadResponse {
        @SerializedName("task_id")
        public String taskId;
        public String status;
        public String message;
        public String data;

        public boolean isNormal() {
            return taskId != null && !taskId.isEmpty();
        }

        public boolean isOneSentence() {
            return status != null;
        }
    }

    public static final class StatusResponse {
        public String status;
        @SerializedName("overview_md")
        public String overviewMarkdown;
        @SerializedName("summary_md")
        public String summaryMarkdown;
        public List<Utterance> details = Collections.emptyList();
        public String message;
        @SerializedName("usage_id")
        public String usageId;
        @SerializedName("suth_task_id")
        public String taskId;
        public List<String> keywords = Collections.emptyList();
        @SerializedName("callback_history")
        public List<CallbackHistory> callbackHistory = Collections.emptyList();
        @SerializedName("task_type")
        public TranscribeTaskType taskType;
    }

    public static final class CallbackHistory {
        public String timestamp;
        public String status;
        public int code;
    }

    public static final class CallbackResponse {
        public String status;
    }

    public static final class ShareLinkResponse {
        @SerializedName("shareUrl")
        public String shareUrl;
        @SerializedName("expiration_time")
        public int expirationTime;
        @SerializedName("expired_at")
        public String expiredAt;
    }

    public static final class SummaryCreateResponse {
        @SerializedName("task_id")
        public String taskId;
    }

    public static final class SummaryContent {
        public String shortSummary;
        public String longSummary;
        @SerializedName("all")
        public String fullText;
        public List<String> keywords = Collections.emptyList();
    }

    public static final class TextTranslator {
        public String status;
        public String data;
    }

    public static final class UtteranceTranslator {
        public String status;
        @SerializedName("target_language")
        public String targetLanguage;
        public List<Utterance> details = Collections.emptyList();
    }

    public static final class TranslationDetail {
        @SerializedName("start_time")
        public double startTime;
        @SerializedName("end_time")
        public double endTime;
        public String text;
        public int speaker;
        public Map<String, String> translations = Collections.emptyMap();
    }

    public static final class TranscribeTranslator {
        @SerializedName("task_id")
        public String taskId;
        @SerializedName("task_type")
        public TranslateTaskType taskType;
        public String status;
        @SerializedName("target_language")
        public String targetLanguage;
        public String message;
        public JsonElement details;
        @SerializedName("overview_md")
        public String overviewMarkdown;
        @SerializedName("summary_md")
        public String summaryMarkdown;
        public List<String> keywords = Collections.emptyList();
    }

    public static final class CallbackRequest {
        @SerializedName("task_id")
        public String taskId;
        public String status;
        public int code;
        public List<Utterance> utterances = Collections.emptyList();
        public SummaryContent summary;
        public Integer duration;
        public String message;
    }

    public static final class Utterance {
        @SerializedName("start_time")
        public double startTime;
        @SerializedName("end_time")
        public double endTime;
        public String text;
        public int speaker;
    }

    // endregion
}
