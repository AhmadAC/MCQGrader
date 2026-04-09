package com.example.mcqgrader;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.*;

public class GradeScanner {
    public static class ScanResult {
        public int score;
        public Map<Integer, Integer> studentAnswers;
        public ScanResult(int score, Map<Integer, Integer> studentAnswers) {
            this.score = score; this.studentAnswers = studentAnswers;
        }
    }

    public ScanResult grade(Mat gray, Map<Integer, Integer> key, int optionsCount) {
        if (gray.empty()) return new ScanResult(0, new HashMap<>());

        Mat warped = new Mat(), blurred = new Mat(), edged = new Mat();
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
            if (approx.total() == 4 && Imgproc.contourArea(c) > 400) {
                markers.add(new MatOfPoint(approx.toArray()));
                if (markers.size() == 4) break;
            }
        }

        if (markers.size() == 4) {
            List<Point> pts = new ArrayList<>();
            for (MatOfPoint m : markers) {
                Rect r = Imgproc.boundingRect(m);
                pts.add(new Point(r.x + r.width/2.0, r.y + r.height/2.0));
            }
            Point[] sorted = sortPoints(pts);
            MatOfPoint2f src = new MatOfPoint2f(sorted);
            MatOfPoint2f dst = new MatOfPoint2f(new Point(0,0), new Point(500,0), new Point(500,600), new Point(0,600));
            Imgproc.warpPerspective(gray, warped, Imgproc.getPerspectiveTransform(src, dst), new Size(500, 600));
        } else {
            gray.copyTo(warped);
        }

        Mat thresh = new Mat();
        Imgproc.threshold(warped, thresh, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);

        List<MatOfPoint> bCnts = new ArrayList<>();
        Imgproc.findContours(thresh, bCnts, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        List<Rect> bubbles = new ArrayList<>();
        for (MatOfPoint c : bCnts) {
            Rect r = Imgproc.boundingRect(c);
            double ar = (double)r.width/r.height;
            if (r.width >= 15 && r.height >= 15 && ar >= 0.7 && ar <= 1.3) bubbles.add(r);
        }
        bubbles.sort(Comparator.comparingInt(r -> r.y));

        int score = 0;
        Map<Integer, Integer> ans = new HashMap<>();
        for (int q = 0; q < key.size(); q++) {
            if ((q * optionsCount) + optionsCount > bubbles.size()) break;
            List<Rect> row = new ArrayList<>(bubbles.subList(q * optionsCount, (q * optionsCount) + optionsCount));
            row.sort(Comparator.comparingInt(r -> r.x));
            int filled = -1, max = 0;
            for (int i = 0; i < row.size(); i++) {
                int count = Core.countNonZero(thresh.submat(row.get(i)));
                if (count > max) { max = count; filled = i; }
            }
            ans.put(q + 1, filled);
            if (key.containsKey(q + 1) && filled == key.get(q + 1)) score++;
        }
        
        gray.release(); warped.release(); blurred.release(); edged.release(); thresh.release();
        return new ScanResult(score, ans);
    }

    private Point[] sortPoints(List<Point> pts) {
        Point[] res = new Point[4];
        pts.sort(Comparator.comparingDouble(p -> p.x + p.y));
        res[0] = pts.get(0); res[2] = pts.get(3);
        pts.sort(Comparator.comparingDouble(p -> p.x - p.y));
        res[3] = pts.get(0); res[1] = pts.get(3);
        return res;
    }
}
