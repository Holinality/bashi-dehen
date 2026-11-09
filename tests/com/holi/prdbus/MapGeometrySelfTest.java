package com.holi.prdbus;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class MapGeometrySelfTest {
    public static void main(String[] args) {
        double[] a={0,0},b={1,0},c={1,1};
        List<double[]> points=MapGeometry.smooth(Arrays.asList(a,b,c),false);
        require(points.size()>15,"insufficient curve samples");
        require(same(points.get(0),a) && same(points.get(points.size()-1),c),"endpoints moved");
        require(points.stream().anyMatch(p -> same(p,b)),"intermediate station moved");
        for (double[] p:points)
            require(Double.isFinite(p[0]) && Double.isFinite(p[1])
                    && p[0]>=-.24 && p[0]<=1.24 && p[1]>=-.24 && p[1]<=1.24,"curve overshot");
        List<double[]> line=MapGeometry.smooth(Arrays.asList(new double[]{0,4},new double[]{5,4}),false);
        for (double[] p:line) require(Math.abs(p[1]-4)<1e-9,"straight rail bent");
        List<double[]> ring=MapGeometry.smooth(Arrays.asList(a,b,c),true);
        require(same(ring.get(0),ring.get(ring.size()-1)),"ring did not close");
        List<double[]> broken=MapGeometry.smooth(Arrays.asList(a,b,null,c),false);
        require(broken.contains(null),"missing station bridged");
        require(MapGeometry.smooth(Collections.emptyList(),false).isEmpty(),"empty input");
        for (double[] p:MapGeometry.smooth(Arrays.asList(a,a,b),false))
            require(Double.isFinite(p[0]) && Double.isFinite(p[1]),"coincident stations broke curve");
        require(MapGeometry.crosses(0,0,5,5,2,2,3,3),"diagonal intersection missed");
        require(!MapGeometry.crosses(0,0,5,5,0,3,1,4),"false label collision");
        require(MapGeometry.crosses(1,0,1,5,0,2,2,3),"vertical intersection missed");
        System.out.println("MapGeometrySelfTest passed (anchors, sampling, rings, gaps, label collision)");
    }
    private static boolean same(double[] a,double[] b) {
        return a!=null && Math.abs(a[0]-b[0])<1e-8 && Math.abs(a[1]-b[1])<1e-8;
    }
    private static void require(boolean valid,String message) {
        if (!valid) throw new AssertionError(message);
    }
}
