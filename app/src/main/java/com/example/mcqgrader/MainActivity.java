package com.example.mcqgrader;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
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

import java.io.File;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private Uri photoUri;
    private TextView tvResults;

    // 1. Launcher for handling the Camera Permission request
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    // Permission was granted by the user, proceed to open the camera app
                    launchCameraIntent();
                } else {
                    // Permission denied
                    Toast.makeText(this, "Camera permission is required to scan the marking key", Toast.LENGTH_LONG).show();
                    if (tvResults != null) {
                        tvResults.setText("Error: Camera permission denied.");
                    }
                }
            });

    // 2. Launcher for handling the photo result returned from the Camera App
    private final ActivityResultLauncher<Intent> takePictureLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    // The photo was taken successfully and saved to 'photoUri'
                    Toast.makeText(this, "Marking key scanned successfully!", Toast.LENGTH_SHORT).show();
                    
                    // Update the screen to show it was successful
                    if (tvResults != null) {
                        tvResults.setText("Marking key scanned successfully!\n\nImage URI:\n" + photoUri.toString());
                    }
                    
                    // TODO: Pass photoUri to your OpenCV or grading logic here
                } else {
                    Toast.makeText(this, "Camera action cancelled", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); 

        // Link the UI elements from your XML layout
        Button btnScanSheet = findViewById(R.id.btnScanSheet); 
        Button btnLoadKey = findViewById(R.id.btnLoadKey);
        tvResults = findViewById(R.id.tvResults);
        
        // Setup click listener for the Camera button
        btnScanSheet.setOnClickListener(v -> scanMarkingKey());
        
        // Setup click listener for the Upload button (Placeholder for now)
        btnLoadKey.setOnClickListener(v -> {
            Toast.makeText(this, "Upload Answer Key feature coming soon!", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Triggered when the user clicks the button to scan the marking key.
     */
    private void scanMarkingKey() {
        // Check if we already have camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCameraIntent();
        } else {
            // We don't have permission. This line triggers the popup dialog!
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    /**
     * Creates a secure file URI and opens the system Camera app.
     */
    private void launchCameraIntent() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

        // Ensure there's a camera app available to handle the intent
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            File photoFile = null;
            try {
                photoFile = createImageFile();
            } catch (IOException ex) {
                Toast.makeText(this, "Error creating file for the image", Toast.LENGTH_SHORT).show();
                return;
            }

            // Continue only if the File was successfully created
            if (photoFile != null) {
                // Use FileProvider to get a content:// URI instead of a file:// URI.
                // This prevents the app from crashing with FileUriExposedException.
                photoUri = FileProvider.getUriForFile(this,
                        getApplicationContext().getPackageName() + ".fileprovider",
                        photoFile);

                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                takePictureLauncher.launch(takePictureIntent);
            }
        } else {
            Toast.makeText(this, "No Camera app found on this device", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Creates a temporary file in the cache directory to store the scanned marking key.
     */
    private File createImageFile() throws IOException {
        String imageFileName = "MARKING_KEY_";
        File storageDir = new File(getCacheDir(), "camera_images");
        
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
        
        return File.createTempFile(
                imageFileName,  /* prefix */
                ".jpg",         /* suffix */
                storageDir      /* directory */
        );
    }
}