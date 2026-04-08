import android.graphics.Rect;
import android.util.Log;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GradeScanner {

    private static final String TAG = "GradeScanner";
    private static final int NUM_OPTIONS = 6; // A, B, C, D, E, F

    // Public method to start the grading process
    public Map<String, String> gradeTest(Mat originalImage, Map<Integer, Integer> answerKey) {
        Map<String, String> results = new HashMap<>();

        if (originalImage.empty() || answerKey.isEmpty()) {
            Log.e(TAG, "Input image or answer key is empty.");
            results.put("Error", "Input image or answer key is empty.");
            return results;
        }

        // 1. Preprocess the image (convert to black and white)
        Mat thresh = preprocessImage(originalImage);

        // 2. Find contours that could be bubbles
        List<MatOfPoint> bubbleContours = findBubbleContours(thresh);

        if (bubbleContours.size() < answerKey.size()) {
            Log.e(TAG, "Could not find enough bubbles. Found: " + bubbleContours.size());
            results.put("Error", "Could not find enough bubbles to match the answer key.");
            return results;
        }

        // 3. Sort contours into a grid of questions and options
        Map<Integer, List<MatOfPoint>> questionRows = sortContoursIntoRows(bubbleContours);

        // 4. Iterate through sorted bubbles, check against the key, and calculate score
        int score = 0;
        for (int questionIndex = 0; questionIndex < answerKey.size(); questionIndex++) {
            List<MatOfPoint> row = questionRows.get(questionIndex);
            if (row == null || row.size() != NUM_OPTIONS) {
                Log.w(TAG, "Warning: Question " + (questionIndex + 1) + " does not have exactly " + NUM_OPTIONS + " options. Skipping.");
                continue;
            }

            int markedOptionIndex = getMarkedOption(thresh, row); // Find which bubble (0-5) is filled
            int correctOptionIndex = answerKey.getOrDefault(questionIndex + 1, -1); // +1 because key is 1-based

            if (markedOptionIndex == correctOptionIndex) {
                score++;
            }
        }

        results.put("Score", score + " / " + answerKey.size());
        return results;
    }

    private Mat preprocessImage(Mat originalImage) {
        Mat gray = new Mat();
        Mat blurred = new Mat();
        Mat thresh = new Mat();

        Imgproc.cvtColor(originalImage, gray, Imgproc.COLOR_BGR2GRAY);
        Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
        // THRESH_OTSU automatically determines the best threshold value from the image
        Imgproc.threshold(blurred, thresh, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);

        return thresh;
    }

    private List<MatOfPoint> findBubbleContours(Mat thresholdImage) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(thresholdImage, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        List<MatOfPoint> bubbleContours = new ArrayList<>();
        for (MatOfPoint contour : contours) {
            Rect rect = new Rect(
                (int) Imgproc.boundingRect(contour).tl().x,
                (int) Imgproc.boundingRect(contour).tl().y,
                Imgproc.boundingRect(contour).width,
                Imgproc.boundingRect(contour).height
            );

            float aspectRatio = (float) rect.width() / (float) rect.height();

            // Filter based on size and shape (bubbles should be roughly square)
            if (rect.width() >= 20 && rect.height() >= 20 && aspectRatio >= 0.8 && aspectRatio <= 1.2) {
                bubbleContours.add(contour);
            }
        }
        return bubbleContours;
    }

    private Map<Integer, List<MatOfPoint>> sortContoursIntoRows(List<MatOfPoint> contours) {
        // Sort all contours from top-to-bottom
        Collections.sort(contours, (c1, c2) -> {
            Rect rect1 = Imgproc.boundingRect(c1);
            Rect rect2 = Imgproc.boundingRect(c2);
            return Integer.compare(rect1.y, rect2.y);
        });

        Map<Integer, List<MatOfPoint>> questionRows = new HashMap<>();
        int questionCounter = 0;
        // Group contours into rows of NUM_OPTIONS
        for (int i = 0; i < contours.size(); i += NUM_OPTIONS) {
            // Get the next slice of contours that should belong to one question
            int end = Math.min(i + NUM_OPTIONS, contours.size());
            List<MatOfPoint> row = new ArrayList<>(contours.subList(i, end));

            // Sort this row from left-to-right
            Collections.sort(row, (c1, c2) -> {
                Rect rect1 = Imgproc.boundingRect(c1);
                Rect rect2 = Imgproc.boundingRect(c2);
                return Integer.compare(rect1.x, rect2.x);
            });

            questionRows.put(questionCounter, row);
            questionCounter++;
        }
        return questionRows;
    }

    private int getMarkedOption(Mat thresh, List<MatOfPoint> optionContours) {
        int markedOption = -1;
        int maxFilledPixels = -1;

        for (int i = 0; i < optionContours.size(); i++) {
            // Create a mask to isolate only the current bubble
            Mat mask = Mat.zeros(thresh.size(), CvType.CV_8UC1);
            List<MatOfPoint> singleContourList = new ArrayList<>();
            singleContourList.add(optionContours.get(i));
            Imgproc.drawContours(mask, singleContourList, -1, new Scalar(255), -1);

            // Get the pixels from the thresholded image that are inside the bubble
            Mat maskedImage = new Mat();
            Core.bitwise_and(thresh, thresh, maskedImage, mask);

            int filledPixels = Core.countNonZero(maskedImage);

            // The bubble with the most non-zero (filled) pixels is the marked one
            if (filledPixels > maxFilledPixels) {
                maxFilledPixels = filledPixels;
                markedOption = i;
            }
        }

        // A simple check: if the "most filled" bubble isn't filled much, assume nothing was marked.
        // This threshold might need adjustment.
        if (maxFilledPixels < 150) {
            return -1; // No option confidently marked
        }

        return markedOption;
    }
}```

---

### 2. `TextScanner.java` (The Text Reader)

This file uses Google ML Kit to read text. It's updated with a listener (callback) interface so it can asynchronously send the result back to `MainActivity`.

**File Name:** `TextScanner.java`

```java
import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

public class TextScanner {

    private static final String TAG = "TextScanner";

    // 1. Define an interface for callbacks
    public interface TextScannerListener {
        void onTextExtracted(String text);
        void onError(String error);
    }

    private TextRecognizer recognizer;

    public TextScanner() {
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    // 2. Update the method to accept the listener
    public void extractText(Bitmap bitmap, TextScannerListener listener) {
        if (bitmap == null) {
            listener.onError("Bitmap cannot be null.");
            return;
        }

        InputImage image = InputImage.fromBitmap(bitmap, 0);

        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    // 3. Use the listener to return the result
                    Log.i(TAG, "Text extraction successful.");
                    listener.onTextExtracted(visionText.getText());
                })
                .addOnFailureListener(e -> {
                    // 4. Use the listener to return the error
                    Log.e(TAG, "Text extraction failed.", e);
                    listener.onError(e.getMessage());
                });
    }
  }
