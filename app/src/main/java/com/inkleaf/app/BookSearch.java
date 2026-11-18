package com.inkleaf.app;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Full-text search across every chapter. Match numbering agrees with IL.find() in reader.js. */
public class BookSearch {
    public static final int MAX_HITS = 300;

    public static class Hit {
        public final int chapter, nth;
        public final String snippet;
        Hit(int chapter, int nth, String snippet) { this.chapter = chapter; this.nth = nth; this.snippet = snippet; }
    }

    /** Words separated by any whitespace, case-insensitive. */
    public static Pattern pattern(String query) {
        String[] words = query.trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append("[\\s\\u00A0]+");
            b.append(Pattern.quote(w));
        }
        return Pattern.compile(b.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public static List<Hit> search(EpubBook book, String query) {
        List<Hit> hits = new ArrayList<>();
        if (query.trim().isEmpty()) return hits;
        Pattern p = pattern(query);
        for (int ch = 0; ch < book.spine.size() && hits.size() < MAX_HITS; ch++) {
            String text;
            try {
                text = plainText(new String(EpubBook.readAll(book.open(book.spine.get(ch))), StandardCharsets.UTF_8));
            } catch (Exception e) {
                continue;
            }
            Matcher m = p.matcher(text);
            int nth = 0;
            while (m.find() && hits.size() < MAX_HITS) {
                int s = Math.max(0, m.start() - 50), e = Math.min(text.length(), m.end() + 50);
                String snip = (s > 0 ? "…" : "") + text.substring(s, e).replaceAll("\\s+", " ").trim() + (e < text.length() ? "…" : "");
                hits.add(new Hit(ch, nth++, snip));
            }
        }
        return hits;
    }

    /** Body text the way the browser sees it: tags removed, entities decoded, soft hyphens dropped. */
    static String plainText(String html) {
        String s = html;
        Matcher body = Pattern.compile("(?is)<body[^>]*>").matcher(s);
        if (body.find()) s = s.substring(body.end());
        s = s.replaceAll("(?is)<script.*?</script\\s*>", "")
                .replaceAll("(?is)<style.*?</style\\s*>", "")
                .replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("(?is)<!\\[CDATA\\[(.*?)\\]\\]>", "$1")
                .replaceAll("(?s)<[^>]*>", "");
        s = decodeEntities(s);
        return s.replace("­", "");
    }

    static String decodeEntities(String s) {
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String e = m.group(1), r;
            try {
                if (e.startsWith("#x") || e.startsWith("#X")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                else {
                    switch (e) {
                        case "amp": r = "&"; break;
                        case "lt": r = "<"; break;
                        case "gt": r = ">"; break;
                        case "quot": r = "\""; break;
                        case "apos": r = "'"; break;
                        case "nbsp": r = " "; break;
                        case "shy": r = "­"; break;
                        case "mdash": r = "—"; break;
                        case "ndash": r = "–"; break;
                        case "hellip": r = "…"; break;
                        case "rsquo": r = "’"; break;
                        case "lsquo": r = "‘"; break;
                        case "rdquo": r = "”"; break;
                        case "ldquo": r = "“"; break;
                        default: r = m.group(0);
                    }
                }
            } catch (Exception ex) {
                r = m.group(0);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString();
    }
}
