#include <jni.h>
#include <sys/sysinfo.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>

static const char* kKeys[] = {
        "MemTotal:", "MemFree:", "MemAvailable:", "Buffers:", "Cached:",
        "SwapTotal:", "SwapFree:", "Dirty:", "Slab:"
};
static const int kKeyCount = 9;

// Reads /proc/meminfo and returns values in kB, in the order of kKeys. -1 means missing.
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_vit_devicememorymonitor_NativeBridge_readProcMeminfo(JNIEnv* env, jobject) {
    jlong values[kKeyCount];
    for (int i = 0; i < kKeyCount; i++) values[i] = -1;

    FILE* f = fopen("/proc/meminfo", "r");
    if (f != nullptr) {
        char line[256];
        while (fgets(line, sizeof(line), f) != nullptr) {
            for (int i = 0; i < kKeyCount; i++) {
                size_t len = strlen(kKeys[i]);
                if (strncmp(line, kKeys[i], len) == 0) {
                    values[i] = strtoll(line + len, nullptr, 10);
                    break;
                }
            }
        }
        fclose(f);
    }

    jlongArray result = env->NewLongArray(kKeyCount);
    if (result == nullptr) return nullptr;
    env->SetLongArrayRegion(result, 0, kKeyCount, values);
    return result;
}

// Calls the sysinfo() system call. Returns bytes, except uptime (seconds) and procs (count).
// Order: totalram, freeram, bufferram, totalswap, freeswap, uptime, procs
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_vit_devicememorymonitor_NativeBridge_readSysinfo(JNIEnv* env, jobject) {
    jlong values[7];
    for (int i = 0; i < 7; i++) values[i] = -1;

    struct sysinfo si;
    if (sysinfo(&si) == 0) {
        jlong unit = static_cast<jlong>(si.mem_unit);
        values[0] = static_cast<jlong>(si.totalram) * unit;
        values[1] = static_cast<jlong>(si.freeram) * unit;
        values[2] = static_cast<jlong>(si.bufferram) * unit;
        values[3] = static_cast<jlong>(si.totalswap) * unit;
        values[4] = static_cast<jlong>(si.freeswap) * unit;
        values[5] = static_cast<jlong>(si.uptime);
        values[6] = static_cast<jlong>(si.procs);
    }

    jlongArray result = env->NewLongArray(7);
    if (result == nullptr) return nullptr;
    env->SetLongArrayRegion(result, 0, 7, values);
    return result;
}//
// Created by Adnan on 16-10-2026.
//
