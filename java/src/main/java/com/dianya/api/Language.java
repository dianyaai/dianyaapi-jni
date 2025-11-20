package com.dianya.api;

public enum Language {
    ZH("zh"),
    EN("en"),
    JA("ja"),
    KO("ko"),
    FR("fr"),
    DE("de");

    final String alias;

    Language(String alias) {
        this.alias = alias;
    }
}
