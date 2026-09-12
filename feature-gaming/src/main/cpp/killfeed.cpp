#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>

// 10 Hz-friendly SAD template match on a grayscale ROI. No OpenCV dependency.
extern "C" JNIEXPORT jfloat JNICALL
Java_com_smriti_brain_gaming_KillFeedJni_matchSad(
        JNIEnv *env, jclass,
        jbyteArray frameGray, jint fw, jint fh,
        jbyteArray templGray, jint tw, jint th,
        jint roiX, jint roiY, jint roiW, jint roiH) {
    if (tw <= 0 || th <= 0 || fw <= 0 || fh <= 0) return 0.f;
    jbyte *frame = env->GetByteArrayElements(frameGray, nullptr);
    jbyte *templ = env->GetByteArrayElements(templGray, nullptr);
    const int x0 = std::max(0, roiX);
    const int y0 = std::max(0, roiY);
    const int x1 = std::min(fw - tw, roiX + roiW);
    const int y1 = std::min(fh - th, roiY + roiH);
    if (x1 < x0 || y1 < y0) {
        env->ReleaseByteArrayElements(frameGray, frame, JNI_ABORT);
        env->ReleaseByteArrayElements(templGray, templ, JNI_ABORT);
        return 0.f;
    }
    double best = 1e18;
    const double norm = (double) (tw * th * 255);
    for (int y = y0; y <= y1; y += 2) {
        for (int x = x0; x <= x1; x += 2) {
            double sad = 0;
            for (int j = 0; j < th; ++j) {
                const jbyte *fr = frame + (y + j) * fw + x;
                const jbyte *tp = templ + j * tw;
                for (int i = 0; i < tw; ++i) {
                    sad += std::abs((int) (uint8_t) fr[i] - (int) (uint8_t) tp[i]);
                }
            }
            if (sad < best) best = sad;
        }
    }
    env->ReleaseByteArrayElements(frameGray, frame, JNI_ABORT);
    env->ReleaseByteArrayElements(templGray, templ, JNI_ABORT);
    float score = (float) (1.0 - best / norm);
    return score < 0.f ? 0.f : score;
}
