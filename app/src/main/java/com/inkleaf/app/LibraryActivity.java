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
import java.util.List;

/** List of every EPUB on the device, most recently opened first. */
public class LibraryActivity extends Activity {
    Prefs prefs;
    final List<File> books = new ArrayList<>();
    BaseAdapter adapter;
    TextView message, updateButton;
    ListView list;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = new Prefs(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(12), dp(12), dp(12));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("InkLeaf", 24, Color.BLACK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titles.addView(title);
        titles.addView(text(BuildConfig.VERSION_NAME, 13, 0xFF444444));
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(button("Refresh", v -> refresh()));
        updateButton = button("Update", v -> Updater.checkAndOffer(this));
        bar.addView(updateButton);
        root.addView(bar);

        View line = new View(this);
        line.setBackgroundColor(Color.BLACK);
        root.addView(line, new LinearLayout.LayoutParams(-1, dp(2)));

        message = text("", 17, Color.BLACK);
        message.setPadding(dp(20), dp(20), dp(20), dp(20));
        message.setOnClickListener(v -> requestAccess());
        root.addView(message);

        list = new ListView(this);
        list.setDivider(new ColorDrawable(0xFF999999));
        list.setDividerHeight(1);
        adapter = new BaseAdapter() {
            public int getCount() { return books.size(); }
            public Object getItem(int i) { return books.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View convert, ViewGroup parent) { return row(books.get(i)); }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, i, id) ->
                startActivity(new Intent(this, ReaderActivity.class).putExtra("path", books.get(i).getAbsolutePath())));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);

        // Opened from the launcher after the app was closed while reading: go straight back to that book.
        String reading = prefs.reading();
        if (saved == null && Intent.ACTION_MAIN.equals(getIntent().getAction())
                && reading != null && new File(reading).isFile()) {
            startActivity(new Intent(this, ReaderActivity.class).putExtra("path", reading));
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
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        refresh();
    }

    void refresh() {
        if (!hasAccess()) {
            books.clear();
            adapter.notifyDataSetChanged();
            message.setText("InkLeaf needs permission to find your books.\n\nTap here, then turn on \"Allow access to manage all files\".");
            message.setVisibility(View.VISIBLE);
            return;
        }
        message.setText("Looking for books…");
        message.setVisibility(View.VISIBLE);
        new Thread(() -> {
            List<File> found = new ArrayList<>();
            scan(Environment.getExternalStorageDirectory(), 0, found);
            Collections.sort(found, (a, b) -> {
                long la = prefs.lastOpened(a.getAbsolutePath()), lb = prefs.lastOpened(b.getAbsolutePath());
                if (la != lb) return Long.compare(lb, la);
                return a.getName().compareToIgnoreCase(b.getName());
            });
            runOnUiThread(() -> {
                books.clear();
                books.addAll(found);
                adapter.notifyDataSetChanged();
                if (found.isEmpty()) message.setText("No EPUB files found. Put books in the Books or Download folder and tap Refresh.");
                message.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    void scan(File dir, int depth, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth < 5 && !(depth == 0 && n.equals("Android"))) scan(f, depth + 1, out);
            } else if (n.toLowerCase().endsWith(".epub")) {
                out.add(f);
            }
        }
    }

    View row(File f) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(20), dp(14), dp(20), dp(14));
        TextView t = text(f.getName().replaceAll("(?i)\\.epub$", "").replace('_', ' '), 18, Color.BLACK);
        r.addView(t);
        int pct = prefs.percent(f.getAbsolutePath());
        String folder = f.getParentFile() == null ? "" : f.getParentFile().getName();
        r.addView(text((pct >= 0 ? pct + "% read  ·  " : "New  ·  ") + folder, 13, 0xFF444444));
        return r;
    }

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    TextView button(String s, View.OnClickListener l) {
        TextView t = text(s, 16, Color.BLACK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setStroke(dp(2), Color.BLACK);
        g.setCornerRadius(dp(6));
        t.setBackground(g);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(4), 0, dp(4), 0);
        t.setLayoutParams(lp);
        return t;
    }

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
