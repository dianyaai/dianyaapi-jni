package com.dianya.api;

public enum ModelType {
    SPEED("speed"),
    QUALITY("quality"),
    QUALITY_V2("quality_v2");

    final String alias;

    ModelType(String alias) {
        this.alias = alias;
    }
}
