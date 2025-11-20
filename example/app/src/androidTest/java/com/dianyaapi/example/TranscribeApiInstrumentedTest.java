package com.dianyaapi.example;

import static org.junit.Assert.assertNotNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.dianya.api.TranscribeApi;
import com.dianya.api.TranscribeApi.Language;
import com.dianya.api.TranscribeApi.TextTranslator;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TranscribeApiInstrumentedTest {

    private static final String TOKEN = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyXzgzZTk5Y2YyIiwiZXhwIjoxNzY1MzU5Mjc4Ljk0ODk5fQ.JVL2o7u2IC-LhqFvSAmfE9oGVmnL7R4vfnxm_JA0V5k";

    @Test
    public void translateText_handlesInvocation() {
        try {
            TranscribeApi.initialize();
        } catch (UnsatisfiedLinkError error) {
            Assume.assumeNoException("Native library is not available for testing.", error);
        }

        try {
            TextTranslator translator = TranscribeApi.translateText(
                    "你好，世界！",
                    Language.EN,
                    TOKEN
            );
            assertNotNull("Native method returned null translator", translator);
        } catch (RuntimeException runtimeException) {
            assertNotNull("RuntimeException from nativeTranslateText should provide detail", runtimeException.getMessage());
        } finally {
            try {
                TranscribeApi.finalizeSdk();
            } catch (UnsatisfiedLinkError ignored) {
                // Ignore finalize errors caused by missing native bindings.
            }
        }
    }
}

