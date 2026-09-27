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
        float[][] out=new float[frames][BANDS];
        for(int f=0;f<frames;f++)out[f]=frame(pcm,start+f*320);
        return out;
    }
    private static final double[] WINDOW=new double[400],FFT_COS=new double[256],FFT_SIN=new double[256];
    private static final double[][] MEL=new double[BANDS][257];
    static {
        for(int i=0;i<256;i++){FFT_COS[i]=Math.cos(-2*Math.PI*i/512);FFT_SIN[i]=Math.sin(-2*Math.PI*i/512);}
        for(int i=0;i<400;i++)WINDOW[i]=.54-.46*Math.cos(2*Math.PI*i/399);
        double[] edges=new double[BANDS+2];double lo=2595*Math.log10(1+80.0/700),hi=2595*Math.log10(1+7600.0/700);
        for(int i=0;i<edges.length;i++)edges[i]=700*(Math.pow(10,(lo+(hi-lo)*i/(BANDS+1))/2595)-1)*512/16000;
        for(int b=0;b<BANDS;b++)for(int k=(int)Math.ceil(edges[b]);k<Math.min(257,edges[b+2]);k++)
            MEL[b][k]=Math.max(0,k<=edges[b+1]?(k-edges[b])/(edges[b+1]-edges[b]):(edges[b+2]-k)/(edges[b+2]-edges[b+1]));
    }
    /** Identical transform for enrollment and each live 20ms hop. No FFT over a growing clip. */
    static float[] frame(short[] pcm,int offset){
        double[] re=new double[512],im=new double[512];float[] out=new float[BANDS];
        for(int i=0;i<400;i++)re[i]=pcm[offset+i]*WINDOW[i];fft(re,im);
        double[] power=new double[257];for(int k=0;k<257;k++)power[k]=re[k]*re[k]+im[k]*im[k];
        double mean=0;for(int b=0;b<BANDS;b++){double sum=0;for(int k=0;k<257;k++)sum+=MEL[b][k]*power[k];out[b]=(float)Math.log(1+sum);mean+=out[b];}
        mean/=BANDS;double norm=0;for(int b=0;b<BANDS;b++){out[b]-=mean;norm+=out[b]*out[b];}
        if(norm<1e-8){Arrays.fill(out,(float)(-1/Math.sqrt(BANDS)));return out;}
        for(int b=0;b<BANDS;b++)out[b]/=Math.sqrt(norm);return out;
    }
    /** A streaming detector supplies boundaries without surrounding silence. Padding restores
     * the frontend's noise-estimation context; it never adds speech or identity evidence. */
    static short[] boundedContext(short[] pcm){
        if(pcm==null)return new short[0];int padding=Math.max(1600,pcm.length/8);
        short[] out=new short[pcm.length+padding*2];System.arraycopy(pcm,0,out,padding,pcm.length);return out;
    }
    static short[] speakerClip(short[] pcm){
        if(!WakePolicy.usableAudio(pcm))return new short[0];
        int count=pcm.length/320;double[] levels=new double[count];
        for(int f=0;f<count;f++){double sum=0;for(int i=f*320;i<(f+1)*320;i++)sum+=pcm[i]*(double)pcm[i];levels[f]=Math.sqrt(sum/320);}
        double[] sorted=levels.clone();Arrays.sort(sorted);double floor=Math.max(150,Math.max(60,sorted[count/10])*3);
        int first=0,last=count-1;while(first<count&&levels[first]<floor)first++;while(last>=first&&levels[last]<floor)last--;
        if(first>last)return new short[0];
        return Arrays.copyOfRange(pcm,Math.max(0,first*320-1600),Math.min(pcm.length,(last+1)*320+1600));
    }
    static boolean valid(float[][] a){
        if(a==null||a.length<12||a.length>MAX_FRAMES)return false;
        for(float[] row:a){if(row==null||row.length!=BANDS)return false;double n=0;for(float x:row){if(!Float.isFinite(x))return false;n+=x*(double)x;}if(n<.98||n>1.02)return false;}return true;
    }
    static double distance(float[][] a,float[][] b){
        if(!valid(a)||!valid(b))return Double.POSITIVE_INFINITY;
        double ratio=a.length/(double)b.length;if(ratio<.45||ratio>2.2)return Double.POSITIVE_INFINITY;
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
    /** Each authenticated example is a pronunciation variant, not an outlier to discard. */
    static double variantScore(float[][] sample,List<float[][]> templates){
        if(!valid(sample)||templates==null||templates.isEmpty())return Double.POSITIVE_INFINITY;
        double best=Double.POSITIVE_INFINITY;
        for(float[][] template:templates)best=Math.min(best,distance(sample,template));
        return best;
    }
    static double variantCalibrate(List<float[][]> templates){
        if(templates==null||templates.size()!=4)throw new IllegalArgumentException("Four complete phrase examples are required");
        for(float[][] template:templates)if(!valid(template))throw new IllegalArgumentException("Incomplete phrase example");
        double worst=0;
        for(int i=0;i<templates.size();i++){
            List<float[][]> others=new ArrayList<>(templates);others.remove(i);
            worst=Math.max(worst,variantScore(templates.get(i),others));
        }
        // Bounded radius even for distinct styles; held-out recordings, never enrollment
        // recordings, must independently demonstrate that the learned bank generalizes.
        return Math.min(.24,Math.max(.08,worst*1.2+.02));
    }
    static double calibrate(List<float[][]> templates){
        if(templates==null||templates.size()!=4)throw new IllegalArgumentException("Four complete phrase examples are required");
        double worst=0;
        for(int i=0;i<templates.size();i++){
            List<float[][]> others=new ArrayList<>(templates);others.remove(i);
            worst=Math.max(worst,score(templates.get(i),others));
        }
        double threshold=Math.max(.025,worst*1.2+.01);
        if(!Double.isFinite(threshold)||threshold>.32)
            throw new IllegalArgumentException("Phrase examples differ too much; record the same complete phrase at a steady distance");
        return threshold;
    }
    /** Leave-one-out score for every template, worst (least consistent with the rest) first.
     *  calibrate() failing does not mean the most-recently-recorded sample is the problem — any
     *  one of the earlier takes could be the actual outlier (different distance from the mic,
     *  background noise that crept in, a slightly different sound) and every leave-one-out score
     *  that includes it gets dragged up regardless of how good the other takes are. Returns the
     *  ORIGINAL INDICES into templates, sorted worst-to-best, so a caller can identify and act on
     *  the least-consistent samples without needing to mutate templates in place (mutating a flat
     *  batch in place by position previously caused real index-corruption bugs once a caller also
     *  needed to keep a second, differently-grouped list in sync across repeated retries — see
     *  MainActivity's enrollment-boundary handling, which now uses this to pick the best N of a
     *  slightly larger batch instead of surgically removing/reinserting one slot). Empty if there
     *  are fewer than 2 templates (leave-one-out needs at least one other sample to compare against).
     */
    static int[] rankByConsistency(List<float[][]> templates){
        if(templates==null||templates.size()<2)return new int[0];
        Integer[] order=new Integer[templates.size()];double[] scores=new double[templates.size()];
        for(int i=0;i<templates.size();i++){
            List<float[][]> others=new ArrayList<>(templates);others.remove(i);
            scores[i]=score(templates.get(i),others);order[i]=i;
        }
        Arrays.sort(order,(a,b)->Double.compare(scores[b],scores[a])); // worst (highest score) first
        int[] out=new int[order.length];for(int i=0;i<order.length;i++)out[i]=order[i];return out;
    }
    /** Given a batch that may hold a few more samples than required (e.g. 10-12 sound takes when
     *  only 10 are needed), returns the indices of the `keep` most mutually-consistent templates
     *  — i.e. everything EXCEPT the worst (size-keep) entries from rankByConsistency(). This lets
     *  a caller recover from "the batch doesn't calibrate" by adding one or two fresh replacement
     *  takes and then keeping only the best `keep` of the resulting larger pool, rather than
     *  guessing which single original slot to blame and surgically patching it in place. */
    static int[] bestSubset(List<float[][]> templates,int keep){
        int[] worstFirst=rankByConsistency(templates);
        if(worstFirst.length<keep)return worstFirst;
        java.util.Set<Integer> drop=new java.util.HashSet<>();
        for(int i=0;i<worstFirst.length-keep;i++)drop.add(worstFirst[i]);
        int[] out=new int[keep];int at=0;
        for(int i=0;i<templates.size();i++)if(!drop.contains(i))out[at++]=i;
        return out;
    }
    private static void fft(double[] re,double[] im){
        for(int i=1,j=0;i<512;i++){int bit=256;for(; (j&bit)!=0;bit>>=1)j^=bit;j^=bit;if(i<j){double t=re[i];re[i]=re[j];re[j]=t;}}
        for(int len=2;len<=512;len<<=1){int stride=512/len;
            for(int base=0;base<512;base+=len)for(int j=0;j<len/2;j++){double c=FFT_COS[j*stride],s=FFT_SIN[j*stride];int a=base+j,b=a+len/2;double r=re[b]*c-im[b]*s,v=re[b]*s+im[b]*c;re[b]=re[a]-r;im[b]=im[a]-v;re[a]+=r;im[a]+=v;}
        }
    }
}
