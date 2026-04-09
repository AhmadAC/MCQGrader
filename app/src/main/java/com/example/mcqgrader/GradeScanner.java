package com.example.mcqgrader;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GradeScanner {

    // Helper object to return both the score and the student's choices
    public static class ScanResult {
        public int score;
        public Map<Integer, Integer> studentAnswers;

        public ScanResult(int score, Map<Integer, Integer> studentAnswers) {
            this.score = score;
            this.studentAnswers = studentAnswers;
        }
    }

    public ScanResult grade(Mat img, Map<Integer, Integer> key, int optionsCount) {
        if (img.empty()) return new ScanResult(0, new HashMap<>());

        Mat gray = new Mat();
        Mat blurred = new Mat();
        Mat thresh = new Mat();

        // Safely convert depending on channel type (1 for real-time YUV Y-plane, 3/4 for Bitmaps)
        if (img.channels() == 1) {
            img.copyTo(gray);
        } else if (img.channels() == 3) {
            Imgproc.cvtColor(img, gray, Imgproc.COLOR_RGB2GRAY);
        } else if (img.channels() == 4) {
            Imgproc.cvtColor(img, gray, Imgproc.COLOR_RGBA2GRAY);
        } else {
            return new ScanResult(0, new HashMap<>());
        }

        Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
        Imgproc.threshold(blurred, thresh, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        List<Rect> bubbles = new ArrayList<>();
        for (MatOfPoint cnt : contours) {
            Rect r = Imgproc.boundingRect(cnt);
            double ar = (double) r.width / r.height;
            if (r.width >= 20 && r.height >= 20 && ar >= 0.7 && ar <= 1.3) {
                bubbles.add(r);
            }
        }

        Collections.sort(bubbles, Comparator.comparingInt(r -> r.y));

        int totalScore = 0;
        Map<Integer, Integer> studentAnswers = new HashMap<>();

        for (int q = 0; q < key.size(); q++) {
            // Prevent out-of-bounds scanning if fewer bubbles exist
            if ((q * optionsCount) + optionsCount > bubbles.size()) break;

            List<Rect> row = new ArrayList<>(bubbles.subList(q * optionsCount, (q * optionsCount) + optionsCount));
            Collections.sort(row, Comparator.comparingInt(r -> r.x));

            int filledIndex = -1;
            int maxPixels = 0;

            for (int i = 0; i < row.size(); i++) {
                Rect b = row.get(i);
                Mat mask = Mat.zeros(thresh.size(), CvType.CV_8UC1);
                Imgproc.rectangle(mask, b, new Scalar(255), -1);
                
                Mat masked = new Mat();
                Core.bitwise_and(thresh, thresh, masked, mask);
                int total = Core.countNonZero(masked);

                if (total > maxPixels) {
                    maxPixels = total;
                    filledIndex = i;
                }
                mask.release();
                masked.release();
            }

            // Save the student's detected bubble index
            studentAnswers.put(q + 1, filledIndex);

            // `key` expects keys exactly mimicking 1, 2, 3.. mapped to index 0, 1, 2..
            if (key.containsKey(q + 1) && filledIndex == key.get(q + 1)) {
                totalScore++;
            }
        }

        gray.release();
        blurred.release();
        thresh.release();
        hierarchy.release();
        
        return new ScanResult(totalScore, studentAnswers);
    }
}