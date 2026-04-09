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

    public ScanResult grade(Mat img, Map<Integer, Integer> key, int optionsCount) {
        if (img.empty()) return new ScanResult(0, new HashMap<>());

        Mat gray = new Mat();
        if (img.channels() > 1) {
            Imgproc.cvtColor(img, gray, Imgproc.COLOR_RGBA2GRAY);
        } else {
            img.copyTo(gray);
        }

        Mat blurred = new Mat();
        Mat edged = new Mat();
        Mat warped = new Mat();

        Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
        Imgproc.Canny(blurred, edged, 75, 200);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(edged, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        contours.sort(Collections.reverseOrder(Comparator.comparingDouble(Imgproc::contourArea)));
        
        List<MatOfPoint> paperMarkers = new ArrayList<>();
        for (MatOfPoint c : contours) {
            MatOfPoint2f approx = new MatOfPoint2f();
            MatOfPoint2f c2f = new MatOfPoint2f(c.toArray());
            Imgproc.approxPolyDP(c2f, approx, 0.02 * Imgproc.arcLength(c2f, true), true);
            if (approx.total() == 4 && Imgproc.contourArea(c) > 400) {
                paperMarkers.add(new MatOfPoint(approx.toArray()));
                if (paperMarkers.size() == 4) break;
            }
        }

        if (paperMarkers.size() == 4) {
            List<Point> allPoints = new ArrayList<>();
            for (MatOfPoint marker : paperMarkers) {
                Rect r = Imgproc.boundingRect(marker);
                allPoints.add(new Point(r.x + (r.width / 2.0), r.y + (r.height / 2.0)));
            }
            Point[] orderedPoints = orderPoints(allPoints);
            MatOfPoint2f src = new MatOfPoint2f(orderedPoints);
            MatOfPoint2f dst = new MatOfPoint2f(new Point(0, 0), new Point(500, 0), new Point(500, 600), new Point(0, 600));
            Mat transformMatrix = Imgproc.getPerspectiveTransform(src, dst);
            Imgproc.warpPerspective(gray, warped, transformMatrix, new Size(500, 600));
        } else {
            gray.copyTo(warped);
        }

        Mat thresh = new Mat();
        Imgproc.threshold(warped, thresh, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);

        List<MatOfPoint> bubbleContours = new ArrayList<>();
        Imgproc.findContours(thresh, bubbleContours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        List<Rect> bubbles = new ArrayList<>();
        for (MatOfPoint cnt : bubbleContours) {
            Rect r = Imgproc.boundingRect(cnt);
            double ar = (double) r.width / r.height;
            if (r.width >= 15 && r.height >= 15 && ar >= 0.7 && ar <= 1.3) {
                bubbles.add(r);
            }
        }
        bubbles.sort(Comparator.comparingInt(r -> r.y));

        int totalScore = 0;
        Map<Integer, Integer> studentAnswers = new HashMap<>();

        for (int q = 0; q < key.size(); q++) {
            if ((q * optionsCount) + optionsCount > bubbles.size()) break;
            List<Rect> row = new ArrayList<>(bubbles.subList(q * optionsCount, (q * optionsCount) + optionsCount));
            row.sort(Comparator.comparingInt(r -> r.x));

            int filledIndex = -1;
            int maxPixels = 0;

            for (int i = 0; i < row.size(); i++) {
                Mat mask = Mat.zeros(thresh.size(), CvType.CV_8UC1);
                Imgproc.rectangle(mask, row.get(i), new Scalar(255), -1);
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
            studentAnswers.put(q + 1, filledIndex);
            if (key.containsKey(q + 1) && filledIndex == key.get(q + 1)) totalScore++;
        }

        gray.release(); blurred.release(); edged.release(); warped.release(); thresh.release(); hierarchy.release();
        return new ScanResult(totalScore, studentAnswers);
    }

    private Point[] orderPoints(List<Point> points) {
        Point[] ordered = new Point[4];
        points.sort(Comparator.comparingDouble(p -> p.x + p.y));
        ordered[0] = points.get(0);
        ordered[2] = points.get(3);
        points.sort(Comparator.comparingDouble(p -> p.x - p.y));
        ordered[3] = points.get(0);
        ordered[1] = points.get(3);
        return ordered;
    }
}
