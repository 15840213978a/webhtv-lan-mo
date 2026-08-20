package com.fongmi.android.tv.ui.novel;

import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;

import com.fongmi.android.tv.App;

import java.util.ArrayList;
import java.util.List;

/**
 * 小说阅读器全局配置（对应反编译中的 o8.a）。
 * 负责：阅读偏好（背景/字号/行距/段距/字距/字重/字体/亮度/翻页动画/自动阅读速度）的
 * 持久化，以及把「标题 + 正文」排版成分页的 {@link ReadPage} 列表。
 */
public final class NovelConfig {

    public enum AnimMode {
        COVER,      // 仿真
        SLIDE,      // 覆盖
        TRANSLATE,  // 平移
        UPDOWN,     // 上下
        SCROLL,     // 纵向连续滚动
        NONE        // 无动画
    }

    public enum BgMode {
        WALLPAPER,  // 壁纸（透明，露出背景壁纸）
        BLACK,      // 纯黑
        WHITE,      // 纯白
        EYE,        // 护眼（淡绿）
        PAPER,      // 羊皮纸
        GRAY        // 深灰
    }

    private static final String SP_NAME = "reader_config";
    private static volatile NovelConfig sInstance;

    private final SharedPreferences sp;
    // 持久化偏好
    private int bgMode = BgMode.WALLPAPER.ordinal();
    private int fontSize = 18;            // sp
    private float lineSpacing = 1.4f;     // 行距倍数
    private int paragraphSpacing = 8;      // dp
    private float letterSpacing = 0f;     // em
    private int fontWeight = 1;            // 0 细 / 1 常规 / 2 微粗 / 3 中粗
    private String fontPath = null;       // 字体文件路径，null=系统默认
    private int brightness = -1;           // -1 = 系统
    private int animMode = AnimMode.COVER.ordinal();
    private int autoScrollSpeed = 50;     // 自动阅读速度

    // 运行时排版度量（由 ReadView 在尺寸变化时写入）
    private int viewW = 0, viewH = 0;
    private int topPad = 0, bottomPad = 0, sidePad = 24; // dp
    private int statusBarH = 0;

    // 缓存画笔
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    public static NovelConfig get() {
        if (sInstance == null) {
            synchronized (NovelConfig.class) {
                if (sInstance == null) sInstance = new NovelConfig();
            }
        }
        return sInstance;
    }

    private NovelConfig() {
        sp = App.get().getSharedPreferences(SP_NAME, 0);
        bgMode = sp.getInt("bgMode", bgMode);
        fontSize = sp.getInt("fontSize", fontSize);
        lineSpacing = sp.getFloat("lineSpacing", lineSpacing);
        paragraphSpacing = sp.getInt("paragraphSpacing", paragraphSpacing);
        letterSpacing = sp.getFloat("letterSpacing", letterSpacing);
        fontWeight = sp.getInt("fontWeight", fontWeight);
        fontPath = sp.getString("fontPath", null);
        brightness = sp.getInt("brightness", brightness);
        animMode = sp.getInt("animMode", animMode);
        autoScrollSpeed = sp.getInt("autoScrollSpeed", autoScrollSpeed);
        applyFontToPaints();
    }

    public void save() {
        sp.edit()
                .putInt("bgMode", bgMode)
                .putInt("fontSize", fontSize)
                .putFloat("lineSpacing", lineSpacing)
                .putInt("paragraphSpacing", paragraphSpacing)
                .putFloat("letterSpacing", letterSpacing)
                .putInt("fontWeight", fontWeight)
                .putString("fontPath", fontPath)
                .putInt("brightness", brightness)
                .putInt("animMode", animMode)
                .putInt("autoScrollSpeed", autoScrollSpeed)
                .apply();
    }

    /* ---------------- getter / setter ---------------- */

    public int getBgMode() { return bgMode; }
    public void setBgMode(int m) { bgMode = m; }

    public int getFontSize() { return fontSize; }
    public void setFontSize(int s) { fontSize = s; applyFontToPaints(); }

    public float getLineSpacing() { return lineSpacing; }
    public void setLineSpacing(float f) { lineSpacing = f; }

    public int getParagraphSpacing() { return paragraphSpacing; }
    public void setParagraphSpacing(int d) { paragraphSpacing = d; }

    public float getLetterSpacing() { return letterSpacing; }
    public void setLetterSpacing(float f) { letterSpacing = f; applyFontToPaints(); }

    public int getFontWeight() { return fontWeight; }
    public void setFontWeight(int w) { fontWeight = w; applyFontToPaints(); }

    public String getFontPath() { return fontPath; }
    public void setFontPath(String p) { fontPath = p; applyFontToPaints(); }

    public int getBrightness() { return brightness; }
    public void setBrightness(int b) { brightness = b; }

    public int getAnimMode() { return animMode; }
    public void setAnimMode(int m) { animMode = m; }

    public AnimMode animMode() { return AnimMode.values()[Math.max(0, Math.min(animMode, AnimMode.values().length - 1))]; }
    public BgMode bgMode() { return BgMode.values()[Math.max(0, Math.min(bgMode, BgMode.values().length - 1))]; }

    public int getAutoScrollSpeed() { return autoScrollSpeed; }
    public void setAutoScrollSpeed(int s) { autoScrollSpeed = s; }

    /* ---------------- 运行时度量 ---------------- */

    public int getViewW() { return viewW; }
    public int getViewH() { return viewH; }
    public void setMetrics(int w, int h, int statusBar, int sideDp, int topDp, int bottomDp) {
        this.viewW = w;
        this.viewH = h;
        this.statusBarH = statusBar;
        this.sidePad = sideDp;
        this.topPad = statusBar + topDp;
        this.bottomPad = bottomDp;
    }

    public int contentW() { return viewW - sidePad * 2; }
    public int contentH() { return viewH - topPad - bottomPad; }
    public int topPad() { return topPad; }
    public int bottomPad() { return bottomPad; }
    public int sidePad() { return sidePad; }

    public TextPaint getTitlePaint() { return titlePaint; }
    public TextPaint getBodyPaint() { return bodyPaint; }

    public boolean isScrollMode() { return animMode() == AnimMode.SCROLL; }

    private void applyFontToPaints() {
        Typeface tf;
        if (fontPath != null && !fontPath.isEmpty()) {
            try { tf = Typeface.createFromFile(fontPath); }
            catch (Exception e) { tf = Typeface.DEFAULT; }
        } else {
            tf = Typeface.DEFAULT;
        }
        int style = fontWeight <= 0 ? Typeface.NORMAL
                : fontWeight >= 3 ? Typeface.BOLD
                : Typeface.NORMAL;
        if (fontWeight == 2) tf = Typeface.create(tf, Typeface.BOLD); // 微粗
        Typeface titleTf = Typeface.create(tf, Typeface.BOLD);

        titlePaint.setTypeface(titleTf);
        titlePaint.setTextSize(sp2px(fontSize + 2));
        titlePaint.setLetterSpacing(letterSpacing);
        titlePaint.setColor(0xFF000000);

        bodyPaint.setTypeface(tf);
        bodyPaint.setTextSize(sp2px(fontSize));
        bodyPaint.setLetterSpacing(letterSpacing);
        bodyPaint.setColor(0xFF000000);
    }

    private int sp2px(float sp) {
        return (int) (sp * App.get().getResources().getDisplayMetrics().scaledDensity + 0.5f);
    }

    private int dp2px(float dp) {
        return (int) (dp * App.get().getResources().getDisplayMetrics().density + 0.5f);
    }

    /* ---------------- 排版分页 ---------------- */

    /**
     * 把「标题 + 正文」排版成若干页。
     * @param title   章节标题（可为空）
     * @param content 章节正文
     * @return 分页列表，每页含若干 {@link ReadLine}
     */
    public List<ReadPage> paginate(String title, String content) {
        List<ReadPage> pages = new ArrayList<>();
        if (contentW() <= 0 || contentH() <= 0) return pages;

        int lineH = (int) (bodyPaint.getFontMetrics().descent - bodyPaint.getFontMetrics().ascent) + dp2px(lineSpacing);
        int titleLineH = (int) (titlePaint.getFontMetrics().descent - titlePaint.getFontMetrics().ascent) + dp2px(4);
        int paraGap = dp2px(paragraphSpacing);

        ReadPage cur = new ReadPage();
        int y = topPad();
        int globalChar = 0;

        // 标题
        if (title != null && !title.isEmpty()) {
            for (String ln : wrap(title, titlePaint)) {
                if (y + titleLineH > topPad() + contentH()) {
                    pages.add(cur); cur = new ReadPage(); y = topPad();
                }
                cur.lines.add(new ReadLine(ln, true, globalChar, y));
                y += titleLineH;
            }
            y += paraGap;
        }

        // 正文：按段落
        String[] paras = (content == null ? "" : content).split("\n");
        for (String p : paras) {
            String para = "\u3000\u3000" + (p == null ? "" : p.trim());
            if (para.trim().isEmpty()) { y += paraGap; continue; }
            for (String ln : wrap(para, bodyPaint)) {
                if (y + lineH > topPad() + contentH()) {
                    pages.add(cur); cur = new ReadPage(); y = topPad();
                }
                cur.lines.add(new ReadLine(ln, false, globalChar, y));
                globalChar += ln.length();
                y += lineH;
            }
            y += paraGap;
        }
        if (!cur.lines.isEmpty()) pages.add(cur);
        return pages;
    }

    /** 按宽度对一行文本做字符级换行（CJK 友好）。 */
    private List<String> wrap(String text, Paint paint) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) { out.add(""); return out; }
        int max = contentW();
        int start = 0;
        while (start < text.length()) {
            int count = paint.breakText(text, start, text.length(), true, max, null);
            if (count <= 0) count = 1;
            out.add(text.substring(start, start + count));
            start += count;
        }
        return out;
    }
}
