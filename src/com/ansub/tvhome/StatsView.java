package com.ansub.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

// A small line at the top left: CPU and RAM use, refreshed every 2 seconds while Home is showing.
final class StatsView extends TextView {
    private long lastIdle = -1, lastTotal = -1;
    private final ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            update();
            postDelayed(this, 2000);
        }
    };

    StatsView(Context c, Typeface tf, int sizePx) {
        super(c);
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        setTextColor(0xA8FFFFFF);
        if (tf != null) setTypeface(tf);
        setSingleLine(true);
        setIncludeFontPadding(false);
        update();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        removeCallbacks(tick);
        if (visibility == View.VISIBLE) {
            lastIdle = -1;
            lastTotal = -1;
            post(tick);
        }
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private void update() {
        setText("CPU " + cpu() + "   RAM " + ram());
    }

    private String ram() {
        try {
            ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
            am.getMemoryInfo(mem);
            double gb = 1024.0 * 1024.0 * 1024.0;
            return String.format(Locale.US, "%.1f/%.1f GB", (mem.totalMem - mem.availMem) / gb, mem.totalMem / gb);
        } catch (Throwable t) {
            return "n/a";
        }
    }

    // Real percentage if /proc/stat is readable, else load average, else CPU speed, else n/a.
    private String cpu() {
        long[] s = stat();
        if (s != null) {
            String out = "--";
            if (lastTotal >= 0 && s[1] > lastTotal) {
                long dt = s[1] - lastTotal, di = s[0] - lastIdle;
                out = Math.max(0, Math.min(100, Math.round(100f * (dt - di) / dt))) + "%";
            }
            lastIdle = s[0];
            lastTotal = s[1];
            return out;
        }
        String la = firstLine("/proc/loadavg");
        if (la != null && la.trim().length() > 0) return "load " + la.trim().split(" ")[0];
        String f = firstLine("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq");
        if (f != null) try { return (Long.parseLong(f.trim()) / 1000) + " MHz"; } catch (Exception ignored) {}
        return "n/a";
    }

    private static long[] stat() {
        String l = firstLine("/proc/stat");
        if (l == null || !l.startsWith("cpu ")) return null;
        String[] p = l.trim().split("\\s+");
        if (p.length < 6) return null;
        try {
            long total = 0;
            for (int i = 1; i < Math.min(p.length, 9); i++) total += Long.parseLong(p[i]);
            return new long[] {Long.parseLong(p[4]) + Long.parseLong(p[5]), total};
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (Throwable t) {
            return null;
        }
    }
}
