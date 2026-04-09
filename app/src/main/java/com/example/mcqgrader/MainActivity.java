package com.example.mcqgrader;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.opencv.android.Utils;
import org.opencv.core.Mat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private Uri photoUri;
    private TextView tvResults;
    private GradeScanner gradeScanner;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); 

        gradeScanner = new GradeScanner();
        tvResults = findViewById(R.id.tvResults);
        Button btnScanSheet = findViewById(R.id.btnScanSheet); 
        Button btnLoadKey = findViewById(R.id.btnLoadKey);
        
        btnScanSheet.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCameraIntent();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });

        btnLoadKey.setOnClickListener(v -> Toast.makeText(this, "JSON Loader not yet implemented", Toast.LENGTH_SHORT).show());
    }

    private void launchCameraIntent() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            try {
                File photoFile = File.createTempFile("SCAN_", ".jpg", getCacheDir());
                photoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                takePictureLauncher.launch(takePictureIntent);
            } catch (IOException ex) {
                Toast.makeText(this, "File creation failed", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void processCapturedImage() {
        try (InputStream is = getContentResolver().openInputStream(photoUri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            if (bitmap != null) {
                Mat mat = new Mat();
                Utils.bitmapToMat(bitmap, mat);
                
                // Create a dummy key for testing (Question 1 = Option 0/A)
                Map<Integer, Integer> testKey = new HashMap<>();
                testKey.put(1, 0); 
                
                int score = gradeScanner.grade(mat, testKey);
                tvResults.setText("Scan Complete.\nScore: " + score);
            }
        } catch (Exception e) {
            tvResults.setText("Error processing image: " + e.getMessage());
        }
    }
}