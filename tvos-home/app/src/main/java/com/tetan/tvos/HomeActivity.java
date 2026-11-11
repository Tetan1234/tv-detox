package com.tetan.tvos;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Liquid Glass" home screen: night-sky wallpaper, big thin clock,
 * a frosted dock of favourite apps, and an All Apps glass sheet.
 * Plain Android views only, so it stays light on a 2 GB Mi Box.
 */
public class HomeActivity extends Activity {

    private static final int GRID_COLS = 7;
    private static final int DOCK_MAX = 8;
    /** Used to fill the dock the first time, if installed. */
    private static final String[] DEFAULT_DOCK = {
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "org.xbmc.kodi", "com.spotify.tv.android", "com.disney.disneyplus", "com.hbo.hbonow"
    };

    static final class App {
        String pkg, label;
        Intent launch;
        Drawable icon;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final DecelerateInterpolator ease = new DecelerateInterpolator(2f);
    private SharedPreferences prefs;
    private float dp;

    private FrameLayout home;           // everything except the All Apps sheet
    private LinearLayout dockRow;
    private GlassView dock;
    private GlassView sheet;            // All Apps
    private LinearLayout grid;
    private ScrollView gridScroll;
    private TextView stats, clock, date;

    private final Map<String, App> apps = new LinkedHashMap<>();
    private final List<String> dockPkgs = new ArrayList<>();
    private boolean showHidden;
    private String focusDock, focusGrid;

    private long lastIdle = -1, lastTotal = -1;
    private boolean procStatWorks = true;

    // ------------------------------------------------------------------ setup

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        dp = dm.density;
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        Wallpaper.build(Math.max(dm.widthPixels, dm.heightPixels), Math.min(dm.widthPixels, dm.heightPixels));

        FrameLayout root = new FrameLayout(this);
        root.addView(new WallpaperView(this), match());

        home = new FrameLayout(this);
        home.setClipChildren(false);
        root.addView(home, match());

        // top-left: clock, date, CPU/RAM
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        clock = text(66, Color.WHITE, "sans-serif-thin");
        clock.setLetterSpacing(-0.02f);
        date = text(15, 0xCCFFFFFF, "sans-serif-medium");
        stats = text(12, 0x99FFFFFF, "sans-serif-medium");
        for (TextView t : new TextView[]{clock, date, stats}) t.setShadowLayer(px(10), 0, px(2), 0x59000000);
        left.addView(clock);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.topMargin = px(8);
        left.addView(date, dl);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = px(6);
        left.addView(stats, sl);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        ll.setMargins(px(56), px(30), 0, 0);
        home.addView(left, ll);

        // top-right: All apps · Settings
        LinearLayout top = new LinearLayout(this);
        adButton = topButton(adLabel(), v -> toggleAdBlock());
        top.addView(adButton);
        top.addView(topButton("All apps", v -> openSheet()));
        top.addView(topButton("Settings", v -> open(new Intent(Settings.ACTION_SETTINGS))));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        tl.setMargins(0, px(36), px(48), 0);
        home.addView(top, tl);

        // bottom: the glass dock
        dock = new GlassView(this, px(30), 0x22FFFFFF, true);
        dock.setPadding(px(9), px(9), px(9), px(9));
        dockRow = new LinearLayout(this);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dock.addView(dockRow, new FrameLayout.LayoutParams(-2, -2));
        dock.setElevation(px(12));
        FrameLayout.LayoutParams dkl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dkl.bottomMargin = px(34);
        home.addView(dock, dkl);

        // All Apps sheet (hidden until opened)
        sheet = new GlassView(this, 0, 0x7005060E, false);
        sheet.setVisibility(View.GONE);
        TextView title = text(26, Color.WHITE, "sans-serif-light");
        title.setText("All Apps");
        FrameLayout.LayoutParams ttl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        ttl.setMargins(px(56), px(34), 0, 0);
        sheet.addView(title, ttl);
        gridScroll = new ScrollView(this);
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setClipChildren(false);
        gridScroll.setClipToPadding(false);
        gridScroll.setPadding(0, px(12), 0, px(40));
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(px(56), 0, px(56), 0);
        gridScroll.addView(grid, new ScrollView.LayoutParams(-1, -2));
        FrameLayout.LayoutParams gl = match();
        gl.topMargin = px(84);
        sheet.addView(gridScroll, gl);
        root.addView(sheet, match());

        setContentView(root);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        loadApps();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    /** HOME pressed while already home: close the sheet, back to the dock. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeSheet();
        View first = dockRow.getChildAt(0);
        if (first != null) first.requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (sheet.getVisibility() == View.VISIBLE) closeSheet();
        // otherwise: a home screen has nowhere to go back to
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    // ------------------------------------------------------------- app list

    private void loadApps() {
        io.execute(() -> {
            final Map<String, App> list = queryApps();
            ui.post(() -> {
                apps.clear();
                apps.putAll(list);
                loadDock();
                buildDock();
                if (sheet.getVisibility() == View.VISIBLE) buildGrid();
            });
        });
    }

    private Map<String, App> queryApps() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> found = new LinkedHashMap<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) found.put(r.activityInfo.packageName, r);
        Intent phone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(phone, 0)) {
            if (!found.containsKey(r.activityInfo.packageName)) found.put(r.activityInfo.packageName, r);
        }
        found.remove(getPackageName());

        List<App> list = new ArrayList<>();
        for (ResolveInfo r : found.values()) {
            ActivityInfo ai = r.activityInfo;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(r.loadLabel(pm));
            a.launch = pm.getLeanbackLaunchIntentForPackage(a.pkg);
            if (a.launch == null) a.launch = pm.getLaunchIntentForPackage(a.pkg);
            if (a.launch == null) {
                a.launch = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(a.pkg, ai.name));
            }
            a.launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            a.icon = r.loadIcon(pm);
            list.add(a);
        }
        Collections.sort(list, (x, y) -> x.label.compareToIgnoreCase(y.label));
        Map<String, App> out = new LinkedHashMap<>();
        for (App a : list) out.put(a.pkg, a);
        return out;
    }

    private void loadDock() {
        dockPkgs.clear();
        String saved = prefs.getString("dock", null);
        if (saved == null) {
            for (String p : DEFAULT_DOCK) if (apps.containsKey(p) && dockPkgs.size() < 6) dockPkgs.add(p);
            for (String p : apps.keySet()) {
                if (dockPkgs.size() >= 6) break;
                if (!dockPkgs.contains(p) && !isHidden(p)) dockPkgs.add(p);
            }
            saveDock();
        } else if (!saved.isEmpty()) {
            for (String p : saved.split(",")) if (apps.containsKey(p)) dockPkgs.add(p);
        }
    }

    private void saveDock() {
        prefs.edit().putString("dock", TextUtils.join(",", dockPkgs)).apply();
    }

    private Set<String> hiddenSet() {
        return new HashSet<>(prefs.getStringSet("hidden", Collections.<String>emptySet()));
    }

    private boolean isHidden(String pkg) { return hiddenSet().contains(pkg); }

    // ----------------------------------------------------------------- dock

    private void buildDock() {
        dockRow.removeAllViews();
        View restore = null;
        for (String p : dockPkgs) {
            App a = apps.get(p);
            if (a == null) continue;
            View item = dockItem(a);
            dockRow.addView(item, new LinearLayout.LayoutParams(px(82), -2));
            if (p.equals(focusDock)) restore = item;
        }
        dock.setVisibility(dockRow.getChildCount() == 0 ? View.INVISIBLE : View.VISIBLE);
        if (sheet.getVisibility() != View.VISIBLE) {
            final View target = restore != null ? restore : dockRow.getChildAt(0);
            if (target != null) target.post(target::requestFocus);
        }
    }

    private View dockItem(final App a) {
        final LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(0, px(10), 0, px(8));
        item.setFocusable(true);
        item.setFocusableInTouchMode(true);

        ImageView img = new ImageView(this);
        img.setImageDrawable(a.icon);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        item.addView(img, new LinearLayout.LayoutParams(px(42), px(42)));

        final TextView label = text(11, Color.WHITE, "sans-serif-medium");
        label.setText(a.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setAlpha(.72f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(76), -2);
        lp.topMargin = px(7);
        item.addView(label, lp);

        final GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(px(21));
        pill.setColor(0x3DFFFFFF);
        pill.setAlpha(0);
        item.setBackground(pill);

        item.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.1f : 1f;
            item.animate().scaleX(s).scaleY(s).setDuration(170).setInterpolator(ease).start();
            label.animate().alpha(has ? 1f : .72f).setDuration(170).start();
            pill.setAlpha(has ? 255 : 0);
            if (has) focusDock = a.pkg;
        });
        attachActions(item, a, true);
        return item;
    }

    // ------------------------------------------------------ All Apps sheet

    private void openSheet() {
        sheet.setVisibility(View.VISIBLE);
        sheet.setAlpha(0f);
        sheet.animate().alpha(1f).setDuration(200).start();
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        focusGrid = null;
        buildGrid();
        gridScroll.scrollTo(0, 0);
    }

    private void closeSheet() {
        if (sheet.getVisibility() != View.VISIBLE) return;
        sheet.animate().alpha(0f).setDuration(160).withEndAction(() -> sheet.setVisibility(View.GONE)).start();
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View back = null;
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            if (dockPkgs.size() > i && dockPkgs.get(i).equals(focusDock)) back = dockRow.getChildAt(i);
        }
        if (back == null) back = dockRow.getChildAt(0);
        if (back != null) back.requestFocus();
    }

    private void buildGrid() {
        grid.removeAllViews();
        Set<String> hidden = hiddenSet();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int gap = px(16);
        int size = Math.min(px(104), (screenW - px(112) - gap * (GRID_COLS - 1)) / GRID_COLS);

        View restore = null, first = null;
        LinearLayout row = null;
        int i = 0;
        for (App a : apps.values()) {
            boolean hid = hidden.contains(a.pkg);
            if (hid && !showHidden) continue;
            if (i % GRID_COLS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                rl.bottomMargin = gap;
                grid.addView(row, rl);
            }
            View tile = gridTile(a, hid, size);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(size, size);
            if (i % GRID_COLS != 0) cl.leftMargin = gap;
            row.addView(tile, cl);
            if (first == null) first = tile;
            if (a.pkg.equals(focusGrid)) restore = tile;
            i++;
        }
        final View target = restore != null ? restore : first;
        if (target != null) target.post(target::requestFocus);
    }

    private View gridTile(final App a, boolean hid, int size) {
        final FrameLayout tile = new FrameLayout(this);
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(18));
        bg.setColor(0x14FFFFFF);
        bg.setStroke(Math.max(1, px(1)), 0x1AFFFFFF);
        tile.setBackground(bg);
        if (hid) tile.setAlpha(.4f);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        ImageView img = new ImageView(this);
        img.setImageDrawable(a.icon);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int ic = Math.round(size * 0.46f);
        col.addView(img, new LinearLayout.LayoutParams(ic, ic));
        TextView label = text(11.5f, 0xCCFFFFFF, "sans-serif-medium");
        label.setText(a.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size - px(10), -2);
        lp.topMargin = px(9);
        col.addView(label, lp);
        tile.addView(col, new FrameLayout.LayoutParams(-1, -1));

        if (dockPkgs.contains(a.pkg)) {   // small dot = this app is in the dock
            View dot = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(0xBFFFFFFF);
            dot.setBackground(d);
            FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(px(6), px(6), Gravity.TOP | Gravity.END);
            dl.setMargins(0, px(8), px(9), 0);
            tile.addView(dot, dl);
        }

        tile.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.1f : 1f;
            tile.animate().scaleX(s).scaleY(s).translationZ(has ? px(10) : 0)
                    .setDuration(170).setInterpolator(ease).start();
            bg.setColor(has ? 0x42FFFFFF : 0x14FFFFFF);
            bg.setStroke(Math.max(1, px(1)), has ? 0x59FFFFFF : 0x1AFFFFFF);
            if (has) focusGrid = a.pkg;
        });
        attachActions(tile, a, false);
        return tile;
    }

    // --------------------------------------------- click / long-press / MENU

    private void attachActions(View v, final App a, final boolean inDockRow) {
        v.setOnClickListener(x -> {
            try {
                startActivity(a.launch);
            } catch (Exception e) {
                Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
            }
        });
        v.setOnLongClickListener(x -> { showMenu(a, inDockRow); return true; });
        v.setOnKeyListener((x, code, ev) -> {
            if (code == KeyEvent.KEYCODE_MENU && ev.getAction() == KeyEvent.ACTION_UP) {
                showMenu(a, inDockRow);
                return true;
            }
            return false;
        });
    }

    private void showMenu(final App a, final boolean inDockRow) {
        final Set<String> hidden = hiddenSet();
        final boolean inDock = dockPkgs.contains(a.pkg);
        final List<String> items = new ArrayList<>();
        if (inDockRow) {
            items.addAll(Arrays.asList("Move left", "Move right", "Remove from dock", "App info"));
        } else {
            items.add(inDock ? "Remove from dock" : "Add to dock");
            items.add(hidden.contains(a.pkg) ? "Unhide" : "Hide");
            items.add(showHidden ? "Stop showing hidden apps" : "Show hidden apps");
            items.add("App info");
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String act = items.get(which);
                    int i = dockPkgs.indexOf(a.pkg);
                    switch (act) {
                        case "Move left":
                            if (i > 0) { Collections.swap(dockPkgs, i, i - 1); saveDock(); buildDock(); }
                            break;
                        case "Move right":
                            if (i >= 0 && i < dockPkgs.size() - 1) { Collections.swap(dockPkgs, i, i + 1); saveDock(); buildDock(); }
                            break;
                        case "Remove from dock":
                            dockPkgs.remove(a.pkg);
                            if (a.pkg.equals(focusDock)) focusDock = null;
                            saveDock(); buildDock();
                            if (!inDockRow) buildGrid();
                            break;
                        case "Add to dock":
                            if (dockPkgs.size() >= DOCK_MAX) {
                                Toast.makeText(this, "Dock is full (" + DOCK_MAX + " apps)", Toast.LENGTH_SHORT).show();
                            } else {
                                dockPkgs.add(a.pkg); saveDock(); buildDock(); buildGrid();
                            }
                            break;
                        case "Hide":
                        case "Unhide":
                            if (hidden.contains(a.pkg)) hidden.remove(a.pkg); else hidden.add(a.pkg);
                            prefs.edit().putStringSet("hidden", hidden).apply();
                            buildGrid();
                            break;
                        case "Show hidden apps":
                        case "Stop showing hidden apps":
                            showHidden = !showHidden;
                            buildGrid();
                            break;
                        case "App info":
                            open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
                            break;
                    }
                })
                .show();
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "Not available on this box", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------ ad blocker (system DNS)
    // Uses Android 9's "Private DNS" with AdGuard's ad-blocking server, so ads
    // and trackers are blocked in every app, with zero RAM/CPU cost.
    // Needs WRITE_SECURE_SETTINGS, which install.sh grants over adb.

    private static final String AD_DNS = "dns.adguard-dns.com";
    private TextView adButton;

    private boolean adBlockOn() {
        String mode = Settings.Global.getString(getContentResolver(), "private_dns_mode");
        String host = Settings.Global.getString(getContentResolver(), "private_dns_specifier");
        return "hostname".equals(mode) && AD_DNS.equals(host);
    }

    private String adLabel() { return adBlockOn() ? "Ad block: On" : "Ad block: Off"; }

    private void toggleAdBlock() {
        boolean on = !adBlockOn();
        try {
            if (on) {
                Settings.Global.putString(getContentResolver(), "private_dns_specifier", AD_DNS);
                Settings.Global.putString(getContentResolver(), "private_dns_mode", "hostname");
            } else {
                Settings.Global.putString(getContentResolver(), "private_dns_mode", "opportunistic");
            }
            Toast.makeText(this, on ? "Ads blocked in all apps (AdGuard DNS)" : "Ad blocker off",
                    Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            Toast.makeText(this, "Run install.sh again to allow the ad blocker", Toast.LENGTH_LONG).show();
        }
        adButton.setText(adLabel());
    }

    private TextView topButton(String label, View.OnClickListener click) {
        final TextView t = text(14, 0xD9FFFFFF, "sans-serif-medium");
        t.setText(label);
        t.setPadding(px(15), px(7), px(15), px(7));
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setShadowLayer(px(6), 0, px(1), 0x66000000);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(18));
        bg.setColor(Color.WHITE);
        bg.setAlpha(0);
        t.setBackground(bg);
        t.setOnFocusChangeListener((v, has) -> {
            bg.setAlpha(has ? 255 : 0);
            t.setTextColor(has ? 0xFF111111 : 0xD9FFFFFF);
            t.setShadowLayer(has ? 0 : px(6), 0, px(1), 0x66000000);
            float s = has ? 1.06f : 1f;
            t.animate().scaleX(s).scaleY(s).setDuration(150).start();
        });
        t.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = px(6);
        t.setLayoutParams(lp);
        return t;
    }

    // -------------------------------------------------- clock + CPU / RAM

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            boolean h24 = DateFormat.is24HourFormat(HomeActivity.this);
            clock.setText(new java.text.SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
            date.setText(new java.text.SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now));
            updateStats();
            ui.postDelayed(this, 2000);
        }
    };

    private void updateStats() {
        StringBuilder sb = new StringBuilder();
        sb.append(cpuText());
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        double gb = 1024.0 * 1024 * 1024;
        double used = (mi.totalMem - mi.availMem) / gb;
        sb.append("  ·  RAM ").append(String.format(Locale.US, "%.1f / %.1f GB", used, mi.totalMem / gb));
        String temp = readTemp();
        if (temp != null) sb.append("  ·  ").append(temp);
        stats.setText(sb);
        boolean low = mi.lowMemory || mi.availMem < 250L * 1024 * 1024;
        stats.setTextColor(low ? 0xFFFFB04A : 0x99FFFFFF);
    }

    private String cpuText() {
        if (procStatWorks) {
            try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
                String[] p = r.readLine().trim().split("\\s+");
                long total = 0;
                for (int i = 1; i < Math.min(p.length, 9); i++) total += Long.parseLong(p[i]);
                long idle = Long.parseLong(p[4]) + Long.parseLong(p[5]);
                String out = "CPU …";
                if (lastTotal >= 0 && total > lastTotal) {
                    long pct = Math.round(100.0 * (1.0 - (idle - lastIdle) / (double) (total - lastTotal)));
                    out = "CPU " + Math.max(0, Math.min(100, pct)) + "%";
                }
                lastTotal = total;
                lastIdle = idle;
                return out;
            } catch (Exception e) {
                procStatWorks = false; // blocked on Android 8+, fall back to clock speed
            }
        }
        long khz = readLong("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq");
        if (khz > 0) return String.format(Locale.US, "CPU %.2f GHz", khz / 1_000_000.0);
        return "CPU";
    }

    private String readTemp() {
        long t = readLong("/sys/class/thermal/thermal_zone0/temp");
        if (t <= 0) return null;
        if (t > 1000) t /= 1000;
        return t + "°C";
    }

    private static long readLong(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return Long.parseLong(r.readLine().trim());
        } catch (Exception e) {
            return -1;
        }
    }

    // --------------------------------------------------------------- helpers

    private TextView text(float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    private int px(float v) { return Math.round(v * dp); }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
