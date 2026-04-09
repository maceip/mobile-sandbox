use core::ffi::c_char;

static CORY_RUST_RUNTIME: &[u8] = b"Rust staticlib via cargo-ndk\0";

#[unsafe(no_mangle)]
pub extern "C" fn cory_rust_runtime_name() -> *const c_char {
    CORY_RUST_RUNTIME.as_ptr().cast()
}

#[unsafe(no_mangle)]
pub extern "C" fn cory_rust_add(lhs: i32, rhs: i32) -> i32 {
    lhs + rhs
}
