package com.holi.prdbus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Project schematic anchors onto their own line without adding connector spurs. */
final class DiagramPath {
    static final class Projection {
        final float x,y;final double along;
        Projection(float x,float y,double along){this.x=x;this.y=y;this.along=along;}
    }
    static Projection project(float[] path,float x,float y) {
        double best=Double.MAX_VALUE,along=0;Projection chosen=null;
        for(int i=2;i<path.length;i+=2) {
            float ax=path[i-2],ay=path[i-1],dx=path[i]-ax,dy=path[i+1]-ay;
            double squared=dx*dx+dy*dy,length=Math.sqrt(squared);
            double t=squared==0?0:Math.max(0,Math.min(1,((x-ax)*dx+(y-ay)*dy)/squared));
            float px=(float)(ax+dx*t),py=(float)(ay+dy*t);
            double distance=(x-px)*(x-px)+(y-py)*(y-py);
            if(distance<best){best=distance;chosen=new Projection(px,py,along+t*length);}
            along+=length;
        }
        return chosen;
    }
    static float[] between(float[] source,float ax,float ay,float bx,float by,boolean ring) {
        if(source.length<4)return new float[0];
        Projection a=project(source,ax,ay),b=project(source,bx,by);
        if(a==null||b==null)return new float[0];
        double[] arcs=new double[source.length/2];
        for(int i=1;i<arcs.length;i++)arcs[i]=arcs[i-1]+Math.hypot(source[i*2]-source[i*2-2],source[i*2+1]-source[i*2-1]);
        double total=arcs[arcs.length-1];
        if(total<.000001)return new float[]{a.x,a.y,b.x,b.y};
        double delta=b.along-a.along;
        // A wrap is valid only for an actually closed source polyline.
        ring=ring&&Math.hypot(source[0]-source[source.length-2],source[1]-source[source.length-1])<.1;
        if(ring&&Math.abs(delta)>total/2)delta+=delta<0?total:-total;
        final int direction=delta>=0?1:-1;
        final double distance=Math.abs(delta);
        class Vertex {final double progress;final int index;Vertex(double p,int i){progress=p;index=i;}}
        List<Vertex> inside=new ArrayList<>();
        for(int i=1;i<arcs.length;i++)for(int wrap=ring?-1:0;wrap<=(ring?1:0);wrap++) {
            double progress=(arcs[i]+wrap*total-a.along)*direction;
            if(progress>.000001&&progress<distance-.000001)inside.add(new Vertex(progress,i));
        }
        Collections.sort(inside,(u,v) -> Double.compare(u.progress,v.progress));
        List<Float> points=new ArrayList<>();points.add(a.x);points.add(a.y);
        for(Vertex v:inside){points.add(source[v.index*2]);points.add(source[v.index*2+1]);}
        points.add(b.x);points.add(b.y);
        float[] result=new float[points.size()];for(int i=0;i<result.length;i++)result[i]=points.get(i);
        return result;
    }
}
