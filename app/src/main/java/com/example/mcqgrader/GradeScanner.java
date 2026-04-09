package com.example.mcqgrader;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class GradeScanner {

    private static final int NUM_OPTIONS = 6; 

    public int grade(Mat img, Map<Integer, Integer> key) {
        Mat gray = new Mat();
        Mat blurred = new Mat();
        Mat thresh = new Mat();

        // Must interpret image via RGBA as Utils.bitmapToMat maps to CV_8UC4 Format
        Imgproc.cvtColor(img, gray, Imgproc.COLOR_RGBA2GRAY);
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
        for (int q = 0; q < key.size(); q++) {
            if ((q * NUM_OPTIONS) + NUM_OPTIONS > bubbles.size()) break;

            List<Rect> row = new ArrayList<>(bubbles.subList(q * NUM_OPTIONS, (q * NUM_OPTIONS) + NUM_OPTIONS));
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
                
                // Address Memory leaks
                mask.release();
                masked.release();
            }

            if (key.containsKey(q + 1) && filledIndex == key.get(q + 1)) {
                totalScore++;
            }
        }

        // Release Mats natively to resolve leak issues entirely
        gray.release();
        blurred.release();
        thresh.release();
        hierarchy.release();
        for (MatOfPoint cnt : contours) {
            cnt.release();
        }

        return totalScore;
    }
}