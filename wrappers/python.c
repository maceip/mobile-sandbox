/*
 * python.c — Python interpreter for Android.
 *
 * Build as a PIE executable linking against libpython3.14.so.
 * Named libpython3.so so Android extracts it to the native lib dir.
 *
 * CMake (add to mobile-sandbox's app/src/main/cpp/CMakeLists.txt):
 *
 *   add_executable(python3 python.c)
 *   target_link_libraries(python3 cory_python_runtime android log)
 *   set_target_properties(python3 PROPERTIES
 *       OUTPUT_NAME "python3"
 *       PREFIX "lib"
 *       SUFFIX ".so"
 *   )
 *
 * At runtime, TerminalBootstrap symlinks:
 *   nativeLibDir/libpython3.so → usr/bin/python3
 *   usr/bin/python → usr/bin/python3
 */
#include <Python.h>

int main(int argc, char *argv[]) {
    return Py_BytesMain(argc, argv);
}
