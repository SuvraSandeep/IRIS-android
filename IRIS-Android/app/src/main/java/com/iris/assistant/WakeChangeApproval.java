package com.iris.assistant;

/** A synchronous write scope entered only after the settings UI's explicit device authentication. */
final class WakeChangeApproval {
    private static final ThreadLocal<Boolean> APPROVED=new ThreadLocal<>();
    private WakeChangeApproval(){}
    static void runApproved(Runnable action){
        Boolean previous=APPROVED.get();
        APPROVED.set(true);
        try{action.run();}finally{if(previous==null)APPROVED.remove();else APPROVED.set(previous);}
    }
    static void require(){
        if(!Boolean.TRUE.equals(APPROVED.get()))
            throw new SecurityException("Owner wake changes require explicit approval in Settings");
    }
}
