use crate::error::{throw_jni_error, throw_message};
use crate::runtime as rt;
use crate::utils::*;
use jni::{
    objects::{JClass, JObject, JString},
    sys::{jboolean, jbyteArray, jint, jstring},
    JNIEnv,
};
use std::ptr;
use transcribe::{
    transcribe::{
        callback as transcribe_callback, create_summary, export as transcribe_export,
        get_share_link, status as transcribe_status, upload,
    },
    translate::{translate_text, translate_transcribe, translate_utterance},
};

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_DianyaRuntime_nativeInitialize(
    mut env: JNIEnv,
    _class: JClass,
) {
    if let Err(err) = rt::initialize() {
        let _ = throw_message(&mut env, err);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_DianyaRuntime_nativeShutdown(
    _env: JNIEnv,
    _class: JClass,
) {
    rt::shutdown();
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_TranscribeApi_nativeUpload(
    mut env: JNIEnv,
    _class: JClass,
    filepath: JString,
    transcribe_only: jboolean,
    short_asr: jboolean,
    model: JString,
    token: JString,
) -> jstring {
    let filepath = match jstring_to_rust(&mut env, filepath) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let model_str = match jstring_to_rust(&mut env, model) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let token = match jstring_to_rust(&mut env, token) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let model = match parse_model_type(&model_str) {
        Ok(m) => m,
        Err(err) => return throw_common(&mut env, &err),
    };

    let response = match block_on_result(
        &mut env,
        upload(
            &filepath,
            jboolean_to_bool(transcribe_only),
            jboolean_to_bool(short_asr),
            model,
            &token,
        ),
    ) {
        Some(value) => value,
        None => return ptr::null_mut(),
    };

    to_jstring(&mut env, response)
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_TranscribeApi_nativeStatus(
    mut env: JNIEnv,
    _class: JClass,
    task_id: JObject,
    share_id: JObject,
    token: JString,
) -> jstring {
    let task_id = match jobject_to_string_option(&mut env, task_id) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let share_id = match jobject_to_string_option(&mut env, share_id) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let token = match jstring_to_rust(&mut env, token) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let response = match block_on_result(
        &mut env,
        transcribe_status(task_id.as_deref(), share_id.as_deref(), &token),
    ) {
        Some(value) => value,
        None => return ptr::null_mut(),
    };

    to_jstring(&mut env, response)
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_TranscribeApi_nativeCallback(
    mut env: JNIEnv,
    _class: JClass,
    request_body: JString,
    token: JString,
) -> jstring {
    let request = match jstring_to_rust(&mut env, request_body) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let token = match jstring_to_rust(&mut env, token) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let request = match parse_callback_request(&request) {
        Ok(req) => req,
        Err(err) => return throw_common(&mut env, &err),
    };

    let response = match block_on_result(&mut env, transcribe_callback(&request, &token)) {
        Some(value) => value,
        None => return ptr::null_mut(),
    };

    to_jstring(&mut env, response)
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_TranscribeApi_nativeGetShareLink(
    mut env: JNIEnv,
    _class: JClass,
    task_id: JString,
    expiration_days: jint,
    token: JString,
) -> jstring {
    let task_id = match jstring_to_rust(&mut env, task_id) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let token = match jstring_to_rust(&mut env, token) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let expiration = if expiration_days < 0 {
        None
    } else {
        Some(expiration_days)
    };

    let response = match block_on_result(&mut env, get_share_link(&task_id, expiration, &token)) {
        Some(value) => value,
        None => return ptr::null_mut(),
    };

    to_jstring(&mut env, response)
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_TranscribeApi_nativeCreateSummary(
    mut env: JNIEnv,
    _class: JClass,
    utterances_json: JString,
    token: JString,
) -> jstring {
    let utterances = match jstring_to_rust(&mut env, utterances_json) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let token = match jstring_to_rust(&mut env, token) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let utterances = match parse_utterances(&utterances) {
        Ok(value) => value,
        Err(err) => return throw_common(&mut env, &err),
    };

    let response = match block_on_result(&mut env, create_summary(utterances, &token)) {
        Some(value) => value,
        None => return ptr::null_mut(),
    };

    to_jstring(&mut env, response)
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_TranscribeApi_nativeExport(
    mut env: JNIEnv,
    _class: JClass,
    task_id: JString,
    export_type: JString,
    export_format: JString,
    token: JString,
) -> jbyteArray {
    let task_id = match jstring_to_rust(&mut env, task_id) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err) as jbyteArray,
    };

    let type_str = match jstring_to_rust(&mut env, export_type) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err) as jbyteArray,
    };

    let format_str = match jstring_to_rust(&mut env, export_format) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err) as jbyteArray,
    };

    let token = match jstring_to_rust(&mut env, token) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err) as jbyteArray,
    };

    let export_type = match parse_export_type(&type_str) {
        Ok(value) => value,
        Err(err) => return throw_common(&mut env, &err) as jbyteArray,
    };

    let export_format = match parse_export_format(&format_str) {
        Ok(value) => value,
        Err(err) => return throw_common(&mut env, &err) as jbyteArray,
    };

    let bytes = match block_on_result(
        &mut env,
        transcribe_export(&task_id, export_type, export_format, &token),
    ) {
        Some(value) => value,
        None => return ptr::null_mut(),
    };

    let data = bytes.to_vec();

    match env.byte_array_from_slice(&data) {
        Ok(array) => array.into_raw(),
        Err(err) => {
            let _ = throw_jni_error(&mut env, &err);
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_TranscribeApi_nativeTranslateText(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
    language: JString,
    token: JString,
) -> jstring {
    let text = match jstring_to_rust(&mut env, text) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let lang_str = match jstring_to_rust(&mut env, language) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let token = match jstring_to_rust(&mut env, token) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let language = match parse_language(&lang_str) {
        Ok(value) => value,
        Err(err) => return throw_common(&mut env, &err),
    };

    let response = match block_on_result(&mut env, translate_text(&text, language, &token)) {
        Some(value) => value,
        None => return ptr::null_mut(),
    };

    to_jstring(&mut env, response)
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_TranscribeApi_nativeTranslateUtterances(
    mut env: JNIEnv,
    _class: JClass,
    utterances_json: JString,
    language: JString,
    token: JString,
) -> jstring {
    let utterances_json = match jstring_to_rust(&mut env, utterances_json) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let lang_str = match jstring_to_rust(&mut env, language) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let token = match jstring_to_rust(&mut env, token) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let utterances = match parse_utterances(&utterances_json) {
        Ok(value) => value,
        Err(err) => return throw_common(&mut env, &err),
    };

    let language = match parse_language(&lang_str) {
        Ok(value) => value,
        Err(err) => return throw_common(&mut env, &err),
    };

    let response =
        match block_on_result(&mut env, translate_utterance(utterances, language, &token)) {
            Some(value) => value,
            None => return ptr::null_mut(),
        };

    to_jstring(&mut env, response)
}

#[no_mangle]
pub extern "system" fn Java_com_dianya_api_TranscribeApi_nativeTranslateTranscribe(
    mut env: JNIEnv,
    _class: JClass,
    task_id: JString,
    language: JString,
    token: JString,
) -> jstring {
    let task_id = match jstring_to_rust(&mut env, task_id) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let lang_str = match jstring_to_rust(&mut env, language) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let token = match jstring_to_rust(&mut env, token) {
        Ok(value) => value,
        Err(err) => return throw_string_error(&mut env, err),
    };

    let language = match parse_language(&lang_str) {
        Ok(value) => value,
        Err(err) => return throw_common(&mut env, &err),
    };

    let response = match block_on_result(&mut env, translate_transcribe(&task_id, language, &token))
    {
        Some(value) => value,
        None => return ptr::null_mut(),
    };

    to_jstring(&mut env, response)
}
