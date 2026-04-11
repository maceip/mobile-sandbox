#include <Python.h>
#include <android/log.h>
#include <errno.h>
#include <git2.h>
#include <jni.h>
#include <linux/limits.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <mutex>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

const char kLogTag[] = "cory";

#ifndef CORY_PYTHON_VERSION
#define CORY_PYTHON_VERSION "unknown"
#endif

#ifndef CORY_LITERT_LM_ENABLED
#define CORY_LITERT_LM_ENABLED 0
#endif

#ifdef GENERATE_PROFILES
extern "C" int __llvm_profile_set_filename(const char*);
extern "C" int __llvm_profile_initialize_file(void);
extern "C" int __llvm_profile_dump(void);
#endif

extern "C" PyObject* PyInit_cory_native(void);
extern "C" const char* cory_rust_runtime_name();
extern "C" int32_t cory_rust_add(int32_t lhs, int32_t rhs);

namespace {

std::mutex g_python_status_mutex;
std::string g_python_status = "embedded runtime not started";
bool g_cory_native_registered = false;

void SetPythonStatus(std::string status) {
  std::lock_guard<std::mutex> lock(g_python_status_mutex);
  g_python_status = std::move(status);
}

std::string GetPythonStatus() {
  std::lock_guard<std::mutex> lock(g_python_status_mutex);
  return g_python_status;
}

std::string StatusMessage(const char* step, const PyStatus& status) {
  std::string message(step);
  if (status.err_msg != nullptr) {
    message += ": ";
    message += status.err_msg;
  }
  return message;
}

std::string FormatPythonException() {
  if (!PyErr_Occurred()) {
    return "unknown Python error";
  }

  PyObject *type = nullptr, *value = nullptr, *traceback = nullptr;
  PyErr_Fetch(&type, &value, &traceback);
  PyErr_NormalizeException(&type, &value, &traceback);

  std::string message = "unknown Python error";
  PyObject* traceback_module = PyImport_ImportModule("traceback");
  if (traceback_module != nullptr) {
    PyObject* lines =
        PyObject_CallMethod(traceback_module, "format_exception", "OOO",
                            type ? type : Py_None, value ? value : Py_None,
                            traceback ? traceback : Py_None);
    if (lines != nullptr) {
      PyObject* separator = PyUnicode_FromString("");
      if (separator != nullptr) {
        PyObject* joined = PyUnicode_Join(separator, lines);
        if (joined != nullptr) {
          const char* utf8 = PyUnicode_AsUTF8(joined);
          if (utf8 != nullptr) {
            message = utf8;
          }
          Py_DECREF(joined);
        }
        Py_DECREF(separator);
      }
      Py_DECREF(lines);
    }
    Py_DECREF(traceback_module);
  }

  Py_XDECREF(type);
  Py_XDECREF(value);
  Py_XDECREF(traceback);
  return message;
}

std::string FinalizePython(int exit_code, const std::string& status_prefix) {
  const int finalize_status = Py_FinalizeEx();
  std::ostringstream status_stream;
  status_stream << status_prefix << exit_code;
  if (finalize_status < 0) {
    status_stream << " (Py_FinalizeEx=" << finalize_status << ")";
  }
  return status_stream.str();
}

std::string InitializePythonRuntime(const char* python_home,
                                    const std::vector<std::string>& argv) {
  PyConfig config;
  PyConfig_InitPythonConfig(&config);

  if (!g_cory_native_registered) {
    if (PyImport_AppendInittab("cory_native", PyInit_cory_native) == -1) {
      PyConfig_Clear(&config);
      return "PyImport_AppendInittab(cory_native) failed";
    }
    g_cory_native_registered = true;
  }

  std::vector<std::vector<char>> storage;
  storage.reserve(argv.size());
  std::vector<char*> argv_ptrs;
  argv_ptrs.reserve(argv.size());
  for (const auto& value : argv) {
    storage.emplace_back(value.begin(), value.end());
    storage.back().push_back('\0');
    argv_ptrs.push_back(storage.back().data());
  }

  PyStatus status = PyConfig_SetBytesArgv(
      &config, static_cast<int>(argv_ptrs.size()), argv_ptrs.data());
  if (PyStatus_Exception(status)) {
    PyConfig_Clear(&config);
    return StatusMessage("PyConfig_SetBytesArgv", status);
  }

  status = PyConfig_SetBytesString(&config, &config.home, python_home);
  if (PyStatus_Exception(status)) {
    PyConfig_Clear(&config);
    return StatusMessage("PyConfig_SetBytesString(home)", status);
  }

  status = Py_InitializeFromConfig(&config);
  PyConfig_Clear(&config);
  if (PyStatus_Exception(status)) {
    return StatusMessage("Py_InitializeFromConfig", status);
  }

  return {};
}

std::pair<int, std::string> InvokeCoryRuntime(const char* function_name,
                                              const char* argument) {
  PyObject* module = PyImport_ImportModule("cory_runtime");
  if (module == nullptr) {
    return {1, std::string("import cory_runtime failed:\n") +
                   FormatPythonException()};
  }

  PyObject* function = PyObject_GetAttrString(module, function_name);
  if (function == nullptr || !PyCallable_Check(function)) {
    Py_XDECREF(function);
    Py_DECREF(module);
    return {1, std::string("cory_runtime.") + function_name +
                   " is not callable"};
  }

  PyObject* arg = PyUnicode_FromString(argument);
  if (arg == nullptr) {
    Py_DECREF(function);
    Py_DECREF(module);
    return {1, std::string("PyUnicode_FromString failed:\n") +
                   FormatPythonException()};
  }

  PyObject* result = PyObject_CallFunctionObjArgs(function, arg, nullptr);
  Py_DECREF(arg);
  Py_DECREF(function);
  Py_DECREF(module);
  if (result == nullptr) {
    return {1, std::string("cory_runtime.") + function_name + " failed:\n" +
                   FormatPythonException()};
  }

  long exit_code = PyLong_AsLong(result);
  Py_DECREF(result);
  if (exit_code == -1 && PyErr_Occurred()) {
    return {1, std::string("cory_runtime.") + function_name +
                   " returned non-integer:\n" + FormatPythonException()};
  }

  return {static_cast<int>(exit_code), {}};
}

std::string SetEnvValue(const char* name, const char* value) {
  if (setenv(name, value, 1) != 0) {
    return std::string("setenv(") + name + ") failed: " + strerror(errno);
  }
  return {};
}

const char* InitSignals() {
  sigset_t set;
  if (sigemptyset(&set) != 0) {
    return "sigemptyset";
  }
  if (sigaddset(&set, SIGUSR1) != 0) {
    return "sigaddset";
  }
  if ((errno = pthread_sigmask(SIG_UNBLOCK, &set, nullptr)) != 0) {
    return "pthread_sigmask";
  }
  return nullptr;
}

[[maybe_unused]] std::string RunEmbeddedPythonProbe(const char* python_home,
                                                    const char* sandbox_root,
                                                    const char* temp_dir) {
  char workspace_dir[PATH_MAX] = {};
  char site_packages_dir[PATH_MAX] = {};
  char bin_dir[PATH_MAX] = {};
  char dev_dir[PATH_MAX] = {};
  char state_dir[PATH_MAX] = {};
  snprintf(workspace_dir, sizeof(workspace_dir), "%s/workspace", sandbox_root);
  snprintf(site_packages_dir, sizeof(site_packages_dir), "%s/site-packages",
           sandbox_root);
  snprintf(bin_dir, sizeof(bin_dir), "%s/bin", sandbox_root);
  snprintf(dev_dir, sizeof(dev_dir), "%s/dev", sandbox_root);
  snprintf(state_dir, sizeof(state_dir), "%s/state", sandbox_root);

  if (chdir(workspace_dir) != 0) {
    return std::string("chdir failed: ") + strerror(errno);
  }

  if (const char* error_prefix = InitSignals(); error_prefix != nullptr) {
    return std::string(error_prefix) + ": " + strerror(errno);
  }

  for (const auto& item :
       {std::pair<const char*, const char*>("CORY_SANDBOX_ROOT", sandbox_root),
        std::pair<const char*, const char*>("CORY_SANDBOX_WORKSPACE",
                                            workspace_dir),
        std::pair<const char*, const char*>("CORY_SANDBOX_SITE_PACKAGES",
                                            site_packages_dir),
        std::pair<const char*, const char*>("CORY_SANDBOX_BIN", bin_dir),
        std::pair<const char*, const char*>("CORY_SANDBOX_DEV", dev_dir),
        std::pair<const char*, const char*>("CORY_SANDBOX_STATE", state_dir),
        std::pair<const char*, const char*>("TMPDIR", temp_dir),
        std::pair<const char*, const char*>("HOME", sandbox_root),
        std::pair<const char*, const char*>("PATH", bin_dir)}) {
    std::string env_error = SetEnvValue(item.first, item.second);
    if (!env_error.empty()) {
      return env_error;
    }
  }

  PyConfig config;
  PyConfig_InitPythonConfig(&config);

  if (!g_cory_native_registered) {
    if (PyImport_AppendInittab("cory_native", PyInit_cory_native) == -1) {
      PyConfig_Clear(&config);
      return "PyImport_AppendInittab(cory_native) failed";
    }
    g_cory_native_registered = true;
  }

  char arg0[] = "";
  char arg1[] = "-c";
  char arg2[] = "import cory_bootstrap\nraise SystemExit(cory_bootstrap.main())\n";
  char* argv[] = {arg0, arg1, arg2};

  PyStatus status = PyConfig_SetBytesArgv(&config, 3, argv);
  if (PyStatus_Exception(status)) {
    PyConfig_Clear(&config);
    return StatusMessage("PyConfig_SetBytesArgv", status);
  }

  status = PyConfig_SetBytesString(&config, &config.home, python_home);
  if (PyStatus_Exception(status)) {
    PyConfig_Clear(&config);
    return StatusMessage("PyConfig_SetBytesString(home)", status);
  }

  status = Py_InitializeFromConfig(&config);
  PyConfig_Clear(&config);
  if (PyStatus_Exception(status)) {
    return StatusMessage("Py_InitializeFromConfig", status);
  }

  const int exit_code = Py_RunMain();
  if (exit_code != 0) {
    char buffer[128] = {};
    snprintf(buffer, sizeof(buffer), "Py_RunMain exited with status %d",
             exit_code);
    return buffer;
  }

  return "embedded runtime OK with inceptionsandbox";
}

std::string RunEmbeddedPythonScript(const char* python_home,
                                    const char* sandbox_root,
                                    const char* temp_dir,
                                    const char* script_path) {
  char workspace_dir[PATH_MAX] = {};
  char site_packages_dir[PATH_MAX] = {};
  char bin_dir[PATH_MAX] = {};
  char dev_dir[PATH_MAX] = {};
  char state_dir[PATH_MAX] = {};
  snprintf(workspace_dir, sizeof(workspace_dir), "%s/workspace", sandbox_root);
  snprintf(site_packages_dir, sizeof(site_packages_dir), "%s/site-packages",
           sandbox_root);
  snprintf(bin_dir, sizeof(bin_dir), "%s/bin", sandbox_root);
  snprintf(dev_dir, sizeof(dev_dir), "%s/dev", sandbox_root);
  snprintf(state_dir, sizeof(state_dir), "%s/state", sandbox_root);

  if (chdir(workspace_dir) != 0) {
    return std::string("chdir failed: ") + strerror(errno);
  }

  if (const char* error_prefix = InitSignals(); error_prefix != nullptr) {
    return std::string(error_prefix) + ": " + strerror(errno);
  }

  for (const auto& item :
       {std::pair<const char*, const char*>("CORY_SANDBOX_ROOT", sandbox_root),
        std::pair<const char*, const char*>("CORY_SANDBOX_WORKSPACE",
                                            workspace_dir),
        std::pair<const char*, const char*>("CORY_SANDBOX_SITE_PACKAGES",
                                            site_packages_dir),
        std::pair<const char*, const char*>("CORY_SANDBOX_BIN", bin_dir),
        std::pair<const char*, const char*>("CORY_SANDBOX_DEV", dev_dir),
        std::pair<const char*, const char*>("CORY_SANDBOX_STATE", state_dir),
        std::pair<const char*, const char*>("TMPDIR", temp_dir),
        std::pair<const char*, const char*>("HOME", sandbox_root),
        std::pair<const char*, const char*>("PATH", bin_dir),
        std::pair<const char*, const char*>("CORY_SCRIPT_PATH", script_path)}) {
    std::string env_error = SetEnvValue(item.first, item.second);
    if (!env_error.empty()) {
      return env_error;
    }
  }

  std::string init_error =
      InitializePythonRuntime(python_home, {"", script_path});
  if (!init_error.empty()) {
    return init_error;
  }

  auto [exit_code, error] = InvokeCoryRuntime("run_script", script_path);
  if (!error.empty()) {
    Py_FinalizeEx();
    return error;
  }

  std::ostringstream prefix;
  prefix << "script " << script_path << " exited with status ";
  return FinalizePython(exit_code, prefix.str());
}

std::string RunEmbeddedPythonCommand(const char* python_home,
                                     const char* sandbox_root,
                                     const char* temp_dir,
                                     const char* command_text) {
  char workspace_dir[PATH_MAX] = {};
  char site_packages_dir[PATH_MAX] = {};
  char bin_dir[PATH_MAX] = {};
  char dev_dir[PATH_MAX] = {};
  char state_dir[PATH_MAX] = {};
  snprintf(workspace_dir, sizeof(workspace_dir), "%s/workspace", sandbox_root);
  snprintf(site_packages_dir, sizeof(site_packages_dir), "%s/site-packages",
           sandbox_root);
  snprintf(bin_dir, sizeof(bin_dir), "%s/bin", sandbox_root);
  snprintf(dev_dir, sizeof(dev_dir), "%s/dev", sandbox_root);
  snprintf(state_dir, sizeof(state_dir), "%s/state", sandbox_root);

  if (chdir(workspace_dir) != 0) {
    return std::string("chdir failed: ") + strerror(errno);
  }

  if (const char* error_prefix = InitSignals(); error_prefix != nullptr) {
    return std::string(error_prefix) + ": " + strerror(errno);
  }

  for (const auto& item :
       {std::pair<const char*, const char*>("CORY_SANDBOX_ROOT", sandbox_root),
        std::pair<const char*, const char*>("CORY_SANDBOX_WORKSPACE",
                                            workspace_dir),
        std::pair<const char*, const char*>("CORY_SANDBOX_SITE_PACKAGES",
                                            site_packages_dir),
        std::pair<const char*, const char*>("CORY_SANDBOX_BIN", bin_dir),
        std::pair<const char*, const char*>("CORY_SANDBOX_DEV", dev_dir),
        std::pair<const char*, const char*>("CORY_SANDBOX_STATE", state_dir),
        std::pair<const char*, const char*>("TMPDIR", temp_dir),
        std::pair<const char*, const char*>("HOME", sandbox_root),
        std::pair<const char*, const char*>("PATH", bin_dir),
        std::pair<const char*, const char*>("CORY_COMMAND", command_text)}) {
    std::string env_error = SetEnvValue(item.first, item.second);
    if (!env_error.empty()) {
      return env_error;
    }
  }

  std::string init_error =
      InitializePythonRuntime(python_home, {"", "-c", command_text});
  if (!init_error.empty()) {
    return init_error;
  }

  auto [exit_code, error] = InvokeCoryRuntime("run_command", command_text);
  if (!error.empty()) {
    Py_FinalizeEx();
    return error;
  }

  return FinalizePython(exit_code, "command exited with status ");
}

}  // namespace

void DumpProfileDataIfNeeded(const char* temp_dir) {
#ifdef GENERATE_PROFILES
  char profile_location[PATH_MAX] = {};
  snprintf(profile_location, sizeof(profile_location), "%s/demo.profraw",
           temp_dir);
  if (__llvm_profile_set_filename(profile_location) == -1) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "__llvm_profile_set_filename(\"%s\") failed: %s",
                        profile_location, strerror(errno));
    return;
  }

  if (__llvm_profile_initialize_file() == -1) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "__llvm_profile_initialize_file failed: %s",
                        strerror(errno));
    return;
  }

  if (__llvm_profile_dump() == -1) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "__llvm_profile_dump() failed: %s", strerror(errno));
    return;
  }
  __android_log_print(ANDROID_LOG_DEBUG, kLogTag, "Wrote profile data to %s",
                      profile_location);
#else
  (void)temp_dir; // To avoid unused-parameter warning
  __android_log_print(ANDROID_LOG_DEBUG, kLogTag,
                      "Did not write profile data because the app was not "
                      "built for profile generation");
#endif
}

std::string BuildNativeSummary() {
  git_libgit2_init();

  int major = 0;
  int minor = 0;
  int rev = 0;
  git_libgit2_version(&major, &minor, &rev);
  const char* rust_runtime_name = cory_rust_runtime_name();
  const int rust_probe = cory_rust_add(20, 22);

  char buffer[640] = {};
  snprintf(
      buffer, sizeof(buffer),
      "libgit2      %d.%d.%d\n"
      "python       %s via official CPython Android package\n"
      "python mode  %s\n"
      "rust         %s (%d)\n"
      "sandbox      inceptionsandbox runtime bootstrapped\n"
      "LiteRT-LM    %s via Maven AAR\n"
      "entry        Compose + JNI bridge",
      major, minor, rev, CORY_PYTHON_VERSION, GetPythonStatus().c_str(),
      rust_runtime_name != nullptr ? rust_runtime_name : "Rust unavailable",
      rust_probe,
      CORY_LITERT_LM_ENABLED ? "enabled" : "disabled");

  git_libgit2_shutdown();
  return buffer;
}

void LogLibgit2Availability() {
  std::string summary = BuildNativeSummary();
  __android_log_print(ANDROID_LOG_DEBUG, kLogTag, "%s", summary.c_str());
}

void RunWorkload(JNIEnv* env, jobject /* this */, jstring python_home,
                 jstring sandbox_root, jstring temp_dir) {
  SetPythonStatus("embedded runtime configured");
  LogLibgit2Availability();
  (void)env;
  (void)python_home;
  (void)sandbox_root;
  (void)temp_dir;
}

jstring NativeSummary(JNIEnv* env, jobject /* this */) {
  std::string summary = BuildNativeSummary();
  return env->NewStringUTF(summary.c_str());
}

jstring RunScript(JNIEnv* env, jobject /* this */, jstring python_home,
                  jstring sandbox_root, jstring temp_dir, jstring script_path) {
  const char* python_home_path = env->GetStringUTFChars(python_home, nullptr);
  if (python_home_path == nullptr) {
    return nullptr;
  }

  const char* sandbox_root_path =
      env->GetStringUTFChars(sandbox_root, nullptr);
  if (sandbox_root_path == nullptr) {
    env->ReleaseStringUTFChars(python_home, python_home_path);
    return nullptr;
  }

  const char* temp_dir_path = env->GetStringUTFChars(temp_dir, nullptr);
  if (temp_dir_path == nullptr) {
    env->ReleaseStringUTFChars(sandbox_root, sandbox_root_path);
    env->ReleaseStringUTFChars(python_home, python_home_path);
    return nullptr;
  }

  const char* script_path_text = env->GetStringUTFChars(script_path, nullptr);
  if (script_path_text == nullptr) {
    env->ReleaseStringUTFChars(temp_dir, temp_dir_path);
    env->ReleaseStringUTFChars(sandbox_root, sandbox_root_path);
    env->ReleaseStringUTFChars(python_home, python_home_path);
    return nullptr;
  }

  std::string status = RunEmbeddedPythonScript(
      python_home_path, sandbox_root_path, temp_dir_path, script_path_text);
  SetPythonStatus(status);
  env->ReleaseStringUTFChars(script_path, script_path_text);
  env->ReleaseStringUTFChars(temp_dir, temp_dir_path);
  env->ReleaseStringUTFChars(sandbox_root, sandbox_root_path);
  env->ReleaseStringUTFChars(python_home, python_home_path);
  return env->NewStringUTF(status.c_str());
}

jstring RunCommand(JNIEnv* env, jobject /* this */, jstring python_home,
                   jstring sandbox_root, jstring temp_dir, jstring command) {
  const char* python_home_path = env->GetStringUTFChars(python_home, nullptr);
  if (python_home_path == nullptr) {
    return nullptr;
  }

  const char* sandbox_root_path =
      env->GetStringUTFChars(sandbox_root, nullptr);
  if (sandbox_root_path == nullptr) {
    env->ReleaseStringUTFChars(python_home, python_home_path);
    return nullptr;
  }

  const char* temp_dir_path = env->GetStringUTFChars(temp_dir, nullptr);
  if (temp_dir_path == nullptr) {
    env->ReleaseStringUTFChars(sandbox_root, sandbox_root_path);
    env->ReleaseStringUTFChars(python_home, python_home_path);
    return nullptr;
  }

  const char* command_text = env->GetStringUTFChars(command, nullptr);
  if (command_text == nullptr) {
    env->ReleaseStringUTFChars(temp_dir, temp_dir_path);
    env->ReleaseStringUTFChars(sandbox_root, sandbox_root_path);
    env->ReleaseStringUTFChars(python_home, python_home_path);
    return nullptr;
  }

  std::string status = RunEmbeddedPythonCommand(
      python_home_path, sandbox_root_path, temp_dir_path, command_text);
  SetPythonStatus(status);
  env->ReleaseStringUTFChars(command, command_text);
  env->ReleaseStringUTFChars(temp_dir, temp_dir_path);
  env->ReleaseStringUTFChars(sandbox_root, sandbox_root_path);
  env->ReleaseStringUTFChars(python_home, python_home_path);
  return env->NewStringUTF(status.c_str());
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* _Nonnull vm,
                                             void* _Nullable) {
  git_libgit2_init();

  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }

  jclass c = env->FindClass("com/cory/app/ComposeSandboxActivity");
  if (c == nullptr) return JNI_ERR;

  static const JNINativeMethod methods[] = {
      {"runWorkload", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
       reinterpret_cast<void*>(RunWorkload)},
      {"runScript",
       "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
       reinterpret_cast<void*>(RunScript)},
      {"runCommand",
       "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
       reinterpret_cast<void*>(RunCommand)},
      {"nativeSummary", "()Ljava/lang/String;",
       reinterpret_cast<void*>(NativeSummary)},
  };
  int rc = env->RegisterNatives(
      c, methods, sizeof(methods) / sizeof(methods[0]));
  if (rc != JNI_OK) return rc;

  return JNI_VERSION_1_6;
}
