package com.example.mcqgrader;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import android.widget.Button;
import android.widget.TextView;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONObject;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc; // <<< FIX: ADDED THIS IMPORT
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScannerActivity extends AppCompatActivity {
    private static final String TAG = "ScannerActivity";
    private PreviewView previewView;
    private TextView tvScoreOverlay;
    private ExecutorService cameraExecutor;
    private GradeScanner gradeScanner;
    private Map<Integer, Integer> answerKey = new HashMap<>();
    private int optionsCount;
    private int bestScore = -1;
    private String bestAnswersJson = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scanner);

        previewView = findViewById(R.id.previewView);
        tvScoreOverlay = findViewById(R.id.tvScoreOverlay);
        Button btnFinish = findViewById(R.id.btnFinish);

        gradeScanner = new GradeScanner();
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Load Answer Key passed from MainActivity
        String keyJson = getIntent().getStringExtra("key_json");
        optionsCount = getIntent().getIntExtra("options_count", 6);
        parseKey(keyJson);

        startCamera();

        btnFinish.setOnClickListener(v -> {
            Intent intent = new Intent();
            intent.putExtra("score", bestScore);
            intent.putExtra("answers", bestAnswersJson);
            setResult(RESULT_OK, intent);
            finish();
        });
    }

    private void parseKey(String jsonStr) {
        try {
            if (jsonStr == null) return;
            JSONObject obj = new JSONObject(jsonStr);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                answerKey.put(Integer.parseInt(k), obj.getInt(k));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse answer key JSON", e);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                analysis.setAnalyzer(cameraExecutor, this::processFrame);

                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (Exception e) {
                Log.e(TAG, "CameraX initialization failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processFrame(@NonNull ImageProxy image) {
        // We need an RGBA Mat for the GradeScanner, so we convert from YUV
        Mat mat = yuvToRgba(image);

        // Correct for rotation
        Core.rotate(mat, mat, Core.ROTATE_90_CLOCKWISE);

        GradeScanner.ScanResult result = gradeScanner.grade(mat, answerKey, optionsCount);

        runOnUiThread(() -> {
            String scoreDisplay = result.score + " / " + answerKey.size();
            tvScoreOverlay.setText(scoreDisplay);

            if (result.score > bestScore) {
                bestScore = result.score;
                bestAnswersJson = new JSONObject(result.studentAnswers).toString();
            }
        });

        mat.release();
        image.close();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }

    private Mat yuvToRgba(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        int width = image.getWidth();
        int height = image.getHeight();

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        Mat yuv = new Mat(height + height / 2, width, CvType.CV_8UC1);
        yuv.put(0, 0, nv21);
        Mat rgba = new Mat();
        Imgproc.cvtColor(yuv, rgba, Imgproc.COLOR_YUV2RGBA_NV21, 4);

        yuv.release();
        return rgba;
    }
}