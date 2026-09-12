/*
 * SMRITI AQUA - JNI bridge for com.smriti.aqua.dsp.DspEngine (SPEC section 3).
 * Static registration by mangled name (Java_com_smriti_aqua_dsp_DspEngine_*),
 * no JNI_OnLoad. All heavy lifting lives in dsp_core (pure C++17).
 */
#include <jni.h>

#include <cstdint>
#include <vector>

#include "dsp_core.h"

extern "C" {

JNIEXPORT jshortArray JNICALL
Java_com_smriti_aqua_dsp_DspEngine_generateChirp(JNIEnv* env, jclass /*clazz*/,
                                                 jint sampleRate, jfloat f0, jfloat f1,
                                                 jint durationMs) {
    std::vector<int16_t> pcm =
        smriti::generateChirp((int)sampleRate, (float)f0, (float)f1, (int)durationMs);
    jshortArray out = env->NewShortArray((jsize)pcm.size());
    if (!out) return nullptr;
    if (!pcm.empty())
        env->SetShortArrayRegion(out, 0, (jsize)pcm.size(),
                                 reinterpret_cast<const jshort*>(pcm.data()));
    return out;
}

JNIEXPORT jfloatArray JNICALL
Java_com_smriti_aqua_dsp_DspEngine_computeFingerprint(JNIEnv* env, jclass /*clazz*/,
                                                      jshortArray pcm, jint sampleRate) {
    const jsize n = env->GetArrayLength(pcm);
    jshort* data = env->GetShortArrayElements(pcm, nullptr);
    if (!data) return nullptr;
    smriti::Fingerprint fp =
        smriti::computeFingerprint(reinterpret_cast<const int16_t*>(data), (size_t)n,
                                   (int)sampleRate);
    env->ReleaseShortArrayElements(pcm, data, JNI_ABORT);
    jfloatArray out = env->NewFloatArray((jsize)smriti::Fingerprint::DIM);
    if (!out) return nullptr;
    env->SetFloatArrayRegion(out, 0, (jsize)smriti::Fingerprint::DIM, fp.v);
    return out;
}

JNIEXPORT jfloatArray JNICALL
Java_com_smriti_aqua_dsp_DspEngine_computeSpectrogram(JNIEnv* env, jclass /*clazz*/,
                                                      jshortArray pcm, jint sampleRate) {
    const jsize n = env->GetArrayLength(pcm);
    jshort* data = env->GetShortArrayElements(pcm, nullptr);
    if (!data) return nullptr;
    int frames = 0, mels = 0;
    std::vector<float> sg =
        smriti::computeSpectrogram(reinterpret_cast<const int16_t*>(data), (size_t)n,
                                   (int)sampleRate, frames, mels);
    env->ReleaseShortArrayElements(pcm, data, JNI_ABORT);
    jfloatArray out = env->NewFloatArray((jsize)sg.size());
    if (!out) return nullptr;
    if (!sg.empty()) env->SetFloatArrayRegion(out, 0, (jsize)sg.size(), sg.data());
    return out;
}

JNIEXPORT jintArray JNICALL
Java_com_smriti_aqua_dsp_DspEngine_spectrogramDims(JNIEnv* env, jclass /*clazz*/,
                                                   jint pcmLen) {
    // frames = (pcmLen - FFT_SIZE)/HOP + 1, clamped >= 1; mels = 64
    int frames = ((int)pcmLen - smriti::FFT_SIZE) / smriti::HOP + 1;
    if (frames < 1) frames = 1;
    const jint dims[2] = {frames, (jint)smriti::MELS};
    jintArray out = env->NewIntArray(2);
    if (!out) return nullptr;
    env->SetIntArrayRegion(out, 0, 2, dims);
    return out;
}

JNIEXPORT jfloat JNICALL
Java_com_smriti_aqua_dsp_DspEngine_anomalyScore(JNIEnv* env, jclass /*clazz*/,
                                                jfloatArray current, jfloatArray baseMean,
                                                jfloatArray baseVar) {
    smriti::Fingerprint fp{};
    const jsize nCur = env->GetArrayLength(current);
    jfloat* cur = env->GetFloatArrayElements(current, nullptr);
    if (!cur) return 0.f;
    const jsize copy = nCur < (jsize)smriti::Fingerprint::DIM ? nCur : (jsize)smriti::Fingerprint::DIM;
    for (jsize i = 0; i < copy; ++i) fp.v[i] = cur[i];
    env->ReleaseFloatArrayElements(current, cur, JNI_ABORT);

    jfloat* mean = env->GetFloatArrayElements(baseMean, nullptr);
    jfloat* var = env->GetFloatArrayElements(baseVar, nullptr);
    if (!mean || !var) {
        if (mean) env->ReleaseFloatArrayElements(baseMean, mean, JNI_ABORT);
        if (var) env->ReleaseFloatArrayElements(baseVar, var, JNI_ABORT);
        return 0.f;
    }
    const float score = smriti::anomalyScore(fp, mean, var);
    env->ReleaseFloatArrayElements(baseMean, mean, JNI_ABORT);
    env->ReleaseFloatArrayElements(baseVar, var, JNI_ABORT);
    return (jfloat)score;
}

JNIEXPORT jfloat JNICALL
Java_com_smriti_aqua_dsp_DspEngine_coherenceScore(JNIEnv* env, jclass /*clazz*/,
                                                  jshortArray mic, jfloatArray accelZ,
                                                  jint micRate, jint accRate) {
    const jsize micN = env->GetArrayLength(mic);
    jshort* micData = env->GetShortArrayElements(mic, nullptr);
    const jsize accN = env->GetArrayLength(accelZ);
    jfloat* accData = env->GetFloatArrayElements(accelZ, nullptr);
    if (!micData || !accData) {
        if (micData) env->ReleaseShortArrayElements(mic, micData, JNI_ABORT);
        if (accData) env->ReleaseFloatArrayElements(accelZ, accData, JNI_ABORT);
        return 0.f;
    }
    const float score =
        smriti::coherenceScore(reinterpret_cast<const int16_t*>(micData), (size_t)micN,
                               accData, (size_t)accN, (int)micRate, (int)accRate);
    env->ReleaseShortArrayElements(mic, micData, JNI_ABORT);
    env->ReleaseFloatArrayElements(accelZ, accData, JNI_ABORT);
    return (jfloat)score;
}

} // extern "C"
