package com.inkleaf.app;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Finds font files: ones added in the app, plus /sdcard/Fonts and KOReader's font folder. */
public class Fonts {

    public static File addedDir(Context c) {
        File d = new File(c.getFilesDir(), "fonts");
        d.mkdirs();
        return d;
    }

    public static boolean isFont(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".woff") || n.endsWith(".woff2");
    }

    public static String displayName(String path) {
        String n = new File(path).getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    public static List<File> added(Context c) {
        List<File> out = new ArrayList<>();
        collect(addedDir(c), 0, out);
        Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    public static List<File> all(Context c) {
        List<File> out = added(c);
        File sd = Environment.getExternalStorageDirectory();
        List<File> device = new ArrayList<>();
        collect(new File(sd, "Fonts"), 0, device);
        collect(new File(sd, "fonts"), 0, device);
        collect(new File(sd, "koreader/fonts"), 0, device);
        Collections.sort(device, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : device) {
            boolean dup = false;
            for (File o : out) if (o.getAbsolutePath().equals(f.getAbsolutePath())) dup = true;
            if (!dup) out.add(f);
        }
        return out;
    }

    private static void collect(File dir, int depth, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) { if (depth < 2) collect(f, depth + 1, out); }
            else if (isFont(f.getName())) out.add(f);
        }
    }
}
