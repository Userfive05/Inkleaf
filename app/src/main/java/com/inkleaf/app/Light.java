package com.inkleaf.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.WindowManager;

/**
 * Front light control. When InkLeaf is allowed to "modify system settings" it sets the
 * system brightness (which drives the Boox front light); it always sets the window
 * brightness too, so the change applies immediately.
 */
public class Light {
    public static final int MAX = 255;

    public static boolean canWriteSystem(Activity a) { return Settings.System.canWrite(a); }

    public static int get(Activity a, Prefs prefs) {
        int sys = 128;
        try { sys = Settings.System.getInt(a.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS); } catch (Exception ignored) { }
        return canWriteSystem(a) ? sys : prefs.light(sys);
    }

    public static void set(Activity a, Prefs prefs, int value) {
        value = Prefs.clamp(value, 0, MAX);
        prefs.light(value);
        if (canWriteSystem(a)) {
            try {
                Settings.System.putInt(a.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(a.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value);
            } catch (Exception ignored) { }
        }
        WindowManager.LayoutParams lp = a.getWindow().getAttributes();
        lp.screenBrightness = value == 0 ? 0f : value / (float) MAX;
        a.getWindow().setAttributes(lp);
    }

    /** Re-applies the saved level when the app can't write the system value. */
    public static void restore(Activity a, Prefs prefs) {
        if (!canWriteSystem(a)) {
            int v = prefs.light(-1);
            if (v >= 0) set(a, prefs, v);
        }
    }

    public static void requestPermission(Activity a) {
        Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + a.getPackageName()));
        try { a.startActivity(i); } catch (Exception ignored) { }
    }

    public static int percent(int value) { return Math.round(value * 100f / MAX); }
}
