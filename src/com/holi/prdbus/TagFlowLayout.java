package com.holi.prdbus;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Keeps every interchange chip visible without forcing a wide row off screen. */
final class TagFlowLayout extends ViewGroup {
    private final int gap, rowGap;
    TagFlowLayout(Context context) { super(context); gap=Ui.dp(context,8); rowGap=Ui.dp(context,8); }
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int limit=Math.max(0,MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight());
        int x=0,y=0,row=0,widest=0;
        for(int i=0;i<getChildCount();i++) {
            View child=getChildAt(i);if(child.getVisibility()==GONE)continue;
            child.measure(MeasureSpec.makeMeasureSpec(limit,MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
            int width=child.getMeasuredWidth(),height=child.getMeasuredHeight();
            if(x>0 && x+width>limit){widest=Math.max(widest,x-gap);x=0;y+=row+rowGap;row=0;}
            x+=width+gap;row=Math.max(row,height);
        }
        widest=Math.max(widest,Math.max(0,x-gap));
        setMeasuredDimension(resolveSize(widest+getPaddingLeft()+getPaddingRight(),widthSpec),
                resolveSize(y+row+getPaddingTop()+getPaddingBottom(),heightSpec));
    }
    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {
        int limit=getWidth()-getPaddingLeft()-getPaddingRight(),x=0,y=getPaddingTop(),row=0;
        for(int i=0;i<getChildCount();i++){
            View child=getChildAt(i);if(child.getVisibility()==GONE)continue;
            int width=child.getMeasuredWidth(),height=child.getMeasuredHeight();
            if(x>0 && x+width>limit){x=0;y+=row+rowGap;row=0;}
            child.layout(getPaddingLeft()+x,y,getPaddingLeft()+x+width,y+height);
            x+=width+gap;row=Math.max(row,height);
        }
    }
    @Override protected LayoutParams generateDefaultLayoutParams(){return new LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT);}
}
