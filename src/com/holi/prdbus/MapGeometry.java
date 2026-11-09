package com.holi.prdbus;

import java.util.ArrayList;
import java.util.List;

/** Display geometry. Station anchors stay fixed when the geographic line is smoothed. */
final class MapGeometry {
    static boolean crosses(double x1, double y1, double x2, double y2,
                           double left, double top, double right, double bottom) {
        if (Math.max(x1,x2) < left || Math.min(x1,x2) > right
                || Math.max(y1,y2) < top || Math.min(y1,y2) > bottom) return false;
        double enter = 0, exit = 1;
        if (x1 != x2) {
            double a = (left-x1)/(x2-x1), b = (right-x1)/(x2-x1);
            enter = Math.max(enter,Math.min(a,b)); exit = Math.min(exit,Math.max(a,b));
        }
        if (y1 != y2) {
            double a = (top-y1)/(y2-y1), b = (bottom-y1)/(y2-y1);
            enter = Math.max(enter,Math.min(a,b)); exit = Math.min(exit,Math.max(a,b));
        }
        return enter <= exit;
    }
    static List<double[]> smooth(List<double[]> anchors, boolean ring) {
        List<double[]> result = new ArrayList<>();
        if (anchors.contains(null)) {
            List<double[]> run = new ArrayList<>();
            for (double[] point : anchors) {
                if (point == null) {
                    result.addAll(smooth(run, false));
                    if (!result.isEmpty()) result.add(null);
                    run.clear();
                } else run.add(point);
            }
            result.addAll(smooth(run, false));
            return result;
        }
        if (anchors.isEmpty()) return result;
        result.add(anchors.get(0).clone());
        int intervals = anchors.size() - 1 + (ring ? 1 : 0);
        for (int i = 0; i < intervals; i++) {
            int last = anchors.size() - 1;
            double[] a = anchors.get(i), b = anchors.get((i + 1) % anchors.size());
            double[] previous = anchors.get(i == 0 ? ring ? last : 0 : i - 1);
            double[] next = anchors.get(i + 2 > last ? ring ? (i + 2) % anchors.size() : last : i + 2);
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            double[] start = handle(a, b, previous, b, length);
            double[] end = handle(b, a, next, a, length);
            int samples = Math.max(6, Math.min(24, (int) Math.ceil(length / .12)));
            for (int j = 1; j <= samples; j++) {
                double t = j / (double) samples, u = 1 - t;
                result.add(new double[]{
                        u*u*u*a[0] + 3*u*u*t*start[0] + 3*u*t*t*end[0] + t*t*t*b[0],
                        u*u*u*a[1] + 3*u*u*t*start[1] + 3*u*t*t*end[1] + t*t*t*b[1]});
            }
        }
        return result;
    }

    private static double[] handle(double[] anchor, double[] target, double[] previous, double[] next, double length) {
        double dx = next[0] - previous[0], dy = next[1] - previous[1];
        double distance = Math.hypot(dx, dy);
        if (distance < 1e-8 || dx * (target[0] - anchor[0]) + dy * (target[1] - anchor[1]) <= 0)
            return anchor.clone();
        double reach = Math.min(length * .23, distance / 6);
        return new double[]{anchor[0] + dx / distance * reach, anchor[1] + dy / distance * reach};
    }

    private MapGeometry() { }
}
