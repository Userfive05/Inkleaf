package com.inkleaf.app;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * Offline StarDict dictionaries (the same files KOReader uses), with an online fallback.
 * Looks in /sdcard/koreader/data/dict, /sdcard/Dictionaries, /sdcard/dict and /sdcard/InkLeaf/dict.
 */
public class Dictionary {

    public static class Entry {
        public final String source, headword, html;
        Entry(String source, String headword, String html) { this.source = source; this.headword = headword; this.html = html; }
    }

    static class Dict {
        String name = "Dictionary", seq;
        File idxFile, dictFile;
        int offsetBytes = 4;
        byte[] idx;
        int[] wordStart;
        long[] dataOff;
        int[] dataSize;
        DictZip dz;
        File plainCache;
    }

    private static List<Dict> dicts;

    public static synchronized List<Dict> dictionaries(Context c) {
        if (dicts != null) return dicts;
        dicts = new ArrayList<>();
        File sd = Environment.getExternalStorageDirectory();
        File[] roots = { new File(sd, "koreader/data/dict"), new File(sd, "Dictionaries"), new File(sd, "dictionaries"),
                new File(sd, "dict"), new File(sd, "InkLeaf/dict") };
        List<File> ifos = new ArrayList<>();
        for (File r : roots) findIfo(r, 0, ifos);
        for (File ifo : ifos) {
            try { Dict d = open(ifo); if (d != null) dicts.add(d); } catch (Exception ignored) { }
        }
        return dicts;
    }

    public static synchronized void rescan() { dicts = null; }

    private static void findIfo(File dir, int depth, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) { if (depth < 3) findIfo(f, depth + 1, out); }
            else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".ifo")) out.add(f);
        }
    }

    private static Dict open(File ifo) throws Exception {
        Dict d = new Dict();
        String text = new String(EpubBook.readAll(new FileInputStream(ifo)), StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
            if (k.equals("bookname")) d.name = v;
            else if (k.equals("sametypesequence")) d.seq = v;
            else if (k.equals("idxoffsetbits") && v.equals("64")) d.offsetBytes = 8;
        }
        String base = ifo.getPath().substring(0, ifo.getPath().length() - 4);
        d.idxFile = first(base + ".idx", base + ".idx.gz", base + ".IDX");
        d.dictFile = first(base + ".dict", base + ".dict.dz", base + ".DICT", base + ".DICT.DZ");
        return d.idxFile == null || d.dictFile == null ? null : d;
    }

    private static File first(String... paths) {
        for (String p : paths) { File f = new File(p); if (f.isFile()) return f; }
        return null;
    }

    // ------------------------------------------------------------ lookup

    public static List<Entry> lookup(Context c, String word) {
        List<Entry> out = new ArrayList<>();
        List<String> forms = forms(word);
        for (Dict d : dictionaries(c)) {
            try {
                loadIndex(c, d);
                int found = 0;
                Set<Integer> seen = new LinkedHashSet<>();
                for (String form : forms) {
                    for (int i : find(d, form)) {
                        if (!seen.add(i) || found >= 3) continue;
                        byte[] data = readData(c, d, d.dataOff[i], d.dataSize[i]);
                        out.add(new Entry(d.name, headword(d, i), render(data, d.seq)));
                        found++;
                    }
                    if (found > 0) break; // the most exact form wins for this dictionary
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** The word plus simple English base forms (plural, past tense, -ing, -ly). */
    static List<String> forms(String w) {
        LinkedHashSet<String> f = new LinkedHashSet<>();
        w = w.trim();
        String l = w.toLowerCase(Locale.ROOT);
        f.add(w); f.add(l);
        if (l.endsWith("'s") || l.endsWith("’s")) f.add(l.substring(0, l.length() - 2));
        if (l.endsWith("ies") && l.length() > 4) f.add(l.substring(0, l.length() - 3) + "y");
        if (l.endsWith("es") && l.length() > 3) f.add(l.substring(0, l.length() - 2));
        if (l.endsWith("s") && l.length() > 3) f.add(l.substring(0, l.length() - 1));
        if (l.endsWith("ied") && l.length() > 4) f.add(l.substring(0, l.length() - 3) + "y");
        if (l.endsWith("ed") && l.length() > 3) { f.add(l.substring(0, l.length() - 2)); f.add(l.substring(0, l.length() - 1)); }
        if (l.endsWith("ing") && l.length() > 4) { String s = l.substring(0, l.length() - 3); f.add(s); f.add(s + "e"); }
        if (l.endsWith("ly") && l.length() > 4) f.add(l.substring(0, l.length() - 2));
        if (l.endsWith("er") && l.length() > 4) f.add(l.substring(0, l.length() - 2));
        if (l.endsWith("est") && l.length() > 5) f.add(l.substring(0, l.length() - 3));
        return new ArrayList<>(f);
    }

    private static synchronized void loadIndex(Context c, Dict d) throws Exception {
        if (d.idx != null) return;
        InputStream in = new FileInputStream(d.idxFile);
        if (d.idxFile.getName().toLowerCase(Locale.ROOT).endsWith(".gz")) in = new GZIPInputStream(in);
        byte[] b = EpubBook.readAll(in);
        List<Integer> starts = new ArrayList<>();
        List<Long> offs = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        int p = 0;
        while (p < b.length) {
            int s = p;
            while (p < b.length && b[p] != 0) p++;
            p++;
            if (p + d.offsetBytes + 4 > b.length) break;
            long off = 0;
            for (int i = 0; i < d.offsetBytes; i++) off = (off << 8) | (b[p++] & 0xFF);
            int size = 0;
            for (int i = 0; i < 4; i++) size = (size << 8) | (b[p++] & 0xFF);
            starts.add(s); offs.add(off); sizes.add(size);
        }
        d.wordStart = new int[starts.size()];
        d.dataOff = new long[starts.size()];
        d.dataSize = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) { d.wordStart[i] = starts.get(i); d.dataOff[i] = offs.get(i); d.dataSize[i] = sizes.get(i); }
        d.idx = b;
    }

    /** StarDict indexes are sorted by ASCII case-insensitive order, so binary search for the first match. */
    private static List<Integer> find(Dict d, String word) {
        byte[] w = word.getBytes(StandardCharsets.UTF_8);
        int lo = 0, hi = d.wordStart.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (asciiCaseCompare(d.idx, d.wordStart[mid], w) < 0) lo = mid + 1; else hi = mid;
        }
        List<Integer> out = new ArrayList<>();
        for (int i = lo; i < d.wordStart.length && asciiCaseCompare(d.idx, d.wordStart[i], w) == 0; i++) {
            if (headword(d, i).equalsIgnoreCase(word)) out.add(i);
        }
        return out;
    }

    private static int asciiCaseCompare(byte[] idx, int start, byte[] w) {
        int i = 0;
        while (true) {
            int a = idx[start + i] & 0xFF;
            int b = i < w.length ? w[i] & 0xFF : 0;
            if (a >= 'A' && a <= 'Z') a += 32;
            if (b >= 'A' && b <= 'Z') b += 32;
            if (a != b) return a - b;
            if (a == 0) return 0;
            i++;
        }
    }

    private static String headword(Dict d, int i) {
        int s = d.wordStart[i], e = s;
        while (d.idx[e] != 0) e++;
        return new String(d.idx, s, e - s, StandardCharsets.UTF_8);
    }

    private static byte[] readData(Context c, Dict d, long off, int size) throws Exception {
        String n = d.dictFile.getName().toLowerCase(Locale.ROOT);
        File plain = d.dictFile;
        if (n.endsWith(".dz")) {
            if (d.dz == null && d.plainCache == null) {
                DictZip z = new DictZip(d.dictFile);
                if (z.ok) d.dz = z;
                else d.plainCache = decompressAll(c, d.dictFile);
            }
            if (d.dz != null) return d.dz.read(off, size);
            plain = d.plainCache;
        }
        try (RandomAccessFile f = new RandomAccessFile(plain, "r")) {
            byte[] b = new byte[size];
            f.seek(off);
            f.readFully(b);
            return b;
        }
    }

    private static File decompressAll(Context c, File dz) throws Exception {
        File dir = new File(c.getCacheDir(), "dict");
        dir.mkdirs();
        File out = new File(dir, Integer.toHexString(dz.getAbsolutePath().hashCode()) + "-" + dz.length() + ".dict");
        if (!out.isFile()) {
            File tmp = new File(out.getPath() + ".tmp");
            ReaderActivity.copy(new GZIPInputStream(new FileInputStream(dz)), new FileOutputStream(tmp));
            tmp.renameTo(out);
        }
        return out;
    }

    // ------------------------------------------------------------ rendering

    static String render(byte[] d, String seq) {
        StringBuilder out = new StringBuilder();
        int p = 0;
        if (seq != null && !seq.isEmpty()) {
            for (int k = 0; k < seq.length() && p <= d.length; k++) {
                char t = seq.charAt(k);
                boolean last = k == seq.length() - 1;
                int[] r = field(d, p, t, last);
                out.append(fieldHtml(t, d, r[0], r[1]));
                p = r[2];
            }
        } else {
            while (p < d.length) {
                char t = (char) d[p++];
                int[] r = field(d, p, t, false);
                out.append(fieldHtml(t, d, r[0], r[1]));
                p = r[2];
            }
        }
        return out.toString();
    }

    /** Returns {start, end, next}. Lowercase types are NUL-terminated text; uppercase have a 4-byte size. */
    private static int[] field(byte[] d, int p, char t, boolean last) {
        if (Character.isLowerCase(t)) {
            if (last) return new int[]{p, d.length, d.length};
            int e = p;
            while (e < d.length && d[e] != 0) e++;
            return new int[]{p, e, e + 1};
        }
        if (last) return new int[]{p, d.length, d.length};
        if (p + 4 > d.length) return new int[]{p, d.length, d.length};
        int size = ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
        int s = p + 4, e = Math.min(d.length, s + size);
        return new int[]{s, e, e};
    }

    private static String fieldHtml(char t, byte[] d, int s, int e) {
        if (e <= s) return "";
        String text = new String(d, s, e - s, StandardCharsets.UTF_8);
        switch (t) {
            case 'h': case 'g':
                return "<div>" + text + "</div>";
            case 'x':
                return "<div>" + text.replace("<k>", "<b>").replace("</k>", "</b><br>") + "</div>";
            case 'm': case 't': case 'y': case 'l': case 'k':
                return "<div>" + escape(text).replace("\n", "<br>") + "</div>";
            default:
                return "";
        }
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------ online fallback

    /** English definitions from dictionaryapi.dev, or null if offline / not found. */
    public static Entry online(String word) {
        try {
            HttpURLConnection c = Updater.open("https://api.dictionaryapi.dev/api/v2/entries/en/" + Uri.encode(word.toLowerCase(Locale.ROOT)));
            if (c.getResponseCode() != 200) return null;
            JSONArray arr = new JSONArray(new String(EpubBook.readAll(c.getInputStream()), StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder();
            String head = word;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.getJSONObject(i);
                head = e.optString("word", head);
                String ph = e.optString("phonetic", "");
                if (!ph.isEmpty()) h.append("<div><i>").append(escape(ph)).append("</i></div>");
                JSONArray meanings = e.optJSONArray("meanings");
                if (meanings == null) continue;
                for (int m = 0; m < meanings.length(); m++) {
                    JSONObject mo = meanings.getJSONObject(m);
                    h.append("<p><b>").append(escape(mo.optString("partOfSpeech"))).append("</b></p><ol>");
                    JSONArray defs = mo.optJSONArray("definitions");
                    for (int k = 0; defs != null && k < defs.length() && k < 5; k++) {
                        JSONObject df = defs.getJSONObject(k);
                        h.append("<li>").append(escape(df.optString("definition")));
                        String ex = df.optString("example", "");
                        if (!ex.isEmpty()) h.append("<br><i>“").append(escape(ex)).append("”</i>");
                        h.append("</li>");
                    }
                    h.append("</ol>");
                }
            }
            return h.length() == 0 ? null : new Entry("Online (Free Dictionary API)", head, h.toString());
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ dictzip random access

    static class DictZip {
        boolean ok;
        final File file;
        int chunkLen;
        long[] chunkStart;
        int[] chunkSize;

        DictZip(File f) {
            file = f;
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                byte[] h = new byte[10];
                r.readFully(h);
                if ((h[0] & 0xFF) != 0x1f || (h[1] & 0xFF) != 0x8b) return;
                int flg = h[3] & 0xFF;
                if ((flg & 4) == 0) return;
                int xlen = r.read() | (r.read() << 8);
                long extraEnd = r.getFilePointer() + xlen;
                while (r.getFilePointer() + 4 <= extraEnd) {
                    int si1 = r.read(), si2 = r.read();
                    int len = r.read() | (r.read() << 8);
                    long next = r.getFilePointer() + len;
                    if (si1 == 'R' && si2 == 'A') {
                        r.skipBytes(2); // version
                        chunkLen = r.read() | (r.read() << 8);
                        int count = r.read() | (r.read() << 8);
                        chunkSize = new int[count];
                        for (int i = 0; i < count; i++) chunkSize[i] = r.read() | (r.read() << 8);
                    }
                    r.seek(next);
                }
                r.seek(extraEnd);
                if ((flg & 8) != 0) while (r.read() > 0) { }
                if ((flg & 16) != 0) while (r.read() > 0) { }
                if ((flg & 2) != 0) r.skipBytes(2);
                if (chunkSize == null || chunkLen == 0) return;
                chunkStart = new long[chunkSize.length];
                long pos = r.getFilePointer();
                for (int i = 0; i < chunkSize.length; i++) { chunkStart[i] = pos; pos += chunkSize[i]; }
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
        }

        byte[] read(long off, int size) throws Exception {
            int firstChunk = (int) (off / chunkLen);
            int lastChunk = (int) ((off + Math.max(size, 1) - 1) / chunkLen);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (RandomAccessFile r = new RandomAccessFile(file, "r")) {
                for (int i = firstChunk; i <= lastChunk && i < chunkSize.length; i++) {
                    byte[] comp = new byte[chunkSize[i]];
                    r.seek(chunkStart[i]);
                    r.readFully(comp);
                    Inflater inf = new Inflater(true);
                    inf.setInput(comp);
                    byte[] buf = new byte[chunkLen];
                    int total = 0;
                    while (total < chunkLen) {
                        int n = inf.inflate(buf, total, chunkLen - total);
                        if (n == 0 && (inf.needsInput() || inf.finished() || inf.needsDictionary())) break;
                        total += n;
                    }
                    inf.end();
                    out.write(buf, 0, total);
                }
            }
            byte[] all = out.toByteArray();
            int start = (int) (off - (long) firstChunk * chunkLen);
            int end = Math.min(all.length, start + size);
            byte[] res = new byte[Math.max(0, end - start)];
            System.arraycopy(all, start, res, 0, res.length);
            return res;
        }
    }
}
