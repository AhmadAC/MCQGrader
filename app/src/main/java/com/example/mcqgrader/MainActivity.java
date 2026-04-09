package com.example.mcqgrader;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.json.JSONObject;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MCQGrader_Main";
    private Uri photoUri;
    private TextView tvResults;
    private Button btnExportAnswers;
    private GradeScanner gradeScanner;

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

    private final ActivityResultLauncher<Intent> takePictureLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    processCapturedImage();
                }
            });

    // A robust launcher that receives the result from the file picker
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

        gradeScanner = new GradeScanner();
        tvResults = findViewById(R.id.tvResults);
        Button btnScanSheet = findViewById(R.id.btnScanSheet); 
        Button btnLoadKey = findViewById(R.id.btnLoadKey);
        btnExportAnswers = findViewById(R.id.btnExportAnswers);
        
        btnScanSheet.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCameraIntent();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });

        // **UPDATED**: Use Intent.createChooser() to force an app selection dialog
        btnLoadKey.setOnClickListener(v -> {
            // 1. Create the base intent to get content
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*"); // Set the general type to all files
            String[] mimeTypes = {"application/json", "text/plain"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes); // Provide specific hints

            // 2. Wrap the base intent in a chooser intent
            Intent chooserIntent = Intent.createChooser(intent, "Select Answer Key using...");
            
            // 3. Launch the chooser intent
            filePickerLauncher.launch(chooserIntent);
        });

        btnExportAnswers.setOnClickListener(v -> {
            if (lastExportJson != null) {
                jsonExportLauncher.launch("student_answers.json");
            } else {
                Toast.makeText(this, "No scan data available to export.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadJsonFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            JSONObject json = new JSONObject(sb.toString());
            currentQuizName = json.optString("quiz_name", "Unknown Quiz");
            currentOptionsCount = json.optInt("options_count", 6);

            JSONObject answers = json.getJSONObject("answers");
            currentAnswerKey.clear();

            Iterator<String> keys = answers.keys();
            while (keys.hasNext()) {
                String keyStr = keys.next();
                int qNum = Integer.parseInt(keyStr);
                
                String ansLetter = answers.getString(keyStr).toUpperCase();
                int ansIndex = ansLetter.charAt(0) - 'A';
                
                currentAnswerKey.put(qNum, ansIndex);
            }

            String successMsg = "Successfully Loaded Key:\n" + currentQuizName + "\nTotal Questions: " + currentAnswerKey.size() + "\nOptions/Question: " + currentOptionsCount;
            tvResults.setText(successMsg);
            Toast.makeText(this, "Answer Key Loaded", Toast.LENGTH_SHORT).show();
            btnExportAnswers.setVisibility(View.GONE);
            lastExportJson = null;

        } catch (Exception e) {
            Log.e(TAG, "Failed to load JSON", e);
            Toast.makeText(this, "Failed to load JSON", Toast.LENGTH_SHORT).show();
            tvResults.setText("Error loading JSON: \n" + e.getMessage());
        }
    }

    private void launchCameraIntent() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        try {
            File photoFile = File.createTempFile("SCAN_", ".jpg", getCacheDir());
            photoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
            takePictureIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            takePictureLauncher.launch(takePictureIntent);
        } catch (IOException ex) {
            Log.e(TAG, "File creation failed", ex);
            Toast.makeText(this, "File creation failed", Toast.LENGTH_SHORT).show();
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "No camera app found", e);
            Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show();
        }
    }

    private void processCapturedImage() {
        if (photoUri == null) return;

        btnExportAnswers.setVisibility(View.GONE);
        lastExportJson = null;
        
        if (currentAnswerKey.isEmpty()) {
            Toast.makeText(this, "Please upload an Answer Key first.", Toast.LENGTH_LONG).show();
            tvResults.setText("Error: Cannot grade without loading a JSON Answer Key.");
            return;
        }
        
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(photoUri)) {
                BitmapFactory.decodeStream(is, null, options);
            }
            
            int reqWidth = 1200;
            int reqHeight = 1200;
            int inSampleSize = 1;
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                final int halfHeight = options.outHeight / 2;
                final int halfWidth = options.outWidth / 2;
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2;
                }
            }
            
            options.inJustDecodeBounds = false;
            options.inSampleSize = inSampleSize;
            
            Bitmap bitmap;
            try (InputStream is = getContentResolver().openInputStream(photoUri)) {
                bitmap = BitmapFactory.decodeStream(is, null, options);
            }
            
            if (bitmap != null) {
                int orientation = ExifInterface.ORIENTATION_NORMAL;
                try (InputStream is = getContentResolver().openInputStream(photoUri)) {
                    ExifInterface ei = new ExifInterface(is);
                    orientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                }
                
                int degree = 0;
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90) degree = 90;
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) degree = 180;
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) degree = 270;
                
                if (degree != 0) {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(degree);
                    Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    bitmap.recycle();
                    bitmap = rotated;
                }

                if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                    Bitmap converted = bitmap.copy(Bitmap.Config.ARGB_8888, true);
                    bitmap.recycle();
                    bitmap = converted;
                }
                
                Mat mat = new Mat();
                Utils.bitmapToMat(bitmap, mat);
                
                GradeScanner.ScanResult result = gradeScanner.grade(mat, currentAnswerKey, currentOptionsCount);
                int score = result.score;

                lastExportJson = generateStudentJson(result.studentAnswers, score);
                if (lastExportJson != null) {
                    btnExportAnswers.setVisibility(View.VISIBLE);
                }
                
                String finalResult = "Scan Complete!\nQuiz: " + currentQuizName + 
                                     "\nFinal Score: " + score + " / " + currentAnswerKey.size();
                tvResults.setText(finalResult);
                
                mat.release();
                bitmap.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing image", e);
            tvResults.setText("Error processing image: " + e.getMessage());
        } catch (Error e) {
            Log.e(TAG, "Native library error", e);
            tvResults.setText("System error (Native): " + e.getMessage());
        }
    }

    private String generateStudentJson(Map<Integer, Integer> studentAnswers, int score) {
        try {
            JSONObject root = new JSONObject();
            root.put("quiz_name", currentQuizName);
            root.put("score", score);
            root.put("total_questions", currentAnswerKey.size());

            JSONObject answersObject = new JSONObject();
            for (Map.Entry<Integer, Integer> entry : studentAnswers.entrySet()) {
                String questionNum = String.valueOf(entry.getKey());
                String answerLetter = "N/A";
                if (entry.getValue() >= 0) {
                    answerLetter = String.valueOf((char) ('A' + entry.getValue()));
                }
                answersObject.put(questionNum, answerLetter);
            }
            root.put("student_answers", answersObject);

            return root.toString(4);
        } catch (Exception e) {
            Log.e(TAG, "Error generating student JSON", e);
            return null;
        }
    }
}