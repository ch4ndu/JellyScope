/*
 * Declaration subset from FFmpeg 8.1 libavcodec/jni.h used by the pinned
 * Android bridge. The implementation remains in the extracted libavcodec.so.
 */
#ifndef JELLYSCOPE_FFMPEG_JNI_H
#define JELLYSCOPE_FFMPEG_JNI_H

#ifdef __cplusplus
extern "C" {
#endif

int av_jni_set_java_vm(void *vm, void *log_ctx);
int av_jni_set_android_app_ctx(void *app_ctx, void *log_ctx);

#ifdef __cplusplus
}
#endif

#endif
