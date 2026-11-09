package com.holi.prdbus;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Authored 24-unit outline icons; identical weight on every Android font/vendor. */
final class TransitIcon extends Drawable {
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final String kind;
    TransitIcon(String kind, int color) {
        this.kind = kind;
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
    }
    @Override public void draw(Canvas c) {
        c.save();
        c.translate(getBounds().left, getBounds().top);
        c.scale(getBounds().width()/24f, getBounds().height()/24f);
        switch (kind) {
            case "settings":
                c.drawLine(4,6,8,6,paint); c.drawLine(12,6,20,6,paint);
                c.drawLine(4,12,14,12,paint); c.drawLine(18,12,20,12,paint);
                c.drawLine(4,18,6,18,paint); c.drawLine(10,18,20,18,paint);
                c.drawCircle(10,6,2,paint);
                c.drawCircle(16,12,2,paint);
                c.drawCircle(8,18,2,paint);
                break;
            case "search":
                c.drawCircle(10.5f,10.5f,6.5f,paint); c.drawLine(16,16,21,21,paint); break;
            case "back":
                line(c,15,5,8,12,15,19); break;
            case "chevron":
                line(c,9,6,15,12,9,18); break;
            case "close":
                c.drawLine(6,6,18,18,paint); c.drawLine(6,18,18,6,paint); break;
            case "plus":
                c.drawLine(5,12,19,12,paint); c.drawLine(12,5,12,19,paint); break;
            case "minus":
                c.drawLine(5,12,19,12,paint); break;
            case "fit":
                line(c,8,3,3,3,3,8);
                line(c,16,3,21,3,21,8);
                line(c,3,16,3,21,8,21);
                line(c,16,21,21,21,21,16);
                break;
            case "locate":
                c.drawCircle(12,12,7,paint); c.drawCircle(12,12,2,paint);
                c.drawLine(12,1,12,5,paint); c.drawLine(12,19,12,23,paint);
                c.drawLine(1,12,5,12,paint); c.drawLine(19,12,23,12,paint); break;
            case "swap":
                c.drawLine(7,4,7,20,paint); line(c,3,8,7,4,11,8);
                c.drawLine(17,4,17,20,paint); line(c,13,16,17,20,21,16); break;
            case "ride-code":
                c.drawRect(3,3,9,9,paint); c.drawRect(15,3,21,9,paint); c.drawRect(3,15,9,21,paint);
                c.drawLine(12,12,17,12,paint); c.drawLine(12,12,12,17,paint);
                c.drawLine(16,16,21,16,paint); c.drawLine(21,16,21,21,paint);
                c.drawLine(15,21,18,21,paint); break;
            case "refresh":
                c.drawArc(4,4,20,20,35,290,false,paint); line(c,16,3,20,7,15,8); break;
            case "route":
                c.drawCircle(6,5,2,paint); c.drawCircle(18,19,2,paint);
                Path p=new Path(); p.moveTo(8,5); p.lineTo(16,5); p.cubicTo(22,5,22,12,15,12);
                p.lineTo(9,12); p.cubicTo(2,12,2,19,9,19); p.lineTo(16,19); c.drawPath(p,paint); break;
            case "star": case "star-filled":
                Path star=new Path();
                for(int i=0;i<10;i++) {
                    double a=-Math.PI/2+i*Math.PI/5;
                    float r=i%2==0?9:4.2f;
                    float x=12+(float)Math.cos(a)*r, y=12+(float)Math.sin(a)*r;
                    if(i==0)star.moveTo(x,y); else star.lineTo(x,y);
                }
                star.close();
                paint.setStyle("star-filled".equals(kind)?Paint.Style.FILL:Paint.Style.STROKE);
                c.drawPath(star,paint); paint.setStyle(Paint.Style.STROKE); break;
            case "pin":
                Path pin=new Path();pin.moveTo(12,22);pin.cubicTo(5,14,3,11,5,6);
                pin.cubicTo(8,-1,20,1,20,9);pin.cubicTo(20,14,15,19,12,22);c.drawPath(pin,paint);
                c.drawCircle(12,9,2.5f,paint); break;
            case "bus":
                Path bus = new Path();
                bus.moveTo(3,16); bus.lineTo(2,16); bus.lineTo(2,8);
                bus.quadTo(2,6,4,6); bus.lineTo(17,6);
                bus.quadTo(19,6,20,8); bus.lineTo(22,11); bus.lineTo(22,16);
                bus.lineTo(20,16);
                c.drawPath(bus,paint);
                c.drawLine(9,16,15,16,paint);
                c.drawLine(3,11,21,11,paint);
                c.drawLine(8,6,8,11,paint); c.drawLine(14,6,14,11,paint);
                c.drawCircle(6.5f,16.5f,2.5f,paint);
                c.drawCircle(17.5f,16.5f,2.5f,paint);
                break;
            case "metro":
                c.drawRoundRect(5,3,19,18,3,3,paint); c.drawLine(5,11,19,11,paint);
                c.drawLine(12,4,12,11,paint);c.drawCircle(8,15,1,paint);c.drawCircle(16,15,1,paint);
                c.drawLine(8,18,6,21,paint);c.drawLine(16,18,18,21,paint);break;
        }
        c.restore();
    }
    private void line(Canvas c,float a,float b,float d,float e,float f,float g) {
        Path p=new Path();p.moveTo(a,b);p.lineTo(d,e);p.lineTo(f,g);c.drawPath(p,paint);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
