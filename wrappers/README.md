# Wrapper Binaries

These source files are built by the host app (mobile-sandbox), not by
this library module. They live here as reference copies.

## python.c

Python interpreter for Android. Links against `libpython3.14.so`
and calls `Py_BytesMain()`. Build as a PIE executable named
`libpython3.so` so Android extracts it to the native lib directory.

### Adding to mobile-sandbox's CMakeLists.txt

```cmake
# After the existing cory_python_runtime target:
add_executable(python3 ${CMAKE_CURRENT_SOURCE_DIR}/python.c)
target_link_libraries(python3 cory_python_runtime android log)
set_target_properties(python3 PROPERTIES
    OUTPUT_NAME "python3"
    PREFIX "lib"
    SUFFIX ".so"
)
```

### What this enables

```
$ python3                     # interactive REPL
$ python3 app.py              # run scripts
$ python3 -m pip install flask # install packages
$ python3 -c "print('hi')"   # one-liners
```

## npm

npm is NOT a compiled binary — it's a JavaScript package. The host app
needs to bundle the npm tarball in assets and extract it to
`filesDir/python/lib/node_modules/npm/`. TerminalBootstrap then creates
shell script stubs at `usr/bin/npm` and `usr/bin/npx`.

### Getting npm

```bash
curl -fsSL https://registry.npmjs.org/npm/-/npm-10.9.2.tgz | tar xz
mv package/ app/src/main/assets/npm/
```

Then in the host app's asset extraction, copy it to
`filesDir/python/lib/node_modules/npm/`.
