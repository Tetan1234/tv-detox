package com.tetan.tvos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/** Shows the pre-painted wallpaper, stretched to the screen. */
public class WallpaperView extends View {

    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();

    public WallpaperView(Context c) {
        super(c);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (Wallpaper.full == null) return;
        dst.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(Wallpaper.full, null, dst, blit);
    }
}
