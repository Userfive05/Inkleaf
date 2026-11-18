package com.inkleaf.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Checks GitHub Releases for a newer build and installs it. */
public class Updater {
    static final String LATEST = "https://api.github.com/repos/Userfive05/inkleaf/releases/latest";

    public interface Listener { void onResult(int build, String apkUrl); }

    /** Fetches the latest release on a background thread; calls back on the UI thread (build -1 on failure). */
    public static void latest(Activity a, Listener l) {
        new Thread(() -> {
            int build = -1;
            String url = null;
            try {
                HttpURLConnection c = open(LATEST);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                JSONObject j = new JSONObject(new String(EpubBook.readAll(c.getInputStream()), StandardCharsets.UTF_8));
                build = Integer.parseInt(j.getString("tag_name").replaceAll("\\D+", ""));
                JSONArray assets = j.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject as = assets.getJSONObject(i);
                    if (as.getString("name").endsWith(".apk")) url = as.getString("browser_download_url");
                }
            } catch (Exception e) {
                build = -1;
            }
            final int b = build;
            final String u = url;
            a.runOnUiThread(() -> l.onResult(b, u));
        }).start();
    }

    /** The Update button: check, confirm, download, install. */
    public static void checkAndOffer(Activity a) {
        Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show();
        latest(a, (build, url) -> {
            if (build < 0) {
                Toast.makeText(a, "Couldn't reach GitHub. Check Wi-Fi and try again.", Toast.LENGTH_LONG).show();
            } else if (build <= BuildConfig.VERSION_CODE || url == null) {
                Toast.makeText(a, "You're on the latest build (" + BuildConfig.VERSION_CODE + ").", Toast.LENGTH_LONG).show();
            } else {
                new AlertDialog.Builder(a)
                        .setTitle("Update available")
                        .setMessage("Build " + build + " is ready (you have build " + BuildConfig.VERSION_CODE + "). Install it now?")
                        .setPositiveButton("Install", (d, w) -> install(a, url))
                        .setNegativeButton("Later", null)
                        .show();
            }
        });
    }

    static void install(Activity a, String url) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "Allow InkLeaf to install apps, then tap Update again.", Toast.LENGTH_LONG).show();
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            return;
        }
        AlertDialog wait = new AlertDialog.Builder(a).setMessage("Downloading update…").setCancelable(false).show();
        new Thread(() -> {
            String error = null;
            try {
                PackageInstaller pi = a.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(a.getPackageName());
                int id = pi.createSession(params);
                PackageInstaller.Session s = pi.openSession(id);
                HttpURLConnection c = open(url);
                long len = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream out = s.openWrite("InkLeaf.apk", 0, len > 0 ? len : -1)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent done = PendingIntent.getBroadcast(a, id, new Intent(a, InstallReceiver.class), flags);
                s.commit(done.getIntentSender());
                s.close();
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String err = error;
            a.runOnUiThread(() -> {
                wait.dismiss();
                if (err != null) Toast.makeText(a, "Update failed: " + err, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "InkLeaf/" + BuildConfig.VERSION_CODE);
        return c;
    }
}
