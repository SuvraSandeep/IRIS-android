package com.iris.assistant;
import java.util.function.Consumer;
/** Scheduled executors otherwise hide task exceptions in an unread Future. */
final class VoiceTaskGuard {
    static void run(Runnable task,Consumer<RuntimeException> failed){
        try{task.run();}catch(RuntimeException error){failed.accept(error);}
    }
}
