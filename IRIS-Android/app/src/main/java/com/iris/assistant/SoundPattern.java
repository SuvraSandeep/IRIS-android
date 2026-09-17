package com.iris.assistant;
import java.util.*;

/** Versioned log-mel sequence matcher. Labels/transcripts are deliberately absent. */
final class SoundPattern {
    static final String VERSION="logmel24-dtw-v1";
    static final int BANDS=24, MAX_FRAMES=250;
    static float[][] extract(short[] pcm){
        if(!WakePolicy.usableAudio(pcm)||pcm.length>16000*8)return new float[0][];
        int count=pcm.length/320;double[] energy=new double[count];
        for(int f=0;f<count;f++){double e=0;for(int i=f*320;i<(f+1)*320;i++)e+=pcm[i]*(double)pcm[i];energy[f]=Math.sqrt(e/320);}
        double[] sorted=energy.clone();Arrays.sort(sorted);double floor=Math.max(150,Math.max(60,sorted[count/10])*3);
        int first=0,last=count-1;while(first<count&&energy[first]<floor)first++;while(last>=first&&energy[last]<floor)last--;
        int start=Math.max(0,first*320-320),end=Math.min(pcm.length,(last+2)*320);
        if(end-start<6400||end-start>16000*5)return new float[0][];
        int frames=1+(end-start-400)/320;if(frames<12||frames>MAX_FRAMES)return new float[0][];
        float[][] out=new float[frames][BANDS];double[] edges=new double[BANDS+2];
        double lo=2595*Math.log10(1+80.0/700),hi=2595*Math.log10(1+7600.0/700);
        for(int i=0;i<edges.length;i++)edges[i]=700*(Math.pow(10,(lo+(hi-lo)*i/(BANDS+1))/2595)-1)*512/16000;
        for(int f=0;f<frames;f++){
            double[] re=new double[512],im=new double[512];
            for(int i=0;i<400;i++)re[i]=pcm[start+f*320+i]*(.54-.46*Math.cos(2*Math.PI*i/399));
            fft(re,im);double mean=0;
            for(int b=0;b<BANDS;b++){double sum=0;for(int k=(int)Math.ceil(edges[b]);k<Math.min(257,edges[b+2]);k++){
                double weight=k<=edges[b+1]?(k-edges[b])/(edges[b+1]-edges[b]):(edges[b+2]-k)/(edges[b+2]-edges[b+1]);
                sum+=Math.max(0,weight)*(re[k]*re[k]+im[k]*im[k]);
            }out[f][b]=(float)Math.log(1+sum);mean+=out[f][b];}
            mean/=BANDS;double norm=0;for(int b=0;b<BANDS;b++){out[f][b]-=mean;norm+=out[f][b]*out[f][b];}
            if(norm<1e-8){Arrays.fill(out[f],(float)(-1/Math.sqrt(BANDS)));continue;}
            for(int b=0;b<BANDS;b++)out[f][b]/=Math.sqrt(norm);
        }return out;
    }
    static boolean valid(float[][] a){
        if(a==null||a.length<12||a.length>MAX_FRAMES)return false;
        for(float[] row:a){if(row==null||row.length!=BANDS)return false;double n=0;for(float x:row){if(!Float.isFinite(x))return false;n+=x*(double)x;}if(n<.98||n>1.02)return false;}return true;
    }
    static double distance(float[][] a,float[][] b){
        if(!valid(a)||!valid(b))return Double.POSITIVE_INFINITY;
        double ratio=a.length/(double)b.length;if(ratio<.65||ratio>1.55)return Double.POSITIVE_INFINITY;
        double[] previous=new double[b.length+1];Arrays.fill(previous,Double.POSITIVE_INFINITY);previous[0]=0;
        int band=Math.max(Math.abs(a.length-b.length)+2,(int)Math.ceil(Math.max(a.length,b.length)*.25));
        for(int i=1;i<=a.length;i++){double[] current=new double[b.length+1];Arrays.fill(current,Double.POSITIVE_INFINITY);
            for(int j=Math.max(1,i-band);j<=Math.min(b.length,i+band);j++){
                double dot=0;for(int k=0;k<BANDS;k++)dot+=a[i-1][k]*b[j-1][k];double cost=Math.max(0,1-dot);
                current[j]=cost+Math.min(previous[j-1],Math.min(previous[j]+.015,current[j-1]+.015));
            }previous=current;
        }return previous[b.length]/Math.max(a.length,b.length);
    }
    static double score(float[][] sample,List<float[][]> templates){
        if(templates==null||templates.size()<3)return Double.POSITIVE_INFINITY;
        double[] scores=new double[templates.size()];for(int i=0;i<scores.length;i++)scores[i]=distance(sample,templates.get(i));Arrays.sort(scores);
        return (scores[0]+scores[1]+scores[2])/3; // A single matching outlier cannot admit a sound.
    }
    static double calibrate(List<float[][]> templates){
        if(templates==null||templates.size()<5)throw new IllegalArgumentException("More consistent sound examples are needed");
        double worst=0;
        for(int i=0;i<templates.size();i++){List<float[][]> others=new ArrayList<>(templates);others.remove(i);worst=Math.max(worst,score(templates.get(i),others));}
        double threshold=Math.max(.025,worst*1.2+.01);
        if(!Double.isFinite(threshold)||threshold>.22)throw new IllegalArgumentException("Sound examples vary too much. Record the same complete sound naturally");
        return threshold;
    }
    /** Same leave-one-out comparison calibrate() uses internally, but returns WHICH template
     *  disagreed with the others the most, instead of only a pass/fail threshold. calibrate()
     *  failing does not mean the most-recently-recorded sample is the problem — any one of the
     *  earlier takes could be the actual outlier (different distance from the mic, background
     *  noise that crept in, a slightly different sound) and every leave-one-out score that
     *  includes it gets dragged up regardless of how good the other takes are. Without this,
     *  the only recovery available was "redo the last take," which can retry forever and never
     *  succeed if take 3 (say) is the real problem. Returns -1 if there are fewer than 2
     *  templates (leave-one-out needs at least one other sample to compare against).
     */
    static int worstOutlier(List<float[][]> templates){
        if(templates==null||templates.size()<2)return -1;
        int worstIndex=0;double worstScore=-1;
        for(int i=0;i<templates.size();i++){
            List<float[][]> others=new ArrayList<>(templates);others.remove(i);
            double s=score(templates.get(i),others);
            if(s>worstScore){worstScore=s;worstIndex=i;}
        }
        return worstIndex;
    }
    private static void fft(double[] re,double[] im){
        for(int i=1,j=0;i<512;i++){int bit=256;for(; (j&bit)!=0;bit>>=1)j^=bit;j^=bit;if(i<j){double t=re[i];re[i]=re[j];re[j]=t;}}
        for(int len=2;len<=512;len<<=1){double angle=-2*Math.PI/len;
            for(int base=0;base<512;base+=len)for(int j=0;j<len/2;j++){double c=Math.cos(angle*j),s=Math.sin(angle*j);int a=base+j,b=a+len/2;double r=re[b]*c-im[b]*s,v=re[b]*s+im[b]*c;re[b]=re[a]-r;im[b]=im[a]-v;re[a]+=r;im[a]+=v;}
        }
    }
}
