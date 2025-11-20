use once_cell::sync::OnceCell;
use std::sync::{Arc, Mutex};
use tokio::runtime::{Builder, Runtime};

static RUNTIME: OnceCell<Mutex<Option<Arc<Runtime>>>> = OnceCell::new();

pub fn initialize() -> Result<(), String> {
    let cell = RUNTIME.get_or_init(|| Mutex::new(None));

    let mut guard = cell.lock().map_err(|_| {
        "Tokio runtime lock has been poisoned, please restart the process".to_string()
    })?;

    if guard.is_none() {
        let runtime = Builder::new_multi_thread()
            .worker_threads(4)
            .enable_all()
            .build()
            .map_err(|e| format!("Failed to create Tokio runtime: {e}"))?;
        *guard = Some(Arc::new(runtime));
    }

    Ok(())
}

pub fn runtime() -> Result<Arc<Runtime>, String> {
    let cell = RUNTIME.get().ok_or_else(|| {
        "Tokio runtime has not been initialized, please call initialize first".to_string()
    })?;

    let guard = cell.lock().map_err(|_| {
        "Tokio runtime lock has been poisoned, please restart the process".to_string()
    })?;

    guard.clone().ok_or_else(|| {
        "Tokio runtime has not been initialized, please call initialize first".to_string()
    })
}

pub fn shutdown() -> bool {
    if let Some(cell) = RUNTIME.get() {
        if let Ok(mut guard) = cell.lock() {
            return guard.take().is_some();
        }
    }
    false
}
