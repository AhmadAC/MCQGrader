package com.example.mcqgrader;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScannerActivity extends AppCompatActivity {
    private static final String TAG = "ScannerActivity";
    private PreviewView previewView;
    private TextView tvScoreOverlay;
    private Button btnDone;

    private ExecutorService cameraExecutor;
    private GradeScanner gradeScanner;

    private Map<Integer, Integer> currentAnswerKey = new HashMap<>();
    private int currentOptionsCount = 6;
    private int totalQuestions = 0;

    private int bestScore = 0;
    private String bestStudentAnswersJson = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scanner);

        previewView = findViewById(R.id.previewView);
        tvScoreOverlay = findViewById(R.id.tvScoreOverlay);
        btnDone = findViewById(R.id.btnDone);

        gradeScanner = new GradeScanner();

        String answerKeyJson = getIntent().getStringExtra("answerKeyJson");
        currentOptionsCount = getIntent().getIntExtra("optionsCount", 6);

        if (answerKeyJson != null) {
            try {
                JSONObject json = new JSONObject(answerKeyJson);
                Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    currentAnswerKey.put(Integer.parseInt(k), json.getInt(k));
                }
                totalQuestions = currentAnswerKey.size();
            } catch (Exception e) {
                Log.e(TAG, "Error parsing answer key", e);
            }
        }
        
        // Set initial overlay text
        tvScoreOverlay.setText("0/" + totalQuestions);

        cameraExecutor = Executors.newSingleThreadExecutor();

        btnDone.setOnClickListener(v -> {
            Intent resultIntent = new Intent();
            resultIntent.putExtra("bestScore", bestScore);
            resultIntent.putExtra("studentAnswersJson", bestStudentAnswersJson);
            setResult(RESULT_OK, resultIntent);
            finish();
        });

        startCamera();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1200, 1200))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processImage);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImage(ImageProxy image) {
        try {
            if (image.getPlanes().length == 0) {
                return;
            }

            // Extract the purely grayscale plane (Y-plane) - High Performance Trick
            ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
            ByteBuffer yBuffer = yPlane.getBuffer();
            int ySize = yBuffer.remaining();
            byte[] yData = new byte[ySize];
            yBuffer.get(yData);

            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride = yPlane.getRowStride();

            Mat grayMat;
            if (rowStride == width) {
                grayMat = new Mat(height, width, CvType.CV_8UC1);
                grayMat.put(0, 0, yData);
            } else {
                Mat paddedMat = new Mat(height, rowStride, CvType.CV_8UC1);
                paddedMat.put(0, 0, yData);
                grayMat = paddedMat.submat(0, height, 0, width).clone();
                paddedMat.release();
            }

            // Accommodate rotation differences per device architecture
            int rotationDegrees = image.getImageInfo().getRotationDegrees();
            if (rotationDegrees == 90) {
                Core.rotate(grayMat, grayMat, Core.ROTATE_90_CLOCKWISE);
            } else if (rotationDegrees == 180) {
                Core.rotate(grayMat, grayMat, Core.ROTATE_180);
            } else if (rotationDegrees == 270) {
                Core.rotate(grayMat, grayMat, Core.ROTATE_90_COUNTERCLOCKWISE);
            }

            // Run OpenCV Logic natively
            GradeScanner.ScanResult result = gradeScanner.grade(grayMat, currentAnswerKey, currentOptionsCount);
            grayMat.release();

            // Push result up to Layout Overlay
            runOnUiThread(() -> {
                String scoreText = result.score + "/" + totalQuestions;
                tvScoreOverlay.setText(scoreText);

                // Remember the most ideal alignment across frames for exporting
                if (result.score >= bestScore) {
                    bestScore = result.score;
                    try {
                        JSONObject ansJson = new JSONObject();
                        for (Map.Entry<Integer, Integer> entry : result.studentAnswers.entrySet()) {
                            ansJson.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                        bestStudentAnswersJson = ansJson.toString();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error processing frame", e);
        } finally {
            image.close();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}