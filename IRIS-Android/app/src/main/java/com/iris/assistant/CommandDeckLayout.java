package com.iris.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/** Decorative grid and scan sweep; never represents traffic or activates hardware. */
public final class CommandDeckLayout extends LinearLayout {
    private final Paint paint = new Paint();
    private ValueAnimator sweep;
    private float position;
    private boolean visible;
    public CommandDeckLayout(Context c, AttributeSet a) {
        super(c,a);setWillNotDraw(false);setBackgroundColor(0xFF05090D);
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();updateMotion();}
    @Override protected void onDetachedFromWindow(){if(sweep!=null){sweep.cancel();sweep=null;}super.onDetachedFromWindow();}
    @Override public void onVisibilityAggregated(boolean shown){super.onVisibilityAggregated(shown);visible=shown;updateMotion();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);updateMotion();}
    private void updateMotion(){
        if(!isAttachedToWindow())return;
        AppSettings settings=new AppSettings(getContext());
        boolean run=visible && getWindowVisibility()==View.VISIBLE && !settings.reduceMotion() && !settings.deckBatterySaver() && settings.deckScanline();
        if(!run){if(sweep!=null){sweep.cancel();sweep=null;}invalidate();return;}
        if(sweep!=null)return;
        sweep=ValueAnimator.ofFloat(0,1);sweep.setDuration(12000);sweep.setRepeatCount(ValueAnimator.INFINITE);
        sweep.setInterpolator(new android.view.animation.LinearInterpolator());
        sweep.addUpdateListener(a->{position=(float)a.getAnimatedValue();invalidate();});sweep.start();
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float step=28*getResources().getDisplayMetrics().density;
        paint.setColor(0x102D7682);paint.setStrokeWidth(1);
        for(float x=0;x<getWidth();x+=step)c.drawLine(x,0,x,getHeight(),paint);
        for(float y=0;y<getHeight();y+=step)c.drawLine(0,y,getWidth(),y,paint);
        if(sweep!=null){paint.setColor(0x2635D9F4);c.drawLine(0,position*getHeight(),getWidth(),position*getHeight(),paint);}
    }
}
