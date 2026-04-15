cmake_minimum_required(VERSION 3.22.1)
project(Cory LANGUAGES C CXX)

get_filename_component(cory_root "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
get_filename_component(libgit2_src "${cory_root}/third_party/libgit2" ABSOLUTE)
get_filename_component(cory_python_prefix_dir "${cory_root}/third_party/python-android/prefix" ABSOLUTE)
get_filename_component(cory_python_include_dir "${cory_python_prefix_dir}/include/python3.14" ABSOLUTE)
get_filename_component(cory_python_lib_dir "${cory_python_prefix_dir}/lib" ABSOLUTE)
get_filename_component(cory_node_root "${cory_root}/third_party/node24-android" ABSOLUTE)
get_filename_component(cory_node_include_src "${cory_node_root}/include/src" ABSOLUTE)
get_filename_component(cory_node_include_v8 "${cory_node_root}/include/deps/v8" ABSOLUTE)
get_filename_component(cory_node_include_uv "${cory_node_root}/include/deps/uv" ABSOLUTE)
get_filename_component(cory_node_lib_dir "${cory_node_root}/lib/${ANDROID_ABI}" ABSOLUTE)
get_filename_component(cory_rust_root "${cory_root}/rust/cory_rust" ABSOLUTE)
set(cory_rust_lib "${cory_rust_root}/target/aarch64-linux-android/release/libcory_rust.a")

# ---------- OpenSSL from CPython's Android prefix ----------
# CPython's official Android build (Platforms/Android) bundles a
# statically-linked OpenSSL that we reuse for libgit2's HTTPS transport.
# This avoids building a second TLS stack just for git.
set(OPENSSL_ROOT_DIR "${cory_python_prefix_dir}" CACHE PATH "OpenSSL root from CPython prefix")
set(OPENSSL_INCLUDE_DIR "${cory_python_prefix_dir}/include" CACHE PATH "OpenSSL headers")
set(OPENSSL_SSL_LIBRARY "${cory_python_prefix_dir}/lib/libssl.a" CACHE FILEPATH "OpenSSL libssl")
set(OPENSSL_CRYPTO_LIBRARY "${cory_python_prefix_dir}/lib/libcrypto.a" CACHE FILEPATH "OpenSSL libcrypto")
set(OPENSSL_USE_STATIC_LIBS ON CACHE BOOL "Use static OpenSSL from CPython prefix")

# Imported OpenSSL targets so libgit2 finds everything via find_package(OpenSSL)
if(NOT TARGET OpenSSL::Crypto)
    add_library(OpenSSL::Crypto STATIC IMPORTED GLOBAL)
    set_target_properties(OpenSSL::Crypto PROPERTIES
        IMPORTED_LOCATION "${OPENSSL_CRYPTO_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${OPENSSL_INCLUDE_DIR}"
    )
endif()
if(NOT TARGET OpenSSL::SSL)
    add_library(OpenSSL::SSL STATIC IMPORTED GLOBAL)
    set_target_properties(OpenSSL::SSL PROPERTIES
        IMPORTED_LOCATION "${OPENSSL_SSL_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${OPENSSL_INCLUDE_DIR}"
        INTERFACE_LINK_LIBRARIES "OpenSSL::Crypto"
    )
endif()

# ---------- libgit2 build options ----------
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build libgit2 as a static library" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "Disable libgit2 tests" FORCE)
set(BUILD_CLI OFF CACHE BOOL "Disable libgit2's own CLI (we build our own from examples)" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "We build examples manually below as the git CLI" FORCE)
set(BUILD_FUZZERS OFF CACHE BOOL "Disable libgit2 fuzzers" FORCE)
set(BUILD_BENCHMARKS OFF CACHE BOOL "Disable libgit2 benchmarks" FORCE)
set(ENABLE_WERROR OFF CACHE BOOL "Do not fail on libgit2 warnings" FORCE)
# HTTPS via the OpenSSL we just imported from CPython's Android prefix.
# USE_HTTPS=ON means "auto-detect a TLS backend"; find_package(OpenSSL)
# above makes libgit2 pick OpenSSL.
set(USE_HTTPS ON CACHE STRING "libgit2 HTTPS backend" FORCE)
set(USE_SSH OFF CACHE STRING "Disable libgit2 SSH for now" FORCE)
set(USE_SHA256 "OpenSSL" CACHE STRING "SHA256 via OpenSSL we already link" FORCE)
set(USE_REGEX builtin CACHE STRING "Use the builtin regex backend" FORCE)
set(USE_COMPRESSION builtin CACHE STRING "Use bundled zlib in libgit2" FORCE)
set(USE_BUNDLED_ZLIB ON CACHE BOOL "Use libgit2's bundled zlib" FORCE)

add_subdirectory(
    "${libgit2_src}"
    "${CMAKE_CURRENT_BINARY_DIR}/libgit2/${CMAKE_BUILD_TYPE}/${ANDROID_ABI}"
    EXCLUDE_FROM_ALL
)

add_library(cory_libgit2 INTERFACE)
target_include_directories(cory_libgit2 INTERFACE "${libgit2_src}/include")
target_link_libraries(cory_libgit2 INTERFACE libgit2package OpenSSL::SSL OpenSSL::Crypto)

set(CORY_PYTHON_VERSION "3.14.3" CACHE STRING "Official CPython Android package version vendored into Cory")

add_library(cory_python_runtime SHARED IMPORTED GLOBAL)
set_target_properties(
    cory_python_runtime
    PROPERTIES
    IMPORTED_LOCATION "${cory_python_lib_dir}/libpython3.14.so"
    INTERFACE_INCLUDE_DIRECTORIES "${cory_python_include_dir}"
)

add_library(cory_libpython INTERFACE)
target_include_directories(
    cory_libpython
    INTERFACE
    "${cory_python_include_dir}"
)
target_link_libraries(cory_libpython INTERFACE cory_python_runtime)

option(CORY_ENABLE_NODE_EMBED "Link the prebuilt Android libnode bundle into cory" OFF)
if(CORY_ENABLE_NODE_EMBED)
    add_library(cory_node_runtime STATIC IMPORTED GLOBAL)
    set_target_properties(
        cory_node_runtime
        PROPERTIES
        IMPORTED_LOCATION "${cory_node_lib_dir}/liblibnode.a"
        INTERFACE_INCLUDE_DIRECTORIES "${cory_node_include_src};${cory_node_include_v8};${cory_node_include_uv}"
    )

    add_library(cory_libnode INTERFACE)
    target_include_directories(
        cory_libnode
        INTERFACE
        "${cory_node_include_src}"
        "${cory_node_include_v8}"
        "${cory_node_include_uv}"
    )
    target_link_libraries(
        cory_libnode
        INTERFACE
        cory_node_runtime
        android
        dl
        log
    )
endif()

add_library(cory_rust_runtime STATIC IMPORTED GLOBAL)
set_target_properties(
    cory_rust_runtime
    PROPERTIES
    IMPORTED_LOCATION "${cory_rust_lib}"
)

add_library(cory_librust INTERFACE)
target_link_libraries(
    cory_librust
    INTERFACE
    cory_rust_runtime
    android
    dl
    log
    m
)

option(CORY_ENABLE_LITERT_LM "Enable the vendored LiteRT-LM native super-build" OFF)
set(cory_litert_lm_enabled 0)

option(GENERATE_PROFILES "PGO: -fprofile-generate" OFF)

# ---------- Main JNI library (Python embed, libgit2, Rust FFI) ----------
# Note: libpython_shell.so (the python3 interpreter PIE wrapper) is now
# fetched from S3 as a prebuilt, not compiled here. See the
# shell-deps-arm64-v8a.tgz bundle fetched via scripts/fetch_prebuilt_assets.py.
add_library(cory SHARED cory_python_module.cpp cory_jni.cpp)

target_compile_features(cory PRIVATE cxx_std_23)
target_compile_options(cory PRIVATE -Wall -Wextra -Werror)
target_compile_definitions(
    cory
    PRIVATE
    CORY_PYTHON_VERSION="${CORY_PYTHON_VERSION}"
    CORY_LITERT_LM_ENABLED=${cory_litert_lm_enabled}
)
target_link_options(
    cory
    PRIVATE
    -Wl,--no-undefined-version
    -Wl,--version-script,${CMAKE_CURRENT_SOURCE_DIR}/libcory.map.txt
)
set_target_properties(
    cory
    PROPERTIES
    LINK_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/libcory.map.txt
)
target_link_libraries(
    cory
    PRIVATE
    android
    cory_libgit2
    cory_libpython
    cory_librust
    log
)

if(CORY_ENABLE_NODE_EMBED)
    target_link_libraries(cory PRIVATE cory_libnode)
endif()

if(GENERATE_PROFILES)
    target_compile_options(cory PRIVATE -fprofile-generate -ftemporal-profile -O1)
    target_link_options(cory PRIVATE -fprofile-generate -ftemporal-profile)
    target_compile_definitions(cory PRIVATE GENERATE_PROFILES)
elseif(USE_PROFILE)
    target_compile_options(cory PRIVATE)
    target_link_options(cory PRIVATE -Wl,--symbol-ordering-file=${USE_PROFILE} -Wl,--no-warn-symbol-ordering)
endif()

# ---------- git CLI (PIE executable built from libgit2's examples/) ----------
# libgit2 ships an `lg2` dispatcher binary in examples/ that implements a
# git-like CLI using the libgit2 C API. We compile those sources plus our
# own worktree.c (libgit2's examples never shipped worktree support) as a
# real `git` binary that shares libgit2 + OpenSSL with the rest of the app.
#
# Commands available: add, blame, cat-file, checkout, clone, commit, config,
# describe, diff, fetch, for-each-ref, general, index-pack, init, log,
# ls-files, ls-remote, merge, push, remote, rev-list, rev-parse, show-index,
# stash, status, tag, worktree.
#
# Output: CMake writes the binary directly as `git` into the generated
# assets source dir (CORY_ASSETS_DIR/python/bin/git). That dir is registered
# in app/build.gradle as `sourceSets.main.assets.srcDir`, so AGP's
# mergeAssets task picks the file up at merge time — *provided* mergeAssets
# is forced to run after CMake (see the afterEvaluate block in build.gradle
# that wires mergeDebugAssets → buildCMake*).
set(libgit2_examples_dir "${libgit2_src}/examples")
set(cory_git_extras_dir "${CMAKE_CURRENT_SOURCE_DIR}/git_extras")
set(libgit2_example_sources
    ${libgit2_examples_dir}/add.c
    ${libgit2_examples_dir}/args.c
    ${libgit2_examples_dir}/blame.c
    ${libgit2_examples_dir}/cat-file.c
    ${libgit2_examples_dir}/checkout.c
    ${libgit2_examples_dir}/clone.c
    ${libgit2_examples_dir}/commit.c
    ${libgit2_examples_dir}/common.c
    ${libgit2_examples_dir}/config.c
    ${libgit2_examples_dir}/describe.c
    ${libgit2_examples_dir}/diff.c
    ${libgit2_examples_dir}/fetch.c
    ${libgit2_examples_dir}/for-each-ref.c
    ${libgit2_examples_dir}/general.c
    ${libgit2_examples_dir}/index-pack.c
    ${libgit2_examples_dir}/init.c
    ${libgit2_examples_dir}/lg2.c
    ${libgit2_examples_dir}/log.c
    ${libgit2_examples_dir}/ls-files.c
    ${libgit2_examples_dir}/ls-remote.c
    ${libgit2_examples_dir}/merge.c
    ${libgit2_examples_dir}/push.c
    ${libgit2_examples_dir}/remote.c
    ${libgit2_examples_dir}/rev-list.c
    ${libgit2_examples_dir}/rev-parse.c
    ${libgit2_examples_dir}/show-index.c
    ${libgit2_examples_dir}/stash.c
    ${libgit2_examples_dir}/status.c
    ${libgit2_examples_dir}/tag.c
    ${cory_git_extras_dir}/worktree.c
)

# CMake target name is `git_cli` (not `git`) to avoid any risk of
# collision with internal targets from the libgit2 subdirectory or from
# Android's NDK CMake wrapper. The on-disk filename is set by OUTPUT_NAME
# below and ends up as the literal `git` in the assets dir.
add_executable(git_cli ${libgit2_example_sources})
target_include_directories(git_cli PRIVATE "${libgit2_examples_dir}" "${cory_git_extras_dir}")
target_compile_options(git_cli PRIVATE
    -Wall
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-unused-function
    -Wno-sign-compare
)
target_link_libraries(git_cli PRIVATE cory_libgit2)

# Output the executable directly into the generated assets source dir
# under python/bin/git. CORY_ASSETS_DIR is passed from app/build.gradle
# via -D (defaulting to the same absolute path if not supplied).
if(NOT DEFINED CORY_ASSETS_DIR)
    set(CORY_ASSETS_DIR "${cory_root}/app/build/generated/cory/python-assets")
endif()
set(cory_git_assets_bin_dir "${CORY_ASSETS_DIR}/python/bin")
set_target_properties(git_cli PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    LINK_FLAGS "-pie"
    RUNTIME_OUTPUT_DIRECTORY "${cory_git_assets_bin_dir}"
    OUTPUT_NAME "git"
)
