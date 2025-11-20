use common::Error;
use jni::objects::{JObject, JThrowable, JValue};
use jni::JNIEnv;

const EXCEPTION_CLASS: &str = "com/dianya/api/DianyaException";
const EXCEPTION_CODE_CLASS: &str = "com/dianya/api/DianyaException$Code";
const EXCEPTION_CTOR_SIG: &str = "(Lcom/dianya/api/DianyaException$Code;Ljava/lang/String;)V";
const CODE_VALUE_OF_SIG: &str = "(Ljava/lang/String;)Lcom/dianya/api/DianyaException$Code;";

fn throw_with_code<'a>(
    env: &mut JNIEnv<'a>,
    code: &str,
    message: impl AsRef<str>,
) -> jni::errors::Result<JThrowable<'a>> {
    let jcode = env.new_string(code)?;
    let jmessage = env.new_string(message.as_ref())?;
    let code_name_obj: JObject = jcode.into();
    let message_obj: JObject = jmessage.into();

    let code_enum_obj = env
        .call_static_method(
            EXCEPTION_CODE_CLASS,
            "valueOf",
            CODE_VALUE_OF_SIG,
            &[JValue::Object(&code_name_obj)],
        )?
        .l()?;

    let throwable_obj = env.new_object(
        EXCEPTION_CLASS,
        EXCEPTION_CTOR_SIG,
        &[JValue::Object(&code_enum_obj), JValue::Object(&message_obj)],
    )?;

    let throwable = JThrowable::from(throwable_obj);
    env.throw(throwable)?;
    env.exception_occurred()
}

pub fn throw_message<'a>(
    env: &mut JNIEnv<'a>,
    message: impl AsRef<str>,
) -> jni::errors::Result<JThrowable<'a>> {
    throw_with_code(env, "UNEXPECTED_ERROR", message)
}

pub fn throw_common_error<'a>(
    env: &mut JNIEnv<'a>,
    err: &Error,
) -> jni::errors::Result<JThrowable<'a>> {
    throw_with_code(env, map_error_code(err), err.to_string())
}

pub fn throw_jni_error<'a>(
    env: &mut JNIEnv<'a>,
    err: &jni::errors::Error,
) -> jni::errors::Result<JThrowable<'a>> {
    throw_with_code(env, "JNI_ERROR", format!("JNI Error: {err}"))
}

fn map_error_code(err: &Error) -> &'static str {
    match err {
        Error::WsError(_) => "WS_ERROR",
        Error::HttpError(_) => "HTTP_ERROR",
        Error::ServerError(_) => "SERVER_ERROR",
        Error::InvalidInput(_) => "INVALID_INPUT",
        Error::InvalidResponse(_) => "INVALID_RESPONSE",
        Error::InvalidToken(_) => "INVALID_TOKEN",
        Error::InvalidApiKey(_) => "INVALID_API_KEY",
        Error::JsonError(_) => "JSON_ERROR",
        Error::OtherError(_) => "OTHER_ERROR",
    }
}
