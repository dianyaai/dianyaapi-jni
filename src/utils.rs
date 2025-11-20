use crate::{
    error::{throw_common_error, throw_jni_error, throw_message},
    runtime as rt,
};
use common::Error;
use jni::{
    objects::{JObject, JString},
    sys::{jboolean, jstring},
    JNIEnv,
};
use serde::Deserialize;
use std::future::Future;
use transcribe::transcribe::{CallbackRequest, ExportFormat, ExportType, ModelType};
use transcribe::translate::Language;
use transcribe::Utterance;

#[derive(Deserialize)]
struct UtterancesWrapper {
    utterances: Vec<Utterance>,
}

pub fn parse_model_type(input: &str) -> Result<ModelType, Error> {
    match input.trim().to_lowercase().as_str() {
        "speed" => Ok(ModelType::Speed),
        "quality" => Ok(ModelType::Quality),
        "quality_v2" => Ok(ModelType::QualityV2),
        other => Err(Error::InvalidInput(format!("Unknown model type: {other}"))),
    }
}

pub fn parse_export_type(input: &str) -> Result<ExportType, Error> {
    match input.trim().to_lowercase().as_str() {
        "transcript" => Ok(ExportType::Transcript),
        "overview" => Ok(ExportType::Overview),
        "summary" => Ok(ExportType::Summary),
        other => Err(Error::InvalidInput(format!("Unknown export type: {other}"))),
    }
}

pub fn parse_export_format(input: &str) -> Result<ExportFormat, Error> {
    match input.trim().to_lowercase().as_str() {
        "pdf" => Ok(ExportFormat::Pdf),
        "txt" => Ok(ExportFormat::Txt),
        "docx" => Ok(ExportFormat::Docx),
        other => Err(Error::InvalidInput(format!(
            "Unknown export format: {other}"
        ))),
    }
}

pub fn parse_language(input: &str) -> Result<Language, Error> {
    match input.trim().to_lowercase().as_str() {
        "zh" | "chinese" | "zh_cn" | "chinese_simplified" => Ok(Language::ChineseSimplified),
        "en" | "english" | "en_us" => Ok(Language::EnglishUS),
        "ja" | "japanese" => Ok(Language::Japanese),
        "ko" | "korean" => Ok(Language::Korean),
        "fr" | "french" => Ok(Language::French),
        "de" | "german" => Ok(Language::German),
        other => Err(Error::InvalidInput(format!(
            "Unknown language type: {other}"
        ))),
    }
}

pub fn parse_utterances(json: &str) -> Result<Vec<Utterance>, Error> {
    if let Ok(request) = serde_json::from_str::<UtterancesWrapper>(json) {
        return Ok(request.utterances);
    }

    serde_json::from_str::<Vec<Utterance>>(json)
        .map_err(|e| Error::InvalidInput(format!("Failed to parse utterances: {e}")))
}

pub fn parse_callback_request(json: &str) -> Result<CallbackRequest, Error> {
    serde_json::from_str::<CallbackRequest>(json)
        .map_err(|e| Error::InvalidInput(format!("Failed to parse callback request: {e}")))
}

pub fn block_on_result<F, T>(env: &mut JNIEnv, fut: F) -> Option<T>
where
    F: Future<Output = Result<T, Error>>,
{
    let runtime = match rt::runtime() {
        Ok(rt) => rt,
        Err(err) => {
            let _ = throw_message(env, err);
            return None;
        }
    };

    match runtime.block_on(fut) {
        Ok(value) => Some(value),
        Err(err) => {
            let _ = throw_common_error(env, &err);
            None
        }
    }
}

pub fn jstring_to_rust(env: &mut JNIEnv, value: JString) -> Result<String, String> {
    env.get_string(&value)
        .map(|s| s.into())
        .map_err(|e| format!("Failed to read string: {e}"))
}

pub fn jobject_to_string_option(env: &mut JNIEnv, obj: JObject) -> Result<Option<String>, String> {
    if obj.is_null() {
        Ok(None)
    } else {
        let jstr = JString::from(obj);
        jstring_to_rust(env, jstr).map(Some)
    }
}

pub fn jboolean_to_bool(value: jboolean) -> bool {
    value != 0
}

pub fn throw_common(env: &mut JNIEnv, err: &Error) -> jstring {
    let _ = throw_common_error(env, err);
    std::ptr::null_mut()
}

pub fn throw_string_error(env: &mut JNIEnv, err: String) -> jstring {
    let _ = throw_message(env, err);
    std::ptr::null_mut()
}

pub fn to_jstring<T>(env: &mut JNIEnv, value: T) -> jstring
where
    T: serde::Serialize,
{
    match serde_json::to_string(&value) {
        Ok(json) => match env.new_string(json) {
            Ok(result) => result.into_raw(),
            Err(err) => {
                let _ = throw_jni_error(env, &err);
                std::ptr::null_mut()
            }
        },
        Err(err) => {
            let error: Error = err.into();
            let _ = throw_common_error(env, &error);
            std::ptr::null_mut()
        }
    }
}
