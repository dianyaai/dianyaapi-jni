use crate::error::{throw_common_error, throw_jni_error, throw_message};
use crate::runtime as rt;
use crate::utils::{
    block_on_result, jstring_to_rust, parse_model_type, throw_common, throw_string_error,
    to_jstring,
};
use common::Error;
use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jlong, jstring},
    JNIEnv,
};
use std::future::Future;
use std::sync::MutexGuard;
use std::{
    ptr,
    sync::{mpsc, Arc, Mutex},
    time::Duration,
};
use tokio::task::JoinHandle;
use tokio_stream::StreamExt;
use transcribe::transcribe::{close_session, create_session, TranscribeWs};
use tungstenite::Message;

type StreamHandle = Mutex<JniTranscribeStream>;

pub struct JniTranscribeStream {
    ws: TranscribeWs,
    reader: Option<JoinHandle<()>>,
    messages_rx: Option<Arc<Mutex<mpsc::Receiver<String>>>>,
}

impl JniTranscribeStream {
    fn new(session_id: &str) -> Self {
        Self {
            ws: TranscribeWs::new(session_id),
            reader: None,
            messages_rx: None,
        }
    }

    async fn start(&mut self) -> Result<(), Error> {
        if self.reader.is_some() {
            return Err(Error::OtherError("WebSocket stream already started".into()));
        }

        let runtime = rt::runtime().map_err(Error::OtherError)?;

        self.ws.start().await?;
        let stream = self.ws.subscribe()?;

        let (tx, rx) = mpsc::channel::<String>();
        let receiver = Arc::new(Mutex::new(rx));
        self.messages_rx = Some(Arc::clone(&receiver));

        let handle = runtime.spawn(async move {
            let mut stream = stream;
            while let Some(item) = stream.next().await {
                if tx.send(item.to_string()).is_err() {
                    break;
                }
            }
        });

        self.reader = Some(handle);

        Ok(())
    }

    fn stop(&mut self) {
        self.ws.stop();

        if let Some(handle) = self.reader.take() {
            handle.abort();
        }

        self.messages_rx.take();
    }

    async fn write_binary(&mut self, payload: Vec<u8>) -> Result<(), Error> {
        self.ws.write(Message::Binary(payload.into())).await
    }

    async fn write_text(&mut self, text: String) -> Result<(), Error> {
        self.ws.write(Message::Text(text.into())).await
    }

    fn clone_receiver(&self) -> Result<Arc<Mutex<mpsc::Receiver<String>>>, Error> {
        self.messages_rx
            .as_ref()
            .map(Arc::clone)
            .ok_or_else(|| Error::OtherError("WebSocket stream has not been started".into()))
    }

    fn wait_for_message(
        receiver: Arc<Mutex<mpsc::Receiver<String>>>,
        timeout: Option<Duration>,
    ) -> Result<Option<String>, Error> {
        let maybe_message = {
            let receiver = receiver
                .lock()
                .map_err(|_| Error::OtherError("Failed to lock receiver".into()))?;
            match timeout {
                Some(duration) => match receiver.recv_timeout(duration) {
                    Ok(msg) => Some(msg),
                    Err(mpsc::RecvTimeoutError::Timeout) => None,
                    Err(mpsc::RecvTimeoutError::Disconnected) => None,
                },
                None => match receiver.recv() {
                    Ok(msg) => Some(msg),
                    Err(_) => None,
                },
            }
        };

        Ok(maybe_message)
    }
}

impl Drop for JniTranscribeStream {
    fn drop(&mut self) {
        self.stop();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_TranscribeStream_nativeCreateSession(
    mut env: JNIEnv,
    _class: JClass,
    model: JString,
    token: JString,
) -> jstring {
    let model = match jstring_to_rust(&mut env, model) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let token = match jstring_to_rust(&mut env, token) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let model = match parse_model_type(&model) {
        Ok(m) => m,
        Err(err) => return throw_common(&mut env, &err),
    };

    let response = match block_on_result(&mut env, create_session(model, &token)) {
        Some(value) => value,
        None => return ptr::null_mut(),
    };

    to_jstring(&mut env, response)
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_TranscribeStream_nativeCloseSession(
    mut env: JNIEnv,
    _class: JClass,
    task_id: JString,
    token: JString,
    timeout_seconds: jlong,
) -> jstring {
    let task_id = match jstring_to_rust(&mut env, task_id) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let token = match jstring_to_rust(&mut env, token) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let timeout = if timeout_seconds < 0 {
        None
    } else {
        Some(timeout_seconds as u64)
    };

    let response = match block_on_result(&mut env, close_session(&task_id, &token, timeout)) {
        Some(value) => value,
        None => return ptr::null_mut(),
    };

    to_jstring(&mut env, response)
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_TranscribeStream_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    session_id: JString,
) -> jlong {
    let session_id: String = match env.get_string(&session_id) {
        Ok(value) => value.into(),
        Err(err) => {
            let _ = throw_jni_error(&mut env, &err);
            return 0;
        }
    };

    match rt::runtime() {
        Ok(_) => {}
        Err(err) => {
            let _ = throw_message(&mut env, err);
            return 0;
        }
    }

    let stream = Box::new(Mutex::new(JniTranscribeStream::new(&session_id)));
    Box::into_raw(stream) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_TranscribeStream_nativeDestroy(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    match unsafe { take_stream(handle) } {
        Ok(stream) => drop(stream),
        Err(err) => {
            let _ = throw_message(&mut env, err);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_TranscribeStream_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    runtime_call(
        &mut env,
        handle,
        (),
        |_, _| Some(()),
        |mut stream, _| async move { stream.start().await },
    );
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_TranscribeStream_nativeStop(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let mutex = match unsafe { stream_ptr(handle) } {
        Ok(mutex) => mutex,
        Err(err) => {
            let _ = throw_message(&mut env, err);
            return;
        }
    };

    match mutex.lock() {
        Ok(mut stream) => stream.stop(),
        Err(_) => {
            let _ = throw_message(&mut env, "Stream handle lock has been poisoned");
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_TranscribeStream_nativeSendBinary(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
) {
    runtime_call(
        &mut env,
        handle,
        data,
        |env, data| match env.convert_byte_array(data) {
            Ok(bytes) => Some(bytes),
            Err(err) => {
                let _ = throw_jni_error(env, &err);
                None
            }
        },
        |mut stream, payload| async move { stream.write_binary(payload).await },
    );
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_TranscribeStream_nativeSendText(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    text: JString,
) {
    runtime_call(
        &mut env,
        handle,
        text,
        |env, text| match env.get_string(&text) {
            Ok(value) => Some(value.into()),
            Err(err) => {
                let _ = throw_jni_error(env, &err);
                None
            }
        },
        |mut stream, text| async move { stream.write_text(text).await },
    );
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_TranscribeStream_nativeRead(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    timeout_millis: jlong,
) -> jstring {
    let timeout = if timeout_millis < 0 {
        None
    } else {
        Some(Duration::from_millis(timeout_millis as u64))
    };

    let mutex = match unsafe { stream_ptr(handle) } {
        Ok(mutex) => mutex,
        Err(err) => {
            let _ = throw_message(&mut env, err);
            return ptr::null_mut();
        }
    };

    let receiver = match mutex.lock() {
        Ok(stream) => {
            let result = stream.clone_receiver();
            drop(stream);
            match result {
                Ok(rx) => rx,
                Err(err) => {
                    let _ = throw_common_error(&mut env, &err);
                    return ptr::null_mut();
                }
            }
        }
        Err(_) => {
            let _ = throw_message(&mut env, "Stream handle lock has been poisoned");
            return ptr::null_mut();
        }
    };

    match JniTranscribeStream::wait_for_message(receiver, timeout) {
        Ok(Some(message)) => match env.new_string(message) {
            Ok(result) => result.into_raw(),
            Err(err) => {
                let _ = throw_jni_error(&mut env, &err);
                ptr::null_mut()
            }
        },
        Ok(None) => ptr::null_mut(),
        Err(err) => {
            let _ = throw_common_error(&mut env, &err);
            ptr::null_mut()
        }
    }
}

unsafe fn stream_ptr(handle: jlong) -> Result<&'static StreamHandle, String> {
    if handle == 0 {
        return Err("Stream handle is null".into());
    }

    let ptr = handle as *mut StreamHandle;
    if ptr.is_null() {
        Err("Stream handle is null".into())
    } else {
        Ok(&*ptr)
    }
}

unsafe fn take_stream(handle: jlong) -> Result<Box<StreamHandle>, String> {
    if handle == 0 {
        return Err("Stream handle is null".into());
    }

    let ptr = handle as *mut StreamHandle;
    if ptr.is_null() {
        Err("Stream handle is null".into())
    } else {
        Ok(Box::from_raw(ptr))
    }
}

fn runtime_call<'a, P1, F1, R1, F2, R2>(env: &mut JNIEnv, handle: jlong, p: P1, f1: F1, f2: F2)
where
    F1: FnOnce(&mut JNIEnv, P1) -> Option<R1>,
    R2: Future<Output = Result<(), Error>>,
    F2: FnOnce(MutexGuard<'a, JniTranscribeStream>, R1) -> R2,
{
    match rt::runtime() {
        Ok(runtime) => {
            if let Some(param) = f1(env, p) {
                let mutex = match unsafe { stream_ptr(handle) } {
                    Ok(mutex) => mutex,
                    Err(err) => {
                        let _ = throw_message(env, err);
                        return;
                    }
                };

                match mutex.lock() {
                    Ok(stream) => {
                        if let Err(err) = runtime.block_on(f2(stream, param)) {
                            let _ = throw_common_error(env, &err);
                        }
                    }
                    Err(_) => {
                        let _ = throw_message(env, "Stream handle lock has been poisoned");
                    }
                }
            }
        }
        Err(err) => {
            let _ = throw_message(env, err);
        }
    }
}
