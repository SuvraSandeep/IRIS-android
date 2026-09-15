package android.os;
import java.util.concurrent.*;
public class Handler {
 private static final ScheduledExecutorService queue=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"fake-main");t.setDaemon(true);return t;});
 private final ConcurrentHashMap<Runnable,ScheduledFuture<?>> jobs=new ConcurrentHashMap<>();
 public Handler(Looper ignored){}
 public boolean post(Runnable r){queue.execute(r);return true;}
 public boolean postDelayed(Runnable r,long delay){jobs.put(r,queue.schedule(r,Math.max(1,delay/100),TimeUnit.MILLISECONDS));return true;}
 public void removeCallbacks(Runnable r){ScheduledFuture<?> f=jobs.remove(r);if(f!=null)f.cancel(false);}
}
