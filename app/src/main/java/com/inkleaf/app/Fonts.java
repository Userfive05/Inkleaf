package com.inkleaf.app;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Finds font files: ones added in the app, plus /sdcard/Fonts and KOReader's font folder. */
public class Fonts {

    // ---- fonts shipped inside the app: assets/fonts/<Family>/<files> ----

    public static final String BUNDLED = "asset:";

    /** Family names of the fonts that ship with InkLeaf, alphabetically. */
    public static List<String> bundledFamilies(Context c) {
        List<String> out = new ArrayList<>();
        try {
            String[] dirs = c.getAssets().list("fonts");
            if (dirs != null) for (String d : dirs) out.add(d);
        } catch (Exception ignored) { }
        Collections.sort(out, String::compareToIgnoreCase);
        return out;
    }

    /** Font files of one bundled family, as asset paths relative to assets/fonts. */
    public static List<String> bundledFaces(Context c, String family) {
        List<String> out = new ArrayList<>();
        try {
            String[] files = c.getAssets().list("fonts/" + family);
            if (files != null) for (String f : files) if (isFont(f)) out.add(family + "/" + f);
        } catch (Exception ignored) { }
        return out;
    }

    /** Variable fonts (…-VF.ttf) cover a range of weights in one file. */
    public static boolean isVariable(String path) {
        return displayName(path).endsWith("-VF");
    }

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

    // ---- font families: Literata-Regular.ttf, Literata-Bold.ttf, Literata-Italic.ttf … ----

    private static final Pattern STYLE_SUFFIX = Pattern.compile(
            "(?i)[-_ ]*(extrabold|ultrabold|semibold|demibold|bold|black|heavy|medium|extralight|ultralight|light|thin|regular|book|normal|roman|italic|oblique)$");

    /** Family name from a file name, e.g. "Literata-BoldItalic.ttf" → "Literata". */
    public static String familyName(String path) {
        String base = displayName(path), prev;
        do { prev = base; base = STYLE_SUFFIX.matcher(base).replaceAll(""); } while (!base.equals(prev) && !base.isEmpty());
        return base.isEmpty() ? displayName(path) : base;
    }

    public static boolean isItalic(String path) {
        String n = displayName(path).toLowerCase(Locale.ROOT);
        return n.contains("italic") || n.contains("oblique");
    }

    public static int weight(String path) {
        String n = displayName(path).toLowerCase(Locale.ROOT).replaceAll("[-_ ]", "");
        if (n.contains("extrabold") || n.contains("ultrabold")) return 800;
        if (n.contains("semibold") || n.contains("demibold")) return 600;
        if (n.contains("bold")) return 700;
        if (n.contains("black") || n.contains("heavy")) return 900;
        if (n.contains("medium")) return 500;
        if (n.contains("extralight") || n.contains("ultralight")) return 200;
        if (n.contains("light")) return 300;
        if (n.contains("thin")) return 100;
        return 400;
    }

    /** Every file in the same folder that belongs to the same family as {@code path}. */
    public static List<File> familyOf(String path) {
        File f = new File(path);
        List<File> out = new ArrayList<>();
        File[] siblings = f.getParentFile() == null ? null : f.getParentFile().listFiles();
        String fam = familyName(path).toLowerCase(Locale.ROOT);
        if (siblings != null)
            for (File s : siblings)
                if (s.isFile() && isFont(s.getName()) && familyName(s.getPath()).toLowerCase(Locale.ROOT).equals(fam)) out.add(s);
        if (out.isEmpty()) out.add(f);
        return out;
    }

    /** One entry per family (key: its regular-style file path, value: family name). */
    public static Map<String, String> families(Context c) {
        Map<String, List<File>> groups = new LinkedHashMap<>();
        for (File f : all(c)) {
            String key = f.getParent() + "/" + familyName(f.getPath()).toLowerCase(Locale.ROOT);
            List<File> g = groups.get(key);
            if (g == null) groups.put(key, g = new ArrayList<>());
            g.add(f);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (List<File> g : groups.values()) {
            File best = g.get(0);
            int bestScore = Integer.MAX_VALUE;
            for (File f : g) {
                int score = Math.abs(weight(f.getPath()) - 400) + (isItalic(f.getPath()) ? 1000 : 0);
                if (score < bestScore) { best = f; bestScore = score; }
            }
            String name = familyName(best.getPath());
            if (g.size() > 1) name += "  (" + g.size() + " styles)";
            out.put(best.getAbsolutePath(), name);
        }
        return out;
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
