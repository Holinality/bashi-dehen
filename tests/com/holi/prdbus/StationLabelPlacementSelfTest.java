package com.holi.prdbus;

/** Regressions for visible station labels lost at edges and beside rails. */
public final class StationLabelPlacementSelfTest {
    private static final float VIEW_WIDTH=360, VIEW_HEIGHT=640;
    private static int cases;

    public static void main(String[] args) {
        StationLabelPlacement.Clear empty=(left,top,right,bottom)->true;
        StationLabelPlacement.Box unchanged=place(180,120,100,100,92,0,empty);
        valid(unchanged,VIEW_WIDTH,VIEW_HEIGHT,"preferred placement");
        require(close(unchanged.left,100)&&close(unchanged.baseline,100),
                "a clear approved anchor moved unnecessarily");
        cases++;

        StationLabelPlacement.Clear rightSide=(left,top,right,bottom)->left>=14;
        StationLabelPlacement.Box leftEdge=place(4,100,-100,100,92,0,rightSide);
        valid(leftEdge,VIEW_WIDTH,VIEW_HEIGHT,"left edge");
        require(leftEdge.left>=14,"left-edge label did not move to an inside slot");
        cases++;

        StationLabelPlacement.Clear leftSide=(left,top,right,bottom)->right<=346;
        StationLabelPlacement.Box rightEdge=place(356,100,354,100,92,0,leftSide);
        valid(rightEdge,VIEW_WIDTH,VIEW_HEIGHT,"right edge");
        require(rightEdge.left+rightEdge.width<=346,"right-edge label remained outside");
        cases++;

        StationLabelPlacement.Clear below=(left,top,right,bottom)->top>=12;
        StationLabelPlacement.Box topEdge=place(180,4,134,-10,92,0,below);
        valid(topEdge,VIEW_WIDTH,VIEW_HEIGHT,"top edge");
        require(topEdge.top>=12,"top-edge label was not placed below the edge");
        cases++;

        StationLabelPlacement.Clear above=(left,top,right,bottom)->bottom<=628;
        StationLabelPlacement.Box bottomEdge=place(180,636,134,650,92,0,above);
        valid(bottomEdge,VIEW_WIDTH,VIEW_HEIGHT,"bottom edge");
        require(bottomEdge.bottom<=628,"bottom-edge label was not placed above the edge");
        cases++;

        // Moving this preferred box up/down cannot clear a full-height rail.
        StationLabelPlacement.Clear verticalRail=(left,top,right,bottom)->right<175||left>185;
        StationLabelPlacement.Box besideRail=place(180,320,144,320,92,0,verticalRail);
        valid(besideRail,VIEW_WIDTH,VIEW_HEIGHT,"vertical rail");
        require(besideRail.left+besideRail.width<175||besideRail.left>185,
                "label did not try a horizontal alternative to the rail");
        cases++;

        StationLabelPlacement.Clear occupiedName=(left,top,right,bottom)->
                !overlap(left,top,right,bottom,140,280,250,320);
        StationLabelPlacement.Box besideName=place(180,320,150,310,92,0,occupiedName);
        valid(besideName,VIEW_WIDTH,VIEW_HEIGHT,"neighbour station name");
        require(occupiedName.accepts(besideName.left,besideName.top,
                        besideName.left+besideName.width,besideName.bottom),
                "fallback covered an existing station name");
        cases++;

        // A corner needs both axes corrected; a vertical-only search is insufficient.
        StationLabelPlacement.Clear cornerClear=(left,top,right,bottom)->left>=12&&top>=12;
        StationLabelPlacement.Box corner=place(3,3,-100,-10,92,0,cornerClear);
        valid(corner,VIEW_WIDTH,VIEW_HEIGHT,"top-left corner");
        require(corner.left>=12&&corner.top>=12,"corner fallback did not fit both axes");
        cases++;

        StationLabelPlacement.Box wrapped=place(330,625,330,645,148,32,empty);
        valid(wrapped,VIEW_WIDTH,VIEW_HEIGHT,"two-row label");
        require(wrapped.bottom-wrapped.top>=61.9f,"second row was omitted from collision bounds");
        cases++;

        require(place(180,320,144,320,92,0,(left,top,right,bottom)->false)==null,
                "an impossible placement ignored the collision rule");
        require(StationLabelPlacement.place(10,10,0,20,500,-24,6,0,
                        100,100,16,14,12,empty)==null,
                "a label wider than the whole viewport was accepted");
        cases+=2;

        require(place(Float.NaN,100,0,100,92,0,empty)==null,"NaN station coordinate accepted");
        require(place(100,Float.POSITIVE_INFINITY,0,100,92,0,empty)==null,
                "infinite station coordinate accepted");
        require(place(100,100,0,100,Float.POSITIVE_INFINITY,0,empty)==null,
                "infinite text width accepted");
        cases+=3;
        zoomOpensRailCorridors();
        narrowEdgeNeedsThreeRows();
        System.out.println("StationLabelPlacementSelfTest passed ("+cases+
                " scenarios: viewport edges, rail/name collisions, preserved anchors, wrapping, zoom and invalid input)");
    }

    private static void narrowEdgeNeedsThreeRows() {
        // Sports Center South: five large glyphs between two vertical tracks at
        // the left screen edge. Two rows are too wide; 2+2+1 stays beside the dot.
        float ascent=-19.413333f,descent=5.546667f,spacing=24.96f;
        // The dot is at x=8 with radius 16 and a 4px clearance; the glyph halo
        // must therefore start at or beyond x=28, giving text-left >=32.
        StationLabelPlacement.Clear corridor=(l,t,r,b)->l-4>=28&&r+4<=87;
        StationLabelPlacement.Box two=StationLabelPlacement.place(8,240,-65,246,
                62.4f,ascent,descent,spacing,360,480,24,24,12,corridor);
        require(two==null,"two large-font rows should not fit the narrow track corridor");
        StationLabelPlacement.Box three=StationLabelPlacement.place(8,240,-54,246,
                41.6f,ascent,descent,spacing*2,360,480,24,24,12,corridor);
        require(three!=null,"three short rows disappeared at the left screen edge");
        require(three.bottom-three.top>74,"third-row hit/collision bounds were truncated");
        require(corridor.accepts(three.left,three.top,three.left+three.width,three.bottom),
                "three-row label covered a neighbouring rail");cases++;
    }

    private static void zoomOpensRailCorridors() {
        // Five full-width glyphs between two parallel lines. Before the cap, both
        // text and line spacing grew together, so zooming never revealed the name.
        for(float density:new float[]{1,2,3})for(float fontScale:new float[]{1,1.3f}) {
            float scaledDensity=density*fontScale,worldTextSize=12*scaledDensity;
            String device="density="+density+", fontScale="+fontScale;
            float overviewSize=StationLabelPlacement.textSize(worldTextSize,1,scaledDensity);
            require(corridorPlacement(overviewSize,1,density)==null,
                    "regression setup should be crowded at overview: "+device);

            float detailedSize=StationLabelPlacement.textSize(worldTextSize,4,scaledDensity);
            require(corridorPlacement(worldTextSize*4,4,density)==null,
                    "uncapped text unexpectedly fit the detailed corridor: "+device);
            StationLabelPlacement.Box visible=corridorPlacement(detailedSize,4,density);
            valid(visible,VIEW_WIDTH*density,VIEW_HEIGHT*density,"zoomed rail corridor: "+device);
            require(detailedSize<=16*scaledDensity+.01f,
                    "detailed label exceeded accessible screen-size cap: "+device);
            require(close(detailedSize,StationLabelPlacement.textSize(worldTextSize,40,scaledDensity)),
                    "more zoom kept growing an already readable label: "+device);
            require(StationLabelPlacement.textSize(worldTextSize,.5f,scaledDensity)<overviewSize,
                    "zoomed-out labels were forced to the maximum size: "+device);
            cases++;
        }
    }
    private static StationLabelPlacement.Box corridorPlacement(float textSize,float zoom,float density) {
        float sx=180*density,sy=320*density,width=5*textSize;
        float halfCorridor=24*density*zoom;
        StationLabelPlacement.Clear betweenRails=(left,top,right,bottom)->
                left>=sx-halfCorridor&&right<=sx+halfCorridor;
        return StationLabelPlacement.place(sx,sy,sx-width/2,sy,width,-.8f*textSize,.2f*textSize,0,
                VIEW_WIDTH*density,VIEW_HEIGHT*density,16*density,14*density,12*density,betweenRails);
    }

    private static StationLabelPlacement.Box place(float sx,float sy,float left,float baseline,
            float width,float extraHeight,StationLabelPlacement.Clear clear) {
        return StationLabelPlacement.place(sx,sy,left,baseline,width,-24,6,extraHeight,
                VIEW_WIDTH,VIEW_HEIGHT,16,14,12,clear);
    }
    private static void valid(StationLabelPlacement.Box box,float width,float height,String description) {
        require(box!=null,"missing label for "+description);
        require(finite(box.left)&&finite(box.baseline)&&finite(box.width)&&finite(box.top)&&finite(box.bottom),
                "non-finite label for "+description);
        require(box.width>0&&box.bottom>box.top,"invalid dimensions for "+description);
        require(box.left>=0&&box.top>=0&&box.left+box.width<=width&&box.bottom<=height,
                "label leaves viewport for "+description);
    }
    private static boolean overlap(float left,float top,float right,float bottom,
                                   float l,float t,float r,float b) {
        return left<r&&l<right&&top<b&&t<bottom;
    }
    private static boolean finite(float value){return !Float.isNaN(value)&&!Float.isInfinite(value);}
    private static boolean close(float a,float b){return Math.abs(a-b)<.01f;}
    private static void require(boolean condition,String why){if(!condition)throw new AssertionError(why);}
}
