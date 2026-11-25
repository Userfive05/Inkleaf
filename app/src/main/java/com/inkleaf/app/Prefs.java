package com.inkleaf.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Reading settings and per-book progress. */
public class Prefs {
    private final SharedPreferences p;

    public Prefs(Context c) { p = c.getSharedPreferences("inkleaf", Context.MODE_PRIVATE); }

    // Text presentation
    public int fontSize() { return p.getInt("fontSize", 20); }           // CSS px
    public void fontSize(int v) { p.edit().putInt("fontSize", clamp(v, 10, 48)).apply(); }
    public int lineHeight() { return p.getInt("lineHeight", 15); }       // tenths: 15 = 1.5
    public void lineHeight(int v) { p.edit().putInt("lineHeight", clamp(v, 10, 25)).apply(); }
    public int margin() { return p.getInt("margin", 22); }               // CSS px
    public void margin(int v) { p.edit().putInt("margin", clamp(v, 0, 80)).apply(); }
    public String font() { return p.getString("font", "book"); }         // book | serif | sans | /path/to/font
    public void font(String v) { p.edit().putString("font", v).apply(); }
    public boolean noHyphens() { return p.getBoolean("noHyphens", true); }
    public void noHyphens(boolean v) { p.edit().putBoolean("noHyphens", v).apply(); }
    public boolean leftAlign() { return p.getBoolean("leftAlign", true); }
    public void leftAlign(boolean v) { p.edit().putBoolean("leftAlign", v).apply(); }
    public int boldness() { return p.getInt("boldness", 0); }           // 0 = normal … 5 = heaviest
    public void boldness(int v) { p.edit().putInt("boldness", clamp(v, 0, 5)).apply(); }
    public String italics() { return p.getString("italics", "book"); }   // book | none | all
    public void italics(String v) { p.edit().putString("italics", v).apply(); }
    public String progressBar() { return p.getString("progressBar", "book"); } // off | chapter | book
    public void progressBar(String v) { p.edit().putString("progressBar", v).apply(); }
    public boolean keepSentences() { return p.getBoolean("keepSentences", true); }
    public void keepSentences(boolean v) { p.edit().putBoolean("keepSentences", v).apply(); }

    // Front light (used when the app can't write the system setting)
    public int lightOr(int def) { return p.getInt("light", def); }
    public void saveLight(int v) { p.edit().putInt("light", v).apply(); }
    public boolean askedLight() { return p.getBoolean("askedLight", false); }
    public void askedLight(boolean v) { p.edit().putBoolean("askedLight", v).apply(); }

    // The book open when the app was last left (null once the reader is closed on purpose)
    public String reading() { return p.getString("reading", null); }
    public void reading(String path) { p.edit().putString("reading", path).apply(); }

    // Per-book progress, keyed by absolute path
    public int chapter(String k) { return p.getInt("ch:" + k, 0); }
    public float fraction(String k) { return p.getFloat("fr:" + k, 0f); }
    public int percent(String k) { return p.getInt("pc:" + k, -1); }
    public long lastOpened(String k) { return p.getLong("la:" + k, 0); }
    public void touch(String k) { p.edit().putLong("la:" + k, System.currentTimeMillis()).apply(); }
    public void progress(String k, int chapter, float fraction, int percent) {
        p.edit().putInt("ch:" + k, chapter).putFloat("fr:" + k, fraction).putInt("pc:" + k, percent).apply();
    }

    // Bookmarks, a JSON array per book: [{ch, fr, pct, label, time}]
    public org.json.JSONArray bookmarks(String k) {
        try { return new org.json.JSONArray(p.getString("bm:" + k, "[]")); } catch (Exception e) { return new org.json.JSONArray(); }
    }
    public void bookmarks(String k, org.json.JSONArray a) { p.edit().putString("bm:" + k, a.toString()).apply(); }

    public String lastSearch() { return p.getString("lastSearch", ""); }
    public void lastSearch(String v) { p.edit().putString("lastSearch", v).apply(); }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
