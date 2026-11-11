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
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
 * tvOS-26-style home screen:
 *  - full-screen Top Shelf (tinted by the focused app's banner) with a big title
 *  - glass shelf with the 6 favourite apps
 *  - all other apps in rows below; scrolling down dims the Top Shelf
 *  - clock pill top-right; OK on it opens Control Center (ad blocker, settings, CPU/RAM)
 * Plain Android views only, so it stays light on a 2 GB Mi Box.
 */
public class HomeActivity extends Activity {

    private static final int COLS = 6;
    private static final String[] DEFAULT_FAV = {
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "org.xbmc.kodi", "com.spotify.tv.android", "com.disney.disneyplus", "com.hbo.hbonow"
    };
    private static final String AD_DNS = "dns.adguard-dns.com";

    static final class App {
        String pkg, label;
        Intent launch;
        Drawable banner, icon;
        int color;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final DecelerateInterpolator ease = new DecelerateInterpolator(2f);
    private SharedPreferences prefs;
    private float dp;
    private int screenW, screenH, tileW, tileH;

    private FrameLayout hero;
    private ImageView heroA, heroB;
    private boolean heroAOn;
    private String heroPkg = "";
    private final Map<String, Bitmap> heroCache = new HashMap<>();
    private TextView title;
    private ScrollView scroll;
    private LinearLayout favRow, grid;
    private LinearLayout pill;
    private TextView clock;
    private GlassView cc;
    private LinearLayout ccItems;
    private TextView ccDate, ccStats, adState, hidState;

    private final Map<String, App> apps = new LinkedHashMap<>();
    private final List<String> fav = new ArrayList<>();
    private boolean showHidden;
    private String focusPkg;

    private long lastIdle = -1, lastTotal = -1;
    private boolean procStatWorks = true;

    // ================================================================= setup

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        dp = dm.density;
        screenW = Math.max(dm.widthPixels, dm.heightPixels);
        screenH = Math.min(dm.widthPixels, dm.heightPixels);
        tileW = (screenW - px(88) - px(16) * (COLS - 1)) / COLS;
        tileH = Math.round(tileW * 0.59f);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        Wallpaper.build(screenW, screenH);

        FrameLayout root = new FrameLayout(this);

        // --- Top Shelf
        hero = new FrameLayout(this);
        hero.addView(new WallpaperView(this), match());
        heroA = heroImage();
        heroB = heroImage();
        hero.addView(heroA, match());
        hero.addView(heroB, match());
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x40000000, 0x00000000, 0x00000000, 0x8C000000, 0xCC000000}));
        hero.addView(shade, match());
        root.addView(hero, match());

        title = text(40, Color.WHITE, "sans-serif-black");
        title.setShadowLayer(px(16), 0, px(2), 0x73000000);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(px(620), -2, Gravity.TOP | Gravity.START);
        tl.setMargins(px(44), px(30), 0, 0);
        root.addView(title, tl);

        // --- scrolling content: spacer, glass shelf with favourites, then all other apps
        scroll = new ScrollView(this) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(0, Math.round(screenH * 0.597f), 0, screenH / 2);

        GlassView shelf = new GlassView(this, px(28), 0x24FFFFFF, true);
        shelf.setPadding(px(20), px(13), px(20), px(13));
        favRow = new LinearLayout(this);
        favRow.setClipChildren(false);
        favRow.setClipToPadding(false);
        shelf.addView(favRow, new FrameLayout.LayoutParams(-1, tileH));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.setMargins(px(24), 0, px(24), px(26));
        content.addView(shelf, sl);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(px(44), 0, px(44), 0);
        content.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, match());

        // --- clock pill (top right)
        pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(px(12), px(4), px(4), px(4));
        pill.setFocusable(true);
        pill.setFocusableInTouchMode(true);
        final GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(px(20));
        pillBg.setColor(0x33FFFFFF);
        pillBg.setStroke(Math.max(1, px(1)), 0x38FFFFFF);
        pill.setBackground(pillBg);
        clock = text(13, Color.WHITE, "sans-serif-medium");
        pill.addView(clock);
        View av = avatar(24);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(px(24), px(24));
        al.leftMargin = px(8);
        pill.addView(av, al);
        pill.setOnFocusChangeListener((v, has) -> {
            pillBg.setColor(has ? 0xEBFFFFFF : 0x33FFFFFF);
            clock.setTextColor(has ? 0xFF111111 : Color.WHITE);
            float s = has ? 1.08f : 1f;
            pill.animate().scaleX(s).scaleY(s).setDuration(150).start();
            if (has) {
                scroll.smoothScrollTo(0, 0);
                dimHero(false);
            }
        });
        pill.setOnClickListener(v -> openCC());
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        pl.setMargins(0, px(24), px(30), 0);
        root.addView(pill, pl);

        // --- Control Center
        cc = buildControlCenter();
        cc.setVisibility(View.GONE);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(px(270), -2, Gravity.TOP | Gravity.END);
        cl.setMargins(0, px(62), px(24), 0);
        root.addView(cc, cl);

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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeCC();
        scroll.smoothScrollTo(0, 0);
        View first = favRow.getChildAt(0);
        if (first != null) first.requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (cc.getVisibility() == View.VISIBLE) { closeCC(); return; }
        View first = favRow.getChildAt(0);
        if (first != null) first.requestFocus();
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    // ============================================================== app list

    private void loadApps() {
        io.execute(() -> {
            final Map<String, App> list = queryApps();
            ui.post(() -> {
                apps.clear();
                apps.putAll(list);
                loadFav();
                build();
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
            if (a.launch == null) a.launch = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(a.pkg, ai.name));
            a.launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try { a.banner = ai.loadBanner(pm); } catch (Exception ignored) { }
            if (a.banner == null) try { a.banner = ai.applicationInfo.loadBanner(pm); } catch (Exception ignored) { }
            a.icon = r.loadIcon(pm);
            a.color = averageColor(a.banner != null ? a.banner : a.icon);
            list.add(a);
        }
        Collections.sort(list, (x, y) -> x.label.compareToIgnoreCase(y.label));
        Map<String, App> out = new LinkedHashMap<>();
        for (App a : list) out.put(a.pkg, a);
        return out;
    }

    private void loadFav() {
        fav.clear();
        String saved = prefs.getString("fav", null);
        if (saved == null) {
            for (String p : DEFAULT_FAV) if (apps.containsKey(p) && fav.size() < COLS) fav.add(p);
            for (String p : apps.keySet()) {
                if (fav.size() >= COLS) break;
                if (!fav.contains(p) && !hiddenSet().contains(p)) fav.add(p);
            }
            saveFav();
        } else if (!saved.isEmpty()) {
            for (String p : saved.split(",")) if (apps.containsKey(p) && fav.size() < COLS) fav.add(p);
        }
    }

    private void saveFav() { prefs.edit().putString("fav", TextUtils.join(",", fav)).apply(); }

    private Set<String> hiddenSet() {
        return new HashSet<>(prefs.getStringSet("hidden", Collections.<String>emptySet()));
    }

    // ================================================================= build

    private void build() {
        favRow.removeAllViews();
        grid.removeAllViews();
        View restore = null;

        for (int i = 0; i < fav.size(); i++) {
            App a = apps.get(fav.get(i));
            View t = tile(a, true, -1);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH);
            if (i > 0) lp.leftMargin = px(16);
            favRow.addView(t, lp);
            if (a.pkg.equals(focusPkg)) restore = t;
        }

        Set<String> hidden = hiddenSet();
        LinearLayout row = null;
        int i = 0, r = -1;
        for (App a : apps.values()) {
            if (fav.contains(a.pkg)) continue;
            boolean hid = hidden.contains(a.pkg);
            if (hid && !showHidden) continue;
            if (i % COLS == 0) {
                r++;
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                rl.bottomMargin = px(16);
                grid.addView(row, rl);
            }
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClipChildren(false);
            View t = tile(a, false, r);
            if (hid) t.setAlpha(.4f);
            cell.addView(t, new LinearLayout.LayoutParams(tileW, tileH));
            TextView lb = text(12, Color.WHITE, "sans-serif-medium");
            lb.setText(a.label);
            lb.setSingleLine(true);
            lb.setEllipsize(TextUtils.TruncateAt.END);
            lb.setGravity(Gravity.CENTER_HORIZONTAL);
            lb.setAlpha(0f);
            lb.setShadowLayer(px(5), 0, px(1), 0x99000000);
            lb.setTag("label");
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(tileW, -2);
            ll.topMargin = px(10);
            cell.addView(lb, ll);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(tileW, -2);
            if (i % COLS != 0) cl.leftMargin = px(16);
            row.addView(cell, cl);
            if (a.pkg.equals(focusPkg)) restore = t;
            i++;
        }

        if (cc.getVisibility() != View.VISIBLE) {
            final View target = restore != null ? restore : favRow.getChildAt(0);
            if (target != null) target.post(target::requestFocus);
        }
    }

    /** One app tile. inFav = on the glass shelf; gridRow = row index below it (-1 for shelf). */
    private View tile(final App a, final boolean inFav, final int gridRow) {
        final FrameLayout t = new FrameLayout(this);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{shade(a.color, 1.18f), shade(a.color, 0.72f)});
        bg.setCornerRadius(px(15));
        t.setBackground(bg);
        t.setClipToOutline(true);
        t.setElevation(px(3));

        ImageView img = new ImageView(this);
        if (a.banner != null) {
            img.setImageDrawable(a.banner);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            t.addView(img, match());
        } else {
            img.setImageDrawable(a.icon);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int s = Math.round(tileH * 0.56f);
            t.addView(img, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }
        final View shine = new View(this);
        shine.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x47FFFFFF, 0x00FFFFFF, 0x00FFFFFF}));
        shine.setAlpha(0f);
        t.addView(shine, match());

        t.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.12f : 1f;
            t.animate().scaleX(s).scaleY(s).translationZ(has ? px(16) : 0)
                    .setDuration(200).setInterpolator(ease).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(200).start();
            ViewGroup parent = (ViewGroup) t.getParent();
            if (!inFav && parent != null) {
                View lb = parent.findViewWithTag("label");
                if (lb != null) lb.animate().alpha(has ? 1f : 0f).setDuration(200).start();
                parent.setTranslationZ(has ? px(8) : 0);
            }
            if (!has) return;
            focusPkg = a.pkg;
            if (inFav) {
                scroll.smoothScrollTo(0, 0);
                dimHero(false);
                title.setText(a.label);
                setHero(a);
            } else {
                View rowView = (View) parent.getParent();
                int y = Math.max(0, grid.getTop() + rowView.getTop() - px(200));
                scroll.smoothScrollTo(0, y);
                dimHero(true);
            }
        });
        t.setOnClickListener(v -> {
            try { startActivity(a.launch); }
            catch (Exception e) { Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show(); }
        });
        t.setOnLongClickListener(v -> { showMenu(a); return true; });
        t.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_MENU && ev.getAction() == KeyEvent.ACTION_UP) { showMenu(a); return true; }
            return false;
        });
        return t;
    }

    // ============================================================= Top Shelf

    private ImageView heroImage() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.FIT_XY);
        v.setAlpha(0f);
        return v;
    }

    /** Tints the Top Shelf with a soft, blurred blow-up of the app's banner. */
    private void setHero(App a) {
        if (a.pkg.equals(heroPkg)) return;
        heroPkg = a.pkg;
        ImageView in = heroAOn ? heroB : heroA, out = heroAOn ? heroA : heroB;
        heroAOn = !heroAOn;
        out.animate().alpha(0f).setDuration(450).start();
        if (a.banner == null) return;           // no banner: the night-sky wallpaper shows
        Bitmap bm = heroCache.get(a.pkg);
        if (bm == null) {
            bm = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            c.drawColor(0xFF05060C);
            drawCopy(a.banner, c, 48, 27);
            Wallpaper.boxBlur(bm, 2, 3);
            heroCache.put(a.pkg, bm);
        }
        in.setImageBitmap(bm);
        in.animate().alpha(.8f).setDuration(450).start();
    }

    private void dimHero(boolean dim) {
        hero.animate().alpha(dim ? .35f : 1f).setDuration(350).start();
        title.animate().alpha(dim ? 0f : 1f).setDuration(250).start();
    }

    // ======================================================== Control Center

    private GlassView buildControlCenter() {
        GlassView g = new GlassView(this, px(26), 0x2EFFFFFF, true);
        g.setPadding(px(14), px(14), px(14), px(14));
        g.setElevation(px(20));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout hd = new LinearLayout(this);
        hd.setGravity(Gravity.CENTER_VERTICAL);
        hd.setPadding(px(4), px(2), 0, px(4));
        hd.addView(avatar(34), new LinearLayout.LayoutParams(px(34), px(34)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView nm = text(15, Color.WHITE, "sans-serif-medium");
        nm.setText("Tetan");
        ccDate = text(11, 0xB3FFFFFF, "sans-serif");
        names.addView(nm);
        names.addView(ccDate);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.leftMargin = px(10);
        hd.addView(names, nl);
        col.addView(hd);

        ccItems = new LinearLayout(this);
        ccItems.setOrientation(LinearLayout.VERTICAL);
        adState = ccItem("Ad blocker", v -> toggleAdBlock());
        hidState = ccItem("Show hidden apps", v -> { showHidden = !showHidden; refreshCC(); build(); });
        ccItem("Settings", v -> open(new Intent(Settings.ACTION_SETTINGS)));
        col.addView(ccItems);

        ccStats = text(11, 0xB3FFFFFF, "sans-serif");
        ccStats.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(-2, -2);
        stl.setMargins(px(4), px(12), 0, px(2));
        col.addView(ccStats, stl);

        g.addView(col, new FrameLayout.LayoutParams(-1, -2));
        return g;
    }

    /** A Control Center row; returns the TextView on its right (On/Off or ›). */
    private TextView ccItem(String label, View.OnClickListener click) {
        final LinearLayout it = new LinearLayout(this);
        it.setGravity(Gravity.CENTER_VERTICAL);
        it.setPadding(px(14), px(11), px(14), px(11));
        it.setFocusable(true);
        it.setFocusableInTouchMode(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(16));
        bg.setColor(0x1AFFFFFF);
        it.setBackground(bg);
        final TextView l = text(13.5f, Color.WHITE, "sans-serif-medium");
        l.setText(label);
        it.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView r = text(12.5f, 0xB3FFFFFF, "sans-serif-medium");
        r.setText("›");
        it.addView(r);
        it.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xEBFFFFFF : 0x1AFFFFFF);
            l.setTextColor(has ? 0xFF111111 : Color.WHITE);
            r.setTextColor(has ? 0xFF111111 : (r.getTag() == Boolean.TRUE ? 0xFF34C759 : 0xB3FFFFFF));
            float s = has ? 1.04f : 1f;
            it.animate().scaleX(s).scaleY(s).setDuration(140).start();
        });
        it.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(8);
        ccItems.addView(it, lp);
        return r;
    }

    private void setSwitch(TextView r, boolean on) {
        r.setText(on ? "On" : "Off");
        r.setTag(on);
        if (!((View) r.getParent()).hasFocus()) r.setTextColor(on ? 0xFF34C759 : 0xB3FFFFFF);
    }

    private void refreshCC() {
        setSwitch(adState, adBlockOn());
        setSwitch(hidState, showHidden);
    }

    private void openCC() {
        refreshCC();
        updateStats();
        cc.setVisibility(View.VISIBLE);
        cc.setAlpha(0f);
        cc.setTranslationY(-px(10));
        cc.animate().alpha(1f).translationY(0).setDuration(200).start();
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        pill.setFocusable(false);
        ccItems.getChildAt(0).requestFocus();
    }

    private void closeCC() {
        if (cc.getVisibility() != View.VISIBLE) return;
        cc.animate().alpha(0f).setDuration(150).withEndAction(() -> cc.setVisibility(View.GONE)).start();
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        pill.setFocusable(true);
        pill.requestFocus();
    }

    // Ad blocker = Android 9 Private DNS pointed at AdGuard (blocks ads in every app, no RAM cost).
    // Needs WRITE_SECURE_SETTINGS, granted by install.sh over adb.
    private boolean adBlockOn() {
        String mode = Settings.Global.getString(getContentResolver(), "private_dns_mode");
        String host = Settings.Global.getString(getContentResolver(), "private_dns_specifier");
        return "hostname".equals(mode) && AD_DNS.equals(host);
    }

    /** True when a VPN (e.g. Tailscale with MagicDNS) supplies its own DNS, which overrides Private DNS. */
    private boolean vpnHasOwnDns() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue;
                android.net.LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null && !lp.getDnsServers().isEmpty()) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void toggleAdBlock() {
        boolean on = !adBlockOn();
        try {
            if (on) {
                Settings.Global.putString(getContentResolver(), "private_dns_specifier", AD_DNS);
                Settings.Global.putString(getContentResolver(), "private_dns_mode", "hostname");
            } else {
                Settings.Global.putString(getContentResolver(), "private_dns_mode", "opportunistic");
            }
            if (on && vpnHasOwnDns()) {
                Toast.makeText(this, "Ad blocker on, but a VPN (Tailscale) is handling DNS, so it's bypassed. "
                        + "Set AdGuard DNS in Tailscale, or turn off 'Use Tailscale DNS'.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, on ? "Ads blocked in all apps" : "Ad blocker off", Toast.LENGTH_SHORT).show();
            }
        } catch (SecurityException e) {
            Toast.makeText(this, "Run install.sh again to allow the ad blocker", Toast.LENGTH_LONG).show();
        }
        refreshCC();
    }

    // ============================================================ app menu

    private void showMenu(final App a) {
        final Set<String> hidden = hiddenSet();
        final boolean inFav = fav.contains(a.pkg);
        final List<String> items = new ArrayList<>();
        if (inFav) {
            items.add("Move left");
            items.add("Move right");
            items.add("Remove from top row");
        } else {
            items.add("Add to top row");
            items.add(hidden.contains(a.pkg) ? "Unhide" : "Hide");
        }
        items.add("App info");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    int i = fav.indexOf(a.pkg);
                    switch (items.get(which)) {
                        case "Move left":
                            if (i > 0) { Collections.swap(fav, i, i - 1); saveFav(); build(); }
                            break;
                        case "Move right":
                            if (i >= 0 && i < fav.size() - 1) { Collections.swap(fav, i, i + 1); saveFav(); build(); }
                            break;
                        case "Remove from top row":
                            fav.remove(a.pkg); saveFav(); build();
                            break;
                        case "Add to top row":
                            if (fav.size() >= COLS) {
                                Toast.makeText(this, "Top row is full (" + COLS + " apps)", Toast.LENGTH_SHORT).show();
                            } else { fav.add(a.pkg); saveFav(); build(); }
                            break;
                        case "Hide":
                        case "Unhide":
                            if (hidden.contains(a.pkg)) hidden.remove(a.pkg); else hidden.add(a.pkg);
                            prefs.edit().putStringSet("hidden", hidden).apply();
                            build();
                            break;
                        case "App info":
                            open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
                            break;
                    }
                })
                .show();
    }

    private void open(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { Toast.makeText(this, "Not available on this box", Toast.LENGTH_SHORT).show(); }
    }

    // ===================================================== clock + CPU/RAM

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            boolean h24 = DateFormat.is24HourFormat(HomeActivity.this);
            clock.setText(new java.text.SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
            ccDate.setText(new java.text.SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now));
            if (cc.getVisibility() == View.VISIBLE) updateStats();
            ui.postDelayed(this, 2000);
        }
    };

    private void updateStats() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        double gb = 1024.0 * 1024 * 1024;
        String line1 = cpuText();
        String temp = readTemp();
        if (temp != null) line1 += " · " + temp;
        String line2 = String.format(Locale.US, "RAM %.1f / %.1f GB used", (mi.totalMem - mi.availMem) / gb, mi.totalMem / gb);
        ccStats.setText(line1 + "\n" + line2);
        boolean low = mi.lowMemory || mi.availMem < 250L * 1024 * 1024;
        ccStats.setTextColor(low ? 0xFFFFB04A : 0xB3FFFFFF);
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
                procStatWorks = false;
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

    // =============================================================== helpers

    private View avatar(int sizeDp) {
        TextView v = text(sizeDp * 0.45f, Color.WHITE, "sans-serif-medium");
        v.setText("T");
        v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFF5A5A5, 0xFFB46BE0});
        g.setShape(GradientDrawable.OVAL);
        v.setBackground(g);
        return v;
    }

    /**
     * Draws a drawable at a given size WITHOUT touching the original: drawables are shared with
     * the tiles' ImageViews, and changing their bounds made the tiles draw tiny, glitched images.
     */
    private static void drawCopy(Drawable d, Canvas c, int w, int h) {
        Drawable.ConstantState cs = d.getConstantState();
        Drawable copy = cs != null ? cs.newDrawable().mutate() : null;
        if (copy != null) {
            copy.setBounds(0, 0, w, h);
            copy.draw(c);
        } else {                       // no copy possible: draw, then put the old size back
            android.graphics.Rect old = d.copyBounds();
            d.setBounds(0, 0, w, h);
            d.draw(c);
            d.setBounds(old);
        }
    }

    private static int averageColor(Drawable d) {
        try {
            Bitmap bm = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            drawCopy(d, new Canvas(bm), 8, 8);
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++;
            }
            bm.recycle();
            if (n == 0) return 0xFF4A525E;
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (b / n), hsv);
            hsv[1] = Math.min(hsv[1], 0.75f);
            hsv[2] = Math.max(0.35f, Math.min(hsv[2], 0.8f));
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFF4A525E;
        }
    }

    private static int shade(int c, float f) {
        int r = Math.min(255, Math.round(Color.red(c) * f));
        int g = Math.min(255, Math.round(Color.green(c) * f));
        int b = Math.min(255, Math.round(Color.blue(c) * f));
        return Color.rgb(r, g, b);
    }

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
