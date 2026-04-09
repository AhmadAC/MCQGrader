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
                    Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<Intent> scannerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    int score = result.getData().getIntExtra("score", 0);
                    String answersJson = result.getData().getStringExtra("answers");
                    
                    tvResults.setText("Scan Complete!\nFinal Score: " + score + " / " + currentAnswerKey.size());
                    btnExportAnswers.setVisibility(View.VISIBLE);
                    
                    try {
                        JSONObject root = new JSONObject();
                        root.put("quiz_name", currentQuizName);
                        root.put("score", score);
                        root.put("total_questions", currentAnswerKey.size());
                        if (answersJson != null) root.put("student_answers", new JSONObject(answersJson));
                        lastExportJson = root.toString(4);
                    } catch (Exception e) {
                        Log.e(TAG, "Export Error", e);
                    }
                }
            });

    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    loadJsonFromUri(result.getData().getData());
                }
            });

    private final ActivityResultLauncher<String> jsonExportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri != null && lastExportJson != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        os.write(lastExportJson.getBytes());
                        Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Log.e(TAG, "Save Error", e);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        OpenCVLoader.initDebug();

        tvResults = findViewById(R.id.tvResults);
        btnExportAnswers = findViewById(R.id.btnExportAnswers);
        btnClearKey = findViewById(R.id.btnClearKey);
        Button btnScanSheet = findViewById(R.id.btnScanSheet);
        Button btnLoadKey = findViewById(R.id.btnLoadKey);

        btnLoadKey.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            filePickerLauncher.launch(Intent.createChooser(intent, "Select Answer Key..."));
        });

        btnScanSheet.setOnClickListener(v -> {
            if (currentAnswerKey.isEmpty()) {
                Toast.makeText(this, "Load a key first!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchScanner();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });

        btnClearKey.setOnClickListener(v -> {
            currentAnswerKey.clear();
            tvResults.setText("Key cleared.");
            btnClearKey.setVisibility(View.GONE);
            btnExportAnswers.setVisibility(View.GONE);
        });

        btnExportAnswers.setOnClickListener(v -> {
            if (lastExportJson != null) {
                jsonExportLauncher.launch(currentQuizName.replace(" ", "_") + "_results.json");
            }
        });
    }

    private void launchScanner() {
        try {
            Intent intent = new Intent(this, ScannerActivity.class);
            // Fix for ClassCastException: Map keys must be Strings for JSONObject
            JSONObject keyObj = new JSONObject();
            for (Map.Entry<Integer, Integer> entry : currentAnswerKey.entrySet()) {
                keyObj.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            intent.putExtra("key_json", keyObj.toString());
            intent.putExtra("options_count", currentOptionsCount);
            scannerLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Launch Error", e);
        }
    }

    private void loadJsonFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);

            JSONObject json = new JSONObject(sb.toString());
            currentQuizName = json.optString("quiz_name", "Quiz");
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
            tvResults.setText("Loaded: " + currentQuizName + "\nTotal: " + currentAnswerKey.size());
            btnClearKey.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            Toast.makeText(this, "JSON Load Error", Toast.LENGTH_SHORT).show();
        }
    }
}
