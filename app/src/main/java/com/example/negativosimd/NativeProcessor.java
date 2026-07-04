package com.example.negativosimd;

public final class NativeProcessor {
    static {
        System.loadLibrary("negativosimd");
    }

    private NativeProcessor() {}

    // Devuelve el tiempo nativo de procesamiento en nanosegundos.
    public static native long negativoARGB(int[] pixels, int count, int threads, boolean useSimd);

    // Devuelve 4 tiempos en nanosegundos usando la misma imagen base:
    // [0] C++ secuencial, [1] C++ con threads, [2] C++ SIMD, [3] C++ threads + SIMD.
    public static native long[] benchmarkARGB(int[] pixels, int count, int threads);
}
