package com.dianya.api;

import com.google.gson.annotations.SerializedName;

public enum TranscribeTaskType {
    @SerializedName("normal_quality")
    NORMAL_QUALITY,
    @SerializedName("normal_speed")
    NORMAL_SPEED,
    @SerializedName("short_asr_quality")
    SHORT_ASR_QUALITY,
    @SerializedName("short_asr_speed")
    SHORT_ASR_SPEED
}
