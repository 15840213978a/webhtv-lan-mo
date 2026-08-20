package com.fongmi.android.tv.ui.novel;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 一页正文（对应反编译中的 p8.d）。
 * 持有该页所有 {@link ReadLine}，并能把整页渲染成 Bitmap 缓存（避免每次 onDraw 重排）。
 */
public class ReadPage {

    public final List<ReadLine> lines = new ArrayList<>();
    public Bitmap bitmap;          // 整页位图缓存（按宽高渲染）
    public boolean dirty = true;   // 需要重建位图
    public Set<Integer> bookmarks; // 书签段落对应的全局字符下标集合

    public void addLine(ReadLine line) {
        lines.add(line);
        dirty = true;
    }

    public boolean containsChar(int charIndex) {
        for (ReadLine l : lines) {
            if (charIndex >= l.charStart && charIndex < l.charStart + l.text.length()) return true;
            if (l.isTitle && charIndex == l.charStart) return true;
        }
        return false;
    }

    /** 重建整页位图缓存。 */
    public void render(int width, int height, int bgColor, Paint titlePaint, Paint bodyPaint, Set<Integer> bookmarks) {
        this.bookmarks = bookmarks;
        if (bitmap != null && (bitmap.getWidth() != width || bitmap.getHeight() != height)) {
            bitmap.recycle();
            bitmap = null;
        }
        if (bitmap == null) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        Canvas c = new Canvas(bitmap);
        if (bgColor != 0 && (bgColor & 0xFF000000) != 0) {
            c.drawColor(bgColor, PorterDuff.Mode.SRC);
        } else {
            c.drawColor(0x00000000, PorterDuff.Mode.CLEAR);
        }
        for (ReadLine l : lines) {
            Paint p = l.isTitle ? titlePaint : bodyPaint;
            boolean marked = bookmarks != null && bookmarks.contains(l.charStart);
            if (marked) {
                // 书签高亮：标题用强调色，正文轻微底纹
                p = new Paint(p);
                p.setColor(0xFFE0A030);
                p.setTypeface(Typeface.create(p.getTypeface(), Typeface.BOLD));
            }
            c.drawText(l.text, 0, l.text.length(), 0, l.y + (-p.ascent()), p);
        }
        dirty = false;
    }

    public void recycle() {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = null;
        dirty = true;
    }
}
