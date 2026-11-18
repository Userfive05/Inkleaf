package com.inkleaf.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReaderActivity extends Activity {
    static final String HOST = "book.local";
    static final String ORIGIN = "https://" + HOST + "/";
    static final int REQ_FONT = 41;

    Prefs prefs;
    EpubBook book;
    String key;
    WebView web;
    LinearLayout menu, panel;
    TextView status, lightToast, sizeValue, lineValue, marginValue, lightValue, fontButton;
    final Handler ui = new Handler(Looper.getMainLooper());

    int chapter = 0, page = 0, count = 1, cssVersion = 1;
    volatile String startPos = "0";

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = new Prefs(this);
        hideSystemBars();
        File f = resolveFile(getIntent());
        if (f == null) { Toast.makeText(this, "Couldn't open that file.", Toast.LENGTH_LONG).show(); finish(); return; }
        try {
            book = new EpubBook(f);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open this EPUB: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        key = f.getAbsolutePath();
        prefs.touch(key);
        buildUi();
        Light.restore(this, prefs);
        int ch = Prefs.clamp(prefs.chapter(key), 0, book.spine.size() - 1);
        loadChapter(ch, String.valueOf(prefs.fraction(key)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lightValue != null) refreshPanel();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (book != null) book.close(); } catch (Exception ignored) { }
    }

    @SuppressWarnings("deprecation")
    void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    File resolveFile(Intent i) {
        String path = i.getStringExtra("path");
        if (path != null) return new File(path);
        Uri u = i.getData();
        if (u == null) return null;
        if ("file".equals(u.getScheme())) return new File(u.getPath());
        // content:// — copy into the app's cache so it can be read as a zip
        try {
            String name = queryName(u);
            if (name == null) name = "opened.epub";
            File dir = new File(getCacheDir(), "opened");
            dir.mkdirs();
            File out = new File(dir, name.replaceAll("[/\\\\]", "_"));
            copy(getContentResolver().openInputStream(u), new FileOutputStream(out));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- UI

    void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        web = new WebView(this);
        setupWebView();
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(-1, -1);
        wl.bottomMargin = dp(28);
        root.addView(web, wl);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setTextColor(0xFF333333);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(-1, dp(28), Gravity.BOTTOM));

        lightToast = new TextView(this);
        lightToast.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        lightToast.setTextColor(Color.BLACK);
        lightToast.setTypeface(Typeface.DEFAULT_BOLD);
        lightToast.setPadding(dp(24), dp(14), dp(24), dp(14));
        lightToast.setBackground(box());
        lightToast.setVisibility(View.GONE);
        root.addView(lightToast, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        root.addView(buildMenu(), new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        setContentView(root);
        setupTouch();
    }

    View buildMenu() {
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackgroundColor(Color.WHITE);
        menu.setVisibility(View.GONE);
        menu.setClickable(true);

        LinearLayout bar = row();
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bar.addView(button("‹ Library", v -> finish()));
        bar.addView(button("Contents", v -> showContents()));
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        bar.addView(button("Settings ▾", v -> {
            panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            refreshPanel();
        }));
        menu.addView(bar);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(4), dp(16), dp(12));
        panel.setVisibility(View.GONE);

        sizeValue = value();
        panel.addView(stepper("Text size", sizeValue,
                () -> { prefs.fontSize(prefs.fontSize() - 1); restyle(); },
                () -> { prefs.fontSize(prefs.fontSize() + 1); restyle(); }));

        LinearLayout fontRow = row();
        fontRow.addView(label("Font"), new LinearLayout.LayoutParams(0, -2, 1));
        fontButton = button("", v -> chooseFont());
        fontRow.addView(fontButton);
        panel.addView(fontRow);

        lineValue = value();
        panel.addView(stepper("Line spacing", lineValue,
                () -> { prefs.lineHeight(prefs.lineHeight() - 1); restyle(); },
                () -> { prefs.lineHeight(prefs.lineHeight() + 1); restyle(); }));

        marginValue = value();
        panel.addView(stepper("Margins", marginValue,
                () -> { prefs.margin(prefs.margin() - 4); restyle(); },
                () -> { prefs.margin(prefs.margin() + 4); restyle(); }));

        lightValue = value();
        panel.addView(stepper("Light", lightValue,
                () -> { Light.set(this, prefs, Light.get(this, prefs) - 13); refreshPanel(); },
                () -> { Light.set(this, prefs, Light.get(this, prefs) + 13); refreshPanel(); }));
        TextView hint = new TextView(this);
        hint.setText("Tip: swipe up or down along the left edge of the page to change the light.");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setTextColor(0xFF444444);
        hint.setPadding(0, 0, 0, dp(6));
        panel.addView(hint);

        CheckBox hyph = check("No hyphens (move whole words to the next line)", prefs.noHyphens());
        hyph.setOnCheckedChangeListener((b, on) -> { prefs.noHyphens(on); restyle(); });
        panel.addView(hyph);
        CheckBox left = check("Left-align text (no justified spacing)", prefs.leftAlign());
        left.setOnCheckedChangeListener((b, on) -> { prefs.leftAlign(on); restyle(); });
        panel.addView(left);

        LinearLayout upd = row();
        upd.setPadding(0, dp(8), 0, 0);
        TextView ver = label("InkLeaf " + BuildConfig.VERSION_NAME);
        upd.addView(ver, new LinearLayout.LayoutParams(0, -2, 1));
        upd.addView(button("Update", v -> Updater.checkAndOffer(this)));
        panel.addView(upd);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        menu.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        View line = new View(this);
        line.setBackgroundColor(Color.BLACK);
        menu.addView(line, new LinearLayout.LayoutParams(-1, dp(2)));
        return menu;
    }

    void refreshPanel() {
        sizeValue.setText(String.valueOf(prefs.fontSize()));
        lineValue.setText(String.format(Locale.US, "%.1f", prefs.lineHeight() / 10f));
        marginValue.setText(String.valueOf(prefs.margin()));
        lightValue.setText(Light.percent(Light.get(this, prefs)) + "%");
        fontButton.setText(fontName(prefs.font()) + " ▾");
    }

    void showMenu(boolean show) {
        menu.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) panel.setVisibility(View.GONE);
        else refreshPanel();
    }

    // ---------------------------------------------------------------- reading

    void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(false);
        web.setBackgroundColor(Color.WHITE);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setLongClickable(false);
        web.setHapticFeedbackEnabled(false);
        web.addJavascriptInterface(new Bridge(), "InkLeaf");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return serve(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return !HOST.equals(req.getUrl().getHost());
            }
        });
    }

    void loadChapter(int idx, String start) {
        chapter = idx;
        startPos = start;
        page = 0;
        count = 1;
        web.loadUrl(ORIGIN + encodePath(book.spine.get(idx)) + "?v=" + cssVersion);
    }

    void restyle() {
        cssVersion++;
        refreshPanel();
        loadChapter(chapter, String.valueOf(count > 0 ? page / (float) count : 0f));
    }

    void next() {
        web.evaluateJavascript("IL.next()", r -> {
            if (r != null && r.contains("end") && chapter < book.spine.size() - 1) loadChapter(chapter + 1, "0");
        });
    }

    void prev() {
        web.evaluateJavascript("IL.prev()", r -> {
            if (r != null && r.contains("start") && chapter > 0) loadChapter(chapter - 1, "last");
        });
    }

    class Bridge {
        @JavascriptInterface public String start() { return startPos; }
        @JavascriptInterface public boolean leftAlign() { return prefs.leftAlign(); }
        @JavascriptInterface public void onPage(int p, int c) {
            ui.post(() -> {
                page = p;
                count = Math.max(1, c);
                int n = book.spine.size();
                int pct = Math.round((chapter + (page + 1) / (float) count) * 100f / n);
                prefs.progress(key, chapter, page / (float) count, Math.min(100, pct));
                status.setText("Chapter " + (chapter + 1) + " of " + n + "     ·     Page " + (page + 1) + " of " + count + "     ·     " + pct + "%");
            });
        }
    }

    void showContents() {
        final List<String> labels = new ArrayList<>();
        final List<String> targets = new ArrayList<>();
        if (book.toc.isEmpty()) {
            for (int i = 0; i < book.spine.size(); i++) { labels.add("Section " + (i + 1)); targets.add(book.spine.get(i)); }
        } else {
            for (EpubBook.TocEntry e : book.toc) { labels.add(e.label); targets.add(e.target); }
        }
        new AlertDialog.Builder(this)
                .setTitle(book.title)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    showMenu(false);
                    goTo(targets.get(which));
                })
                .show();
    }

    void goTo(String target) {
        int hash = target.indexOf('#');
        String path = hash >= 0 ? target.substring(0, hash) : target;
        String frag = hash >= 0 ? target.substring(hash + 1) : null;
        int idx = book.spineIndex(path);
        if (idx < 0) return;
        if (idx == chapter && frag != null) web.evaluateJavascript("IL.anchor(" + org.json.JSONObject.quote(frag) + ")", null);
        else loadChapter(idx, frag != null ? "#" + frag : "0");
    }

    void openLink(String href) {
        Uri u = Uri.parse(href);
        if (!HOST.equals(u.getHost())) {
            Toast.makeText(this, "Web links aren't opened in InkLeaf.", Toast.LENGTH_SHORT).show();
            return;
        }
        String path = u.getPath() == null ? "" : u.getPath().replaceFirst("^/", "");
        goTo(u.getFragment() != null ? path + "#" + u.getFragment() : path);
    }

    // ---------------------------------------------------------------- touch: page turns + left-edge light

    void setupTouch() {
        web.setOnTouchListener(new View.OnTouchListener() {
            float x0, y0;
            boolean edge, sliding, moved;
            int startLight, lastLight;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x0 = e.getX(); y0 = e.getY();
                        edge = x0 < v.getWidth() * 0.15f;
                        sliding = false; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getX() - x0, dy = e.getY() - y0;
                        if (Math.abs(dx) > dp(12) || Math.abs(dy) > dp(12)) moved = true;
                        if (edge && !sliding && Math.abs(dy) > dp(20) && Math.abs(dy) > Math.abs(dx) * 1.5f) {
                            sliding = true;
                            startLight = lastLight = Light.get(ReaderActivity.this, prefs);
                        }
                        if (sliding) {
                            int val = Prefs.clamp(startLight + Math.round(-dy / (v.getHeight() * 0.7f) * Light.MAX), 0, Light.MAX);
                            if (Math.abs(val - lastLight) >= 3 || val == 0 || val == Light.MAX) {
                                lastLight = val;
                                Light.set(ReaderActivity.this, prefs, val);
                                lightToast.setText("Light " + Light.percent(val) + "%");
                                lightToast.setVisibility(View.VISIBLE);
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP: {
                        float dx = e.getX() - x0, dy = e.getY() - y0;
                        if (sliding) {
                            ui.postDelayed(() -> lightToast.setVisibility(View.GONE), 700);
                            maybeAskLightPermission();
                        } else if (!moved) {
                            tap(e.getX(), e.getY(), v.getWidth());
                        } else if (Math.abs(dx) > dp(50) && Math.abs(dx) > Math.abs(dy)) {
                            if (menu.getVisibility() == View.VISIBLE) showMenu(false);
                            else if (dx < 0) next(); else prev();
                        }
                        return true;
                    }
                }
                return true;
            }
        });
    }

    void tap(float x, float y, int width) {
        if (menu.getVisibility() == View.VISIBLE) { showMenu(false); return; }
        web.evaluateJavascript("IL.linkAt(" + x + "," + y + ")", r -> {
            String href = "";
            try { if (r != null && !r.equals("null")) href = new JSONArray("[" + r + "]").getString(0); } catch (Exception ignored) { }
            if (!href.isEmpty()) openLink(href);
            else if (x < width / 3f) prev();
            else if (x > width * 2 / 3f) next();
            else showMenu(true);
        });
    }

    void maybeAskLightPermission() {
        if (Light.canWriteSystem(this) || prefs.askedLight()) return;
        prefs.askedLight(true);
        new AlertDialog.Builder(this)
                .setTitle("Front light")
                .setMessage("To control the Boox front light reliably, allow InkLeaf to \"modify system settings\". You only need to do this once.")
                .setPositiveButton("Allow", (d, w) -> Light.requestPermission(this))
                .setNegativeButton("Not now", null)
                .show();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_PAGE_DOWN: case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_DOWN:
                next(); return true;
            case KeyEvent.KEYCODE_PAGE_UP: case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_UP:
                prev(); return true;
            case KeyEvent.KEYCODE_BACK:
                if (menu.getVisibility() == View.VISIBLE) { showMenu(false); return true; }
        }
        return super.onKeyDown(code, e);
    }

    // ---------------------------------------------------------------- fonts

    String fontName(String f) {
        if (f.equals("book")) return "Book default";
        if (f.equals("serif")) return "Serif";
        if (f.equals("sans")) return "Sans-serif";
        return Fonts.displayName(f);
    }

    void chooseFont() {
        final List<String> values = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        values.add("book"); values.add("serif"); values.add("sans");
        for (File f : Fonts.all(this)) values.add(f.getAbsolutePath());
        for (String v : values) names.add(fontName(v));
        names.add("+ Add font file…");
        final boolean canRemove = !Fonts.added(this).isEmpty();
        if (canRemove) names.add("Remove an added font…");
        new AlertDialog.Builder(this)
                .setTitle("Font")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (which < values.size()) { prefs.font(values.get(which)); restyle(); }
                    else if (which == values.size()) pickFontFile();
                    else removeFont();
                })
                .show();
    }

    void pickFontFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { startActivityForResult(i, REQ_FONT); }
        catch (Exception e) { Toast.makeText(this, "No file picker found. Put fonts in a \"Fonts\" folder on the device instead.", Toast.LENGTH_LONG).show(); }
    }

    void removeFont() {
        final List<File> added = Fonts.added(this);
        String[] names = new String[added.size()];
        for (int i = 0; i < names.length; i++) names[i] = Fonts.displayName(added.get(i).getName());
        new AlertDialog.Builder(this).setTitle("Remove font").setItems(names, (d, which) -> {
            File f = added.get(which);
            if (prefs.font().equals(f.getAbsolutePath())) prefs.font("book");
            f.delete();
            restyle();
        }).show();
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_FONT || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        String name = queryName(u);
        if (name == null || !Fonts.isFont(name)) {
            Toast.makeText(this, "Pick a .ttf, .otf, .woff or .woff2 file.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            File out = new File(Fonts.addedDir(this), name.replaceAll("[/\\\\]", "_"));
            copy(getContentResolver().openInputStream(u), new FileOutputStream(out));
            prefs.font(out.getAbsolutePath());
            Toast.makeText(this, "Added " + Fonts.displayName(name), Toast.LENGTH_SHORT).show();
            restyle();
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't add font: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- serving book content to the WebView

    WebResourceResponse serve(Uri u) {
        if (!HOST.equals(u.getHost())) return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        String path = u.getPath() == null ? "" : u.getPath().replaceFirst("^/", "");
        try {
            if (path.equals("__inkleaf/style.css"))
                return new WebResourceResponse("text/css", "utf-8", new ByteArrayInputStream(css().getBytes(StandardCharsets.UTF_8)));
            if (path.equals("__inkleaf/reader.js"))
                return new WebResourceResponse("application/javascript", "utf-8", getAssets().open("reader.js"));
            if (path.equals("__inkleaf/font")) {
                String p = u.getQueryParameter("p");
                File f = p == null ? null : new File(p);
                if (f == null || !Fonts.isFont(f.getName()) || !f.isFile()) return notFound();
                return new WebResourceResponse(mime(f.getName()), null, new FileInputStream(f));
            }
            InputStream in = book.open(path);
            if (in == null) return notFound();
            String type = mime(path);
            if (type.contains("html")) {
                String html = new String(EpubBook.readAll(in), StandardCharsets.UTF_8);
                return new WebResourceResponse(type, "utf-8", new ByteArrayInputStream(inject(html).getBytes(StandardCharsets.UTF_8)));
            }
            return new WebResourceResponse(type, null, in);
        } catch (Exception e) {
            return notFound();
        }
    }

    WebResourceResponse notFound() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        return r;
    }

    String inject(String html) {
        String tags = "<link rel=\"stylesheet\" type=\"text/css\" href=\"/__inkleaf/style.css?v=" + cssVersion + "\"/>"
                + "<script type=\"text/javascript\" src=\"/__inkleaf/reader.js?v=" + cssVersion + "\"></script>";
        Matcher head = Pattern.compile("(?i)</head\\s*>").matcher(html);
        if (head.find()) return html.substring(0, head.start()) + tags + html.substring(head.start());
        Matcher body = Pattern.compile("(?i)<body[^>]*>").matcher(html);
        if (body.find()) return html.substring(0, body.end()) + tags + html.substring(body.end());
        return tags + html;
    }

    /** Reader stylesheet, applied on top of the book's own CSS. */
    String css() {
        int m = prefs.margin();
        int v = Math.max(12, m * 3 / 4);
        String lh = String.format(Locale.US, "%.1f", prefs.lineHeight() / 10f);
        StringBuilder b = new StringBuilder();
        String family = null;
        String font = prefs.font();
        if (font.equals("serif")) family = "serif";
        else if (font.equals("sans")) family = "sans-serif";
        else if (!font.equals("book") && new File(font).isFile()) {
            b.append("@font-face{font-family:\"InkLeafUser\";src:url(\"/__inkleaf/font?p=")
                    .append(Uri.encode(font)).append("\");}\n");
            family = "\"InkLeafUser\", serif";
        }
        b.append("html{margin:0!important;padding:0!important;height:100vh!important;overflow:hidden!important;")
                .append("background:#fff!important;-webkit-text-size-adjust:none!important;font-size:")
                .append(prefs.fontSize()).append("px!important;}\n");
        b.append("body{margin:0!important;box-sizing:border-box!important;height:100vh!important;")
                .append("padding:").append(v).append("px ").append(m).append("px!important;")
                .append("column-width:calc(100vw - ").append(2 * m).append("px)!important;")
                .append("column-gap:").append(2 * m).append("px!important;column-fill:auto!important;")
                .append("width:auto!important;max-width:none!important;font-size:1rem!important;")
                .append("color:#000!important;background:#fff!important;}\n");
        b.append("body,body *{line-height:").append(lh).append("!important;}\n");
        if (family != null)
            b.append("body,body *:not(code):not(pre):not(kbd):not(samp):not(tt){font-family:").append(family).append("!important;}\n");
        if (prefs.noHyphens())
            b.append("*{-webkit-hyphens:none!important;hyphens:none!important;}\n");
        else
            b.append("body{-webkit-hyphens:auto;hyphens:auto;}\n");
        if (prefs.leftAlign()) b.append("body{text-align:left!important;}\n");
        b.append("*{word-break:normal!important;overflow-wrap:break-word!important;}\n");
        b.append("img,svg,video,image{max-width:100%!important;max-height:calc(100vh - ").append(2 * v + 4)
                .append("px)!important;object-fit:contain;break-inside:avoid;}\n");
        b.append("img{height:auto;}\n");
        b.append("a{color:inherit!important;}\n");
        return b.toString();
    }

    static String mime(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".xhtml") || p.endsWith(".xht") || p.endsWith(".xml")) return "application/xhtml+xml";
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".otf")) return "font/otf";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".js")) return "application/javascript";
        return "application/octet-stream";
    }

    static String encodePath(String path) {
        StringBuilder b = new StringBuilder();
        for (String seg : path.split("/")) {
            if (b.length() > 0) b.append('/');
            b.append(Uri.encode(seg));
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- small helpers

    String queryName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        String last = u.getLastPathSegment();
        return last == null ? null : last.substring(last.lastIndexOf('/') + 1);
    }

    static void copy(InputStream in, OutputStream out) throws Exception {
        try (InputStream i = in; OutputStream o = out) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    GradientDrawable box() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setStroke(dp(2), Color.BLACK);
        g.setCornerRadius(dp(6));
        return g;
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(4), 0, dp(4));
        return r;
    }

    TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTextColor(Color.BLACK);
        return t;
    }

    TextView value() {
        TextView t = label("");
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(64));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    TextView button(String s, View.OnClickListener l) {
        TextView t = label(s);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        t.setMinWidth(dp(48));
        t.setBackground(box());
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(4), 0, dp(4), 0);
        t.setLayoutParams(lp);
        return t;
    }

    View stepper(String name, TextView valueView, Runnable minus, Runnable plus) {
        LinearLayout r = row();
        r.addView(label(name), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(button("−", v -> minus.run()));
        r.addView(valueView);
        r.addView(button("+", v -> plus.run()));
        return r;
    }

    CheckBox check(String s, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        c.setTextColor(Color.BLACK);
        c.setChecked(on);
        c.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
        return c;
    }
}
