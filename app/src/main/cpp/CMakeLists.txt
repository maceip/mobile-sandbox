cmake_minimum_required(VERSION 3.22.1)
project(OrderfileDemo LANGUAGES C CXX)

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
set(cory_rust_lib "${cory_rust_root}/target/aarch64-linux-android/debug/libcory_rust.a")

find_package(game-activity REQUIRED CONFIG)

# Export GameActivity_onCreate for the AGDK-style Java activity shell.
set(CMAKE_SHARED_LINKER_FLAGS
    "${CMAKE_SHARED_LINKER_FLAGS} -u GameActivity_onCreate -u Java_com_google_androidgamesdk_GameActivity_initializeNativeCode")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build libgit2 as a static library" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "Disable libgit2 tests" FORCE)
set(BUILD_CLI OFF CACHE BOOL "Disable the libgit2 CLI" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "Disable libgit2 examples" FORCE)
set(BUILD_FUZZERS OFF CACHE BOOL "Disable libgit2 fuzzers" FORCE)
set(BUILD_BENCHMARKS OFF CACHE BOOL "Disable libgit2 benchmarks" FORCE)
set(ENABLE_WERROR OFF CACHE BOOL "Do not fail on libgit2 warnings" FORCE)
set(USE_HTTPS OFF CACHE STRING "Disable libgit2 HTTPS for the Android sample" FORCE)
set(USE_SSH OFF CACHE STRING "Disable libgit2 SSH for the Android sample" FORCE)
set(USE_SHA256 builtin CACHE STRING "Use the builtin SHA256 backend" FORCE)
set(USE_REGEX builtin CACHE STRING "Use the builtin regex backend" FORCE)
set(USE_COMPRESSION builtin CACHE STRING "Use bundled zlib in libgit2" FORCE)

add_subdirectory(
    "${libgit2_src}"
    "${CMAKE_CURRENT_BINARY_DIR}/libgit2/${CMAKE_BUILD_TYPE}/${ANDROID_ABI}"
    EXCLUDE_FROM_ALL
)

add_library(cory_libgit2 INTERFACE)
target_include_directories(cory_libgit2 INTERFACE "${libgit2_src}/include")
target_link_libraries(cory_libgit2 INTERFACE libgit2package)

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
#set(USE_PROFILE "${CMAKE_SOURCE_DIR}/demo.orderfile")

add_library(orderfiledemo SHARED android_main.cpp cory_python_module.cpp orderfile.cpp)

target_compile_features(orderfiledemo PRIVATE cxx_std_23)
target_compile_options(orderfiledemo PRIVATE -Wall -Wextra -Werror)
target_compile_definitions(
    orderfiledemo
    PRIVATE
    CORY_PYTHON_VERSION="${CORY_PYTHON_VERSION}"
    CORY_LITERT_LM_ENABLED=${cory_litert_lm_enabled}
)
target_link_options(
    orderfiledemo
    PRIVATE
    -Wl,--no-undefined-version
    -Wl,--version-script,${CMAKE_CURRENT_SOURCE_DIR}/liborderfiledemo.map.txt
)
set_target_properties(
    orderfiledemo
    PROPERTIES
    LINK_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/liborderfiledemo.map.txt
)
target_link_libraries(
    orderfiledemo
    PRIVATE
    android
    cory_libgit2
    cory_libnode
    cory_libpython
    cory_librust
    game-activity::game-activity_static
    log
)

if(GENERATE_PROFILES)
    # Generating profiles requires any optimization flag aside from -O0.
    target_compile_options(orderfiledemo PRIVATE -fprofile-generate -ftemporal-profile -O1)
    target_link_options(orderfiledemo PRIVATE -fprofile-generate -ftemporal-profile)
    target_compile_definitions(orderfiledemo PRIVATE GENERATE_PROFILES)
elseif(USE_PROFILE)
    target_compile_options(orderfiledemo PRIVATE)
    target_link_options(orderfiledemo PRIVATE -Wl,--symbol-ordering-file=${USE_PROFILE} -Wl,--no-warn-symbol-ordering)
endif()
