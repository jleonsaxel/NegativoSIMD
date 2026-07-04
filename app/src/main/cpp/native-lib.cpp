#include <jni.h>
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <thread>
#include <vector>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

static void negativo_rango(uint32_t *p, int inicio, int fin, bool usar_simd) {
    int i = inicio;

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    if (usar_simd) {
        const uint32x4_t mascara_rgb = vdupq_n_u32(0x00FFFFFFu);
        for (; i + 4 <= fin; i += 4) {
            uint32x4_t pix = vld1q_u32(p + i);
            // XOR con 0x00FFFFFF invierte R, G y B, dejando alfa intacto.
            // Para cada canal de 8 bits: canal_negativo = 255 - canal.
            pix = veorq_u32(pix, mascara_rgb);
            vst1q_u32(p + i, pix);
        }
    }
#endif

    for (; i < fin; i++) {
        p[i] ^= 0x00FFFFFFu;
    }
}

static void ejecutar_negativo(uint32_t *data, int count, int threads, bool usar_threads, bool usar_simd) {
    if (count <= 0) return;

    // Modo secuencial real: no crea hilos; procesa todo en el hilo actual.
    if (!usar_threads) {
        negativo_rango(data, 0, count, usar_simd);
        return;
    }

    int T = std::max(1, std::min(threads, count));
    int bloque = (count + T - 1) / T;

    std::vector<std::thread> hs;
    hs.reserve(T);

    for (int t = 0; t < T; t++) {
        int ini = t * bloque;
        int fin = std::min(count, ini + bloque);
        if (ini >= fin) break;
        hs.emplace_back(negativo_rango, data, ini, fin, usar_simd);
    }

    for (auto &h: hs) h.join();
}

static long long medir_ns(uint32_t *data, int count, int threads, bool usar_threads, bool usar_simd) {
    auto ti = std::chrono::high_resolution_clock::now();
    ejecutar_negativo(data, count, threads, usar_threads, usar_simd);
    auto tf = std::chrono::high_resolution_clock::now();
    return std::chrono::duration_cast<std::chrono::nanoseconds>(tf - ti).count();
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_negativosimd_NativeProcessor_negativoARGB(
        JNIEnv *env,
        jclass,
        jintArray pixels,
        jint count,
        jint threads,
        jboolean useSimd) {

    jint *raw = env->GetIntArrayElements(pixels, nullptr);
    if (raw == nullptr || count <= 0) return 0;

    auto *data = reinterpret_cast<uint32_t *>(raw);
    long long ns = medir_ns(data, count, threads, true, useSimd == JNI_TRUE);

    env->ReleaseIntArrayElements(pixels, raw, 0);
    return ns;
}

extern "C"
JNIEXPORT jlongArray JNICALL
Java_com_example_negativosimd_NativeProcessor_benchmarkARGB(
        JNIEnv *env,
        jclass,
        jintArray pixels,
        jint count,
        jint threads) {

    jlongArray salida = env->NewLongArray(4);
    if (salida == nullptr || count <= 0) return salida;

    jint *raw = env->GetIntArrayElements(pixels, nullptr);
    if (raw == nullptr) return salida;

    auto *base = reinterpret_cast<uint32_t *>(raw);
    std::vector<jlong> tiempos(4, 0);

    // Las cuatro mediciones usan copias de la misma imagen base.
    // Así la comparación es justa: todos procesan exactamente los mismos píxeles.
    {
        std::vector<uint32_t> work(base, base + count);
        tiempos[0] = medir_ns(work.data(), count, threads, false, false); // Secuencial C++
    }
    {
        std::vector<uint32_t> work(base, base + count);
        tiempos[1] = medir_ns(work.data(), count, threads, true, false);  // Threads sin SIMD
    }
    {
        std::vector<uint32_t> work(base, base + count);
        tiempos[2] = medir_ns(work.data(), count, threads, false, true);  // SIMD sin threads
    }
    {
        std::vector<uint32_t> work(base, base + count);
        tiempos[3] = medir_ns(work.data(), count, threads, true, true);   // Threads + SIMD
    }

    env->ReleaseIntArrayElements(pixels, raw, JNI_ABORT);
    env->SetLongArrayRegion(salida, 0, 4, tiempos.data());
    return salida;
}
