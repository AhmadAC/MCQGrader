import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

    private Button btnLoadKey, btnScanSheet;
    private TextView tvResults;

    private Map<Integer, Integer> answerKey;
    private GradeScanner gradeScanner;
    private TextScanner textScanner;

    // Modern way to handle activity results (getting a file or a camera image)
    private ActivityResultLauncher<String> filePickerLauncher;
    private ActivityResultLauncher<Intent> cameraLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV initialization failed!");
            Toast.makeText(this, "OpenCV failed to load. The app may not work.", Toast.LENGTH_LONG).show();
        } else {
            Log.d(TAG, "OpenCV initialized successfully!");
        }

        // Initialize UI components and scanners
        btnLoadKey = findViewById(R.id.btnLoadKey);
        btnScanSheet = findViewById(R.id.btnScanSheet);
        tvResults = findViewById(R.id.tvResults);
        answerKey = new HashMap<>();
        gradeScanner = new GradeScanner();
        textScanner = new TextScanner();

        // Register the ActivityResultLauncher for the file picker
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        loadAnswerKey(uri);
                    }
                }
        );

        // Register the ActivityResultLauncher for the camera
        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Bundle extras = result.getData().getExtras();
                        if (extras != null) {
                            Bitmap imageBitmap = (Bitmap) extras.get("data");
                            if (imageBitmap != null) {
                                processImage(imageBitmap);
                            }
                        }
                    }
                }
        );

        // --- SETUP BUTTON CLICK LISTENERS ---

        // Button to launch the file picker for the JSON key
        btnLoadKey.setOnClickListener(v -> {
            filePickerLauncher.launch("application/json");
        });

        // Button to launch the camera to scan the sheet
        btnScanSheet.setOnClickListener(v -> {
            if (answerKey.isEmpty()) {
                Toast.makeText(this, "Please load an answer key JSON file first.", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cameraLauncher.launch(takePictureIntent);
        });
    }

    private void loadAnswerKey(Uri uri) {
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            // Read the file content
            int size = inputStream.available();
            byte[] buffer = new byte[size];
            inputStream.read(buffer);
            String jsonString = new String(buffer, "UTF-8");

            // Parse the JSON
            JSONObject jsonObject = new JSONObject(jsonString);
            JSONObject answers = jsonObject.getJSONObject("answers");
            Iterator<String> keys = answers.keys();
            answerKey.clear();

            // Map "A"->0, "B"->1, etc.
            Map<String, Integer> letterToIndex = new HashMap<>();
            letterToIndex.put("A", 0); letterToIndex.put("B", 1); letterToIndex.put("C", 2);
            letterToIndex.put("D", 3); letterToIndex.put("E", 4); letterToIndex.put("F", 5);

            while (keys.hasNext()) {
                String key = keys.next(); // Question number as a string ("1", "2")
                String correctLetter = answers.getString(key);
                answerKey.put(Integer.parseInt(key), letterToIndex.get(correctLetter));
            }

            tvResults.setText("Answer key loaded successfully.\n" + answerKey.size() + " questions found.");
            Toast.makeText(this, "Answer key loaded!", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Failed to load or parse JSON", e);
            tvResults.setText("Error loading answer key: " + e.getMessage());
        }
    }

    private void processImage(Bitmap bitmap) {
        // --- Step 1: Use TextScanner to find any text ---
        textScanner.extractText(bitmap, new TextScanner.TextScannerListener() {
            @Override
            public void onTextExtracted(String text) {
                // We'll display this text along with the score later
                String studentInfo = "Detected Text:\n" + text + "\n\n";

                // --- Step 2: Convert Bitmap to OpenCV Mat for grading ---
                Mat imageMat = new Mat();
                Bitmap bmp32 = bitmap.copy(Bitmap.Config.ARGB_8888, true);
                Utils.bitmapToMat(bmp32, imageMat);

                // --- Step 3: Use GradeScanner to get the score ---
                Map<String, String> gradeResults = gradeScanner.gradeTest(imageMat, answerKey);

                // --- Step 4: Combine results and display them ---
                StringBuilder finalResult = new StringBuilder(studentInfo);
                for (Map.Entry<String, String> entry : gradeResults.entrySet()) {
                    finalResult.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
                tvResults.setText(finalResult.toString());
            }

            @Override
            public void onError(String error) {
                tvResults.setText("Could not extract text: " + error);
            }
        });
    }
}
