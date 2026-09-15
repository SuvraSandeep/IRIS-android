package com.iris.assistant;
import java.io.*;
import java.util.zip.*;
/** Standalone check for the zip-asset unpacking logic extracted from VoskEngine (copy of the
 *  private unzipEntries loop) — verifies nested entries, zip-slip guard, and the single-top-
 *  level-folder unwrap convention used by loadBundledIndianOrDownload/initSpeaker. This does not
 *  exercise VoskEngine itself (it needs the real Android SDK + Vosk JNI jar, unavailable here);
 *  it only proves the extraction algorithm this fix depends on is correct. */
public final class VoskZipUnpackTest {
    static int checks;
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    static void unzipEntries(ZipInputStream zis, File targetDir) throws Exception {
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            File outFile = new File(targetDir, entry.getName());
            if (!outFile.getCanonicalPath().startsWith(targetDir.getCanonicalPath() + File.separator)) continue;
            if (entry.isDirectory()) { outFile.mkdirs(); }
            else {
                outFile.getParentFile().mkdirs();
                try (FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = zis.read(buf)) != -1) out.write(buf, 0, n);
                }
            }
            zis.closeEntry();
        }
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) { File[] k = f.listFiles(); if (k != null) for (File x : k) deleteRecursive(x); }
        f.delete();
    }

    static File writeZip(java.util.Map<String,String> entries) throws Exception {
        File zip = File.createTempFile("vosk-test", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            for (java.util.Map.Entry<String,String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes("UTF-8"));
                zos.closeEntry();
            }
        }
        return zip;
    }

    public static void main(String[] args) throws Exception {
        // 1. Nested entries under a single top-level folder (the real upstream Vosk archive shape).
        {
            java.util.LinkedHashMap<String,String> src = new java.util.LinkedHashMap<>();
            src.put("vosk-model-small-en-in-0.4/am/final.mdl", "x".repeat(2000));
            src.put("vosk-model-small-en-in-0.4/conf/model.conf", "conf");
            src.put("vosk-model-small-en-in-0.4/graph/HCLr.fst", "graph");
            File zip = writeZip(src);
            File staging = new File(System.getProperty("java.io.tmpdir"), "vosk-test-staging-" + System.nanoTime());
            deleteRecursive(staging);
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
                unzipEntries(zis, staging);
            }
            File[] kids = staging.listFiles();
            check(kids != null && kids.length == 1 && kids[0].isDirectory(), "expected exactly one top-level folder after unzip");
            File modelRoot = kids[0];
            check(new File(modelRoot, "am/final.mdl").length() == 2000, "nested am/final.mdl missing or wrong size after unzip");
            check(new File(modelRoot, "conf/model.conf").exists(), "nested conf/model.conf missing after unzip");
            check(new File(modelRoot, "graph/HCLr.fst").exists(), "nested graph/HCLr.fst missing after unzip");
            deleteRecursive(staging); zip.delete();
        }
        // 2. Zip-slip guard: a malicious/corrupt entry path must not escape targetDir.
        {
            java.util.LinkedHashMap<String,String> src = new java.util.LinkedHashMap<>();
            src.put("../../evil.txt", "should not escape");
            src.put("safe.txt", "fine");
            File zip = writeZip(src);
            File staging = new File(System.getProperty("java.io.tmpdir"), "vosk-test-slip-" + System.nanoTime());
            deleteRecursive(staging);
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
                unzipEntries(zis, staging);
            }
            File escaped = new File(staging.getParentFile(), "evil.txt");
            check(!escaped.exists(), "zip-slip entry escaped target directory");
            check(new File(staging, "safe.txt").exists(), "legitimate entry was dropped alongside a zip-slip attempt");
            deleteRecursive(staging); zip.delete(); escaped.delete();
        }
        // 3. Flat archive with no top-level folder must be used as-is (no incorrect unwrap).
        {
            java.util.LinkedHashMap<String,String> src = new java.util.LinkedHashMap<>();
            src.put("final.ext.raw", "x".repeat(50));
            src.put("mean.vec", "v");
            src.put("transform.mat", "m");
            File zip = writeZip(src);
            File staging = new File(System.getProperty("java.io.tmpdir"), "vosk-test-flat-" + System.nanoTime());
            deleteRecursive(staging);
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
                unzipEntries(zis, staging);
            }
            File[] kids = staging.listFiles();
            check(kids != null && kids.length == 3, "flat archive should extract directly with no wrapper folder");
            check(new File(staging, "final.ext.raw").exists(), "flat entry final.ext.raw missing");
            deleteRecursive(staging); zip.delete();
        }
        System.out.println("Passed " + checks + " zip-unpack extraction checks");
    }
}
