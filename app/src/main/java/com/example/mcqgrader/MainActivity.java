package com.example.mcqgrader;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private TextView tvResults;
    private Map<Integer, Integer> answerKey = new HashMap<>();
    private Map<String, Integer> alphaMap = new HashMap<>();

    private final ActivityResultLauncher<String> jsonPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> { if (uri != null) loadJson(uri); });

    private final ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Bundle extras = result.getData().getExtras();
                    if (extras != null) {
                        Bitmap bitmap = (Bitmap) extras.get("data");
                        if (bitmap != null) {
                            processImage(bitmap);
                        }
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV initialization failed!");
        } else {
            Log.d(TAG, "OpenCV initialized successfully!");
        }

        tvResults = findViewById(R.id.tvResults);
        Button btnLoadKey = findViewById(R.id.btnLoadKey);
        Button btnScan = findViewById(R.id.btnScanSheet);

        // Map A-F to 0-5
        String alpha = "ABCDEF";
        for (int i = 0; i < alpha.length(); i++) {
            alphaMap.put(String.valueOf(alpha.charAt(i)), i);
        }

        btnLoadKey.setOnClickListener(v -> jsonPicker.launch("application/json"));
        btnScan.setOnClickListener(v -> {
            if (answerKey.isEmpty()) {
                Toast.makeText(this, "Please load an answer key JSON file first.", Toast.LENGTH_SHORT).show();
            } else {
                cameraLauncher.launch(new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
            }
        });
    }

    private void loadJson(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            JSONObject json = new JSONObject(new String(buffer, "UTF-8")).getJSONObject("answers");
            
            answerKey.clear();
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String qNum = keys.next();
                answerKey.put(Integer.parseInt(qNum), alphaMap.get(json.getString(qNum)));
            }
            tvResults.setText("Key Loaded: " + answerKey.size() + " Questions.");
            Toast.makeText(this, "Answer key loaded!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load or parse JSON", e);
            tvResults.setText("Error loading answer key: " + e.getMessage());
            Toast.makeText(this, "Invalid JSON Format", Toast.LENGTH_SHORT).show();
        }
    }

    private void processImage(Bitmap bitmap) {
        // --- Step 1: Use TextScanner to find any text ---
        new TextScanner().scan(bitmap, text -> {
            // This is an asynchronous callback, the code inside here runs when text scanning is complete.
            
            // --- Step 2: Convert Bitmap to OpenCV Mat for grading ---
            Mat imageMat = new Mat();
            Bitmap bmp32 = bitmap.copy(Bitmap.Config.ARGB_8888, true);
            Utils.bitmapToMat(bmp32, imageMat);

            // --- Step 3: Use GradeScanner to get the score ---
            int score = new GradeScanner().grade(imageMat, answerKey);
            
            // --- Step 4: Combine results and display them ---
            String studentInfo = "Detected Text:\n" + text + "\n\n";
            String finalResult = studentInfo + "Final Score: " + score + " / " + answerKey.size();
            
            // UI updates must be run on the main thread
            runOnUiThread(() -> tvResults.setText(finalResult));
        });
    }
}