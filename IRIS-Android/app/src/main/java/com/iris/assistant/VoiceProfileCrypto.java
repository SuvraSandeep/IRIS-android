package com.iris.assistant;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Portable, authenticated package. No device-bound keys or configurable work factors in untrusted input. */
final class VoiceProfileCrypto {
    static final int LIMIT=1024*1024;
    private static final int MAGIC=0x49525631, ITERATIONS=210000;
    static byte[] seal(byte[] plain,char[] password)throws Exception {
        if(plain.length>LIMIT)throw new IllegalArgumentException("Profile too large");
        byte[] salt=new byte[16],nonce=new byte[12];SecureRandom rng=new SecureRandom();rng.nextBytes(salt);rng.nextBytes(nonce);
        byte[] header=ByteBuffer.allocate(32).putInt(MAGIC).put(salt).put(nonce).array();
        Cipher c=cipher(Cipher.ENCRYPT_MODE,password,salt,nonce);c.updateAAD(header);
        byte[] encrypted=c.doFinal(plain);return ByteBuffer.allocate(header.length+encrypted.length).put(header).put(encrypted).array();
    }
    static byte[] open(byte[] packageBytes,char[] password)throws Exception {
        if(packageBytes.length<48||packageBytes.length>LIMIT+48)throw new IllegalArgumentException("Invalid profile size");
        ByteBuffer b=ByteBuffer.wrap(packageBytes);if(b.getInt()!=MAGIC)throw new IllegalArgumentException("Unsupported owner profile format");
        byte[] salt=new byte[16],nonce=new byte[12];b.get(salt);b.get(nonce);
        Cipher c=cipher(Cipher.DECRYPT_MODE,password,salt,nonce);c.updateAAD(Arrays.copyOf(packageBytes,32));
        return c.doFinal(packageBytes,32,packageBytes.length-32);
    }
    private static Cipher cipher(int mode,char[] password,byte[] salt,byte[] nonce)throws Exception {
        if(password==null||password.length<12||password.length>256)throw new IllegalArgumentException("Use a passphrase of 12–256 characters");
        PBEKeySpec spec=new PBEKeySpec(password,salt,ITERATIONS,256);byte[] key=null;
        try {key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));return c;
        }finally{spec.clearPassword();if(key!=null)Arrays.fill(key,(byte)0);}
    }
}
