package com.dianya.api;

import com.google.gson.annotations.SerializedName;

public enum ExportFormat {
    @SerializedName("pdf")
    PDF("pdf"),
    @SerializedName("txt")
    TXT("txt"),
    @SerializedName("docx")
    DOCX("docx");

    final String alias;

    ExportFormat(String alias) {
        this.alias = alias;
    }
}
