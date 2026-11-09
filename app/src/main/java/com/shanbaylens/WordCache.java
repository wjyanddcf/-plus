package com.shanbaylens;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * 单词讲解缓存：同一个词第二次看直接秒出，不重复花钱。
 */
public final class WordCache {

    private final File dir;

    public WordCache(Context c) {
        dir = new File(c.getFilesDir(), "explain_cache");
        if (!dir.exists()) dir.mkdirs();
    }

    private File fileOf(String word) {
        String safe = word.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\-]", "_");
        if (safe.length() > 60) safe = safe.substring(0, 60);
        return new File(dir, safe + ".md");
    }

    public String get(String word) {
        if (word == null || word.trim().isEmpty()) return null;
        File f = fileOf(word);
        if (!f.isFile() || f.length() == 0) return null;
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public void put(String word, String md) {
        if (word == null || md == null || md.trim().isEmpty()) return;
        final File f = fileOf(word);
        final byte[] data = md.getBytes(StandardCharsets.UTF_8);
        new Thread(() -> {
            try (OutputStream os = new FileOutputStream(f)) {
                os.write(data);
            } catch (Exception ignored) {
            }
        }).start();
    }

    public long sizeBytes() {
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        long sum = 0;
        for (File f : fs) sum += f.length();
        return sum;
    }

    public int count() {
        File[] fs = dir.listFiles();
        return fs == null ? 0 : fs.length;
    }

    public void clear() {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) f.delete();
    }
}
