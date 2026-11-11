package com.tetan.tvos;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Random;

/**
 * Paints the night-sky-over-mountains wallpaper once (at half screen size),
 * plus a tiny pre-blurred copy that the glass panels draw behind themselves.
 * Total memory: about 2 MB.
 */
final class Wallpaper {

    static Bitmap full;   // the wallpaper
    static Bitmap blur;   // ~1/8 size, box-blurred: used for the frosted glass

    private Wallpaper() { }

    static void build(int screenW, int screenH) {
        int w = Math.max(16, screenW / 2), h = Math.max(9, screenH / 2);
        if (full != null && full.getWidth() == w && full.getHeight() == h) return;

        full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        paint(new Canvas(full), w, h);

        Bitmap small = Bitmap.createScaledBitmap(full, Math.max(4, w / 8), Math.max(4, h / 8), true);
        boxBlur(small, 2, 3);
        blur = small;
    }

    private static void paint(Canvas c, int w, int h) {
        Random r = new Random(7);
        float k = w / 1920f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        // sky
        p.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xFF04060F, 0xFF0C1331, 0xFF241F4B, 0xFF4F2F5C, 0xFF97506A, 0xFFDE8A5E},
                new float[]{0f, .38f, .58f, .70f, .77f, .82f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        // milky way
        for (int i = 0; i < 70; i++) {
            float t = r.nextFloat();
            float x = w * (.08f + .9f * t) + (r.nextFloat() - .5f) * .09f * w;
            float y = h * (-.05f + .62f * t) + (r.nextFloat() - .5f) * .09f * h;
            float rad = w * (.035f + r.nextFloat() * .075f);
            boolean warm = r.nextFloat() < .45f;
            int a = (int) (255 * (.035f + r.nextFloat() * .055f));
            int rgb = warm ? 0xFFD6BE : 0xA896FF;
            p.setShader(new RadialGradient(x, y, rad, (a << 24) | rgb, rgb, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, rad, p);
        }
        p.setShader(null);

        // stars
        p.setColor(0xFFFFFFFF);
        for (int i = 0; i < 1100; i++) star(c, p, r, r.nextFloat() * w, r.nextFloat() * h * .8f, k);
        for (int i = 0; i < 500; i++) {
            float t = r.nextFloat();
            star(c, p, r, w * (.08f + .9f * t) + (r.nextFloat() - .5f) * .12f * w,
                    h * (-.05f + .62f * t) + (r.nextFloat() - .5f) * .12f * h, k);
        }
        p.setAlpha(255);

        // warm glow on the horizon
        p.setShader(new RadialGradient(w * .62f, h * .8f, w * .55f, 0x59FF965F, 0x00FF965F, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // mountains, far to near
        ridge(c, p, r, w, h, .74f, .075f, 0xFF3A2B52, 1.0f);
        ridge(c, p, r, w, h, .83f, .08f, 0xFF1B162D, 1.3f);
        ridge(c, p, r, w, h, .93f, .06f, 0xFF07060D, 1.7f);

        // darken top (clock) and bottom (dock) a little
        p.setShader(new LinearGradient(0, 0, 0, h * .32f, 0x59000000, 0, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * .32f, p);
        p.setShader(new LinearGradient(0, h * .7f, 0, h, 0, 0x73000000, Shader.TileMode.CLAMP));
        c.drawRect(0, h * .7f, w, h, p);
        p.setShader(null);
    }

    private static void star(Canvas c, Paint p, Random r, float x, float y, float k) {
        boolean big = r.nextFloat() < .07f;
        float sz = (big ? 1.3f + r.nextFloat() * 1.3f : .5f + r.nextFloat() * .8f) * k * 1.4f;
        p.setAlpha((int) (255 * (.25f + r.nextFloat() * .75f)));
        c.drawCircle(x, y, Math.max(.5f, sz), p);
    }

    private static void ridge(Canvas c, Paint p, Random r, int w, int h, float base, float amp, int color, float f) {
        float p0 = r.nextFloat() * 6, p1 = r.nextFloat() * 6, p2 = r.nextFloat() * 6;
        Path path = new Path();
        path.moveTo(0, h);
        for (int i = 0; i <= 160; i++) {
            float u = i / 160f, x = u * w;
            double y = h * base + h * amp * (.55 * Math.sin(u * f * 3.1 + p0)
                    + .3 * Math.sin(u * f * 7.3 + p1) + .15 * Math.sin(u * f * 17.9 + p2))
                    + (r.nextFloat() - .5f) * h * amp * .12f;
            path.lineTo(x, (float) y);
        }
        path.lineTo(w, h);
        path.close();
        p.setColor(color);
        c.drawPath(path, p);
        p.setColor(0xFFFFFFFF);
    }

    /** Simple separable box blur, done a few times ≈ gaussian. Tiny bitmap, so it's instant. */
    static void boxBlur(Bitmap b, int radius, int passes) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int n = 0; n < passes; n++) {
            pass(px, tmp, w, h, radius, true);
            pass(tmp, px, w, h, radius, false);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void pass(int[] in, int[] out, int w, int h, int rad, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) {
                int r = 0, g = 0, b = 0, cnt = 0;
                for (int d = -rad; d <= rad; d++) {
                    int j = Math.max(0, Math.min(len - 1, i + d));
                    int c = horizontal ? in[l * w + j] : in[j * w + l];
                    r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; cnt++;
                }
                int v = 0xFF000000 | ((r / cnt) << 16) | ((g / cnt) << 8) | (b / cnt);
                if (horizontal) out[l * w + i] = v; else out[i * w + l] = v;
            }
        }
    }
}
