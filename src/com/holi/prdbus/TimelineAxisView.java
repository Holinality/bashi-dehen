package com.holi.prdbus;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** 按所在行真实高度绘制的连续站点轴，避免内容换行或间距变化造成断线。 */
final class TimelineAxisView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int lineColor;
    private final int dotColor;
    private final boolean first;
    private final boolean last;
    private final boolean emphasized;
    private final float density;
    private float anchor = -1;
    void setAnchor(float y) { if (Math.abs(anchor-y)>.5f) { anchor=y; invalidate(); } }

    TimelineAxisView(Context context, int lineColor, int dotColor,
                     boolean first, boolean last, boolean emphasized) {
        super(context);
        this.lineColor = lineColor;
        this.dotColor = dotColor;
        this.first = first;
        this.last = last;
        this.emphasized = emphasized;
        this.density = context.getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(Math.round(34 * density), widthMeasureSpec);
        int height = resolveSize(Math.round(72 * density), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = anchor >= 0 ? Math.max(0,Math.min(getHeight(),anchor)) : getHeight() / 2f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4 * density);
        paint.setStrokeCap(Paint.Cap.SQUARE);
        paint.setColor(lineColor);
        if (!first) canvas.drawLine(cx, 0, cx, cy, paint);
        if (!last) canvas.drawLine(cx, cy, cx, getHeight(), paint);

        float radius = (emphasized ? 8 : 6) * density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, radius + 2 * density, paint);
        paint.setColor(dotColor);
        canvas.drawCircle(cx, cy, radius, paint);
    }
}
