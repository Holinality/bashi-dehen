package com.holi.prdbus;

public final class DiagramPathSelfTest {
    private static void close(float expected,float actual) {
        if(Math.abs(expected-actual)>.001)throw new AssertionError(expected+" != "+actual);
    }
    public static void main(String[] args) {
        // Shared station anchors lie between parallel lines. Slicing must keep
        // each route on its own centreline, never add an anchor-to-track spur.
        float[] upper={0,0,50,0,100,0},lower={0,10,50,10,100,10};
        float[] a=DiagramPath.between(upper,20,5,80,5,false);
        float[] b=DiagramPath.between(lower,20,5,80,5,false);
        close(20,a[0]);close(80,a[a.length-2]);
        for(int i=1;i<a.length;i+=2)close(0,a[i]);
        for(int i=1;i<b.length;i+=2)close(10,b[i]);
        float[] reverse=DiagramPath.between(upper,80,5,20,5,false);
        close(80,reverse[0]);close(20,reverse[reverse.length-2]);
        float[] bend={0,0,20,0,20,20};
        float[] corner=DiagramPath.between(bend,10,3,23,10,false);
        close(10,corner[0]);close(0,corner[1]);close(20,corner[2]);close(0,corner[3]);
        close(20,corner[corner.length-2]);close(10,corner[corner.length-1]);
        float[] ring={0,0,10,0,10,10,0,10,0,0};
        float[] wrapped=DiagramPath.between(ring,1,0,0,1,true);
        close(1,wrapped[0]);close(0,wrapped[1]);
        double distance=0;for(int i=2;i<wrapped.length;i+=2)distance+=Math.hypot(wrapped[i]-wrapped[i-2],wrapped[i+1]-wrapped[i-1]);
        if(Math.abs(distance-2)>.001)throw new AssertionError("Ring took the long way: "+distance);
        if(DiagramPath.between(new float[0],0,0,1,1,false).length!=0)throw new AssertionError("Missing geometry");
        System.out.println("DiagramPathSelfTest OK");
    }
}
