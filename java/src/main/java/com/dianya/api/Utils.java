package com.dianya.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

final class Utils {
    public static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private Utils() {
        throw new IllegalStateException("Utility class");
    }
    
    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            throw new RuntimeException("Native layer returned empty JSON for " + clazz.getSimpleName());
        }
        return GSON.fromJson(json, clazz);
    }
}
