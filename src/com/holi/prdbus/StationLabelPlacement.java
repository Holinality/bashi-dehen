package com.holi.prdbus;

/** Bounded screen-space alternatives for labels whose authored anchor cannot fit. */
final class StationLabelPlacement {
    interface Clear { boolean accepts(float left,float top,float right,float bottom); }
    static final class Box {
        final float left,baseline,width,top,bottom;
        Box(float left,float baseline,float width,float ascent,float descent,float extraHeight) {
            this.left=left;this.baseline=baseline;this.width=width;
            top=baseline+ascent;bottom=baseline+descent+extraHeight;
        }
    }
    private StationLabelPlacement() {}
    static float textSize(float worldTextSize,float zoom,float scaledDensity) {
        return Math.min(worldTextSize*zoom,16*scaledDensity);
    }
    static Box place(float sx,float sy,float preferredLeft,float preferredBaseline,float width,
                     float ascent,float descent,float extraHeight,float viewportWidth,float viewportHeight,
                     float horizontalGap,float verticalGap,float step,Clear clear) {
        if(clear==null||!finite(sx)||!finite(sy)||!finite(preferredLeft)||!finite(preferredBaseline)
                ||!finite(width)||!finite(ascent)||!finite(descent)||!finite(extraHeight)
                ||!finite(viewportWidth)||!finite(viewportHeight)||!finite(horizontalGap)
                ||!finite(verticalGap)||!finite(step)||width<=0||step<=0||extraHeight<0
                ||descent<ascent||horizontalGap<0||verticalGap<0)return null;
        float margin=Math.max(2,Math.min(12,step/4));
        if(width>viewportWidth-2*margin||descent-ascent+extraHeight>viewportHeight-2*margin)return null;
        Box found=tryAt(preferredLeft,preferredBaseline,width,ascent,descent,extraHeight,
                viewportWidth,viewportHeight,margin,clear);
        if(found!=null)return found;
        // Preserve the authored side first, then try both sides and all quadrants.
        for(int i=1;i<=4;i++) {
            float offset=((i+1)/2)*step*(i%2==1?-1:1);
            found=tryAt(preferredLeft,preferredBaseline+offset,width,ascent,descent,extraHeight,
                    viewportWidth,viewportHeight,margin,clear);
            if(found!=null)return found;
        }
        for(int ring=0;ring<8;ring++) {
            float horizontal=horizontalGap+ring*step,vertical=verticalGap+ring*step;
            float above=sy-vertical-descent-extraHeight,below=sy+vertical-ascent;
            float middle=sy-(ascent+descent+extraHeight)/2;
            for(int side=0;side<8;side++) {
                float left,baseline;
                if(side==0){left=sx+horizontal;baseline=middle;}
                else if(side==1){left=sx-horizontal-width;baseline=middle;}
                else if(side==2){left=sx-width/2;baseline=above;}
                else if(side==3){left=sx-width/2;baseline=below;}
                else {left=side%2==0?sx+horizontal:sx-horizontal-width;baseline=side<6?above:below;}
                found=tryAt(left,baseline,width,ascent,descent,extraHeight,
                        viewportWidth,viewportHeight,margin,clear);
                if(found!=null)return found;
            }
            if(ring>0) {
                // Diagonal corridors need independent horizontal/vertical shifts.
                // Expanding both together skips the close gaps beside a station.
                for(int axis=0;axis<2;axis++)for(int corner=0;corner<4;corner++) {
                    float hg=axis==0?horizontalGap:horizontal;
                    float vg=axis==0?vertical:verticalGap;
                    float left=corner%2==0?sx+hg:sx-hg-width;
                    float baseline=corner<2?sy-vg-descent-extraHeight:sy+vg-ascent;
                    found=tryAt(left,baseline,width,ascent,descent,extraHeight,
                            viewportWidth,viewportHeight,margin,clear);
                    if(found!=null)return found;
                }
            }
        }
        return null;
    }
    private static Box tryAt(float left,float baseline,float width,float ascent,float descent,float extra,
                             float viewportWidth,float viewportHeight,float margin,Clear clear) {
        float top=baseline+ascent,bottom=baseline+descent+extra;
        if(left>=margin&&left+width<=viewportWidth-margin&&top>=margin&&bottom<=viewportHeight-margin
                &&clear.accepts(left,top,left+width,bottom))return new Box(left,baseline,width,ascent,descent,extra);
        float clampedLeft=Math.max(margin,Math.min(left,viewportWidth-margin-width));
        float clampedBaseline=Math.max(margin-ascent,Math.min(baseline,viewportHeight-margin-descent-extra));
        if((Math.abs(clampedLeft-left)>.01f||Math.abs(clampedBaseline-baseline)>.01f)
                &&clear.accepts(clampedLeft,clampedBaseline+ascent,clampedLeft+width,clampedBaseline+descent+extra))
            return new Box(clampedLeft,clampedBaseline,width,ascent,descent,extra);
        return null;
    }
    private static boolean finite(float value){return !Float.isNaN(value)&&!Float.isInfinite(value);}
}
