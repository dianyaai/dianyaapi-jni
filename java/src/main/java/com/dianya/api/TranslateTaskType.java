package com.dianya.api;

import com.google.gson.annotations.SerializedName;

public enum TranslateTaskType {
    @SerializedName("transcribe")
    TRANSCRIBE,
    @SerializedName("summary")
    SUMMARY
}
