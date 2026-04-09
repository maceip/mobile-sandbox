#include <android/log.h>
#include <android/looper.h>

#include "game-activity/native_app_glue/android_native_app_glue.h"

namespace {

constexpr char kLogTag[] = "orderfiledemo";

}  // namespace

extern "C" void android_main(struct android_app* app) {
  __android_log_print(ANDROID_LOG_INFO, kLogTag,
                      "GameActivity native thread started");

  while (app->destroyRequested == 0) {
    int events = 0;
    android_poll_source* source = nullptr;
    if (ALooper_pollOnce(-1, nullptr, &events,
                         reinterpret_cast<void**>(&source)) >= 0) {
      if (source != nullptr) {
        source->process(app, source);
      }
    }
  }

  __android_log_print(ANDROID_LOG_INFO, kLogTag,
                      "GameActivity native thread exiting");
}
