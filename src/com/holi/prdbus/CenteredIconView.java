package com.holi.prdbus;
import android.content.Context;
import android.graphics.Canvas;
import android.widget.TextView;

/** Icon-only targets use the view center, independent of the text font's baseline. */
final class CenteredIconView extends TextView {
    private TransitIcon icon;
    CenteredIconView(Context context,String kind,int color) {
        super(context);setIcon(kind,color);setMinimumHeight(Ui.dp(context,48));setMinimumWidth(Ui.dp(context,32));
        setGravity(android.view.Gravity.CENTER);setIncludeFontPadding(false);
    }
    void setIcon(String kind,int color) {icon=new TransitIcon(kind,color);invalidate();}
    @Override public void setTextColor(int color) {super.setTextColor(color);if(icon!=null){icon.paint.setColor(color);invalidate();}}
    @Override protected void onDraw(Canvas canvas) {
        int size=Math.min(Ui.dp(getContext(),22),Math.min(getWidth(),getHeight()));
        int x=(getWidth()-size)/2,y=(getHeight()-size)/2;
        icon.setBounds(x,y,x+size,y+size);icon.draw(canvas);
    }
}
