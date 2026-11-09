package com.holi.prdbus;

/** Select a single nearby route interval, keeping transfer-point GPS jitter stable. */
final class RouteProgressSelection {
    static int select(double[] distances, float[] fractions, int previous) {
        int best = -1;
        for (int i = 0; i < distances.length; i++) {
            if (Double.isNaN(distances[i]) || distances[i] < 0 || distances[i] > 1.5) continue;
            if (best < 0 || distances[i] < distances[best] - .001) best = i;
        }
        if (best < 0) return -1;
        if (previous >= 0 && previous < distances.length
                && distances[previous] >= 0 && distances[previous] <= 1.5
                && distances[previous] <= distances[best] + .10) {
            if (fractions[previous] >= .99f) {
                // At a shared transfer stop, prefer the next leg when it is equally close.
                for (int i = previous + 1; i < distances.length; i++)
                    if (distances[i] >= 0 && distances[i] <= distances[best] + .05
                            && fractions[i] <= .10f) return i;
            }
            return previous;
        }
        return best;
    }
}
