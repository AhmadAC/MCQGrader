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

    private TextView tvResults;
    private Map<Integer, Integer> answerKey = new HashMap<>();
    private Map<String, Integer> alphaMap = new HashMap<>();

    private final ActivityResultLauncher<String> jsonPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> { if (uri != null) loadJson(uri); });

    private final ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Bitmap bitmap = (Bitmap) result.getData().getExtras().get("data");
                    processImage(bitmap);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        OpenCVLoader.initDebug();

        tvResults = findViewById(R.id.tvResults);
        Button btnLoadKey = findViewById(R.id.btnLoadKey);
        Button btnScan = findViewById(R.id.btnScanSheet);

        // Map A-F to 0-5
        String alpha = "ABCDEF";
        for (int i = 0; i < alpha.length(); i++) alphaMap.put(String.valueOf(alpha.charAt(i)), i);

        btnLoadKey.setOnClickListener(v -> jsonPicker.launch("application/json"));
        btnScan.setOnClickListener(v -> {
            if (answerKey.isEmpty()) {
                Toast.makeText(this, "Load JSON first!", Toast.LENGTH_SHORT).show();
            } else {
                cameraLauncher.launch(new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
            }
        });
    }

    private void loadJson(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();
            JSONObject json = new JSONObject(new String(buffer, "UTF-8")).getJSONObject("answers");
            
            answerKey.clear();
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String qNum = keys.next();
                answerKey.put(Integer.parseInt(qNum), alphaMap.get(json.getString(qNum)));
            }
            tvResults.setText("Key Loaded: " + answerKey.size() + " Questions.");
        } catch (Exception e) {
            Toast.makeText(this, "Invalid JSON Format", Toast.LENGTH_SHORT).show();
        }
    }

    private void processImage(Bitmap bitmap) {
        new TextScanner().scan(bitmap, text -> {
            Mat mat = new Mat();
            Utils.bitmapToMat(bitmap, mat);
            int score = new GradeScanner().grade(mat, answerKey);
            
            String output = "--- SCAN COMPLETE ---\n" +
                            "Text Found: " + text.substring(0, Math.min(text.length(), 50)) + "...\n" +
                            "Final Score: " + score + " / " + answerKey.size();
            tvResults.setText(output);
        });
    }
}
