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
    private String bestAnswersJson = "{}";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scanner);

        previewView = findViewById(R.id.previewView);
        tvScoreOverlay = findViewById(R.id.tvScoreOverlay);
        Button btnFinish = findViewById(R.id.btnFinish);

        gradeScanner = new GradeScanner();
        cameraExecutor = Executors.newSingleThreadExecutor();

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
            Log.e(TAG, "Key Parse Error", e);
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
                Log.e(TAG, "Camera Init Error", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processFrame(@NonNull ImageProxy image) {
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);

        Mat matWithStride = new Mat(image.getHeight(), plane.getRowStride(), CvType.CV_8UC1);
        matWithStride.put(0, 0, data);

        // **CRASH FIX IS HERE**: We clone the submatrix to a new, compact Mat
        // This prevents using memory that has been deallocated (use-after-free).
        Mat mat;
        if (plane.getRowStride() != image.getWidth()) {
            mat = matWithStride.submat(0, image.getHeight(), 0, image.getWidth()).clone();
        } else {
            mat = matWithStride.clone();
        }
        matWithStride.release(); // The original is now safe to release.

        int rotation = image.getImageInfo().getRotationDegrees();
        if (rotation == 90) Core.rotate(mat, mat, Core.ROTATE_90_CLOCKWISE);
        else if (rotation == 180) Core.rotate(mat, mat, Core.ROTATE_180);
        else if (rotation == 270) Core.rotate(mat, mat, Core.ROTATE_90_COUNTERCLOCKWISE);
        
        GradeScanner.ScanResult result = gradeScanner.grade(mat, answerKey, optionsCount);

        runOnUiThread(() -> {
            String scoreDisplay = result.score + " / " + answerKey.size();
            tvScoreOverlay.setText(scoreDisplay);

            if (result.score >= bestScore) {
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
}