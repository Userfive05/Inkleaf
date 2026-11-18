package com.inkleaf.app;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** Minimal EPUB 2/3 reader: spine order, title and table of contents. */
public class EpubBook implements Closeable {

    public static class TocEntry {
        public final String label;
        public final String target; // zip path, optionally with #fragment
        TocEntry(String label, String target) { this.label = label; this.target = target; }
    }

    public final File file;
    public final ZipFile zip;
    public String title = "";
    public final List<String> spine = new ArrayList<>();
    public final List<TocEntry> toc = new ArrayList<>();

    public EpubBook(File f) throws Exception {
        file = f;
        zip = new ZipFile(f);

        String opfPath = "";
        for (Element e : elements(parse("META-INF/container.xml"), "rootfile")) {
            opfPath = e.getAttribute("full-path");
            if (!opfPath.isEmpty()) break;
        }
        if (opfPath.isEmpty()) throw new IOException("missing package file");
        String base = dirOf(opfPath);
        Document opf = parse(opfPath);

        List<Element> titles = elements(opf, "title");
        if (!titles.isEmpty()) title = titles.get(0).getTextContent().trim();

        Map<String, String> hrefById = new HashMap<>();
        String nav = null, ncx = null;
        for (Element item : elements(opf, "item")) {
            String href = resolve(base, item.getAttribute("href"));
            hrefById.put(item.getAttribute("id"), href);
            if ((" " + item.getAttribute("properties") + " ").contains(" nav ")) nav = href;
            if ("application/x-dtbncx+xml".equals(item.getAttribute("media-type"))) ncx = href;
        }
        for (Element ref : elements(opf, "itemref")) {
            String href = hrefById.get(ref.getAttribute("idref"));
            if (href != null) spine.add(href);
        }
        if (spine.isEmpty()) throw new IOException("book has no chapters");

        if (nav != null) { try { readNav(nav); } catch (Exception ignored) { toc.clear(); } }
        if (toc.isEmpty() && ncx != null) { try { readNcx(ncx); } catch (Exception ignored) { toc.clear(); } }
        if (title.isEmpty()) title = f.getName().replaceAll("(?i)\\.epub$", "");
    }

    /** Opens an entry by zip path, tolerating case differences. Returns null if missing. */
    public InputStream open(String path) throws IOException {
        ZipEntry e = zip.getEntry(path);
        if (e == null) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry z = all.nextElement();
                if (z.getName().equalsIgnoreCase(path)) { e = z; break; }
            }
        }
        return e == null ? null : zip.getInputStream(e);
    }

    public int spineIndex(String path) {
        int hash = path.indexOf('#');
        if (hash >= 0) path = path.substring(0, hash);
        for (int i = 0; i < spine.size(); i++) if (spine.get(i).equalsIgnoreCase(path)) return i;
        return -1;
    }

    @Override public void close() throws IOException { zip.close(); }

    // ---- table of contents ----

    private void readNcx(String path) throws Exception {
        String base = dirOf(path);
        for (Element point : elements(parse(path), "navPoint")) {
            String label = "", src = "";
            for (Node n = point.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element)) continue;
                String name = local(n.getNodeName());
                if (name.equals("navLabel")) label = n.getTextContent().trim().replaceAll("\\s+", " ");
                else if (name.equals("content")) src = ((Element) n).getAttribute("src");
            }
            int depth = 0;
            for (Node p = point.getParentNode(); p != null; p = p.getParentNode())
                if (local(p.getNodeName()).equals("navPoint")) depth++;
            if (!src.isEmpty()) toc.add(new TocEntry(indent(depth) + label, resolve(base, src)));
        }
    }

    private void readNav(String path) throws Exception {
        String base = dirOf(path);
        String html = new String(readAll(open(path)), StandardCharsets.UTF_8);
        Matcher navM = Pattern.compile("(?is)<nav[^>]*toc[^>]*>(.*?)</nav>").matcher(html);
        String section = navM.find() ? navM.group(1) : html;
        // Walk tags in order so nested <ol> depth can be tracked.
        Matcher m = Pattern.compile("(?is)<(/?)(ol|a)\\b([^>]*)>(.*?)(?=<(?:/?ol|a)\\b|$)").matcher(section);
        Pattern hrefP = Pattern.compile("(?is)href\\s*=\\s*[\"']([^\"']*)[\"']");
        int depth = -1;
        while (m.find()) {
            boolean closing = !m.group(1).isEmpty();
            String tag = m.group(2).toLowerCase();
            if (tag.equals("ol")) { depth += closing ? -1 : 1; continue; }
            if (closing) continue;
            Matcher h = hrefP.matcher(m.group(3));
            if (!h.find()) continue;
            String inner = m.group(4);
            int end = inner.toLowerCase().indexOf("</a");
            if (end >= 0) inner = inner.substring(0, end);
            String label = unescape(inner.replaceAll("(?s)<[^>]*>", "")).trim().replaceAll("\\s+", " ");
            if (!label.isEmpty()) toc.add(new TocEntry(indent(Math.max(0, depth)) + label, resolve(base, h.group(1))));
        }
    }

    // ---- helpers ----

    private Document parse(String path) throws Exception {
        InputStream in = open(path);
        if (in == null) throw new IOException("missing " + path);
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setValidating(false);
        try { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); } catch (Exception ignored) { }
        DocumentBuilder b = f.newDocumentBuilder();
        try { return b.parse(in); } finally { in.close(); }
    }

    private static List<Element> elements(Document d, String name) {
        List<Element> out = new ArrayList<>();
        NodeList all = d.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (local(n.getNodeName()).equals(name)) out.add((Element) n);
        }
        return out;
    }

    private static String local(String name) {
        int c = name.indexOf(':');
        return c >= 0 ? name.substring(c + 1) : name;
    }

    private static String indent(int depth) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < depth; i++) b.append("    ");
        return b.toString();
    }

    static String dirOf(String path) {
        int s = path.lastIndexOf('/');
        return s >= 0 ? path.substring(0, s + 1) : "";
    }

    /** Resolves an href relative to a directory inside the zip; keeps any #fragment. */
    static String resolve(String baseDir, String href) {
        String frag = "";
        int hash = href.indexOf('#');
        if (hash >= 0) { frag = href.substring(hash); href = href.substring(0, hash); }
        try { href = URLDecoder.decode(href.replace("+", "%2B"), "UTF-8"); } catch (Exception ignored) { }
        String joined = href.startsWith("/") ? href.substring(1) : baseDir + href;
        List<String> parts = new ArrayList<>();
        for (String p : joined.split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) { if (!parts.isEmpty()) parts.remove(parts.size() - 1); }
            else parts.add(p);
        }
        return String.join("/", parts) + frag;
    }

    static byte[] readAll(InputStream in) throws IOException {
        if (in == null) throw new IOException("missing entry");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static String unescape(String s) {
        return s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&");
    }
}
