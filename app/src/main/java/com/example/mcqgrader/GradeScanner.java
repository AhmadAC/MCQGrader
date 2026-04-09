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
        Mat blurred = new Mat();
        Mat edged = new Mat();
        Mat warped = new Mat();

        // 1. Convert to Grayscale
        Imgproc.cvtColor(img, gray, Imgproc.COLOR_RGBA2GRAY);
        Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
        Imgproc.Canny(blurred, edged, 75, 200);

        // 2. Find the 4 corner markers of the paper
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(edged, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        // Sort contours by area and get the largest ones
        contours.sort(Collections.reverseOrder(Comparator.comparingDouble(Imgproc::contourArea)));
        
        List<MatOfPoint> paperMarkers = new ArrayList<>();
        for (MatOfPoint c : contours) {
            MatOfPoint2f c2f = new MatOfPoint2f(c.toArray());
            double peri = Imgproc.arcLength(c2f, true);
            MatOfPoint2f approx = new MatOfPoint2f();
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true);
            
            // The corner markers should be squares (4 vertices)
            if (approx.total() == 4) {
                paperMarkers.add(new MatOfPoint(approx.toArray()));
                if (paperMarkers.size() == 4) break; // Found all 4
            }
        }

        if (paperMarkers.size() == 4) {
            // 3. Apply Perspective Transform to get a top-down view
            List<Point> allPoints = new ArrayList<>();
            for (MatOfPoint marker : paperMarkers) {
                Rect r = Imgproc.boundingRect(marker);
                allPoints.add(new Point(r.x, r.y)); // Top-Left
                allPoints.add(new Point(r.x + r.width, r.y)); // Top-Right
                allPoints.add(new Point(r.x, r.y + r.height)); // Bottom-Left
                allPoints.add(new Point(r.x + r.width, r.y + r.height)); // Bottom-Right
            }

            // Order the points: TL, TR, BR, BL
            Point[] orderedPoints = orderPoints(allPoints);

            MatOfPoint2f src = new MatOfPoint2f(orderedPoints);
            MatOfPoint2f dst = new MatOfPoint2f(
                new Point(0, 0),
                new Point(500 - 1, 0),
                new Point(500 - 1, 600 - 1),
                new Point(0, 600 - 1)
            );

            Mat transformMatrix = Imgproc.getPerspectiveTransform(src, dst);
            Imgproc.warpPerspective(gray, warped, transformMatrix, new Size(500, 600));
        } else {
            // Fallback: If markers aren't found, use the original image (less reliable)
            img.copyTo(warped);
        }

        // 4. Process the flattened, warped image
        Mat thresh = new Mat();
        Imgproc.adaptiveThreshold(warped, thresh, 255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 57, 5);

        contours.clear();
        hierarchy.release();
        Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        List<Rect> bubbles = new ArrayList<>();
        for (MatOfPoint cnt : contours) {
            Rect r = Imgproc.boundingRect(cnt);
            double ar = (double) r.width / r.height;
            // Looser aspect ratio check for bubbles
            if (r.width >= 15 && r.height >= 15 && ar >= 0.7 && ar <= 1.3) {
                bubbles.add(r);
            }
        }
        
        // Sort bubbles from top to bottom
        Collections.sort(bubbles, Comparator.comparingInt(r -> r.y));

        int totalScore = 0;
        Map<Integer, Integer> studentAnswers = new HashMap<>();

        // 5. Grade the bubbles row by row
        for (int q = 0; q < key.size(); q++) {
            if ((q * optionsCount) + optionsCount > bubbles.size()) break;

            List<Rect> row = new ArrayList<>(bubbles.subList(q * optionsCount, (q * optionsCount) + optionsCount));
            // Sort bubbles in the row from left to right
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

            studentAnswers.put(q + 1, filledIndex);

            if (key.containsKey(q + 1) && filledIndex == key.get(q + 1)) {
                totalScore++;
            }
        }

        // Release all Mats
        gray.release();
        blurred.release();
        edged.release();
        warped.release();
        thresh.release();
        hierarchy.release();
        
        return new ScanResult(totalScore, studentAnswers);
    }

    // Helper function to order the four corner points
    private Point[] orderPoints(List<Point> points) {
        Point[] ordered = new Point[4];
        points.sort(Comparator.comparingDouble(p -> p.x + p.y));
        ordered[0] = points.get(0); // Top-Left
        
        points.sort(Collections.reverseOrder(Comparator.comparingDouble(p -> p.x + p.y)));
        ordered[2] = points.get(0); // Bottom-Right

        points.sort(Comparator.comparingDouble(p -> p.y - p.x));
        ordered[1] = points.get(0); // Top-Right

        points.sort(Collections.reverseOrder(Comparator.comparingDouble(p -> p.y - p.x)));
        ordered[3] = points.get(0); // Bottom-Left

        return ordered;
    }
}