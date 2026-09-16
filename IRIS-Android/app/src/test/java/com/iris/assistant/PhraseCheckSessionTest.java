package com.iris.assistant;
import org.junit.Test;
import static org.junit.Assert.*;
public class PhraseCheckSessionTest {
 @Test public void similarNamesCannotUnlockEnrollment(){
  PhraseCheckSession session=new PhraseCheckSession();session.begin();
  for(String heard:new String[]{"hello","hello alice","hello i this","hello heidi's","hello iris please"}){assertFalse(session.recognize("Hello Iris",heard));assertFalse(session.passed());}
  assertThrows(IllegalStateException.class,session::startEnrollment);
 }
 @Test public void successRequiresExplicitContinueAndResetsForNewPhrase(){
  PhraseCheckSession session=new PhraseCheckSession();session.begin();assertTrue(session.recognize("Hello Nova","hello nova"));assertTrue(session.checking());assertTrue(session.passed());
  session.startEnrollment();assertFalse(session.checking());assertFalse(session.passed());
  session.begin();assertFalse(session.passed());assertFalse(session.recognize("Hello Iris","hello nova"));session.clear();assertFalse(session.recognize("Hello Iris","hello iris"));
 }
}
