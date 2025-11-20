package com.dianya.api;

import com.google.gson.annotations.SerializedName;

public enum ExportType {
    @SerializedName("transcript")
    TRANSCRIPT("transcript"),
    @SerializedName("overview")
    OVERVIEW("overview"),
    @SerializedName("summary")
    SUMMARY("summary");

    final String alias;

    ExportType(String alias) {
        this.alias = alias;
    }
}
