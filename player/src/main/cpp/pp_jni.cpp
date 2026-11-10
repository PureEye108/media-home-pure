// Player Pure - JNI bindings for app.playerpure.audio.Native
#include <jni.h>
#include <unistd.h>
#include <string>
#include "pp_decoder.h"
#include "pp_dsd.h"
#include "pp_usb.h"

using namespace pp;

static thread_local std::string gLastError;

static void* addr(JNIEnv* env, jobject buf, jint offset) {
    uint8_t* p = (uint8_t*) env->GetDirectBufferAddress(buf);
    return p ? p + offset : nullptr;
}

#define FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_app_playerpure_audio_Native_##name

FN(jlong, decOpen)(JNIEnv*, jclass, jint fd) {
    int own = dup(fd);
    if (own < 0) { gLastError = "cannot open file"; return 0; }
    std::string err;
    Decoder* d = openDecoder(new FdIo(own), err);
    if (!d) { gLastError = err; return 0; }
    return (jlong) (intptr_t) d;
}

FN(jstring, decLastError)(JNIEnv* env, jclass) { return env->NewStringUTF(gLastError.c_str()); }

FN(jlongArray, decInfo)(JNIEnv* env, jclass, jlong h) {
    Decoder* d = (Decoder*) (intptr_t) h;
    jlong v[7] = { d->rate, d->channels, d->bits, d->dsd ? 1 : 0, d->totalFrames, d->codec, d->isFloat ? 1 : 0 };
    jlongArray a = env->NewLongArray(7);
    env->SetLongArrayRegion(a, 0, 7, v);
    return a;
}

FN(jint, decRead)(JNIEnv* env, jclass, jlong h, jobject buf, jint maxFrames) {
    Decoder* d = (Decoder*) (intptr_t) h;
    void* p = addr(env, buf, 0);
    if (!p) return -1;
    jlong cap = env->GetDirectBufferCapacity(buf) / d->frameBytes();
    if (maxFrames > cap) maxFrames = (jint) cap;
    return (jint) d->read(p, maxFrames);
}

FN(jboolean, decSeek)(JNIEnv*, jclass, jlong h, jlong frame) {
    return ((Decoder*) (intptr_t) h)->seek(frame) ? JNI_TRUE : JNI_FALSE;
}

FN(void, decClose)(JNIEnv*, jclass, jlong h) { delete (Decoder*) (intptr_t) h; }

FN(jlong, d2pOpen)(JNIEnv*, jclass, jint dsdRate, jint channels, jint outRate) {
    auto* c = new DsdToPcm(dsdRate, channels, outRate);
    if (!c->ok()) { delete c; return 0; }
    return (jlong) (intptr_t) c;
}

FN(jint, d2pProcess)(JNIEnv* env, jclass, jlong h, jobject src, jint frames, jobject dst) {
    void* s = addr(env, src, 0); void* d = addr(env, dst, 0);
    if (!s || !d) return -1;
    return ((DsdToPcm*) (intptr_t) h)->process((const uint8_t*) s, frames, (int32_t*) d);
}

FN(void, d2pReset)(JNIEnv*, jclass, jlong h) { ((DsdToPcm*) (intptr_t) h)->reset(); }
FN(void, d2pClose)(JNIEnv*, jclass, jlong h) { delete (DsdToPcm*) (intptr_t) h; }

/** cfg: dataEp, dataMaxPacket, dataInterval, fbEp, fbMaxPacket, highSpeed, rate, channels, subslot, bits, dsd (0 PCM, 1 DoP, 2 native, +4 = swap bytes), urbs, urbMicros */
FN(jlong, usbCreate)(JNIEnv* env, jclass, jint fd, jintArray cfgArr) {
    jint c[13];
    if (env->GetArrayLength(cfgArr) < 13) return 0;
    env->GetIntArrayRegion(cfgArr, 0, 13, c);
    UsbConfig cfg;
    cfg.dataEp = c[0]; cfg.dataMaxPacket = c[1]; cfg.dataInterval = c[2]; cfg.fbEp = c[3]; cfg.fbMaxPacket = c[4];
    cfg.highSpeed = c[5] != 0; cfg.rate = c[6]; cfg.channels = c[7]; cfg.subslot = c[8]; cfg.bits = c[9];
    cfg.dsd = c[10] & 3; cfg.dsdSwap = (c[10] & 4) != 0; cfg.urbs = c[11] > 0 ? c[11] : 12; cfg.urbMicros = c[12] > 0 ? c[12] : 4000;
    return (jlong) (intptr_t) new UsbEngine(fd, cfg);
}

FN(jint, usbRun)(JNIEnv*, jclass, jlong h) { return ((UsbEngine*) (intptr_t) h)->run(); }

FN(jint, usbWrite)(JNIEnv* env, jclass, jlong h, jobject buf, jint offsetBytes, jint frames, jint gainQ16) {
    UsbEngine* e = (UsbEngine*) (intptr_t) h;
    void* p = addr(env, buf, offsetBytes);
    if (!p || offsetBytes + (jlong) frames * e->inputFrameBytes() > env->GetDirectBufferCapacity(buf)) return -1;
    return e->write(p, frames, gainQ16);
}

FN(void, usbFlush)(JNIEnv*, jclass, jlong h) { ((UsbEngine*) (intptr_t) h)->flush(); }
FN(void, usbInterrupt)(JNIEnv*, jclass, jlong h) { ((UsbEngine*) (intptr_t) h)->interrupt(); }
FN(void, usbStop)(JNIEnv*, jclass, jlong h) { ((UsbEngine*) (intptr_t) h)->stop(); }
FN(void, usbSetActive)(JNIEnv*, jclass, jlong h, jboolean a) { ((UsbEngine*) (intptr_t) h)->setActive(a); }
FN(void, usbSetPaused)(JNIEnv*, jclass, jlong h, jboolean p) { ((UsbEngine*) (intptr_t) h)->setPaused(p); }

FN(jlongArray, usbStats)(JNIEnv* env, jclass, jlong h) {
    UsbEngine* e = (UsbEngine*) (intptr_t) h;
    jlong v[8] = { e->played.load(), e->underruns.load(), e->packets.load(), e->feedbacks.load(), e->badFeedback.load(),
                   (jlong) e->lastFeedback.load(), e->queuedFrames(), e->error.load() };
    jlongArray a = env->NewLongArray(8);
    env->SetLongArrayRegion(a, 0, 8, v);
    return a;
}

FN(void, usbDestroy)(JNIEnv*, jclass, jlong h) { delete (UsbEngine*) (intptr_t) h; }

FN(jint, usbReattach)(JNIEnv*, jclass, jint fd, jint iface) { return usbReattach(fd, iface); }

FN(jint, usbSpeed)(JNIEnv*, jclass, jint fd) { return usbSpeed(fd); }
