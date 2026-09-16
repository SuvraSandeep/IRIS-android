package com.iris.assistant;

import android.content.Context;
import android.content.res.Configuration;
import android.view.*;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Measures real Android layouts, including large fonts; does not claim physical-device QA. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class TrainingLayoutTest {
    @Test public void trainingControlsDoNotOverlapAtNarrowWidthsAndLargeFonts(){
        for(int width:new int[]{320,360,412})for(float scale:new float[]{1f,1.5f,2f}){
            Configuration config=new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
            config.fontScale=scale;config.densityDpi=160;
            Context context=new ContextThemeWrapper(RuntimeEnvironment.getApplication().createConfigurationContext(config),android.R.style.Theme_Material_NoActionBar);
            View root=LayoutInflater.from(context).inflate(R.layout.view_training,null,false);
            // Exercise all controls together: a conservative worst-case layout including retry text.
            reveal(root);
            ((TextView)root.findViewById(R.id.ownerPhraseHero)).setText("Today I am teaching my assistant to recognize my natural voice.");
            ((TextView)root.findViewById(R.id.wakeWizardPrompt)).setText("The microphone did not provide enough speech. Check your headset and try recording again when ready.");
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));
            root.layout(0,0,width,480);check(root);
        }
    }
    private void reveal(View view){
        view.setVisibility(View.VISIBLE);
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)reveal(group.getChildAt(i));}
    }
    private void check(View view){
        if(view instanceof Button){
            Button b=(Button)view;assertTrue("Touch height "+b.getText(),b.getHeight()>=48);
            assertNotNull(b.getLayout());assertTrue("Clipped text: "+b.getText(),b.getLayout().getHeight()<=b.getHeight()-b.getCompoundPaddingTop()-b.getCompoundPaddingBottom());
        }
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;int bottom=-1;
            for(int i=0;i<g.getChildCount();i++){View child=g.getChildAt(i);
                assertTrue("Child wider than parent",child.getRight()<=g.getWidth());
                if(g instanceof LinearLayout&&((LinearLayout)g).getOrientation()==LinearLayout.VERTICAL){assertTrue("Overlapping controls",child.getTop()>=bottom);bottom=child.getBottom();}
                check(child);
            }
        }
    }
}
