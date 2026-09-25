package com.iris.assistant;
import android.content.Context;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Pinned, bundled SpeechBrain model. Build verifies raw-waveform parity at 0.5–4 seconds.
 * Runtime verifies SHA-256 and model IO. Missing/mismatched evidence never bypasses identity. */
final class EcapaEmbedding {
    static final int OUTPUT_DIM=192;
    private static final String NAME="ecapa_tdnn_voxceleb";
    private static final Object INSTALL=new Object();private final Object lock=new Object();
    private OrtSession session;private OrtEnvironment env;private boolean closed;private volatile String fingerprint="";
    interface InitListener {void onReady();void onError(String message);}
    boolean isReady(){synchronized(lock){return session!=null&&!closed;}}
    String fingerprint(){return fingerprint;}
    void load(Context context,InitListener listener){
        Context app=context.getApplicationContext();new Thread(()->{
            try{
                String expected;File model=new File(app.getFilesDir(),NAME+".onnx");
                try(InputStream in=app.getAssets().open(NAME+".json")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);
                    JSONObject manifest=new JSONObject(bytes.toString("UTF-8"));expected=manifest.getString("sha256");
                    if(!expected.matches("[a-f0-9]{64}")||manifest.getInt("dimension")!=192||manifest.getInt("sample_rate")!=16000)throw new IOException("Invalid owner model manifest");}
                synchronized(INSTALL){
                    if(!model.isFile()||!expected.equals(sha(model))){File temp=new File(app.getFilesDir(),NAME+".part");
                        try(InputStream in=app.getAssets().open(NAME+".onnx");OutputStream out=new FileOutputStream(temp)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
                        if(!expected.equals(sha(temp))){temp.delete();throw new IOException("Owner model checksum mismatch");}
                        if(model.exists()&&!model.delete())throw new IOException("Cannot replace owner model");
                        if(!temp.renameTo(model))throw new IOException("Cannot install owner model");
                    }
                    synchronized(lock){if(closed)return;env=OrtEnvironment.getEnvironment();
                        try(OrtSession.SessionOptions opts=new OrtSession.SessionOptions()){opts.setIntraOpNumThreads(2);opts.setInterOpNumThreads(1);session=env.createSession(model.getAbsolutePath(),opts);}
                        if(!session.getInputNames().contains("wav"))throw new IOException("Incompatible owner model input");fingerprint=expected;}
                }
                listener.onReady();
            }catch(Exception error){synchronized(lock){if(session!=null)try{session.close();}catch(Exception ignored){}session=null;}listener.onError("Dedicated owner model: "+error.getMessage());}
        },"IRIS-OwnerModel").start();
    }
    float[] extract(short[] pcm){
        if(pcm==null||pcm.length<8000)return null;
        synchronized(lock){if(session==null||closed)return null;
            float[] audio=new float[pcm.length];for(int i=0;i<audio.length;i++)audio[i]=pcm[i]/32768f;
            try(OnnxTensor input=OnnxTensor.createTensor(env,FloatBuffer.wrap(audio),new long[]{1,audio.length});
                OrtSession.Result output=session.run(java.util.Collections.singletonMap("wav",input))){
                Object value=output.get(0).getValue();if(!(value instanceof float[][]))return null;
                float[][] matrix=(float[][])value;if(matrix.length!=1||!WakePolicy.ownerDim(matrix[0],matrix[0],.99,192))return null;return matrix[0].clone();
            }catch(Exception error){return null;}finally{java.util.Arrays.fill(audio,0);}
        }
    }
    void close(){synchronized(lock){closed=true;if(session!=null)try{session.close();}catch(Exception ignored){}session=null;}}
    private static String sha(File file)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}StringBuilder out=new StringBuilder();for(byte b:md.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
