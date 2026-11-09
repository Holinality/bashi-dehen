package com.holi.prdbus;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** View-scoped, bounded background cache. Rails and station text stay vector.
 * Panning reprojects the previous bitmap; expensive water polygons are only
 * rasterized after the gesture settles, on one worker, never on the UI thread.
 */
final class MetroBackdrop {
    private final View owner;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Paint bitmapPaint=new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF destination=new RectF();
    private Path land,water,rivers,coast,districts;
    private Bitmap cached;
    private float cachedX,cachedY,cachedZoom,padding,cachedWidth,cachedHeight;
    private float requestX,requestY,requestZoom;
    private int requestWidth,requestHeight;
    private volatile int generation;
    private volatile boolean closed;
    private boolean rendering,enabled;
    private final Runnable render=this::render;

    MetroBackdrop(View owner){this.owner=owner;}

    void set(Path land,Path water,Path rivers,Path coast,Path districts,boolean enabled) {
        generation++;owner.removeCallbacks(render);cached=null;
        this.enabled=enabled;
        if(!enabled){this.land=null;this.water=null;this.rivers=null;this.coast=null;this.districts=null;return;}
        // Snapshot once. A mode change can then rebuild the View's paths while
        // a cancelled background task safely finishes using its old snapshot.
        this.land=new Path(land);this.water=new Path(water);this.rivers=new Path(rivers);
        this.coast=new Path(coast);this.districts=new Path(districts);
    }

    void draw(Canvas canvas,float x,float y,float zoom,boolean moving) {
        if(!enabled||closed||owner.getWidth()==0||owner.getHeight()==0)return;
        if(cached!=null) {
            float ratio=zoom/cachedZoom;
            float left=x-(cachedX+padding)*ratio,top=y-(cachedY+padding)*ratio;
            destination.set(left,top,left+cachedWidth*ratio,top+cachedHeight*ratio);
            canvas.drawBitmap(cached,null,destination,bitmapPaint);
            if(Math.abs(ratio-1)<.06f&&destination.left<=0&&destination.top<=0
                    &&destination.right>=owner.getWidth()&&destination.bottom>=owner.getHeight())return;
        }
        requestX=x;requestY=y;requestZoom=zoom;
        requestWidth=owner.getWidth();requestHeight=owner.getHeight();
        owner.removeCallbacks(render);
        if(!moving&&!rendering)owner.postDelayed(render,cached==null?0:100);
    }

    private void render() {
        if(closed||!enabled||rendering||requestWidth<=0||requestHeight<=0)return;
        rendering=true;
        final int token=generation,w=requestWidth,h=requestHeight;
        final float sx=requestX,sy=requestY,scale=requestZoom;
        final float pad=Ui.dp(owner.getContext(),64),width=w+2*pad,height=h+2*pad;
        // At most 1.2M RGB565 pixels (~2.4 MB), regardless of screen resolution.
        final float resolution=Math.min(1f,(float)Math.sqrt(1_200_000f/(width*height)));
        final Path l=land,wa=water,r=rivers,c=coast,d=districts;
        final boolean night=Ui.NIGHT;
        final float density=owner.getResources().getDisplayMetrics().density;
        worker.execute(() -> {
            Bitmap bitmap=null;
            try {
                if(token==generation&&!closed) {
                    bitmap=Bitmap.createBitmap(Math.max(1,Math.round(width*resolution)),
                            Math.max(1,Math.round(height*resolution)),Bitmap.Config.RGB_565);
                    Canvas out=new Canvas(bitmap);
                    out.drawColor(night?0xff19272f:0xffe6f0f6);
                    out.scale(resolution,resolution);out.translate(sx+pad,sy+pad);out.scale(scale,scale);
                    Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setColor(night?0xff202c28:0xfff3f6f1);out.drawPath(l,p);
                    p.setColor(night?0xff203540:0xffdcecf5);out.drawPath(wa,p);
                    p.setStyle(Paint.Style.STROKE);p.setStrokeJoin(Paint.Join.ROUND);p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeWidth(1.6f*density/scale);p.setColor(night?0xff29434f:0xffd5e8f2);out.drawPath(r,p);
                    p.setStrokeWidth(.65f*density/scale);p.setColor(night?0xff405c65:0xffb3cdd8);out.drawPath(c,p);
                    p.setStrokeWidth(.8f*density/scale);p.setColor(night?0xff526075:0xffc6cfd8);out.drawPath(d,p);
                }
            } catch(OutOfMemoryError|RuntimeException unavailable) {
                if(bitmap!=null)bitmap.recycle();bitmap=null;
            }
            final Bitmap result=bitmap;
            owner.post(() -> {
                rendering=false;
                if(closed||token!=generation) {if(result!=null)result.recycle();}
                else if(result!=null) {
                    cached=result;cachedX=sx;cachedY=sy;cachedZoom=scale;
                    padding=pad;cachedWidth=width;cachedHeight=height;
                } else enabled=false; // Never retry an OOM in an invalidation loop.
                // Repaint latest camera once. Continuous GPS updates do not
                // schedule polygon work when the cached viewport still fits.
                if(!closed)owner.invalidate();
            });
        });
    }

    void close(){closed=true;generation++;owner.removeCallbacks(render);cached=null;worker.shutdownNow();}
}
