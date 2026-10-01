#include <jni.h>
#include <cstring>

#include <mpv/client.h>

#include "jni_utils.h"
#include "log.h"
#include "globals.h"

extern "C" {
    jni_func(void, nativeAttachSurface, jlong instance, jobject surface);
    jni_func(void, nativeDetachSurface, jlong instance);
    jni_func(jint, nativeTryDetachStoppedSurface, jlong instance);
}

jni_func(void, nativeAttachSurface, jlong instance, jobject surface) {
    auto mpv_instance = reinterpret_cast<MPVInstance*>(instance);

    if (mpv_instance->surface) {
        die(env, "surface is still attached");
        return;
    }

    mpv_instance->surface = env->NewGlobalRef(surface);
    if (!mpv_instance->surface) {
        die(env, "invalid surface provided");
        return;
    }

    int64_t wid = reinterpret_cast<intptr_t>(mpv_instance->surface);
    int result = mpv_set_option(mpv_instance->mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0)
        ALOGE("mpv_set_option(wid) returned error %s", mpv_error_string(result));
}

// The caller serializes stop/check/clear against every attach and load. A zero
// track count alone is insufficient: demux teardown can dispatch before END_FILE.
jni_func(jint, nativeTryDetachStoppedSurface, jlong instance) {
    auto mpv_instance = reinterpret_cast<MPVInstance*>(instance);
    if (!mpv_instance->surface) return 0;

    char *force_window = nullptr;
    int result = mpv_get_property(mpv_instance->mpv, "force-window", MPV_FORMAT_STRING, &force_window);
    if (result < 0) return result;
    bool window_disabled = std::strcmp(force_window, "no") == 0;
    mpv_free(force_window);
    if (!window_disabled) return 1;

    int idle = 0;
    result = mpv_get_property(mpv_instance->mpv, "idle-active", MPV_FORMAT_FLAG, &idle);
    if (result < 0) return result;
    if (!idle) return 1;

    int64_t tracks = 0;
    result = mpv_get_property(mpv_instance->mpv, "track-list/count", MPV_FORMAT_INT64, &tracks);
    if (result < 0) return result;
    if (tracks != 0) return 1;

    char *path = nullptr;
    result = mpv_get_property(mpv_instance->mpv, "path", MPV_FORMAT_STRING, &path);
    mpv_free(path); // Never expose the media path to Java or logging.
    if (result >= 0) return 1;
    if (result != MPV_ERROR_PROPERTY_UNAVAILABLE) return result;

    int64_t wid = 0;
    result = mpv_set_option(mpv_instance->mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0) return result;
    char *vo = nullptr;
    result = mpv_get_property(mpv_instance->mpv, "current-vo", MPV_FORMAT_STRING, &vo);
    mpv_free(vo);
    if (result != MPV_ERROR_PROPERTY_UNAVAILABLE)
        return result < 0 ? result : MPV_ERROR_GENERIC;

    env->DeleteGlobalRef(mpv_instance->surface);
    mpv_instance->surface = nullptr;
    return 0;
}

jni_func(void, nativeDetachSurface, jlong instance) {
    auto mpv_instance = reinterpret_cast<MPVInstance*>(instance);
    int64_t wid = 0;
    int result = mpv_set_option(mpv_instance->mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0)
        ALOGE("mpv_set_option(wid) returned error %s", mpv_error_string(result));

    env->DeleteGlobalRef(mpv_instance->surface);
    mpv_instance->surface = nullptr;
}
