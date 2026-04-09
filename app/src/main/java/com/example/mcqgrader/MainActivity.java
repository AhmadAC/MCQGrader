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
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MCQGrader_Main";
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

        // Critical: Initialize OpenCV before any Mat operations
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
        try {
            File photoFile = File.createTempFile("SCAN_", ".jpg", getCacheDir());
            photoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
            // Allow Uri permissions for Samsung Camera App
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
        
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(photoUri)) {
                BitmapFactory.decodeStream(is, null, options);
            }
            
            // Scaled down to prevent OOM on S21+ high-res images
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
                // Correct Samsung Auto-Rotation
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
                
                Map<Integer, Integer> testKey = new HashMap<>();
                testKey.put(1, 0); 
                
                int score = gradeScanner.grade(mat, testKey);
                tvResults.setText("Scan Complete.\nScore: " + score);
                
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
}