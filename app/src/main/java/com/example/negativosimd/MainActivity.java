package com.example.negativosimd;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private static final int REQ_CAMERA = 10;

    private ImageView imageView;
    private TextView infoText;
    private TextView benchmarkText;
    private Button saveButton;
    private Button benchmarkButton;

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService benchmarkExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean busy = false;
    private final Object bitmapLock = new Object();
    private Bitmap currentBitmap = null;

    private long lastCaptureMs = 0;
    private long lastConvertMs = 0;
    private long lastProcessMs = 0;
    private long lastStorageMs = 0;
    private int frame = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 12, 12, 12);

        imageView = new ImageView(this);
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(imageView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        infoText = new TextView(this);
        infoText.setGravity(Gravity.CENTER);
        infoText.setText("Negativo en tiempo real: C++ + threads + SIMD NEON");
        root.addView(infoText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        benchmarkText = new TextView(this);
        benchmarkText.setGravity(Gravity.CENTER);
        benchmarkText.setText("Benchmark: presiona el botón para comparar la misma foto en 4 modos.");
        root.addView(benchmarkText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        benchmarkButton = new Button(this);
        benchmarkButton.setText("Comparar threads y SIMD");
        benchmarkButton.setOnClickListener(v -> runBenchmarkOnCurrentPhoto());
        root.addView(benchmarkButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        saveButton = new Button(this);
        saveButton.setText("Guardar foto procesada");
        saveButton.setOnClickListener(v -> saveProcessedPhoto());
        root.addView(saveButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Se necesita permiso de cámara", Toast.LENGTH_LONG).show();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        analysis
                );
            } catch (Exception e) {
                Toast.makeText(this, "Error iniciando cámara: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(ImageProxy image) {
        if (busy) {
            image.close();
            return;
        }
        busy = true;

        try {
            long t0 = System.nanoTime();

            int width = image.getWidth();
            int height = image.getHeight();
            ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
            ByteBuffer yBuffer = yPlane.getBuffer();
            byte[] yData = new byte[yBuffer.remaining()];
            yBuffer.get(yData);

            long t1 = System.nanoTime();

            int rowStride = yPlane.getRowStride();
            int pixelStride = yPlane.getPixelStride();
            int[] pixels = new int[width * height];

            // Conversión Y de YUV_420_888 a imagen gris ARGB.
            // Cada píxel de 8 bits queda como R=G=B=Y.
            for (int y = 0; y < height; y++) {
                int rowOffset = y * rowStride;
                int outOffset = y * width;
                for (int x = 0; x < width; x++) {
                    int lum = yData[rowOffset + x * pixelStride] & 0xFF;
                    pixels[outOffset + x] = 0xFF000000 | (lum << 16) | (lum << 8) | lum;
                }
            }

            long t2 = System.nanoTime();

            int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
            boolean useSimd = true;
            long nativeNs = NativeProcessor.negativoARGB(pixels, pixels.length, threads, useSimd);

            long t3 = System.nanoTime();

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);

            lastCaptureMs = (t1 - t0) / 1_000_000;
            lastConvertMs = (t2 - t1) / 1_000_000;
            lastProcessMs = nativeNs / 1_000_000;

            runOnUiThread(() -> {
                synchronized (bitmapLock) {
                    currentBitmap = bitmap;
                }
                imageView.setImageBitmap(bitmap);
                frame++;
                if (frame % 5 == 0) {
                    updateTimingText();
                }
            });
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Error procesando frame: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        } finally {
            image.close();
            busy = false;
        }
    }

    private void updateTimingText() {
        long totalFrame = lastCaptureMs + lastConvertMs + lastProcessMs;
        long totalWithStorage = totalFrame + lastStorageMs;

        infoText.setText(String.format(Locale.US,
                "Modo tiempo real: C++ threads + SIMD NEON\n" +
                        "Captura: %d ms | Conversión: %d ms | Negativo: %d ms\n" +
                        "Guardado: %d ms | Total frame: %d ms | Total con guardado: %d ms",
                lastCaptureMs, lastConvertMs, lastProcessMs,
                lastStorageMs, totalFrame, totalWithStorage));
    }

    private void runBenchmarkOnCurrentPhoto() {
        Bitmap bitmapToTest;
        synchronized (bitmapLock) {
            if (currentBitmap == null) {
                Toast.makeText(this, "Aún no hay imagen procesada", Toast.LENGTH_SHORT).show();
                return;
            }
            bitmapToTest = currentBitmap.copy(Bitmap.Config.ARGB_8888, false);
        }

        benchmarkText.setText("Benchmark ejecutándose sobre la misma foto...");

        benchmarkExecutor.execute(() -> {
            try {
                int width = bitmapToTest.getWidth();
                int height = bitmapToTest.getHeight();
                int[] basePixels = new int[width * height];
                bitmapToTest.getPixels(basePixels, 0, width, 0, 0, width, height);

                int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
                long[] ns = NativeProcessor.benchmarkARGB(basePixels, basePixels.length, threads);

                double secuencialMs = ns[0] / 1_000_000.0;
                double threadsMs = ns[1] / 1_000_000.0;
                double simdMs = ns[2] / 1_000_000.0;
                double ambosMs = ns[3] / 1_000_000.0;
                double speedup = ambosMs > 0.0 ? secuencialMs / ambosMs : 0.0;

                runOnUiThread(() -> benchmarkText.setText(String.format(Locale.US,
                        "Benchmark misma foto %dx%d, %d hilos:\n" +
                                "C++ secuencial: %.3f ms | Threads: %.3f ms\n" +
                                "SIMD: %.3f ms | Threads+SIMD: %.3f ms\n" +
                                "Aceleración aprox.: %.2fx frente a secuencial",
                        width, height, threads,
                        secuencialMs, threadsMs, simdMs, ambosMs, speedup)));
            } catch (Exception e) {
                runOnUiThread(() -> benchmarkText.setText("Error en benchmark: " + e.getMessage()));
            }
        });
    }

    private void saveProcessedPhoto() {
        Bitmap bitmapToSave;
        synchronized (bitmapLock) {
            if (currentBitmap == null) {
                Toast.makeText(this, "Aún no hay imagen procesada", Toast.LENGTH_SHORT).show();
                return;
            }
            bitmapToSave = currentBitmap.copy(Bitmap.Config.ARGB_8888, false);
        }

        long ti = System.nanoTime();
        String name = "negativo_" + System.currentTimeMillis() + ".png";

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NegativoSIMD");
            }

            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new RuntimeException("No se pudo crear el archivo");

            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new RuntimeException("No se pudo abrir salida");
                bitmapToSave.compress(Bitmap.CompressFormat.PNG, 100, out);
            }

            long tf = System.nanoTime();
            lastStorageMs = (tf - ti) / 1_000_000;
            updateTimingText();
            Toast.makeText(this, "Foto guardada en Pictures/NegativoSIMD\nTiempo: " + lastStorageMs + " ms", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error guardando: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        benchmarkExecutor.shutdown();
    }
}
