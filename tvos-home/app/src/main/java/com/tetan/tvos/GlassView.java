package com.tetan.tvos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.widget.FrameLayout;

/**
 * "Liquid glass" panel. Android 9 can't blur live, so it draws the matching
 * piece of the pre-blurred wallpaper behind itself, then a light tint,
 * a thin bright edge and a highlight on top. Looks frosted, costs nothing.
 */
public class GlassView extends FrameLayout {

    private final float radius;
    private final int tint;
    private final boolean border;
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();
    private final Rect dst = new Rect();
    private final int[] loc = new int[2];

    public GlassView(Context c, float radiusPx, int tintColor, boolean withBorder) {
        super(c);
        radius = radiusPx;
        tint = tintColor;
        border = withBorder;
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, c.getResources().getDisplayMetrics().density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        box.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);

        canvas.save();
        canvas.clipPath(clip);
        if (Wallpaper.blur != null) {
            // line the blurred wallpaper up with the real one behind us
            getLocationInWindow(loc);
            int sw = getRootView().getWidth(), sh = getRootView().getHeight();
            dst.set(-loc[0], -loc[1], sw - loc[0], sh - loc[1]);
            canvas.drawBitmap(Wallpaper.blur, null, dst, blit);
        }
        fill.setColor(tint);
        canvas.drawRect(box, fill);
        canvas.restore();

        if (border) {
            float i = edge.getStrokeWidth() / 2f;
            box.inset(i, i);
            edge.setColor(0x38FFFFFF);
            canvas.drawRoundRect(box, radius, radius, edge);
            // brighter top edge, like light hitting glass
            edge.setColor(0x30FFFFFF);
            canvas.drawLine(radius, i + 1, w - radius, i + 1, edge);
        }
    }
}
