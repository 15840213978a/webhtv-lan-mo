package com.fongmi.android.tv.ui.novel;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 小说自绘阅读 View（对应反编译中的 ReadView / 排版分页引擎）。
 *
 * 功能：
 *  - 用 {@link NovelConfig#paginate} 把「标题+正文」排版成 Bitmap 缓存的页 {@link ReadPage}
 *  - 4 种翻页动画（仿真/覆盖/平移/上下）+ 纵向连续滚动模式 + 无动画
 *  - 手势：点击左/右 1/3 翻页，点击中部切换工具栏，左右/上下拖动翻页，快速滑动 fling 翻页
 *  - 自动阅读：定时翻页（分页模式）或匀速滚动（滚动模式），顶部进度条由 Activity 承载
 *  - 长按段落加/去书签（回调 {@link OnBookmarkListener}）
 */
public class ReadView extends View {

    public interface OnPageChangeListener {
        void onPageChange(int page, int total);
    }

    public interface OnMenuToggleListener {
        void onMenuToggle();
    }

    public interface OnBookmarkListener {
        /** @param charIndex 书签命中的整章全局字符下标（用于续读/定位） */
        void onBookmarkToggle(int charIndex);
    }

    private final NovelConfig cfg = NovelConfig.get();
    private final List<ReadPage> pages = new ArrayList<>();
    private String curTitle = "";
    private String curContent = "";
    private int curPage = 0;
    private boolean loaded = false;

    private Set<Integer> bookmarks = new HashSet<>();

    private OnPageChangeListener pageListener;
    private OnMenuToggleListener menuListener;
    private OnBookmarkListener bookmarkListener;

    /* 拖动 / 动画状态 */
    private int downX, downY, lastX, lastY;
    private long downTime;
    private boolean dragging = false;
    private boolean longPressed = false;
    private boolean horizontal = true;       // 当前动画轴：翻页横向 / 上下纵向
    private final android.os.Handler lpHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.lang.Runnable lpTask = new java.lang.Runnable() {
        @Override
        public void run() {
            if (!dragging && !loaded) return;
            longPressed = true;
            if (bookmarkListener != null) bookmarkListener.onBookmarkToggle(charIndexAt(downX, downY));
            performHaptic();
        }
    };
    private float dragOffset = 0;            // 拖动位移（px，正=向「上一页」方向）
    private int animDir = 0;                 // +1 前进 / -1 后退（用于动画兜底方向）
    private ValueAnimator pageAnim;
    private boolean animating = false;

    /* 滚动模式 */
    private float scrollY = 0;               // 纵向滚动偏移
    private final android.widget.Scroller scroller;
    private VelocityTracker vTracker;

    /* 自动阅读 */
    private final java.lang.Runnable autoTask = new java.lang.Runnable() {
        @Override
        public void run() {
            if (!autoRunning) return;
            if (cfg.isScrollMode()) {
                scrollY += autoStep();
                clampScroll();
                invalidate();
            } else {
                if (curPage < pages.size() - 1) setPage(curPage + 1);
                else if (autoNextChapter != null) autoNextChapter.run();
            }
            autoHandler.postDelayed(this, autoInterval());
        }
    };
    private final android.os.Handler autoHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean autoRunning = false;
    private java.lang.Runnable autoNextChapter;

    public ReadView(Context context) {
        super(context);
        scroller = new android.widget.Scroller(context, new DecelerateInterpolator());
        setBackgroundColor(0x00000000);
    }

    /* ---------------- 对外回调 ---------------- */

    public void setOnPageChangeListener(OnPageChangeListener l) { this.pageListener = l; }
    public void setOnMenuToggleListener(OnMenuToggleListener l) { this.menuListener = l; }
    public void setOnBookmarkListener(OnBookmarkListener l) { this.bookmarkListener = l; }
    public void setOnAutoNextChapter(java.lang.Runnable r) { this.autoNextChapter = r; }

    public void setBookmarks(Set<Integer> set) {
        this.bookmarks = set == null ? new HashSet<>() : set;
        for (ReadPage p : pages) p.dirty = true;
        invalidate();
    }

    /* ---------------- 内容 / 排版 ---------------- */

    /** 设置章节内容并重新分页（尺寸就绪后调用）。 */
    public void setContent(String title, String content) {
        this.curTitle = title == null ? "" : title;
        this.curContent = content == null ? "" : content;
        repaginate(0);
    }

    /** 重新分页，尽量保持当前阅读位置（按全局字符下标）。 */
    public void relayout() {
        int charPos = currentChar();
        repaginate(charPos);
    }

    private void repaginate(int keepChar) {
        stopAuto();
        List<ReadPage> old = pages;
        for (ReadPage p : old) p.recycle();
        pages.clear();
        pages.addAll(cfg.paginate(curTitle, curContent));
        loaded = !pages.isEmpty();
        curPage = Math.max(0, pageIndexForChar(keepChar));
        scrollY = curPage * pageHeight();
        vTracker = null;
        invalidate();
        notifyPage();
    }

    /** 当前页首字符的全局下标（续读锚点）。 */
    public int currentChar() {
        if (pages.isEmpty() || curPage >= pages.size()) return 0;
        List<ReadLine> ls = pages.get(curPage).lines;
        return ls.isEmpty() ? 0 : ls.get(0).charStart;
    }

    /** 跳转到包含指定全局字符下标的页。 */
    public void goToChar(int charIndex) {
        curPage = Math.max(0, Math.min(pages.size() - 1, pageIndexForChar(charIndex)));
        scrollY = curPage * pageHeight();
        invalidate();
        notifyPage();
    }

    private int pageIndexForChar(int charIndex) {
        for (int i = 0; i < pages.size(); i++) {
            for (ReadLine l : pages.get(i).lines) {
                if (charIndex <= l.charStart) return i;
            }
        }
        return pages.size() - 1;
    }

    public int getCurPage() { return curPage; }
    public int getTotalPage() { return pages.size(); }
    public boolean isLoaded() { return loaded; }

    /** 当前页纯文本（TTS 朗读用）。 */
    public String getCurrentPageText() {
        if (pages.isEmpty() || curPage >= pages.size()) return "";
        StringBuilder sb = new StringBuilder();
        for (ReadLine l : pages.get(curPage).lines) {
            if (l.isTitle) continue;
            sb.append(l.text).append("\n");
        }
        return sb.toString();
    }

    public int getChapterCharCount() {
        return curContent == null ? 0 : curContent.length();
    }

    /* ---------------- 翻页 ---------------- */

    public void nextPage() { setPage(curPage + 1); }
    public void prevPage() { setPage(curPage - 1); }

    public void setPage(int page) {
        if (pages.isEmpty()) return;
        page = Math.max(0, Math.min(pages.size() - 1, page));
        if (page == curPage) { invalidate(); return; }
        curPage = page;
        scrollY = curPage * pageHeight();
        invalidate();
        notifyPage();
    }

    private void notifyPage() {
        if (pageListener != null) pageListener.onPageChange(curPage, pages.size());
    }

    /* ---------------- 度量 ---------------- */

    private int pageHeight() {
        return Math.max(1, cfg.contentH());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && loaded) relayout();
    }

    /* ---------------- 绘制 ---------------- */

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!loaded || pages.isEmpty()) return;

        if (cfg.isScrollMode()) {
            drawScroll(canvas);
            return;
        }

        ensurePageBitmap(pages.get(curPage));

        if (animating || dragging) {
            drawTransition(canvas);
        } else {
            drawPageAt(canvas, curPage, 0, 0, 1f);
        }
    }

    private void ensurePageBitmap(ReadPage p) {
        if (p.bitmap == null || p.dirty) {
            int bg = bgColor();
            p.render(getWidth(), getHeight(), bg, cfg.getTitlePaint(), cfg.getBodyPaint(), bookmarks);
        }
    }

    private int bgColor() {
        switch (cfg.bgMode()) {
            case BLACK: return 0xFF111111;
            case WHITE: return 0xFFF5F5F0;
            case EYE:   return 0xFFC7EDCC;
            case PAPER: return 0xFFE9D9B8;
            case GRAY:  return 0xFF2B2B2B;
            case WALLPAPER:
            default:    return 0x00000000; // 透明，露出 Activity 壁纸
        }
    }

    /** 把第 page 页画到 (offsetX, offsetY) 处，scale 仅用于留扩展。 */
    private void drawPageAt(Canvas canvas, int page, float offsetX, float offsetY, float alpha) {
        if (page < 0 || page >= pages.size()) return;
        ReadPage p = pages.get(page);
        ensurePageBitmap(p);
        if (p.bitmap == null) return;
        int save = canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.drawBitmap(p.bitmap, 0, 0, null);
        canvas.restoreToCount(save);
    }

    /** 翻页过渡：根据动画模式把当前页与目标页按位移绘制。 */
    private void drawTransition(Canvas canvas) {
        boolean horiz = horizontal;
        float offset = dragging ? dragOffset : animOffset;
        int w = getWidth(), h = getHeight();
        int target = curPage + (offset > 0 ? -1 : 1); // offset>0 表示向左拖（去看上一页）
        NovelConfig.AnimMode mode = cfg.animMode();

        if (mode == NovelConfig.AnimMode.NONE) {
            drawPageAt(canvas, target, 0, 0, 1f);
            return;
        }

        if (mode == NovelConfig.AnimMode.TRANSLATE) {
            // 双页同时平移：当前页移出，目标页补入
            if (horiz) {
                drawPageAt(canvas, curPage, -offset, 0, 1f);
                drawPageAt(canvas, target, (offset > 0 ? -w : w) - offset, 0, 1f);
            } else {
                drawPageAt(canvas, curPage, 0, -offset, 1f);
                drawPageAt(canvas, target, 0, (offset > 0 ? -h : h) - offset, 1f);
            }
        } else if (mode == NovelConfig.AnimMode.SLIDE || mode == NovelConfig.AnimMode.COVER) {
            // 覆盖：当前页不动，目标页从一侧滑入
            if (horiz) {
                float from = offset > 0 ? -w : w;
                drawPageAt(canvas, curPage, 0, 0, 1f);
                drawPageAt(canvas, target, from - offset, 0, 1f);
                if (mode == NovelConfig.AnimMode.COVER) drawEdgeShadow(canvas, target, from - offset, true);
            } else {
                float from = offset > 0 ? -h : h;
                drawPageAt(canvas, curPage, 0, 0, 1f);
                drawPageAt(canvas, target, 0, from - offset, 1f);
            }
        } else {
            // 默认兜底：覆盖式
            if (horiz) drawPageAt(canvas, target, (offset > 0 ? -w : w) - offset, 0, 1f);
            else drawPageAt(canvas, target, 0, (offset > 0 ? -h : h) - offset, 1f);
        }
    }

    private void drawEdgeShadow(Canvas canvas, int page, float edgeX, boolean horiz) {
        ReadPage p = pages.get(page);
        if (p.bitmap == null) return;
        int w = getWidth(), h = getHeight();
        Paint shadow = new Paint();
        if (horiz) {
            int x = (int) (edgeX + (edgeX < 0 ? w : 0));
            LinearGradient g = new LinearGradient(x, 0, x + (edgeX < 0 ? -40 : 40), 0,
                    0x00000000, 0x33000000, Shader.TileMode.CLAMP);
            shadow.setShader(g);
            canvas.drawRect(edgeX < 0 ? edgeX : edgeX - 40, 0, edgeX < 0 ? edgeX + 40 : edgeX, h, shadow);
        }
    }

    /* 纵向连续滚动绘制 */
    private void drawScroll(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        int ph = pageHeight();
        int first = (int) (scrollY / ph);
        int last = (int) ((scrollY + h) / ph) + 1;
        for (int i = Math.max(0, first); i <= Math.min(pages.size() - 1, last); i++) {
            ReadPage p = pages.get(i);
            ensurePageBitmap(p);
            if (p.bitmap == null) continue;
            int top = (int) (i * ph - scrollY);
            canvas.drawBitmap(p.bitmap, 0, top, null);
        }
    }

    /* ---------------- 触摸手势 ---------------- */

    private float animOffset = 0;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!loaded) return super.onTouchEvent(e);
        if (cfg.isScrollMode()) return onTouchScroll(e);
        return onTouchPage(e);
    }

    private boolean onTouchPage(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                cancelAnim();
                downX = lastX = (int) e.getX();
                downY = lastY = (int) e.getY();
                downTime = System.currentTimeMillis();
                dragging = false;
                longPressed = false;
                animDir = 0;
                if (vTracker == null) vTracker = VelocityTracker.obtain();
                else vTracker.clear();
                vTracker.addMovement(e);
                lpHandler.removeCallbacks(lpTask);
                lpHandler.postDelayed(lpTask, 450);
                return true;
            case MotionEvent.ACTION_MOVE:
                vTracker.addMovement(e);
                int dx = (int) (e.getX() - lastX);
                int dy = (int) (e.getY() - lastY);
                if (!dragging && Math.abs(e.getX() - downX) + Math.abs(e.getY() - downY) > dp2px(8)) {
                    dragging = true;
                    longPressed = false;
                    lpHandler.removeCallbacks(lpTask);
                    horizontal = Math.abs(e.getX() - downX) >= Math.abs(e.getY() - downY);
                }
                if (dragging) {
                    int delta = horizontal ? (int) (e.getX() - downX) : (int) (e.getY() - downY);
                    dragOffset = delta;
                    invalidate();
                }
                lastX = (int) e.getX();
                lastY = (int) e.getY();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lpHandler.removeCallbacks(lpTask);
                if (longPressed) {
                    longPressed = false;
                    dragging = false;
                    dragOffset = 0;
                    return true;
                }
                if (dragging) {
                    int delta = horizontal ? (int) (e.getX() - downX) : (int) (e.getY() - downY);
                    vTracker.addMovement(e);
                    vTracker.computeCurrentVelocity(1000);
                    float vel = horizontal ? vTracker.getXVelocity() : vTracker.getYVelocity();
                    finishDrag(delta, vel);
                } else {
                    handleTap((int) e.getX(), (int) e.getY());
                }
                dragging = false;
                dragOffset = 0;
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void handleTap(int x, int y) {
        int w = getWidth();
        if (x < w / 3f) {
            prevPage();
        } else if (x > w * 2f / 3f) {
            nextPage();
        } else {
            if (menuListener != null) menuListener.onMenuToggle();
        }
    }

    private void finishDrag(int delta, float vel) {
        int w = getWidth(), h = getHeight();
        boolean goNext = false;
        if (horizontal) {
            goNext = delta < -w / 4 || vel < -800;       // 向左拖 = 下一页
            if (!goNext && (delta > w / 4 || vel > 800)) goNext = false; // 向右拖 = 上一页
        } else {
            goNext = delta < -h / 4 || vel < -800;
        }
        boolean goPrev = !goNext && (Math.abs(delta) > (horizontal ? w : h) / 4 || Math.abs(vel) > 800);

        int from = delta;
        int to;
        if (goNext && curPage < pages.size() - 1) {
            to = horizontal ? -w : -h;
            startPageAnim(from, to, +1);
        } else if (goPrev && curPage > 0) {
            to = horizontal ? w : h;
            startPageAnim(from, to, -1);
        } else {
            // 回弹
            startPageAnim(from, 0, 0);
        }
    }

    private void startPageAnim(int from, int to, int dir) {
        animDir = dir;
        animating = true;
        if (pageAnim != null) pageAnim.cancel();
        pageAnim = ValueAnimator.ofInt(from, to);
        pageAnim.setDuration(280);
        pageAnim.setInterpolator(new DecelerateInterpolator());
        pageAnim.addUpdateListener(a -> {
            animOffset = (int) a.getAnimatedValue();
            dragOffset = animOffset;
            invalidate();
        });
        pageAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                animating = false;
                animOffset = 0;
                dragOffset = 0;
                if (dir > 0) curPage = Math.min(pages.size() - 1, curPage + 1);
                else if (dir < 0) curPage = Math.max(0, curPage - 1);
                invalidate();
                notifyPage();
            }
        });
        pageAnim.start();
    }

    private void cancelAnim() {
        if (pageAnim != null && pageAnim.isRunning()) pageAnim.cancel();
        animating = false;
        animOffset = 0;
        dragOffset = 0;
    }

    /* 滚动模式触摸 */
    private boolean onTouchScroll(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                stopAuto();
                downY = lastY = (int) e.getY();
                downX = (int) e.getX();
                downTime = System.currentTimeMillis();
                dragging = false;
                if (vTracker == null) vTracker = VelocityTracker.obtain();
                else vTracker.clear();
                vTracker.addMovement(e);
                return true;
            case MotionEvent.ACTION_MOVE:
                vTracker.addMovement(e);
                int dy = (int) (e.getY() - lastY);
                if (!dragging && Math.abs(e.getY() - downY) > dp2px(8)) dragging = true;
                if (dragging) {
                    scrollY -= dy;
                    clampScroll();
                    invalidate();
                }
                lastY = (int) e.getY();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    vTracker.addMovement(e);
                    vTracker.computeCurrentVelocity(1000);
                    scroller.fling(0, (int) scrollY, 0, -(int) vTracker.getYVelocity(),
                            0, 0, 0, maxScroll());
                    invalidate();
                } else {
                    handleTap(downX, downY);
                }
                dragging = false;
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.getCurrY();
            clampScroll();
            invalidate();
        }
    }

    private int maxScroll() {
        return Math.max(0, pages.size() * pageHeight() - getHeight());
    }

    private void clampScroll() {
        if (scrollY < 0) scrollY = 0;
        if (scrollY > maxScroll()) scrollY = maxScroll();
        curPage = Math.max(0, Math.min(pages.size() - 1, (int) (scrollY / pageHeight())));
    }

    public float getScrollProgress() {
        if (maxScroll() <= 0) return 0;
        return scrollY / maxScroll();
    }

    /* ---------------- 自动阅读 ---------------- */

    private int autoInterval() { return Math.max(400, 2600 - cfg.getAutoScrollSpeed() * 20); }
    private int autoStep() { return Math.max(1, cfg.getAutoScrollSpeed() / 5); }

    public void startAuto() {
        if (autoRunning || !loaded) return;
        autoRunning = true;
        autoHandler.postDelayed(autoTask, autoInterval());
    }

    public void stopAuto() {
        autoRunning = false;
        autoHandler.removeCallbacks(autoTask);
    }

    public boolean isAutoRunning() { return autoRunning; }

    /* ---------------- 工具 ---------------- */

    private int dp2px(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 把触摸坐标映射到整章全局字符下标（书签定位用）。 */
    private int charIndexAt(int x, int y) {
        if (pages.isEmpty()) return 0;
        int page;
        int localY;
        if (cfg.isScrollMode()) {
            page = (int) ((scrollY + y) / pageHeight());
            localY = (int) ((scrollY + y) % pageHeight()) - cfg.topPad();
        } else {
            page = curPage;
            localY = y - cfg.topPad();
        }
        if (page < 0 || page >= pages.size()) return 0;
        int best = -1, bestDist = Integer.MAX_VALUE;
        for (ReadLine l : pages.get(page).lines) {
            if (l.isTitle) continue;
            int d = Math.abs((int) l.y - localY);
            if (d < bestDist) { bestDist = d; best = l.charStart; }
        }
        return best < 0 ? 0 : best;
    }

    private void performHaptic() {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(20);
        } catch (Throwable ignore) {}
    }
}
