package com.inkleaf.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Backs up and restores everything InkLeaf remembers: settings, reading progress and bookmarks.
 * Backups are JSON files in /sdcard/InkLeaf/Backups.
 */
public class Backup {
    public static final int REQ_RESTORE_FILE = 52;
    static final String AUTO_NAME = "inkleaf-auto.json";
    static final String SAFETY_NAME = "inkleaf-before-restore.json";
    static final long DAY = 24L * 60 * 60 * 1000;
    /** Added fonts beyond this total are left out, to keep backups a sensible size. */
    static final long MAX_FONT_BYTES = 40L * 1024 * 1024;
    /** Per-book keys are "<prefix><absolute path>". */
    static final String[] BOOK_PREFIXES = {"ch:", "fr:", "pc:", "la:", "bm:"};
    /** Not worth carrying to another install. */
    static final Set<String> SKIP = new HashSet<>(Arrays.asList("reading", "lastAutoBackup"));

    public static File dir() {
        File d = new File(Environment.getExternalStorageDirectory(), "InkLeaf/Backups");
        d.mkdirs();
        return d;
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("inkleaf", Context.MODE_PRIVATE); }

    // ------------------------------------------------------------ writing

    static JSONObject export(Context c) throws Exception {
        JSONObject values = new JSONObject();
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            if (SKIP.contains(e.getKey())) continue;
            Object v = e.getValue();
            JSONObject t = new JSONObject();
            if (v instanceof Boolean) t.put("t", "b");
            else if (v instanceof Integer) t.put("t", "i");
            else if (v instanceof Long) t.put("t", "l");
            else if (v instanceof Float) t.put("t", "f");
            else if (v instanceof String) t.put("t", "s");
            else continue;
            t.put("v", v instanceof Float ? ((Float) v).doubleValue() : v);
            values.put(e.getKey(), t);
        }
        JSONObject root = new JSONObject();
        root.put("app", "InkLeaf");
        root.put("format", 1);
        root.put("build", BuildConfig.VERSION_CODE);
        root.put("created", System.currentTimeMillis());
        root.put("prefs", values);
        // Fonts added in the app live in its private storage, so they go into the backup itself.
        JSONArray fonts = new JSONArray();
        long total = 0;
        for (File f : Fonts.added(c)) {
            if (total + f.length() > MAX_FONT_BYTES) continue;
            total += f.length();
            JSONObject o = new JSONObject();
            o.put("name", f.getName());
            o.put("data", Base64.encodeToString(EpubBook.readAll(new java.io.FileInputStream(f)), Base64.NO_WRAP));
            fonts.put(o);
        }
        root.put("fonts", fonts);
        return root;
    }

    static File write(Context c, String name) throws Exception {
        File out = new File(dir(), name);
        File tmp = new File(out.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(export(c).toString(1));
        }
        if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out); }
        return out;
    }

    public static File backupNow(Context c) throws Exception {
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date());
        return write(c, "inkleaf-" + stamp + ".json");
    }

    /** Keeps one rolling automatic backup, refreshed at most once a day. */
    public static void autoBackup(Context c) {
        SharedPreferences p = prefs(c);
        long last = p.getLong("lastAutoBackup", 0);
        if (System.currentTimeMillis() - last < DAY) return;
        new Thread(() -> {
            try {
                write(c, AUTO_NAME);
                p.edit().putLong("lastAutoBackup", System.currentTimeMillis()).apply();
            } catch (Exception ignored) { }
        }).start();
    }

    // ------------------------------------------------------------ restoring

    /**
     * Replaces InkLeaf's data with the backup. Progress for a book whose path no longer exists is
     * moved to a book with the same file name, so backups survive books being moved or a new device.
     * Returns how many books' progress was matched.
     */
    static int restore(Context c, JSONObject root, List<File> books) throws Exception {
        if (!"InkLeaf".equals(root.optString("app"))) throw new Exception("not an InkLeaf backup");
        JSONObject values = root.getJSONObject("prefs");
        Map<String, File> byName = new HashMap<>();
        for (File f : books) byName.put(f.getName().toLowerCase(Locale.ROOT), f);

        write(c, SAFETY_NAME); // so a mistaken restore can be undone

        Set<String> fontNames = new HashSet<>();
        JSONArray fonts = root.optJSONArray("fonts");
        for (int i = 0; fonts != null && i < fonts.length(); i++) {
            JSONObject o = fonts.getJSONObject(i);
            String name = new File(o.getString("name")).getName(); // no paths from the file
            if (!Fonts.isFont(name)) continue;
            try (FileOutputStream out = new FileOutputStream(new File(Fonts.addedDir(c), name))) {
                out.write(Base64.decode(o.getString("data"), Base64.DEFAULT));
            }
            fontNames.add(name);
        }

        SharedPreferences.Editor ed = prefs(c).edit().clear();
        Set<String> matched = new HashSet<>();
        Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject t = values.getJSONObject(key);
            String target = key;
            for (String pre : BOOK_PREFIXES) {
                if (!key.startsWith(pre)) continue;
                String path = key.substring(pre.length());
                File f = new File(path);
                if (!f.isFile()) {
                    File same = byName.get(f.getName().toLowerCase(Locale.ROOT));
                    if (same != null) path = same.getAbsolutePath();
                }
                if (new File(path).isFile()) matched.add(path);
                target = pre + path;
            }
            // The chosen font may point at an added font from another install: point it at the restored copy.
            if (key.equals("font") && "s".equals(t.optString("t"))) {
                String name = new File(t.getString("v")).getName();
                if (fontNames.contains(name) && !new File(t.getString("v")).isFile()) {
                    ed.putString("font", new File(Fonts.addedDir(c), name).getAbsolutePath());
                    continue;
                }
            }
            switch (t.getString("t")) {
                case "b": ed.putBoolean(target, t.getBoolean("v")); break;
                case "i": ed.putInt(target, t.getInt("v")); break;
                case "l": ed.putLong(target, t.getLong("v")); break;
                case "f": ed.putFloat(target, (float) t.getDouble("v")); break;
                case "s": ed.putString(target, t.getString("v")); break;
            }
        }
        ed.putLong("lastAutoBackup", System.currentTimeMillis());
        ed.commit();
        return matched.size();
    }

    // ------------------------------------------------------------ UI

    /** Backup & restore menu. {@code books} is the current library; {@code after} runs once a restore is done. */
    public static void showMenu(Activity a, List<File> books, Runnable after) {
        String[] items = {"Back up now", "Restore from a backup…", "Restore from another file…"};
        new AlertDialog.Builder(a)
                .setTitle("Backup & restore")
                .setMessage(null)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        try {
                            File f = backupNow(a);
                            new AlertDialog.Builder(a).setTitle("Backed up")
                                    .setMessage("Settings, reading progress, bookmarks and added fonts were saved to\n\nInkLeaf/Backups/" + f.getName()
                                            + "\n\nCopy this file somewhere safe to keep it, for example to your computer or phone.")
                                    .setPositiveButton("OK", null).show();
                        } catch (Exception e) {
                            Toast.makeText(a, "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    } else if (which == 1) {
                        pickFromFolder(a, books, after);
                    } else {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        try { a.startActivityForResult(i, REQ_RESTORE_FILE); }
                        catch (Exception e) { Toast.makeText(a, "No file picker found.", Toast.LENGTH_LONG).show(); }
                    }
                })
                .show();
    }

    static void pickFromFolder(Activity a, List<File> books, Runnable after) {
        File[] files = dir().listFiles((d, n) -> n.endsWith(".json"));
        if (files == null || files.length == 0) {
            Toast.makeText(a, "No backups yet in InkLeaf/Backups.", Toast.LENGTH_LONG).show();
            return;
        }
        Arrays.sort(files, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        String[] names = new String[files.length];
        SimpleDateFormat fmt = new SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault());
        for (int i = 0; i < files.length; i++) {
            String n = files[i].getName();
            String label = n.equals(AUTO_NAME) ? "Automatic daily backup"
                    : n.equals(SAFETY_NAME) ? "Before last restore (undo)" : "Backup";
            names[i] = label + "\n" + fmt.format(new Date(files[i].lastModified()));
        }
        new AlertDialog.Builder(a).setTitle("Restore which backup?")
                .setItems(names, (d, which) -> {
                    try { confirmRestore(a, new JSONObject(new String(EpubBook.readAll(new java.io.FileInputStream(files[which])), StandardCharsets.UTF_8)), books, after); }
                    catch (Exception e) { Toast.makeText(a, "Couldn't read that backup: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** For the "another file" picker result. */
    public static void restoreFromUri(Activity a, Uri u, List<File> books, Runnable after) {
        try (InputStream in = a.getContentResolver().openInputStream(u)) {
            confirmRestore(a, new JSONObject(new String(EpubBook.readAll(in), StandardCharsets.UTF_8)), books, after);
        } catch (Exception e) {
            Toast.makeText(a, "Couldn't read that backup: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    static void confirmRestore(Activity a, JSONObject root, List<File> books, Runnable after) {
        if (!"InkLeaf".equals(root.optString("app"))) {
            Toast.makeText(a, "That file isn't an InkLeaf backup.", Toast.LENGTH_LONG).show();
            return;
        }
        int bookmarks = 0, progress = 0;
        JSONObject values = root.optJSONObject("prefs");
        if (values != null) {
            Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (k.startsWith("ch:")) progress++;
                if (k.startsWith("bm:")) {
                    try { bookmarks += new JSONArray(values.getJSONObject(k).getString("v")).length(); } catch (Exception ignored) { }
                }
            }
        }
        JSONArray fontList = root.optJSONArray("fonts");
        int fonts = fontList == null ? 0 : fontList.length();
        String when = new SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault()).format(new Date(root.optLong("created")));
        new AlertDialog.Builder(a).setTitle("Restore this backup?")
                .setMessage("From " + when + ": settings, progress for " + progress + (progress == 1 ? " book" : " books")
                        + ", " + bookmarks + (bookmarks == 1 ? " bookmark" : " bookmarks")
                        + " and " + fonts + (fonts == 1 ? " added font file" : " added font files")
                        + ".\n\nThis replaces what InkLeaf has now. Your current data is saved first as \"Before last restore\", so you can undo.")
                .setPositiveButton("Restore", (d, w) -> {
                    try {
                        int matched = restore(a, root, books);
                        Toast.makeText(a, "Restored. Progress matched for " + matched + (matched == 1 ? " book." : " books."), Toast.LENGTH_LONG).show();
                        if (after != null) after.run();
                    } catch (Exception e) {
                        Toast.makeText(a, "Restore failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** Lists the user's books (used to re-match progress after books move). */
    public static List<File> scanBooks(File dir, int depth, List<File> out) {
        if (out == null) out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) { if (depth < 5 && !(depth == 0 && n.equals("Android"))) scanBooks(f, depth + 1, out); }
            else if (n.toLowerCase(Locale.ROOT).endsWith(".epub")) out.add(f);
        }
        return out;
    }
}
