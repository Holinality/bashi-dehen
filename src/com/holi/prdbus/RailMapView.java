package com.holi.prdbus;

import android.content.Context;
import android.graphics.*;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/** Interactive topology view; public builds use original vectors when artwork is absent. */
final class RailMapView extends View {
    interface Selection {void select(String station);}
    private final Bitmap image;
    private final BitmapRegionDecoder regionDecoder;
    private final int sourceWidth,sourceHeight,overviewSample;
    private final boolean guangdong;
    private final boolean vector;
    private final java.util.concurrent.ExecutorService rasterWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile int rasterGeneration;
    private volatile boolean released;
    private Bitmap detail;
    private Rect detailSource;
    private int detailSample;
    private String requestedRegion="";
    private final Map<String,PointF> stations;
    private final Map<RailCatalog.Line,Path> routes=new LinkedHashMap<>();
    private final List<float[]> edges=new ArrayList<>();
    private final Map<String,Integer> colors=new LinkedHashMap<>();
    private final Set<String> transfers=new HashSet<>();
    private final Map<String,RectF> labels=new LinkedHashMap<>();
    private final List<RectF> occupied=new ArrayList<>();
    private final RectF bounds=new RectF();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final GestureDetector gestures;
    private final ScaleGestureDetector pinch;
    private float fit=1,scale=1,left,top;
    private String from="",to="",selected="",nearby="";

    RailMapView(Context context,String asset,Map<String,PointF> stations,Selection selection) throws IOException {
        super(context);this.stations=stations;guangdong="rail-map-gd.jpg".equals(asset);
        BitmapRegionDecoder decoder=null;Bitmap overview=null;int sw=1580,sh=1572,sample=1;
        try(InputStream in=context.getAssets().open(guangdong?"rail-map-gd-hd.jpg":asset)) {
            decoder=BitmapRegionDecoder.newInstance(in,false);
            sw=decoder.getWidth();sh=decoder.getHeight();
            while(Math.max(sw,sh)/sample>1800)sample*=2;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.RGB_565;options.inSampleSize=sample;
            overview=decoder.decodeRegion(new Rect(0,0,sw,sh),options);
            if(overview==null)throw new IOException("线路图不可读");
        } catch(IOException|OutOfMemoryError unavailable) {
            if(decoder!=null)decoder.recycle();decoder=null;
            // Both regions retain the original topology fallback without an operator image.
        }
        image=overview;regionDecoder=decoder;sourceWidth=sw;sourceHeight=sh;overviewSample=sample;vector=image==null;
        if(!stations.isEmpty())buildRoutes();
        setContentDescription("线路拓扑示意图，不代表地理位置或官方图。双指缩放，点击圆圈或站名选站");
        pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoom(detector.getScaleFactor(),detector.getFocusX(),detector.getFocusY());return true;
            }
        });pinch.setQuickScaleEnabled(false);
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event){return true;}
            @Override public boolean onScroll(MotionEvent first,MotionEvent current,float dx,float dy) {
                if(!pinch.isInProgress()){left-=dx;top-=dy;clamp();}return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event){zoom(1.8f,event.getX(),event.getY());return true;}
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                String station=hit(event.getX(),event.getY());
                if(station==null)return false;performClick();selection.select(station);return true;
            }
        });
    }
    private float dp(float value){return Ui.dp(getContext(),value);}
    private float sp(float value){return value*getResources().getDisplayMetrics().scaledDensity;}
    private float width(){return sourceWidth;}
    private float height(){return sourceHeight;}
    private float margin(){return stations.isEmpty()?0:Math.max(width(),height())*.02f;}
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){reset();}
    void reset() {
        if(getWidth()==0||getHeight()==0)return;
        fit=Math.max(.0001f,Math.min(getWidth()/(width()+2*margin()),getHeight()/(height()+2*margin())));
        scale=fit;left=(getWidth()-(width()+2*margin())*scale)/2;top=(getHeight()-(height()+2*margin())*scale)/2;clamp();
    }
    void zoom(float factor){zoom(factor,getWidth()/2f,getHeight()/2f);}
    private void zoom(float factor,float x,float y) {
        float next=Math.max(fit,Math.min(fit*14,scale*factor));
        left=x-(x-left)*next/scale;top=y-(y-top)*next/scale;scale=next;clamp();
    }
    private void clamp() {
        float w=(width()+2*margin())*scale,h=(height()+2*margin())*scale;
        left=w<=getWidth()?(getWidth()-w)/2:Math.min(0,Math.max(getWidth()-w,left));
        top=h<=getHeight()?(getHeight()-h)/2:Math.min(0,Math.max(getHeight()-h,top));
        bounds.set(left+margin()*scale,top+margin()*scale,left+(margin()+width())*scale,top+(margin()+height())*scale);invalidate();
    }
    void focus(String station) {
        PointF point=stations.get(station);if(point==null||getWidth()==0)return;
        scale=Math.max(scale,fit*(guangdong&&!vector?6:4));
        left=getWidth()/2f-(margin()+point.x*width())*scale;
        top=getHeight()/2f-(margin()+point.y*height())*scale;clamp();
    }
    void mark(String from,String to,String selected,String nearby) {
        this.from=from;this.to=to;this.selected=selected;this.nearby=nearby;invalidate();
    }
    private float x(PointF p){return bounds.left+p.x*bounds.width();}
    private float y(PointF p){return bounds.top+p.y*bounds.height();}
    private String hit(float x,float y) {
        for(Map.Entry<String,RectF> label:labels.entrySet())if(label.getValue().contains(x,y))return label.getKey();
        if(scale<fit*1.4f)return null;
        float best=dp(24);String match=null;
        for(Map.Entry<String,PointF> station:stations.entrySet()) {
            float gap=(float)Math.hypot(x-x(station.getValue()),y-y(station.getValue()));
            if(gap<best){best=gap;match=station.getKey();}
        }return match;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN)getParent().requestDisallowInterceptTouchEvent(true);
        pinch.onTouchEvent(event);gestures.onTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)
            getParent().requestDisallowInterceptTouchEvent(false);
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Ui.SURFACE);paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);
        if(image!=null&&!image.isRecycled()) {
            canvas.drawBitmap(image,null,bounds,paint);
            if(detail!=null&&!detail.isRecycled()&&detailSource!=null) {
                RectF target=new RectF(bounds.left+detailSource.left*scale,bounds.top+detailSource.top*scale,
                        bounds.left+detailSource.right*scale,bounds.top+detailSource.bottom*scale);
                canvas.drawBitmap(detail,null,target,paint);
            }
            requestDetail();
        }
        canvas.save();canvas.translate(bounds.left,bounds.top);canvas.scale(scale,scale);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        for(Map.Entry<RailCatalog.Line,Path> route:routes.entrySet()) {
            paint.setColor(route.getKey().color);
            paint.setStrokeWidth(dp(vector?3.5f:4)/scale);canvas.drawPath(route.getValue(),paint);
        }canvas.restore();paint.setStyle(Paint.Style.FILL);
        labels.clear();occupied.clear();
        for(Map.Entry<String,PointF> station:stations.entrySet())ring(canvas,station.getKey(),station.getValue());
        if(vector&&scale>=fit*1.8f) {
            List<String> names=new ArrayList<>(stations.keySet());
            Collections.sort(names,(a,b)->Integer.compare(priority(b),priority(a)));
            for(String name:names)label(canvas,name);
        }
        marker(canvas,nearby,"近",0xff246fca);
        if(!selected.equals(from)&&!selected.equals(to))marker(canvas,selected,"",0xff35668e);
        marker(canvas,from,"起",0xff087568);marker(canvas,to,"终",0xffac4c29);
    }
    private int priority(String name){return name.equals(selected)?4:name.equals(from)||name.equals(to)?3:transfers.contains(name)?2:1;}
    private void ring(Canvas canvas,String name,PointF point) {
        float x=x(point),y=y(point),r=dp(transfers.contains(name)?5:scale>=fit*1.8f?3.6f:2.4f);
        if(x-r<0||y-r<0||x+r>getWidth()||y+r>getHeight())return;
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xffffffff);canvas.drawCircle(x,y,r,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.6f));
        paint.setColor(colors.containsKey(name)?colors.get(name):Ui.PRIMARY);canvas.drawCircle(x,y,r,paint);
        paint.setStyle(Paint.Style.FILL);occupied.add(new RectF(x-r-dp(2),y-r-dp(2),x+r+dp(2),y+r+dp(2)));
    }
    private void label(Canvas canvas,String name) {
        PointF point=stations.get(name);float sx=x(point),sy=y(point);
        if(sx<0||sy<0||sx>getWidth()||sy>getHeight())return;
        paint.setTextAlign(Paint.Align.LEFT);paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        paint.setTextSize(sp(priority(name)>2?16:14));
        float w=paint.measureText(name),h=paint.descent()-paint.ascent(),gap=dp(11);
        for(int i=0;i<8;i++) {
            float tx=i%3==0?sx+gap:i%3==1?sx-w-gap:sx-w/2;
            float ty=i<3?sy-gap-h:i<6?sy+gap:sy-h/2;
            RectF rect=new RectF(tx-dp(2),ty-dp(2),tx+w+dp(2),ty+h+dp(2));
            if(!fits(rect))continue;
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(3));paint.setColor(Ui.SURFACE);
            canvas.drawText(name,tx,ty-paint.ascent(),paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(priority(name)>2?Ui.PRIMARY:Ui.TEXT);
            canvas.drawText(name,tx,ty-paint.ascent(),paint);labels.put(name,rect);occupied.add(rect);return;
        }
    }
    private boolean fits(RectF box) {
        float pad=dp(4);
        if(box.left<pad||box.top<pad||box.right>getWidth()-pad||box.bottom>getHeight()-pad)return false;
        for(RectF previous:occupied)if(RectF.intersects(box,previous))return false;
        for(float[] edge:edges)if(MapGeometry.crosses(bounds.left+edge[0]*bounds.width(),bounds.top+edge[1]*bounds.height(),
                bounds.left+edge[2]*bounds.width(),bounds.top+edge[3]*bounds.height(),box.left-pad,box.top-pad,box.right+pad,box.bottom+pad))return false;
        return true;
    }
    private void buildRoutes() {
        for (RailCatalog.Line line : RailCatalog.search(guangdong ? RailCatalog.GD : RailCatalog.SH,"")) {
            List<PointF> knots = new ArrayList<>();
            for (String stop : line.stops) {
                colors.put(stop,line.color);
                if (!RailCatalog.fromRail(line.region,stop).isEmpty()) transfers.add(stop);
                PointF point=stations.get(stop);
                if (point!=null) knots.add(point);
            }
            if (knots.size()>1) routes.put(line,path(knots));
        }
    }
    private Path path(List<PointF> knots) {
        Path result=new Path();if(knots.isEmpty())return result;
        PointF first=knots.get(0);result.moveTo(first.x*width(),first.y*height());
        for(int i=1;i<knots.size();i++) {
            PointF a=knots.get(i-1),b=knots.get(i);edges.add(new float[]{a.x,a.y,b.x,b.y});
            boolean stop=false;for(PointF p:stations.values())if(Math.hypot(p.x-b.x,p.y-b.y)<.0008){stop=true;break;}
            if(!stop&&i+1<knots.size()) {
                PointF c=knots.get(i+1);double in=Math.hypot(b.x-a.x,b.y-a.y),out=Math.hypot(c.x-b.x,c.y-b.y);
                if(in>1e-6&&out>1e-6){double r=Math.min(.009,Math.min(in,out)*.25);
                    result.lineTo((float)(b.x+(a.x-b.x)*r/in)*width(),(float)(b.y+(a.y-b.y)*r/in)*height());
                    result.quadTo(b.x*width(),b.y*height(),(float)(b.x+(c.x-b.x)*r/out)*width(),(float)(b.y+(c.y-b.y)*r/out)*height());continue;}
            }result.lineTo(b.x*width(),b.y*height());
        }return result;
    }
    private void marker(Canvas canvas,String station,String text,int color) {
        PointF p=stations.get(station);if(p==null)return;float x=x(p),y=y(p),r=dp(9);
        if(x-r<0||y-r<0||x+r>getWidth()||y+r>getHeight())return;
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xffffffff);canvas.drawCircle(x,y,r+dp(2),paint);
        paint.setColor(color);canvas.drawCircle(x,y,r,paint);
        paint.setTextSize(sp(11));paint.setTextAlign(Paint.Align.CENTER);paint.setColor(0xffffffff);
        canvas.drawText(text,x,y-(paint.ascent()+paint.descent())/2,paint);
    }
    private void requestDetail() {
        if(released||regionDecoder==null||rasterWorker.isShutdown()||scale<=0)return;
        int sample=1;
        while(sample*2*scale<.85f&&sample<16)sample*=2;
        if(sample>=overviewSample)return;
        Rect visible=new Rect(Math.max(0,(int)Math.floor(-bounds.left/scale)),Math.max(0,(int)Math.floor(-bounds.top/scale)),
                Math.min(sourceWidth,(int)Math.ceil((getWidth()-bounds.left)/scale)),
                Math.min(sourceHeight,(int)Math.ceil((getHeight()-bounds.top)/scale)));
        if(visible.isEmpty()||(detailSource!=null&&detailSample<=sample&&detailSource.contains(visible)))return;
        Rect needed=new Rect(Math.max(0,visible.left/128*128-128),Math.max(0,visible.top/128*128-128),
                Math.min(sourceWidth,(visible.right+127)/128*128+128),Math.min(sourceHeight,(visible.bottom+127)/128*128+128));
        while(needed.width()/sample>1800||needed.height()/sample>1800)sample*=2;
        if(sample>=overviewSample)return;
        String signature=sample+":"+needed.toShortString();
        if(signature.equals(requestedRegion))return;
        requestedRegion=signature;final int request=++rasterGeneration,decodeSample=sample;
        rasterWorker.execute(()->{
            if(released||request!=rasterGeneration)return;
            Bitmap decoded=null;
            try {
                BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=decodeSample;
                options.inPreferredConfig=Bitmap.Config.RGB_565;
                synchronized(regionDecoder){if(!released)decoded=regionDecoder.decodeRegion(needed,options);}
            } catch(RuntimeException|OutOfMemoryError unavailable) { }
            final Bitmap result=decoded;
            if(result==null)return;
            post(()->{
                if(released||request!=rasterGeneration){result.recycle();return;}
                Bitmap previous=detail;detail=result;detailSource=new Rect(needed);detailSample=decodeSample;
                if(previous!=null&&!previous.isRecycled())previous.recycle();invalidate();
            });
        });
    }
    @Override protected void onDetachedFromWindow() {
        released=true;rasterGeneration++;rasterWorker.shutdownNow();
        if(detail!=null&&!detail.isRecycled())detail.recycle();detail=null;
        if(image!=null&&!image.isRecycled())image.recycle();
        if(regionDecoder!=null)synchronized(regionDecoder){if(!regionDecoder.isRecycled())regionDecoder.recycle();}
        super.onDetachedFromWindow();
    }
    static Map<String,PointF> points(int width,int height,String[] names,int... positions) {
        if(names.length*2!=positions.length)throw new IllegalArgumentException("站名和坐标数量不一致");
        Map<String,PointF> result=new LinkedHashMap<>();
        for(int i=0;i<names.length;i++)result.put(names[i],new PointF(positions[2*i]/(float)width,positions[2*i+1]/(float)height));return result;
    }
}
