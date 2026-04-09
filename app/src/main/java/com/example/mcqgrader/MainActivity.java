package com.example.mcqgrader;

import android.Manifest;
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

        if (OpenCVLoader.initDebug()) {
            Log.d("OpenCV", "OpenCV initialized successfully");
        } else {
            Log.e("OpenCV", "OpenCV initialization failed");
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
        try {
            // Decode boundaries to verify dimensions and prevent OutOfMemory crashes
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(photoUri)) {
                BitmapFactory.decodeStream(is, null, options);
            }
            
            // Calculate scale down factor
            int reqWidth = 1024;
            int reqHeight = 1024;
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
                // Read EXIF tag to detect incorrect image rotations natively
                int orientation = ExifInterface.ORIENTATION_NORMAL;
                try (InputStream is = getContentResolver().openInputStream(photoUri)) {
                    ExifInterface ei = new ExifInterface(is);
                    orientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                }
                
                int degree = 0;
                switch (orientation) {
                    case ExifInterface.ORIENTATION_ROTATE_90: degree = 90; break;
                    case ExifInterface.ORIENTATION_ROTATE_180: degree = 180; break;
                    case ExifInterface.ORIENTATION_ROTATE_270: degree = 270; break;
                }
                
                if (degree != 0) {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(degree);
                    Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    if (rotatedBitmap != bitmap) {
                        bitmap.recycle();
                        bitmap = rotatedBitmap;
                    }
                }

                // Strictly enforce format compatibility with OpenCV BitmapToMat algorithm
                if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                    Bitmap convertedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
                    if (convertedBitmap != bitmap) {
                        bitmap.recycle();
                        bitmap = convertedBitmap;
                    }
                }
                
                Mat mat = new Mat();
                Utils.bitmapToMat(bitmap, mat);
                
                // Create a dummy key for testing (Question 1 = Option 0/A)
                Map<Integer, Integer> testKey = new HashMap<>();
                testKey.put(1, 0); 
                
                int score = gradeScanner.grade(mat, testKey);
                tvResults.setText("Scan Complete.\nScore: " + score);
                
                mat.release();
            } else {
                tvResults.setText("Error: Could not decode image.");
            }
        } catch (Exception e) {
            tvResults.setText("Error processing image: " + e.getMessage());
            e.printStackTrace();
        } catch (Error e) {
            tvResults.setText("System error processing image: " + e.getMessage());
            e.printStackTrace();
        }
    }
}