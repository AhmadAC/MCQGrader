package com.example.mcqgrader;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GradeScanner {

    public static class ScanResult {
        public int score;
        public Map<Integer, Integer> studentAnswers;

        public ScanResult(int score, Map<Integer, Integer> studentAnswers) {
            this.score = score;
            this.studentAnswers = studentAnswers;
        }
    }

    public ScanResult grade(Mat gray, Map<Integer, Integer> key, int optionsCount) {
        if (gray.empty()) return new ScanResult(0, new HashMap<>());

        Mat warped = new Mat();
        Mat blurred = new Mat();
        Mat edged = new Mat();
        
        // 1. Pre-process for marker detection
        Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
        Imgproc.Canny(blurred, edged, 75, 200);

        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(edged, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        contours.sort((a, b) -> Double.compare(Imgproc.contourArea(b), Imgproc.contourArea(a)));

        List<MatOfPoint> markers = new ArrayList<>();
        for (MatOfPoint c : contours) {
            MatOfPoint2f approx = new MatOfPoint2f();
            MatOfPoint2f c2f = new MatOfPoint2f(c.toArray());
            Imgproc.approxPolyDP(c2f, approx, 0.02 * Imgproc.arcLength(c2f, true), true);
            // Detect the 4 black square markers
            if (approx.total() == 4 && Imgproc.contourArea(c) > 300) {
                markers.add(new MatOfPoint(approx.toArray()));
                if (markers.size() == 4) break;
            }
        }

        // 2. Perspective Transform (Flatten the sheet)
        if (markers.size() == 4) {
            List<Point> pts = new ArrayList<>();
            for (MatOfPoint m : markers) {
                Rect r = Imgproc.boundingRect(m);
                pts.add(new Point(r.x + r.width / 2.0, r.y + r.height / 2.0));
            }
            Point[] sorted = sortPoints(pts);
            MatOfPoint2f src = new MatOfPoint2f(sorted);
            MatOfPoint2f dst = new MatOfPoint2f(new Point(0, 0), new Point(500, 0), new Point(500, 700), new Point(0, 700));
            Imgproc.warpPerspective(gray, warped, Imgproc.getPerspectiveTransform(src, dst), new Size(500, 700));
        } else {
            gray.copyTo(warped); // Fallback if markers aren't clearly seen
        }

        // 3. Adaptive Thresholding (Crucial for real-time video shadows)
        Mat thresh = new Mat();
        Imgproc.adaptiveThreshold(warped, thresh, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 31, 10);

        List<MatOfPoint> bCnts = new ArrayList<>();
        Imgproc.findContours(thresh, bCnts, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        
        List<Rect> bubbles = new ArrayList<>();
        for (MatOfPoint c : bCnts) {
            Rect r = Imgproc.boundingRect(c);
            double ar = (double) r.width / r.height;
            // Filter for circular-ish bubble shapes
            if (r.width >= 12 && r.height >= 12 && r.width <= 60 && ar >= 0.6 && ar <= 1.4) {
                bubbles.add(r);
            }
        }
        
        // Sort bubbles Top-to-Bottom
        bubbles.sort(Comparator.comparingInt(r -> r.y));

        int score = 0;
        Map<Integer, Integer> ans = new HashMap<>();

        // 4. Group bubbles into rows and detect filling
        for (int q = 0; q < key.size(); q++) {
            // Ensure we have enough detected bubbles for the question
            if ((q * optionsCount) + optionsCount > bubbles.size()) break;

            List<Rect> row = new ArrayList<>(bubbles.subList(q * optionsCount, (q * optionsCount) + optionsCount));
            row.sort(Comparator.comparingInt(r -> r.x)); // Sort row Left-to-Right

            int filledIdx = -1;
            double maxIntensity = 0;

            for (int i = 0; i < row.size(); i++) {
                Mat roi = thresh.submat(row.get(i));
                int totalPixels = Core.countNonZero(roi);
                double pixelRatio = (double) totalPixels / (row.get(i).width * row.get(i).height);
                
                if (pixelRatio > maxIntensity) {
                    maxIntensity = pixelRatio;
                    filledIdx = i;
                }
                roi.release();
            }

            // Only count as an answer if the most filled bubble has significant marking
            if (maxIntensity > 0.2) {
                ans.put(q + 1, filledIdx);
                if (key.containsKey(q + 1) && filledIdx == key.get(q + 1)) {
                    score++;
                }
            } else {
                ans.put(q + 1, -1); // Unanswered
            }
        }

        gray.release();
        warped.release();
        blurred.release();
        edged.release();
        thresh.release();
        
        return new ScanResult(score, ans);
    }

    private Point[] sortPoints(List<Point> pts) {
        Point[] res = new Point[4];
        pts.sort(Comparator.comparingDouble(p -> p.x + p.y));
        res[0] = pts.get(0); // Top-Left
        res[2] = pts.get(3); // Bottom-Right
        pts.sort(Comparator.comparingDouble(p -> p.x - p.y));
        res[3] = pts.get(0); // Bottom-Left
        res[1] = pts.get(3); // Top-Right
        return res;
    }
}
