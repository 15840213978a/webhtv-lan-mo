package com.fongmi.android.tv.ui.novel;

/** 单页中的一行文字（对应反编译中的 p8.c）。 */
public class ReadLine {
    public final String text;
    public final boolean isTitle;
    public final int charStart;   // 该行首字符在整章中的全局下标（书签/跳转用）
    public final float y;         // 该行基线顶部在页内的 y 坐标

    public ReadLine(String text, boolean isTitle, int charStart, float y) {
        this.text = text;
        this.isTitle = isTitle;
        this.charStart = charStart;
        this.y = y;
    }
}
