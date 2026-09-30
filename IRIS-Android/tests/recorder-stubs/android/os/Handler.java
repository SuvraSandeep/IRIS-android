package android.os;
import java.util.concurrent.*;
public class Handler {
 private static final ScheduledExecutorService queue=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"fake-main");t.setDaemon(true);return t;});
 private final ConcurrentHashMap<Runnable,ScheduledFuture<?>> jobs=new ConcurrentHashMap<>();
 public Handler(Looper ignored){}
 public boolean post(Runnable r){queue.execute(r);return true;}
 /**
  * Production delays are compressed so scenarios that can only end via TimedRecorder's watchdog
  * ("blocked-start", where startRecording never returns) still terminate inside the test's 3s
  * awaits. The scale must not be so aggressive that the watchdog can beat a successful capture.
  *
  * At the previous 1/100 scale the 8.5s watchdog fired after 85ms. That is under the cold-start
  * cost of the very first scenario on a loaded CI runner (worker thread start, class loading and
  * first-pass interpretation), so the watchdog could win the race and deliver a spurious
  * "Microphone timed out" in the "ok" scenario. The symptom was a single terminal callback and a
  * correctly released recorder, with only the completion assertion failing: "missing PCM
  * completion". The same race could also fire the watchdog before the cancellation test called
  * stop().
  *
  * 1/10 keeps the watchdog at 850ms for a 500ms take and 1600ms for a phrase: roughly an order of
  * magnitude above a cold capture and still well inside every 3s await. The floor keeps short
  * delays from collapsing to zero.
  */
 public boolean postDelayed(Runnable r,long delay){jobs.put(r,queue.schedule(r,Math.max(250,delay/10),TimeUnit.MILLISECONDS));return true;}
 public void removeCallbacks(Runnable r){ScheduledFuture<?> f=jobs.remove(r);if(f!=null)f.cancel(false);}
}
