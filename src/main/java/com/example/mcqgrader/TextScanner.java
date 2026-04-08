package com.example.mcqgrader;

import android.graphics.Bitmap;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

public class TextScanner {

    public interface OCRListener {
        void onSuccess(String text);
    }

    public void scan(Bitmap bitmap, OCRListener listener) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        recognizer.process(image)
                .addOnSuccessListener(visionText -> listener.onSuccess(visionText.getText()))
                .addOnFailureListener(e -> listener.onSuccess("Error reading text."));
    }
}
