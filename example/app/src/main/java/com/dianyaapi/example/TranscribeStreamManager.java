package com.dianyaapi.example;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.dianya.api.DianyaRuntime;
import com.dianya.api.ModelType;
import com.dianya.api.TranscribeStream;
import com.dianya.api.TranscribeStream.SessionCreateResponse;

import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理 TranscribeStream 的生命周期与音频采集线程，避免在 UI 层直接操作 JNI。
 */
public final class TranscribeStreamManager {

    public enum StreamState {
        IDLE,
        STARTING,
        RUNNING,
        STOPPING
    }

    public enum Status {
        INITIALIZING,
        SESSION_CREATED,
        STARTED,
        STOPPING,
        STOPPED,
        SESSION_CLOSED
    }

    public enum ErrorType {
        SESSION,
        AUDIO,
        READ,
        CLOSE
    }

    public interface Listener {
        void onStateChanged(@NonNull StreamState newState);

        void onStatus(@NonNull Status status, @Nullable String detail);

        void onTranscript(@NonNull String transcript);

        void onError(@NonNull ErrorType type, @Nullable String detail);
    }

    private static final int STREAM_SAMPLE_RATE = 16_000;
    private static final int STREAM_CLOSE_TIMEOUT_SECONDS = 5;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final ExecutorService sessionExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService readExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean stopRequested = new AtomicBoolean(true);
    private final String token;

    private volatile StreamState streamState = StreamState.IDLE;
    private volatile boolean streamStarted;
    @Nullable
    private volatile TranscribeStream currentStream;
    @Nullable
    private volatile SessionCreateResponse currentSession;
    @Nullable
    private volatile AudioRecord audioRecord;

    @Nullable
    private Future<?> sessionFuture;
    @Nullable
    private Future<?> audioFuture;
    @Nullable
    private Future<?> readFuture;

    public TranscribeStreamManager(@NonNull String token, @NonNull Listener listener) {
        this.token = token;
        this.listener = listener;
    }

    public synchronized void start(@NonNull ModelType modelType) {
        if (streamState != StreamState.IDLE) {
            return;
        }
        stopRequested.set(false);
        streamStarted = false;
        updateState(StreamState.STARTING);
        sessionFuture = sessionExecutor.submit(() -> runStreamingSession(modelType));
    }

    public void requestStop() {
        if (!stopRequested.compareAndSet(false, true)) {
            return;
        }
        postStatus(Status.STOPPING, null);
        updateState(StreamState.STOPPING);
        AudioRecord recorder = audioRecord;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
                // 忽略 stop 前未 start 的异常
            }
        }
    }

    public void release() {
        requestStop();
        if (sessionFuture != null) {
            sessionFuture.cancel(true);
            sessionFuture = null;
        }
        sessionExecutor.shutdownNow();
        audioExecutor.shutdownNow();
        readExecutor.shutdownNow();
    }

    private void runStreamingSession(@NonNull ModelType modelType) {
        SessionCreateResponse session = null;
        TranscribeStream stream = null;
        AudioRecord recorder = null;
        try {
            postStatus(Status.INITIALIZING, null);
            DianyaRuntime.initialize();
            session = TranscribeStream.createSession(modelType, token);
            currentSession = session;
            postStatus(Status.SESSION_CREATED, session.sessionId);

            stream = new TranscribeStream(session.sessionId);
            currentStream = stream;
            stream.start();
            streamStarted = true;
            postStatus(Status.STARTED, null);
            updateState(StreamState.RUNNING);

            int bufferSize = prepareAudioRecorder();
            recorder = audioRecord;
            if (recorder == null) {
                throw new IllegalStateException("AudioRecord unavailable.");
            }
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("AudioRecord failed to start recording.");
            }

            final TranscribeStream activeStream = stream;
            final AudioRecord activeRecorder = recorder;
            final int activeBufferSize = bufferSize;

            // 启动两个线程：一个发送音频数据，一个读取转写结果
            audioFuture = audioExecutor.submit(() -> captureAudioLoop(activeStream, activeRecorder, activeBufferSize));
            readFuture = readExecutor.submit(() -> readStreamLoop(activeStream));

            // 等待音频采集线程完成
            if (audioFuture != null) {
                try {
                    audioFuture.get();
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException executionException) {
                    Throwable cause = executionException.getCause();
                    postError(ErrorType.AUDIO, cause != null ? cause.getMessage() : executionException.getMessage());
                } catch (CancellationException ignored) {
                    // 已取消，直接进入清理阶段
                }
            }

            // 等待读取转写结果线程完成
            if (readFuture != null) {
                try {
                    readFuture.get();
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException executionException) {
                    Throwable cause = executionException.getCause();
                    postError(ErrorType.READ, cause != null ? cause.getMessage() : executionException.getMessage());
                } catch (CancellationException ignored) {
                    // 已取消，直接进入清理阶段
                }
            }

            // 两个线程都已完成，转写结束
        } catch (RuntimeException runtimeException) {
            postError(ErrorType.SESSION, runtimeException.getMessage());
        } finally {
            // 标记停止请求，让两个循环线程退出
            boolean shouldStopStream = streamStarted;
            stopRequested.set(true);

            // 先停止录音
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (IllegalStateException ignored) {
                    // 忽略
                }
                recorder.release();
            }
            audioRecord = null;

            // 等待两个线程完成（如果它们还在运行）
            if (audioFuture != null) {
                if (!audioFuture.isDone()) {
                    audioFuture.cancel(true);
                    try {
                        audioFuture.get();
                    } catch (Exception ignored) {
                        // 忽略取消或中断异常
                    }
                }
                audioFuture = null;
            }
            if (readFuture != null) {
                if (!readFuture.isDone()) {
                    readFuture.cancel(true);
                    try {
                        readFuture.get();
                    } catch (Exception ignored) {
                        // 忽略取消或中断异常
                    }
                }
                readFuture = null;
            }

            // 只有在录音转写结束后才关闭 stream（先 stop 再 close）
            if (shouldStopStream && stream != null) {
                try {
                    stream.stop();
                } catch (RuntimeException ignored) {
                    // 忽略 native stop 异常
                }
                try {
                    stream.close();
                } catch (RuntimeException ignored) {
                    // 忽略 close 异常
                }
            }

            // 最后关闭 session
            if (session != null && session.taskId != null && !session.taskId.isEmpty()) {
                try {
                    TranscribeStream.closeSession(session.taskId, token, STREAM_CLOSE_TIMEOUT_SECONDS);
                    postStatus(Status.SESSION_CLOSED, session.taskId);
                } catch (RuntimeException runtimeException) {
                    postError(ErrorType.CLOSE, runtimeException.getMessage());
                }
            }

            // 清理状态
            currentStream = null;
            currentSession = null;
            streamStarted = false;
            updateState(StreamState.IDLE);
            postStatus(Status.STOPPED, null);
        }
    }

    private int prepareAudioRecorder() {
        int minBufferSize = AudioRecord.getMinBufferSize(
                STREAM_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            throw new IllegalStateException("Invalid AudioRecord buffer size.");
        }
        int bufferSize = minBufferSize * 2;
        AudioRecord record = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                STREAM_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
        );
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new IllegalStateException("AudioRecord initialisation failed.");
        }
        audioRecord = record;
        return bufferSize;
    }

    private void captureAudioLoop(@NonNull TranscribeStream stream,
                                  @NonNull AudioRecord recorder,
                                  int bufferSize) {
        byte[] buffer = new byte[bufferSize];
        while (!stopRequested.get() && !Thread.currentThread().isInterrupted()) {
            int read = recorder.read(buffer, 0, buffer.length);
            if (read > 0) {
                byte[] chunk = Arrays.copyOf(buffer, read);
                try {
                    stream.sendBinary(chunk);
                } catch (RuntimeException runtimeException) {
                    postError(ErrorType.AUDIO, runtimeException.getMessage());
                    break;
                }
            } else if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                postError(ErrorType.AUDIO, "AudioRecord read error: " + read);
                break;
            } else if (read == 0 && stopRequested.get()) {
                break;
            }
        }
    }

    private void readStreamLoop(@NonNull TranscribeStream stream) {
        while (!stopRequested.get() && !Thread.currentThread().isInterrupted()) {
            try {
                String message = stream.readNext(500);
                if (message != null && !message.isEmpty()) {
                    postTranscript(message);
                }
            } catch (IllegalStateException stateException) {
                if (!stopRequested.get()) {
                    postError(ErrorType.READ, stateException.getMessage());
                }
                break;
            } catch (RuntimeException runtimeException) {
                if (!stopRequested.get()) {
                    postError(ErrorType.READ, runtimeException.getMessage());
                }
                break;
            }
        }
    }

    private void updateState(@NonNull StreamState newState) {
        streamState = newState;
        mainHandler.post(() -> listener.onStateChanged(newState));
    }

    private void postStatus(@NonNull Status status, @Nullable String detail) {
        mainHandler.post(() -> listener.onStatus(status, detail));
    }

    private void postTranscript(@NonNull String transcript) {
        mainHandler.post(() -> listener.onTranscript(transcript));
    }

    private void postError(@NonNull ErrorType type, @Nullable String detail) {
        mainHandler.post(() -> listener.onError(type, detail));
    }
}

