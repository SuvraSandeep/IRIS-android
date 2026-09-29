package com.iris.assistant;
/** Transactional communication routing. Failed selection always releases the mode request. */
final class CommunicationRouteLease implements AutoCloseable {
 interface Controls {int mode();void communication();boolean select();void clear();void normal();}
 private final Controls controls;private boolean owned;
 CommunicationRouteLease(Controls c){controls=c;}
 boolean acquire(){if(owned)return true;if(controls.mode()!=0)return false;try{owned=true;controls.communication();if(controls.select())return true;}catch(RuntimeException ignored){}close();return false;}
 boolean owned(){return owned;}
 public void close(){if(!owned)return;owned=false;try{controls.clear();}catch(RuntimeException ignored){}try{controls.normal();}catch(RuntimeException ignored){}}
}
