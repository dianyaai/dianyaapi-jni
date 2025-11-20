package com.dianyaapi.example;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.dianya.api.*;
import com.dianyaapi.example.TranscribeStreamManager.ErrorType;
import com.dianyaapi.example.TranscribeStreamManager.Status;
import com.dianyaapi.example.TranscribeStreamManager.StreamState;
import com.dianyaapi.example.databinding.FragmentFirstBinding;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class FirstFragment extends Fragment {

    private static final String TOKEN = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyXzgzZTk5Y2YyIiwiZXhwIjoxNzY1MzU5Mjc4Ljk0ODk5fQ.JVL2o7u2IC-LhqFvSAmfE9oGVmnL7R4vfnxm_JA0V5k";

    private FragmentFirstBinding binding;
    private Handler mainHandler;

    private ExecutorService translationExecutor;
    @Nullable
    private Future<?> translationTask;

    private final StringBuilder streamBuffer = new StringBuilder();
    private StreamState streamState = StreamState.IDLE;
    @Nullable
    private TranscribeStreamManager streamManager;

    private final ActivityResultLauncher<String> microphonePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startStreamingInternal();
                } else {
                    appendStreamMessage(getString(R.string.stream_permission_denied));
                    streamState = StreamState.IDLE;
                    updateStreamingUi();
                }
            });

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        binding = FragmentFirstBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        translationExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        setupLanguageSpinner(binding.spinnerLanguage);
        binding.buttonTranslate.setOnClickListener(v -> triggerTranslation());
        binding.buttonStreamToggle.setOnClickListener(v -> toggleStreaming());
        streamManager = new TranscribeStreamManager(TOKEN, new TranscribeStreamManager.Listener() {
            @Override
            public void onStateChanged(@NonNull StreamState newState) {
                streamState = newState;
                updateStreamingUi();
            }

            @Override
            public void onStatus(@NonNull Status status, @Nullable String detail) {
                handleStreamStatus(status, detail);
            }

            @Override
            public void onTranscript(@NonNull String transcript) {
                appendStreamMessage(transcript);
            }

            @Override
            public void onError(@NonNull ErrorType type, @Nullable String detail) {
                handleStreamError(type, detail);
            }
        });
        updateStreamingUi();
    }

    private void setupLanguageSpinner(Spinner spinner) {
        Language[] languages = Language.values();
        ArrayAdapter<Language> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                languages
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(getDefaultLanguageIndex(languages, Language.EN));
    }

    private int getDefaultLanguageIndex(Language[] languages, Language target) {
        for (int i = 0; i < languages.length; i++) {
            if (languages[i] == target) {
                return i;
            }
        }
        return 0;
    }

    private void triggerTranslation() {
        String text = binding.editTextInput.getText() == null ? "" : binding.editTextInput.getText().toString();
        if (TextUtils.isEmpty(text)) {
            binding.editTextInput.setError(getString(R.string.translate_input_hint));
            return;
        }
        binding.editTextInput.setError(null);
        final Language language = (Language) binding.spinnerLanguage.getSelectedItem();
        setLoadingState(true);
        translationTask = translationExecutor.submit(() -> {
            try {
                DianyaRuntime.initialize();
                TranscribeApi.TextTranslator translator = TranscribeApi.translateText(text, language, TOKEN);
                String status = translator != null ? translator.status : "null";
                String data = translator != null ? translator.data : "null";
                postResult(getString(R.string.translate_result_success, status, data));
            } catch (UnsatisfiedLinkError error) {
                postResult(getString(R.string.translate_result_unavailable, error.getMessage()));
            } catch (RuntimeException runtimeException) {
                postResult(getString(R.string.translate_result_error, runtimeException.getMessage()));
            } finally {
                postLoading(false);
            }
        });
    }

    private void toggleStreaming() {
        if (streamManager == null) {
            return;
        }
        if (streamState == StreamState.IDLE) {
            startStreaming();
        } else if (streamState == StreamState.RUNNING) {
            streamManager.requestStop();
        }
    }

    private void startStreaming() {
        if (streamManager == null || streamState != StreamState.IDLE) {
            return;
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        startStreamingInternal();
    }

    private void startStreamingInternal() {
        if (streamManager == null || streamState != StreamState.IDLE) {
            return;
        }
        resetStreamOutput();
        streamManager.start(ModelType.SPEED);
    }

    private void handleStreamStatus(@NonNull Status status, @Nullable String detail) {
        switch (status) {
            case INITIALIZING:
                appendStreamMessage(getString(R.string.stream_status_initializing));
                break;
            case SESSION_CREATED:
                String sessionId = detail == null ? "" : detail;
                appendStreamMessage(getString(R.string.stream_status_session_created, sessionId));
                break;
            case STARTED:
                appendStreamMessage(getString(R.string.stream_status_started));
                break;
            case STOPPING:
                appendStreamMessage(getString(R.string.stream_status_stopping));
                break;
            case STOPPED:
                appendStreamMessage(getString(R.string.stream_status_stopped));
                break;
            case SESSION_CLOSED:
                appendStreamMessage(getString(R.string.stream_status_closed));
                break;
        }
    }

    private void handleStreamError(@NonNull ErrorType type, @Nullable String detail) {
        String messageDetail = TextUtils.isEmpty(detail)
                ? getString(R.string.stream_error_default_detail)
                : detail;
        switch (type) {
            case SESSION:
                appendStreamMessage(getString(R.string.stream_error_session, messageDetail));
                break;
            case AUDIO:
                appendStreamMessage(getString(R.string.stream_error_audio, messageDetail));
                break;
            case READ:
                appendStreamMessage(getString(R.string.stream_error_read, messageDetail));
                break;
            case CLOSE:
                appendStreamMessage(getString(R.string.stream_error_close_session, messageDetail));
                break;
        }
    }

    private void resetStreamOutput() {
        streamBuffer.setLength(0);
        if (binding != null) {
            binding.textStreamResult.setText(getString(R.string.stream_result_placeholder));
        }
    }

    private void appendStreamMessage(String message) {
        if (TextUtils.isEmpty(message)) {
            return;
        }
        if (mainHandler == null) {
            return;
        }
        mainHandler.post(() -> {
            if (binding == null) {
                return;
            }
            if (streamBuffer.length() > 0) {
                streamBuffer.append('\n');
            }
            streamBuffer.append(message);
            binding.textStreamResult.setText(streamBuffer.toString());
        });
    }

    private void updateStreamingUi() {
        if (binding == null) {
            return;
        }
        switch (streamState) {
            case IDLE:
                binding.buttonStreamToggle.setText(R.string.stream_button_start);
                binding.buttonStreamToggle.setEnabled(true);
                break;
            case STARTING:
                binding.buttonStreamToggle.setText(R.string.stream_button_stop);
                binding.buttonStreamToggle.setEnabled(false);
                break;
            case RUNNING:
                binding.buttonStreamToggle.setText(R.string.stream_button_stop);
                binding.buttonStreamToggle.setEnabled(true);
                break;
            case STOPPING:
                binding.buttonStreamToggle.setText(R.string.stream_button_disabling);
                binding.buttonStreamToggle.setEnabled(false);
                break;
        }
    }

    private void postResult(String message) {
        if (mainHandler == null) {
            return;
        }
        mainHandler.post(() -> {
            if (binding != null) {
                binding.textResult.setText(message);
            }
        });
    }

    private void postLoading(boolean loading) {
        if (mainHandler == null) {
            return;
        }
        mainHandler.post(() -> {
            if (binding != null) {
                setLoadingViews(loading);
            }
        });
    }

    private void setLoadingState(boolean loading) {
        setLoadingViews(loading);
        binding.textResult.setText(loading ? getString(R.string.translate_result_placeholder) : binding.textResult.getText());
    }

    private void setLoadingViews(boolean loading) {
        binding.progressLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.buttonTranslate.setEnabled(!loading);
        binding.spinnerLanguage.setEnabled(!loading);
        binding.editTextInput.setEnabled(!loading);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (streamManager != null) {
            streamManager.release();
            streamManager = null;
        }
        streamState = StreamState.IDLE;
        if (translationTask != null) {
            translationTask.cancel(true);
            translationTask = null;
        }
        if (translationExecutor != null) {
            translationExecutor.shutdownNow();
            translationExecutor = null;
        }
        binding = null;
        mainHandler = null;
    }
}