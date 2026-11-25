package com.inkleaf.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The library: a "Continue reading" card, then every EPUB on the device as a cover grid or a list. */
public class LibraryActivity extends Activity {
    Prefs prefs;
    final List<File> books = new ArrayList<>();
    final Map<String, EpubMeta> metas = new HashMap<>();
    BaseAdapter adapter;
    TextView message, updateButton, viewButton, countLabel;
    ListView list;
    LinearLayout continueCard;
    int scanGeneration = 0;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = new Prefs(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // ---- top bar
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(14), dp(12), dp(12));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("InkLeaf", 27, Color.BLACK);
        title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        titles.addView(title);
        titles.addView(text(BuildConfig.VERSION_NAME, 12, 0xFF555555));
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        viewButton = button("", v -> {
            prefs.libraryView(isGrid() ? "list" : "grid");
            updateViewButton();
            adapter.notifyDataSetChanged();
        });
        bar.addView(viewButton);
        bar.addView(button("Refresh", v -> refresh()));
        bar.addView(button("Backup", v -> {
            if (!hasAccess()) { requestAccess(); return; }
            Backup.showMenu(this, new ArrayList<>(books), this::refresh);
        }));
        updateButton = button("Update", v -> Updater.checkAndOffer(this));
        bar.addView(updateButton);
        root.addView(bar);
        updateViewButton();

        View line = new View(this);
        line.setBackgroundColor(Color.BLACK);
        root.addView(line, new LinearLayout.LayoutParams(-1, dp(2)));

        message = text("", 17, Color.BLACK);
        message.setPadding(dp(20), dp(20), dp(20), dp(20));
        message.setOnClickListener(v -> requestAccess());
        root.addView(message);

        // ---- list (grid rows or list rows) with the Continue card as its header
        list = new ListView(this);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        continueCard = new LinearLayout(this);
        continueCard.setVisibility(View.GONE);
        header.addView(continueCard);
        countLabel = text("", 13, 0xFF444444);
        countLabel.setTypeface(Typeface.DEFAULT_BOLD);
        countLabel.setLetterSpacing(0.08f);
        countLabel.setPadding(dp(20), dp(18), dp(20), dp(6));
        header.addView(countLabel);
        list.addHeaderView(header, null, false);

        adapter = new BaseAdapter() {
            public int getCount() { return isGrid() ? (books.size() + columns() - 1) / columns() : books.size(); }
            public Object getItem(int i) { return i; }
            public long getItemId(int i) { return i; }
            public View getView(int i, View convert, ViewGroup parent) { return isGrid() ? gridRow(i) : listRow(books.get(i)); }
        };
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);

        // Opened from the launcher after the app was closed while reading: go straight back to that book.
        String reading = prefs.reading();
        if (saved == null && Intent.ACTION_MAIN.equals(getIntent().getAction())
                && reading != null && new File(reading).isFile()) {
            open(new File(reading));
        }

        // Quiet check so the Update button shows when there's something new.
        Updater.latest(this, (build, url) -> {
            if (build > BuildConfig.VERSION_CODE && url != null) updateButton.setText("Update ●");
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    boolean isGrid() { return !"list".equals(prefs.libraryView()); }

    void updateViewButton() { viewButton.setText(isGrid() ? "List" : "Grid"); }

    int columns() {
        float widthDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        return Math.max(2, Math.min(6, (int) ((widthDp - 24) / 140)));
    }

    void open(File f) {
        startActivity(new Intent(this, ReaderActivity.class).putExtra("path", f.getAbsolutePath()));
    }

    // ---------------------------------------------------------------- permissions

    boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    void requestAccess() {
        if (hasAccess()) return;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == Backup.REQ_RESTORE_FILE && result == RESULT_OK && data != null && data.getData() != null)
            Backup.restoreFromUri(this, data.getData(), new ArrayList<>(books), this::refresh);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        refresh();
    }

    // ---------------------------------------------------------------- scanning

    void refresh() {
        if (!hasAccess()) {
            books.clear();
            adapter.notifyDataSetChanged();
            continueCard.setVisibility(View.GONE);
            countLabel.setText("");
            message.setText("InkLeaf needs permission to find your books.\n\nTap here, then turn on \"Allow access to manage all files\".");
            message.setVisibility(View.VISIBLE);
            return;
        }
        Backup.autoBackup(this);
        if (books.isEmpty()) {
            message.setText("Looking for books…");
            message.setVisibility(View.VISIBLE);
        }
        final int gen = ++scanGeneration;
        new Thread(() -> {
            List<File> found = Backup.scanBooks(Environment.getExternalStorageDirectory(), 0, null);
            Collections.sort(found, (a, b) -> {
                long la = prefs.lastOpened(a.getAbsolutePath()), lb = prefs.lastOpened(b.getAbsolutePath());
                if (la != lb) return Long.compare(lb, la);
                return a.getName().compareToIgnoreCase(b.getName());
            });
            // Cached titles/covers are instant; the rest are read below.
            Map<String, EpubMeta> cached = new HashMap<>();
            List<File> missing = new ArrayList<>();
            for (File f : found) {
                EpubMeta m = EpubMeta.cached(this, f);
                if (m != null) cached.put(f.getAbsolutePath(), m); else missing.add(f);
            }
            runOnUiThread(() -> {
                if (gen != scanGeneration) return;
                books.clear();
                books.addAll(found);
                metas.putAll(cached);
                showAll();
            });
            // New books: read title, author and cover, updating the screen in batches (gentle on e-ink).
            int done = 0;
            for (File f : missing) {
                if (gen != scanGeneration) return;
                EpubMeta m = EpubMeta.get(this, f);
                runOnUiThread(() -> metas.put(f.getAbsolutePath(), m));
                if (++done % 8 == 0 || done == missing.size()) runOnUiThread(() -> { if (gen == scanGeneration) showAll(); });
            }
        }).start();
    }

    void showAll() {
        adapter.notifyDataSetChanged();
        buildContinueCard();
        countLabel.setText(books.isEmpty() ? "" : ("LIBRARY  ·  " + books.size() + (books.size() == 1 ? " BOOK" : " BOOKS")));
        if (books.isEmpty()) message.setText("No EPUB files found. Put books in the Books or Download folder and tap Refresh.");
        message.setVisibility(books.isEmpty() ? View.VISIBLE : View.GONE);
    }

    String titleOf(File f) {
        EpubMeta m = metas.get(f.getAbsolutePath());
        return m != null && !m.title.isEmpty() ? m.title : EpubMeta.fallbackTitle(f);
    }

    String authorOf(File f) {
        EpubMeta m = metas.get(f.getAbsolutePath());
        return m != null ? m.author : "";
    }

    int percentOf(File f) { return prefs.percent(f.getAbsolutePath()); }

    // ---------------------------------------------------------------- continue reading

    void buildContinueCard() {
        continueCard.removeAllViews();
        File last = null;
        long best = 0;
        for (File f : books) {
            long t = prefs.lastOpened(f.getAbsolutePath());
            if (t > best) { best = t; last = f; }
        }
        if (last == null) { continueCard.setVisibility(View.GONE); return; }
        final File book = last;
        continueCard.setVisibility(View.VISIBLE);
        continueCard.setOrientation(LinearLayout.HORIZONTAL);
        continueCard.setGravity(Gravity.CENTER_VERTICAL);
        continueCard.setPadding(dp(14), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setStroke(dp(2), Color.BLACK);
        g.setCornerRadius(dp(10));
        continueCard.setBackground(g);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.setMargins(dp(14), dp(16), dp(14), dp(2));
        continueCard.setLayoutParams(cl);
        continueCard.setOnClickListener(v -> open(book));

        CoverView cover = new CoverView(this);
        cover.bind(this, book, titleOf(book), authorOf(book), percentOf(book));
        continueCard.addView(cover, new LinearLayout.LayoutParams(dp(104), -2));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(18), 0, 0, 0);
        TextView label = text("CONTINUE READING", 12, 0xFF444444);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.1f);
        info.addView(label);
        TextView t = text(titleOf(book), 21, Color.BLACK);
        t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        t.setMaxLines(3);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, dp(6), 0, 0);
        info.addView(t);
        String author = authorOf(book);
        if (!author.isEmpty()) {
            TextView a = text(author, 15, 0xFF333333);
            a.setTypeface(Typeface.SERIF);
            a.setMaxLines(1);
            a.setEllipsize(TextUtils.TruncateAt.END);
            a.setPadding(0, dp(2), 0, 0);
            info.addView(a);
        }
        int pct = percentOf(book);
        if (pct >= 2) {
            ProgressBarView pb = new ProgressBarView(this);
            pb.set(pct / 100f, null);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dp(9));
            pl.topMargin = dp(14);
            info.addView(pb, pl);
            info.addView(text(pct >= 100 ? "Finished" : pct + "% read", 13, 0xFF333333));
        }
        TextView go = button("Continue  ›", v -> open(book));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2);
        gl.topMargin = dp(12);
        go.setLayoutParams(gl);
        info.addView(go);
        continueCard.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
    }

    // ---------------------------------------------------------------- grid & list

    View gridRow(int row) {
        int cols = columns();
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(10), dp(8), dp(10), dp(8));
        for (int c = 0; c < cols; c++) {
            int i = row * cols + c;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(dp(6), 0, dp(6), 0);
            if (i < books.size()) {
                File f = books.get(i);
                CoverView cover = new CoverView(this);
                cover.bind(this, f, titleOf(f), authorOf(f), percentOf(f));
                cell.addView(cover, new LinearLayout.LayoutParams(-1, -2));
                TextView t = text(titleOf(f), 14, Color.BLACK);
                t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
                t.setMaxLines(2);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(0, dp(6), 0, 0);
                cell.addView(t);
                String a = authorOf(f);
                if (!a.isEmpty()) {
                    TextView at = text(a, 12, 0xFF444444);
                    at.setMaxLines(1);
                    at.setEllipsize(TextUtils.TruncateAt.END);
                    cell.addView(at);
                }
                cell.setOnClickListener(v -> open(f));
            }
            r.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return r;
    }

    View listRow(File f) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(18), dp(10), dp(18), dp(10));
        CoverView cover = new CoverView(this);
        cover.bind(this, f, titleOf(f), authorOf(f), percentOf(f));
        r.addView(cover, new LinearLayout.LayoutParams(dp(62), -2));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(16), 0, 0, 0);
        TextView t = text(titleOf(f), 18, Color.BLACK);
        t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(t);
        String a = authorOf(f);
        if (!a.isEmpty()) {
            TextView at = text(a, 14, 0xFF333333);
            at.setTypeface(Typeface.SERIF);
            at.setMaxLines(1);
            at.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(at);
        }
        int pct = percentOf(f);
        if (pct >= 2) {
            ProgressBarView pb = new ProgressBarView(this);
            pb.set(pct / 100f, null);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(160), dp(8));
            pl.topMargin = dp(8);
            info.addView(pb, pl);
            info.addView(text(pct >= 100 ? "Finished" : pct + "% read", 12, 0xFF444444));
        }
        r.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        r.setOnClickListener(v -> open(f));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(r);
        View div = new View(this);
        div.setBackgroundColor(0xFFBBBBBB);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, 1);
        dl.setMargins(dp(18), 0, dp(18), 0);
        wrap.addView(div, dl);
        return wrap;
    }

    // ---------------------------------------------------------------- small helpers

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    TextView button(String s, View.OnClickListener l) {
        TextView t = text(s, 15, Color.BLACK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setStroke(dp(2), Color.BLACK);
        g.setCornerRadius(dp(6));
        t.setBackground(g);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        return t;
    }

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
