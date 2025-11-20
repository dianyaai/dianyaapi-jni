# 👋 `dianyaapi-jni`

`dianyaapi-jni` 是 `dianya_api_sdk` 的 Android/Java 封装层，将 `transcribe` crate 中的异步接口通过 JNI 暴露给 Java/Kotlin 使用。所有复杂的网络、Tokio runtime 与错误处理均在 Rust 侧完成，Java 侧只需按同步方式调用。

## 构建

### Rust 动态库

```bash
# 在仓库根目录运行
cargo build -p dianyaapi-jni --release

# 如果需要产出特定 ABI 的 so，可配合 cargo-ndk
cargo ndk -o ./target/jniLibs -t arm64-v8a -t armeabi-v7a -p 21 -- build -p dianyaapi-jni --release
```

产物为 `target/<profile>/libdianyaapi_jni.so`，复制到 Android 工程的 `jniLibs` 目录即可。

### Java/AAR SDK

Gradle 项目位于 `wrapper-jni/`，包含两个 module：

- `:java`：纯 Java 库，输出 `dianyaapi-jni-<version>.jar`
- `:android`：Android Library，输出 `dianyaapi-jni-<version>.aar`

快速构建脚本（首次使用需 `chmod +x scripts/build_sdk.sh`）：

```bash
# 在 wrapper-jni 目录下运行
cd wrapper-jni

# 生成 JAR（默认 debug）
scripts/build_sdk.sh jar

# 生成 AAR，使用 release 原生库
scripts/build_sdk.sh --release aar

# 同时生成 JAR + AAR（默认 debug，传 --release 可切换）
scripts/build_sdk.sh

# 自定义 Android API Level / ABI（示例：API 33 + arm64/x86_64），并一次性生成全部产物
scripts/build_sdk.sh --platform 33 --arch arm64-v8a,x86_64 all
```

构建成功后产物存放在 `wrapper-jni/dist/`，文件名统一为 `dianyaapi-jni-<version>.jar` 与 `dianyaapi-jni-<version>.aar`，方便与版本号对应。脚本会优先使用仓库根目录的 `gradlew`，若不存在则回退到系统里的 `gradle` 命令。

> **注意**
> - 脚本会自动调用 `cargo build -p dianyaapi-jni`（桌面平台）以及 `cargo ndk`（Android ABI）。请提前安装 `cargo-ndk`，并执行 `rustup target add aarch64-linux-android x86_64-linux-android armv7-linux-androideabi` 等所需目标。通过 `--platform` 和 `--arch` 可以指定 Android API level 与 ABI 列表，默认分别为 `21` 与 `arm64-v8a,x86_64`。
> - JAR 会根据当前宿主系统打包对应的动态库：`META-INF/lib/linux-*/libdianyaapi_jni.so`、`META-INF/lib/macos-*/libdianyaapi_jni.dylib`、`META-INF/lib/windows-*/dianyaapi_jni.dll`。在 `all` 模式下还会额外附带 `META-INF/lib/android-<abi>/libdianyaapi_jni.so`，方便统一分发；AAR 则始终包含 `jni/<abi>` 目录。

## 初始化与生命周期

- **初始化**：调用 `DianyaRuntime.initialize()` 一次，完成 Tokio runtime、日志等资源准备。
- **销毁**：应用退出或不再需要 SDK 时，调用 `DianyaRuntime.shutdown()` 用于释放 runtime。
- 所有后续方法都会复用同一个多线程 Tokio runtime，每次调用会阻塞当前 JVM 线程直到 Rust 侧完成任务。

## Java/Kotlin 入口类

所有 JNI 导出的方法都集中在 `com.dianya.api.TranscribeApi`，Rust 层仅暴露 `native*` 方法，Java 侧对外提供强类型包装。主要 API 如下：

| 方法 | 说明 | 返回值                     |
| --- | --- |-------------------------|
| `createSession(model, token)` | 创建实时转写会话 | `SessionCreateResponse` |
| `closeSession(taskId, token, timeoutSeconds)` | 关闭实时转写会话 | `SessionCloseResponse`  |
| `upload(path, transcribeOnly, shortAsr, model, token)` | 上传音频文件 | `UploadResponse`        |
| `status(taskId, shareId, token)` | 获取任务状态/结果 | `StatusResponse`        |
| `callback(request, token)` | 转发业务回调 | `CallbackResponse`      |
| `getShareLink(taskId, expirationDays, token)` | 获取分享链接 | `ShareLinkResponse`     |
| `createSummary(utterances, token)` | 创建总结任务 | `SummaryCreateResponse` |
| `export(taskId, type, format, token)` | 导出结果文件 | `byte[]`                |
| `translateText(text, lang, token)` | 翻译纯文本 | `TextTranslator`        |
| `translateUtterances(utterances, lang, token)` | 翻译对话列表 | `UtteranceTranslator`   |
| `translateTranscribe(taskId, lang, token)` | 获取任务翻译结果 | `TranscribeTranslator`  |

> Java 层使用 Gson 解析 JSON，请在宿主工程中加入 `com.google.code.gson:gson` 以及 `org.jetbrains:annotations` 依赖。

## 参数与类型约定

### 枚举类型

#### ModelType（模型类型）
- `SPEED`：速度模式（对应字符串 `"speed"`）
- `QUALITY`：质量模式（对应字符串 `"quality"`）
- `QUALITY_V2`：质量模式 v2（对应字符串 `"quality_v2"`）

#### Language（目标语言）
- `ZH`：中文（简体）
- `EN`：英语（美式）
- `JA`：日语
- `KO`：韩语
- `FR`：法语
- `DE`：德语

#### ExportType（导出类型）
- `TRANSCRIPT`：转写内容（注意：总结任务不支持此类型）
- `OVERVIEW`：概览内容
- `SUMMARY`：总结内容

#### ExportFormat（导出格式）
- `PDF`：PDF 格式（默认）
- `TXT`：TXT 文本格式
- `DOCX`：DOCX Word 文档格式

### 其他约定

- `timeoutSeconds`：超时秒数，传负数（如 `-1`）表示使用默认值（30 秒）
- `expirationDays`：过期天数，传负数表示使用默认值（7 天）
- `timeoutMillis`：超时毫秒数（仅用于 `TranscribeStream.readNext()`），传负数表示无超时
- 所有方法都使用强类型枚举，无需传递字符串

### 异常处理

Rust 侧的错误会被封装成 `DianyaException`（继承自 `RuntimeException`）抛出，包含错误代码和详细信息。错误代码包括：

- `WS_ERROR`：WebSocket 相关错误
- `HTTP_ERROR`：HTTP 请求失败
- `SERVER_ERROR`：服务端返回错误消息
- `INVALID_INPUT`：请求参数校验失败
- `INVALID_RESPONSE`：服务端响应解析失败
- `INVALID_TOKEN`：鉴权 token 无效
- `INVALID_API_KEY`：API Key 无效
- `JSON_ERROR`：JSON 序列化/反序列化异常
- `OTHER_ERROR`：其他错误
- `JNI_ERROR`：JNI 层调用失败
- `UNEXPECTED_ERROR`：未分类的异常

## 使用示例

### Kotlin 示例

#### 1. 上传音频文件并查询状态

```kotlin
import com.dianya.api.*
import android.util.Log

class TranscribeViewModel : ViewModel() {
    fun uploadAndCheckStatus(filePath: String, token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                DianyaRuntime.initialize()

                // 上传音频文件
                val uploadResp = TranscribeApi.upload(
                    filePath = filePath,
                    transcribeOnly = false,  // 是否仅转写（不进行总结）
                    shortAsr = false,        // 是否使用一句话转写模式
                    model = TranscribeApi.ModelType.QUALITY_V2,  // 使用质量模式 v2
                    token = token
                )

                // 判断响应类型
                val taskId = if (uploadResp.isNormal()) {
                    // 普通转写模式，返回 taskId
                    uploadResp.taskId
                } else if (uploadResp.isOneSentence()) {
                    // 一句话转写模式，直接返回结果
                    Log.d("Transcribe", "一句话转写结果: ${uploadResp.data}")
                    return@launch
                } else {
                    Log.e("Transcribe", "上传失败: ${uploadResp.message}")
                    return@launch
                }

                // 查询任务状态
                val status = TranscribeApi.status(
                    taskId = taskId,
                    shareId = null,  // 使用 taskId 查询，shareId 传 null
                    token = token
                )

                Log.d("Transcribe", "任务状态: ${status.status}")
                Log.d("Transcribe", "转写详情数量: ${status.details.size}")
                
                // 打印转写结果
                status.details.forEach { utterance ->
                    Log.d("Transcribe", 
                        "[${utterance.startTime}s-${utterance.endTime}s] " +
                        "说话人${utterance.speaker}: ${utterance.text}"
                    )
                }

                // 如果有总结结果
                status.summaryMarkdown?.let {
                    Log.d("Transcribe", "总结: $it")
                }

            } catch (ex: DianyaException) {
                Log.e("Transcribe", "SDK 调用失败 [${ex.code}]: ${ex.message}", ex)
            } catch (ex: RuntimeException) {
                Log.e("Transcribe", "SDK 调用失败", ex)
            }
        }
    }
}
```

#### 2. 创建实时转写会话

```kotlin
fun createRealtimeSession(token: String) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            DianyaRuntime.initialize()

            // 创建会话
            val session = TranscribeApi.createSession(
                model = TranscribeApi.ModelType.QUALITY,
                token = token
            )

            Log.d("Transcribe", "会话创建成功")
            Log.d("Transcribe", "任务ID: ${session.taskId}")
            Log.d("Transcribe", "会话ID: ${session.sessionId}")
            Log.d("Transcribe", "最大转写时长: ${session.maxTime}秒")

            // 使用会话ID创建 WebSocket 流（见示例 3）

            // 关闭会话
            val closeResp = TranscribeApi.closeSession(
                taskId = session.taskId,
                token = token,
                timeoutSeconds = -1  // 使用默认超时（30秒）
            )

            Log.d("Transcribe", "会话关闭: ${closeResp.status}")

        } catch (ex: DianyaException) {
            Log.e("Transcribe", "错误 [${ex.code}]: ${ex.message}", ex)
        }
    }
}
```

#### 3. 使用 WebSocket 进行实时转写

```kotlin
fun startRealtimeTranscribe(sessionId: String) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            DianyaRuntime.initialize()

            // 创建 WebSocket 流
            val stream = TranscribeStream(sessionId)
            
            // 启动连接
            stream.start()
            Log.d("Transcribe", "WebSocket 连接已建立")

            // 发送音频数据（二进制）
            val audioData: ByteArray = // ... 从麦克风或文件读取音频数据
            stream.sendBinary(audioData)

            // 读取转写结果（带超时）
            while (true) {
                val message = stream.readNext(timeoutMillis = 5000)  // 5秒超时
                if (message != null) {
                    Log.d("Transcribe", "收到转写结果: $message")
                    // 解析 JSON 获取转写内容
                    // val result = Gson().fromJson(message, TranscribeResult::class.java)
                } else {
                    // 超时或连接关闭
                    break
                }
            }

            // 停止并关闭
            stream.stop()
            stream.close()

        } catch (ex: DianyaException) {
            Log.e("Transcribe", "错误 [${ex.code}]: ${ex.message}", ex)
        }
    }
}
```

#### 4. 翻译功能

```kotlin
fun translateExample(token: String, taskId: String) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            DianyaRuntime.initialize()

            // 翻译纯文本
            val textResult = TranscribeApi.translateText(
                text = "你好，世界！",
                language = TranscribeApi.Language.EN,
                token = token
            )
            Log.d("Translate", "翻译结果: ${textResult.data}")

            // 翻译转写任务
            val transcribeResult = TranscribeApi.translateTranscribe(
                taskId = taskId,
                language = TranscribeApi.Language.JA,
                token = token
            )
            Log.d("Translate", "任务翻译状态: ${transcribeResult.status}")
            Log.d("Translate", "概览翻译: ${transcribeResult.overviewMarkdown}")

        } catch (ex: DianyaException) {
            Log.e("Translate", "错误 [${ex.code}]: ${ex.message}", ex)
        }
    }
}
```

#### 5. 创建总结任务

```kotlin
fun createSummaryExample(token: String) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            DianyaRuntime.initialize()

            // 准备对话数据
            val utterances = listOf(
                TranscribeApi.Utterance().apply {
                    startTime = 0.0
                    endTime = 1.5
                    text = "你好，今天天气不错。"
                    speaker = 0
                },
                TranscribeApi.Utterance().apply {
                    startTime = 1.8
                    endTime = 3.2
                    text = "是的，适合出去走走。"
                    speaker = 1
                }
            )

            // 创建总结任务
            val summaryResp = TranscribeApi.createSummary(
                utterances = utterances,
                token = token
            )

            Log.d("Summary", "总结任务ID: ${summaryResp.taskId}")

            // 查询总结结果
            val status = TranscribeApi.status(
                taskId = summaryResp.taskId,
                shareId = null,
                token = token
            )

            status.summaryMarkdown?.let {
                Log.d("Summary", "总结内容: $it")
            }

        } catch (ex: DianyaException) {
            Log.e("Summary", "错误 [${ex.code}]: ${ex.message}", ex)
        }
    }
}
```

#### 6. 导出转写结果

```kotlin
fun exportExample(token: String, taskId: String) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            DianyaRuntime.initialize()

            // 导出为 PDF
            val pdfData = TranscribeApi.export(
                taskId = taskId,
                type = TranscribeApi.ExportType.SUMMARY,
                format = TranscribeApi.ExportFormat.PDF,
                token = token
            )

            // 保存到文件
            val outputFile = File(context.getExternalFilesDir(null), "summary.pdf")
            outputFile.writeBytes(pdfData)
            Log.d("Export", "导出成功: ${outputFile.absolutePath}")

        } catch (ex: DianyaException) {
            Log.e("Export", "错误 [${ex.code}]: ${ex.message}", ex)
        }
    }
}
```

### Java 示例

```java
import com.dianya.api.*;

public class TranscribeExample {
    public void uploadExample(String filePath, String token) {
        try {
            DianyaRuntime.initialize();

            TranscribeApi.UploadResponse uploadResp = TranscribeApi.upload(
                filePath,
                false,  // transcribeOnly
                false,  // shortAsr
                TranscribeApi.ModelType.QUALITY_V2,
                token
            );

            if (uploadResp.isNormal()) {
                String taskId = uploadResp.taskId;
                TranscribeApi.StatusResponse status = TranscribeApi.status(
                    taskId,
                    null,  // shareId
                    token
                );

                System.out.println("任务状态: " + status.status);
                for (TranscribeApi.Utterance utterance : status.details) {
                    System.out.println(String.format(
                        "[%.3fs-%.3fs] 说话人%d: %s",
                        utterance.startTime,
                        utterance.endTime,
                        utterance.speaker,
                        utterance.text
                    ));
                }
            }

        } catch (DianyaException e) {
            System.err.println("错误 [" + e.getCode() + "]: " + e.getMessage());
        }
    }
}
```

## 注意事项

### 生命周期管理

- **初始化**：在调用任何 API 前必须先调用 `DianyaRuntime.initialize()`。多次调用是安全的（幂等）。
- **销毁**：应用退出或不再需要 SDK 时，可调用 `DianyaRuntime.shutdown()` 释放资源。非必须，但建议在合适时机调用。

### 线程安全

- **阻塞调用**：所有 API 方法都会阻塞当前线程直到 Rust 侧完成任务，**必须**在后台线程中调用。
- **Android 最佳实践**：在 Kotlin 中使用 `viewModelScope.launch(Dispatchers.IO)` 或 `lifecycleScope.launch(Dispatchers.IO)`，在 Java 中使用 `ExecutorService` 或 `AsyncTask`（已废弃，不推荐）。
- **线程模型**：Rust 侧 Tokio runtime 采用多线程构建（默认 4 个 worker），适合同时处理多个请求。

### 错误处理

- 所有方法都可能抛出 `DianyaException`（继承自 `RuntimeException`），包含错误代码和详细消息。
- 建议使用 `try-catch` 捕获异常并处理，避免应用崩溃。
- 网络错误、参数校验失败等都会通过异常返回。

### 其他

- **Token 管理**：SDK 不会缓存 Token，请确保业务侧传入的凭证始终有效。
- **导出文件**：`export()` 返回的 `byte[]` 需由调用方自行保存，例如写入 `FileOutputStream` 或使用文件 I/O。
- **WebSocket 流**：`TranscribeStream` 实现了 `AutoCloseable` 接口，建议使用 `try-with-resources`（Java）或 `use`（Kotlin）确保资源释放。
- **超时设置**：`closeSession()` 和 `TranscribeStream.readNext()` 支持超时参数，传负数使用默认值。

欢迎根据业务需求扩展更多 JNI 接口，新增 Rust 依赖时请先写入工作区根或 `wrapper-jni/Cargo.toml` 的 `[workspace.dependencies]`。

## 打包成 JAR

1. Java 源码位于 `wrapper-jni/java/src/main/java`，已使用 JetBrains 的 `@NotNull/@Nullable` 注解，避免对 Android 依赖。
2. 进入 `wrapper-jni/java`，执行：
   ```bash
   ./gradlew jar   # 或者已安装 gradle 时执行 gradle jar
   ```
   生成的 `build/libs/dianyaapi-jni-<version>.jar` 即可分发，必要时可同时打包 `-sources.jar`。
3. 发布 jar 时，请同时提供对应平台的 `libdianyaapi_jni.so`（位于 Rust 构建输出），上层需要在运行期加载该动态库。
4. 添加aar或jar作为依赖时，需要添加`com.google.code.gson`及`org.jetbrains.annotations`

