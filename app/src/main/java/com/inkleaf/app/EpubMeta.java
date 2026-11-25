package com.inkleaf.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

/** Title, author and cover thumbnail of an EPUB, cached on disk so the library opens quickly. */
public class EpubMeta {
    public static final int COVER_WIDTH = 360;

    public String title = "", author = "";
    public boolean hasCover;

    public static String fallbackTitle(File f) {
        return f.getName().replaceAll("(?i)\\.epub$", "").replace('_', ' ').trim();
    }

    // ------------------------------------------------------------ cache

    static String key(File f) {
        String s = f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 10; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    static File dir(Context c, String name) {
        File d = new File(c.getCacheDir(), name);
        d.mkdirs();
        return d;
    }

    public static File coverFile(Context c, File book) { return new File(dir(c, "covers"), key(book) + ".jpg"); }

    /** Cached metadata if present, else null (cheap; safe on the UI thread). */
    public static EpubMeta cached(Context c, File book) {
        File m = new File(dir(c, "meta"), key(book) + ".json");
        if (!m.isFile()) return null;
        try {
            JSONObject j = new JSONObject(new String(EpubBook.readAll(new FileInputStream(m)), StandardCharsets.UTF_8));
            EpubMeta e = new EpubMeta();
            e.title = j.optString("title");
            e.author = j.optString("author");
            e.hasCover = j.optBoolean("cover");
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    /** Cached metadata, or reads the EPUB (and writes the cover thumbnail). Call off the UI thread. */
    public static EpubMeta get(Context c, File book) {
        EpubMeta e = cached(c, book);
        if (e != null) return e;
        e = new EpubMeta();
        try { e.extract(c, book); } catch (Exception ignored) { }
        if (e.title.isEmpty()) e.title = fallbackTitle(book);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir(c, "meta"), key(book) + ".json")), StandardCharsets.UTF_8)) {
            JSONObject j = new JSONObject();
            j.put("title", e.title);
            j.put("author", e.author);
            j.put("cover", e.hasCover);
            w.write(j.toString());
        } catch (Exception ignored) { }
        return e;
    }

    // ------------------------------------------------------------ reading the EPUB

    private void extract(Context c, File book) throws Exception {
        try (ZipFile zip = new ZipFile(book)) {
            String opfPath = "";
            for (Element el : elements(parse(zip, "META-INF/container.xml"), "rootfile")) {
                opfPath = el.getAttribute("full-path");
                if (!opfPath.isEmpty()) break;
            }
            if (opfPath.isEmpty()) return;
            String base = EpubBook.dirOf(opfPath);
            Document opf = parse(zip, opfPath);

            List<Element> t = elements(opf, "title");
            if (!t.isEmpty()) title = t.get(0).getTextContent().trim().replaceAll("\\s+", " ");
            List<Element> a = elements(opf, "creator");
            if (!a.isEmpty()) author = a.get(0).getTextContent().trim().replaceAll("\\s+", " ");

            Map<String, String[]> items = new HashMap<>(); // id -> {href, media-type}
            List<String[]> ordered = new ArrayList<>();
            String cover = null;
            for (Element it : elements(opf, "item")) {
                String[] v = {EpubBook.resolve(base, it.getAttribute("href")), it.getAttribute("media-type")};
                items.put(it.getAttribute("id"), v);
                ordered.add(new String[]{it.getAttribute("id"), v[0], v[1]});
                if (cover == null && (" " + it.getAttribute("properties") + " ").contains(" cover-image ")) cover = v[0];
            }
            if (cover == null) {
                for (Element m : elements(opf, "meta")) {
                    if ("cover".equals(m.getAttribute("name"))) {
                        String[] v = items.get(m.getAttribute("content"));
                        if (v != null && v[1].startsWith("image/")) cover = v[0];
                    }
                }
            }
            if (cover == null) {
                for (String[] o : ordered) {
                    if (o[2].startsWith("image/") && (o[0].toLowerCase(Locale.ROOT).contains("cover") || o[1].toLowerCase(Locale.ROOT).contains("cover"))) { cover = o[1]; break; }
                }
            }
            if (cover == null) {
                for (Element r : elements(opf, "reference")) {
                    if ("cover".equalsIgnoreCase(r.getAttribute("type"))) { cover = firstImage(zip, EpubBook.resolve(base, r.getAttribute("href"))); break; }
                }
            }
            if (cover == null) {
                List<Element> refs = elements(opf, "itemref");
                if (!refs.isEmpty()) {
                    String[] v = items.get(refs.get(0).getAttribute("idref"));
                    if (v != null) cover = firstImage(zip, v[0]);
                }
            }
            if (cover != null) hasCover = writeThumbnail(zip, cover, coverFile(c, book));
        }
    }

    /** First raster image referenced by an (X)HTML page, e.g. a cover page wrapping an image. */
    private static String firstImage(ZipFile zip, String htmlPath) {
        try {
            int hash = htmlPath.indexOf('#');
            if (hash >= 0) htmlPath = htmlPath.substring(0, hash);
            ZipEntry e = zip.getEntry(htmlPath);
            if (e == null) return null;
            String html = new String(EpubBook.readAll(zip.getInputStream(e)), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("(?i)(?:src|xlink:href|href)\\s*=\\s*[\"']([^\"']+\\.(?:jpe?g|png|gif|webp))[\"']").matcher(html);
            return m.find() ? EpubBook.resolve(EpubBook.dirOf(htmlPath), m.group(1)) : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean writeThumbnail(ZipFile zip, String path, File out) {
        try {
            int hash = path.indexOf('#');
            if (hash >= 0) path = path.substring(0, hash);
            ZipEntry e = zip.getEntry(path);
            if (e == null || e.getSize() > 20L * 1024 * 1024) return false;
            byte[] data = EpubBook.readAll(zip.getInputStream(e));
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return false;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= COVER_WIDTH) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, d);
            if (b == null) return false;
            if (b.getWidth() > COVER_WIDTH) {
                Bitmap s = Bitmap.createScaledBitmap(b, COVER_WIDTH, Math.round(b.getHeight() * (COVER_WIDTH / (float) b.getWidth())), true);
                b.recycle();
                b = s;
            }
            try (FileOutputStream fo = new FileOutputStream(out)) { b.compress(Bitmap.CompressFormat.JPEG, 88, fo); }
            b.recycle();
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    // ------------------------------------------------------------ XML helpers

    private static Document parse(ZipFile zip, String path) throws Exception {
        ZipEntry e = zip.getEntry(path);
        if (e == null) throw new Exception("missing " + path);
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setValidating(false);
        try (InputStream in = zip.getInputStream(e)) { return f.newDocumentBuilder().parse(in); }
    }

    private static List<Element> elements(Document d, String name) {
        List<Element> out = new ArrayList<>();
        NodeList all = d.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            String nn = n.getNodeName();
            int c = nn.indexOf(':');
            if ((c >= 0 ? nn.substring(c + 1) : nn).equals(name)) out.add((Element) n);
        }
        return out;
    }
}
