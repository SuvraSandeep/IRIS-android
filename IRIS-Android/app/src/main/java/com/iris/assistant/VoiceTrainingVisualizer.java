package com.iris.assistant;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;

/** Audio-driven bars: no fake listening animation and no idle animation loop. */
public final class VoiceTrainingVisualizer extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float level;private boolean listening;
    public VoiceTrainingVisualizer(Context c,AttributeSet attrs){super(c,attrs);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    public void update(boolean active,float amplitude){listening=active;level=Math.max(0,Math.min(1,amplitude));invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float w=getWidth(),h=getHeight(),mid=h/2,step=w/31;
        paint.setColor(listening?Color.rgb(74,231,205):Color.rgb(76,87,113));
        for(int i=0;i<29;i++){
            float shape=(float)(.35+.65*Math.abs(Math.sin(i*1.7))),envelope=1-Math.abs(i-14)/18f;
            float bar=listening?Math.max(4,h*.82f*level*shape*envelope):4;
            float x=(i+1)*step;canvas.drawRoundRect(x,mid-bar/2,x+Math.max(3,step*.38f),mid+bar/2,4,4,paint);
        }
    }
}
