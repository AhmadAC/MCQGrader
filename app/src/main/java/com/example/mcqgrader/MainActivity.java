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
    private Button btnClearKey;

    private Map<Integer, Integer> currentAnswerKey = new HashMap<>();
    private int currentOptionsCount = 6;
    private String currentQuizName = "Unknown Quiz";
    private String lastExportJson = null;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    launchScanner();
                } else {
                    Toast.makeText(this, "Camera permission is required to scan.", Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<Intent> scannerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    int score = result.getData().getIntExtra("score", -1);
                    if (score == -1) {
                         tvResults.setText("Scanning was cancelled or no score was captured.");
                         return;
                    }

                    String answersJson = result.getData().getStringExtra("answers");
                    String finalResultText = "Scan Complete!\nQuiz: " + currentQuizName +
                                         "\nFinal Score: " + score + " / " + currentAnswerKey.size();
                    tvResults.setText(finalResultText);
                    
                    btnExportAnswers.setVisibility(View.VISIBLE);
                    generateExportJson(score, answersJson);
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
                        Toast.makeText(this, "Export failed.", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV initialization failed");
        }

        tvResults = findViewById(R.id.tvResults);
        btnExportAnswers = findViewById(R.id.btnExportAnswers);
        btnClearKey = findViewById(R.id.btnClearKey);
        Button btnScanSheet = findViewById(R.id.btnScanSheet);
        Button btnLoadKey = findViewById(R.id.btnLoadKey);

        btnScanSheet.setOnClickListener(v -> {
            if (currentAnswerKey.isEmpty()) {
                Toast.makeText(this, "Please load an answer key first.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchScanner();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });

        btnLoadKey.setOnClickListener(v -> {
            // This intent forces a chooser, allowing you to select your preferred file explorer.
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            
            Intent chooser = Intent.createChooser(intent, "Select Answer Key using...");
            filePickerLauncher.launch(chooser);
        });

        btnClearKey.setOnClickListener(v -> {
            currentAnswerKey.clear();
            currentQuizName = "Unknown Quiz";
            tvResults.setText("No data loaded.");
            btnExportAnswers.setVisibility(View.GONE);
            btnClearKey.setVisibility(View.GONE);
            Toast.makeText(this, "Answer Key cleared.", Toast.LENGTH_SHORT).show();
        });

        btnExportAnswers.setOnClickListener(v -> {
             if (lastExportJson != null) {
                jsonExportLauncher.launch(currentQuizName.replaceAll("\\s+", "_") + "_answers.json");
            } else {
                Toast.makeText(this, "No scan data to export.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void launchScanner() {
        Intent intent = new Intent(this, ScannerActivity.class);
        // Pass the answer key to the scanner as a simple JSON string
        intent.putExtra("key_json", new JSONObject(currentAnswerKey).toString());
        intent.putExtra("options_count", currentOptionsCount);
        scannerLauncher.launch(intent);
    }

    private void loadJsonFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);

            JSONObject json = new JSONObject(sb.toString());
            currentQuizName = json.optString("quiz_name", "Unknown Quiz");
            currentOptionsCount = json.optInt("options_count", 6);
            JSONObject answers = json.getJSONObject("answers");
            currentAnswerKey.clear();
            Iterator<String> keys = answers.keys();
            while (keys.hasNext()) {
                String keyStr = keys.next();
                int qNum = Integer.parseInt(keyStr);
                int ansIndex = answers.getString(keyStr).toUpperCase().charAt(0) - 'A';
                currentAnswerKey.put(qNum, ansIndex);
            }
            String successMsg = "Loaded Key: '" + currentQuizName + "'\n(" + currentAnswerKey.size() + " Questions)";
            tvResults.setText(successMsg);
            btnClearKey.setVisibility(View.VISIBLE);
            btnExportAnswers.setVisibility(View.GONE);
        } catch (Exception e) {
            tvResults.setText("Error: Could not load or parse the JSON file.");
            Log.e(TAG, "JSON Load Error", e);
        }
    }
    
    private void generateExportJson(int score, String answersJson) {
         try {
            JSONObject root = new JSONObject();
            root.put("quiz_name", currentQuizName);
            root.put("score", score);
            root.put("total_questions", currentAnswerKey.size());

            // Convert the student answers string back to a JSON object to embed
            if (answersJson != null && !answersJson.isEmpty()) {
                 JSONObject studentAnswers = new JSONObject(answersJson);
                 JSONObject formattedAnswers = new JSONObject();
                 Iterator<String> keys = studentAnswers.keys();
                 while(keys.hasNext()) {
                     String qNum = keys.next();
                     int ansIndex = studentAnswers.getInt(qNum);
                     String ansLetter = (ansIndex >= 0) ? String.valueOf((char)('A' + ansIndex)) : "N/A";
                     formattedAnswers.put(qNum, ansLetter);
                 }
                 root.put("student_answers", formattedAnswers);
            }
            lastExportJson = root.toString(4);
        } catch (Exception e) {
            Log.e(TAG, "Error generating export JSON", e);
            lastExportJson = null;
        }
    }
}