package com.example.mcqgrader;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;
import org.opencv.android.OpenCVLoader;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MCQGrader_Main";
    private TextView tvResults;
    private Button btnExportAnswers;

    // JSON Loaded Data
    private Map<Integer, Integer> currentAnswerKey = new HashMap<>();
    private int currentOptionsCount = 6;
    private String currentQuizName = "Unknown Quiz";

    // Store JSON dump of last scan
    private String lastExportJson = null;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    launchCameraIntent();
                } else {
                    Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<Intent> scannerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    int bestScore = result.getData().getIntExtra("bestScore", 0);
                    String studentAnswersJson = result.getData().getStringExtra("studentAnswersJson");
                    
                    Map<Integer, Integer> studentAnswers = new HashMap<>();
                    if (studentAnswersJson != null) {
                        try {
                            JSONObject json = new JSONObject(studentAnswersJson);
                            Iterator<String> keys = json.keys();
                            while (keys.hasNext()) {
                                String k = keys.next();
                                studentAnswers.put(Integer.parseInt(k), json.getInt(k));
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing best answers", e);
                        }
                    }

                    lastExportJson = generateStudentJson(studentAnswers, bestScore);
                    if (lastExportJson != null) {
                        btnExportAnswers.setVisibility(View.VISIBLE);
                    }

                    String finalResult = "Scan Complete!\nQuiz: " + currentQuizName + 
                                         "\nCaptured Score: " + bestScore + " / " + currentAnswerKey.size();
                    tvResults.setText(finalResult);
                }
            });

    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        loadJsonFromUri(uri);
                    }
                }
            });

    private final ActivityResultLauncher<String> jsonExportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri != null && lastExportJson != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            os.write(lastExportJson.getBytes());
                            Toast.makeText(this, "Export successful!", Toast.LENGTH_LONG).show();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to save export file", e);
                        Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); 

        if (OpenCVLoader.initDebug()) {
            Log.d(TAG, "OpenCV initialized successfully");
        } else {
            Log.e(TAG, "OpenCV initialization failed");
            Toast.makeText(this, "OpenCV initialization failed", Toast.LENGTH_LONG).show();
        }

        tvResults = findViewById(R.id.tvResults);
        Button btnScanSheet = findViewById(R.id.btnScanSheet); 
        Button btnLoadKey = findViewById(R.id.btnLoadKey);
        btnExportAnswers = findViewById(R.id.btnExportAnswers);
        
        btnScanSheet.setOnClickListener(v -> {
            if (currentAnswerKey.isEmpty()) {
                Toast.makeText(this, "Please upload an Answer Key first.", Toast.LENGTH_LONG).show();
                tvResults.setText("Error: Cannot grade without loading a JSON Answer Key.");
                return;
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCameraIntent();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });

        btnLoadKey.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*"); 
            String