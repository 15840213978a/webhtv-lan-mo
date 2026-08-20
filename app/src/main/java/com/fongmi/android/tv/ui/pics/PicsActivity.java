package com.fongmi.android.tv.ui.pics;

import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 漫画阅读器（对应反编译中的 PicsActivity）。
 * 1:1 对齐影视+ 漫画：pics:// 与 manga:// 地址解析（&& 多图），
 * 翻页 / 漫画 / 画廊 / 九宫格 四模式，Glide 加载（含重试），
 * 漫画模式自动滚动（其他模式提示），章节切换，进度记忆。
 */
public class PicsActivity extends AppCompatActivity {

    public static final String EXTRA_SITE_KEY = "siteKey";
    public static final String EXTRA_FLAG = "flag";
    public static final String EXTRA_VOD_ID = "vodId";
    public static final String EXTRA_VOD_NAME = "vodName";
    public static final String EXTRA_VOD_PIC = "vodPic";
    public static final String EXTRA_CHAPTERS = "chapters";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_PAYLOAD = "payload";

    private String siteKey, flag, vodId, vodName, vodPic;
    private ArrayList<Episode> chapters;
    private int curIndex = 0;
    private int initialIndex = 0;
    private String initialPayload;

    private final PicsConfig cfg = PicsConfig.get();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private RecyclerView rv;
    private View topBar, bottomBar, panelSettings, loading;
    private TextView tvTitle, tvChapter;
    private SeekBar seekChapter, seekBrightness, seekAuto;
    private PagerSnapHelper snap;
    private List<String> images = new ArrayList<>();
    private PicsAdapter adapter;

    private boolean autoRunning = false;
    private final android.os.Handler autoHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.lang.Runnable autoTask = new java.lang.Runnable() {
        @Override
        public void run() {
            if (!autoRunning) return;
            if (cfg.mode() == PicsConfig.Mode.COMIC) {
                rv.smoothScrollBy(0, Math.max(2, cfg.getAutoSpeed() / 4));
            }
            autoHandler.postDelayed(this, 60);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pics);
        applyImmersive();
        try {
            initIntent();
            bindViews();
        } catch (Throwable e) {
            android.util.Log.e("LabPics", "init failed", e);
            Toast.makeText(this, "漫画阅读器初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (cfg.getBrightness() >= 0) setBrightness(cfg.getBrightness());
        seekBrightness.setProgress(cfg.getBrightness() < 0 ? 50 : cfg.getBrightness());
        seekAuto.setProgress(cfg.getAutoSpeed());
        applyMode();
        if (chapters == null || chapters.isEmpty()) {
            Toast.makeText(this, "该漫画暂无章节", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        safeLoad(() -> loadChapter(curIndex, false));
    }

    private void safeLoad(Runnable r) {
        try { r.run(); } catch (Throwable e) { android.util.Log.e("LabPics", "load failed", e); }
    }

    /** 全屏沉浸：隐藏状态栏与导航栏，宽度/高度满屏，粘性沉浸。 */
    private void applyImmersive() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                getWindow().setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } catch (Throwable ignore) {}
        } else {
            try {
                int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                getWindow().getDecorView().setSystemUiVisibility(flags);
            } catch (Throwable ignore) {}
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    @Override
    public void onBackPressed() {
        if (panelSettings != null && panelSettings.getVisibility() == View.VISIBLE) {
            panelSettings.setVisibility(View.GONE);
            return;
        }
        finish();
    }

    private void initIntent() {
        Bundle b = getIntent().getExtras();
        siteKey = b == null ? "" : b.getString(EXTRA_SITE_KEY, "");
        flag = b == null ? "" : b.getString(EXTRA_FLAG, "");
        vodId = b == null ? "" : b.getString(EXTRA_VOD_ID, "");
        vodName = b == null ? "" : b.getString(EXTRA_VOD_NAME, "");
        vodPic = b == null ? "" : b.getString(EXTRA_VOD_PIC, "");
        chapters = b == null ? new ArrayList<>() : b.getParcelableArrayList(EXTRA_CHAPTERS);
        if (chapters == null) chapters = new ArrayList<>();
        curIndex = b == null ? 0 : b.getInt(EXTRA_INDEX, 0);
        initialIndex = curIndex;
        initialPayload = b == null ? null : b.getString(EXTRA_PAYLOAD);
        if (hasResume()) curIndex = getResumeChapter();
    }

    private void bindViews() {
        rv = findViewById(R.id.rv_pics);
        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        panelSettings = findViewById(R.id.panel_settings);
        loading = findViewById(R.id.loading);
        tvTitle = findViewById(R.id.tv_title);
        tvChapter = findViewById(R.id.tv_chapter);
        seekChapter = findViewById(R.id.seek_chapter);
        seekBrightness = findViewById(R.id.seek_brightness);
        seekAuto = findViewById(R.id.seek_auto);
        tvTitle.setText(vodName);

        // 让漫画列表贴满（去除 RecyclerView 自身 padding/margin）
        rv.setPadding(0, 0, 0, 0);
        rv.setClipToPadding(true);
        if (rv.getItemAnimator() != null) rv.getItemAnimator().setChangeDuration(0);

        adapter = new PicsAdapter();
        rv.setAdapter(adapter);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_mode).setOnClickListener(v -> { cfg.cycleMode(); applyMode(); Toast.makeText(this, "模式：" + modeName(), Toast.LENGTH_SHORT).show(); });
        findViewById(R.id.btn_auto).setOnClickListener(v -> toggleAuto());
        findViewById(R.id.btn_settings).setOnClickListener(v -> togglePanel(panelSettings));
        findViewById(R.id.btn_settings_close).setOnClickListener(v -> panelSettings.setVisibility(View.GONE));
        findViewById(R.id.btn_prev_ch).setOnClickListener(v -> loadChapter(curIndex - 1, false));
        findViewById(R.id.btn_next_ch).setOnClickListener(v -> loadChapter(curIndex + 1, false));

        seekChapter.setMax(Math.max(0, chapters.size() - 1));
        seekChapter.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { if (u) loadChapter(p, false); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        seekBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { if (u) { cfg.setBrightness(p); setBrightness(p); cfg.save(); } }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        seekAuto.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { if (u) { cfg.setAutoSpeed(p); cfg.save(); } }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private void applyMode() {
        RecyclerView.LayoutManager lm;
        if (cfg.mode() == PicsConfig.Mode.PAGE) {
            lm = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
            if (snap == null) { snap = new PagerSnapHelper(); snap.attachToRecyclerView(rv); }
        } else if (cfg.mode() == PicsConfig.Mode.GALLERY) {
            lm = new GridLayoutManager(this, 2);
            if (snap != null) { snap.attachToRecyclerView(null); snap = null; }
        } else if (cfg.mode() == PicsConfig.Mode.GRID) {
            lm = new GridLayoutManager(this, 3);
            if (snap != null) { snap.attachToRecyclerView(null); snap = null; }
        } else { // COMIC
            lm = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
            if (snap != null) { snap.attachToRecyclerView(null); snap = null; }
        }
        rv.setLayoutManager(lm);
        adapter.notifyDataSetChanged();
    }

    private String modeName() {
        switch (cfg.mode()) {
            case PAGE: return "翻页";
            case COMIC: return "漫画";
            case GALLERY: return "画廊";
            case GRID: return "九宫格";
            default: return "";
        }
    }

    /* ---------------- 章节加载 ---------------- */

    private void loadChapter(int index, boolean restore) {
        if (index < 0 || index >= chapters.size()) return;
        curIndex = index;
        Episode ep = chapters.get(index);
        tvChapter.setText(ep.getName());
        seekChapter.setProgress(index);
        loading.setVisibility(View.VISIBLE);
        panelSettings.setVisibility(View.GONE);

        // 实验室：首章直接显示内联 payload（play_url 已是 pics:// 图片列表），避免重复网络请求
        if (initialPayload != null && index == initialIndex) {
            List<String> list = parseImages(initialPayload);
            initialPayload = null;
            loading.setVisibility(View.GONE);
            images = list;
            adapter.setImages(list);
            if (restore) restorePosition();
            saveResume(curIndex, 0);
            return;
        }

        executor.execute(() -> {
            String raw = fetchChapterUrl(ep.getUrl());
            List<String> list = parseImages(raw);
            runOnUiThread(() -> {
                loading.setVisibility(View.GONE);
                images = list;
                adapter.setImages(list);
                if (restore) restorePosition();
                saveResume(curIndex, 0);
            });
        });
    }

    private List<String> parseImages(String raw) {
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        if (raw.startsWith("pics://") || raw.startsWith("manga://")) {
            raw = raw.substring(raw.indexOf("://") + 3);
        }
        String[] arr = raw.contains("&&") ? raw.split("&&") : new String[]{raw};
        List<String> out = new ArrayList<>();
        for (String s : arr) {
            if (s == null) continue;
            s = s.trim();
            if (s.isEmpty()) continue;
            // 蜘蛛约定：URL 末尾可能带 @Referer=... 防盗链头（meizi5 等）。
            // 当前 Glide 走默认无 Referer，保留原始 Referer 串会让 Glide 拿到错误 URL。
            // 这里先剥离后缀得到纯 URL，避免 404/403；Referer 注入放到后续自定义 OkHttp interceptor。
            int refAt = s.indexOf("@Referer=");
            if (refAt > 0) s = s.substring(0, refAt);
            int uaAt = s.indexOf("@User-Agent=");
            if (uaAt > 0) s = s.substring(0, uaAt);
            // 兼容 data:image/...;base64, 与 file://
            out.add(s);
        }
        return out;
    }

    private String fetchChapterUrl(String chapterId) {
        try {
            Site site = VodConfig.get().getSite(siteKey);
            Result r;
            if (site != null && site.getType() != null && site.getType() == 3) {
                String json = site.recent().spider().playerContent(flag, chapterId, VodConfig.get().getFlags());
                r = Result.fromJson(json);
            } else {
                r = SiteApi.playerContent(siteKey, flag, chapterId);
            }
            String raw = (r.getUrl() != null) ? r.getUrl().v() : null;
            if (raw == null || raw.isEmpty()) {
                raw = notEmpty(r.getMsg()) ? r.getMsg() : "";
                if (raw.isEmpty() && r.getHeader() != null) {
                    for (String hv : r.getHeader().values()) if (notEmpty(hv)) { raw = hv; break; }
                }
            }
            if (raw.startsWith("pics://") || raw.startsWith("manga://")) {
                raw = raw.substring(raw.indexOf("://") + 3);
            }
            return raw;
        } catch (Throwable e) {
            return "";
        }
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    /* ---------------- 自动滚动 ---------------- */

    private void toggleAuto() {
        if (cfg.mode() != PicsConfig.Mode.COMIC) {
            Toast.makeText(this, "自动滚动仅漫画模式可用", Toast.LENGTH_SHORT).show();
            return;
        }
        if (autoRunning) { autoRunning = false; autoHandler.removeCallbacks(autoTask); }
        else { autoRunning = true; autoHandler.postDelayed(autoTask, 60); }
    }

    /* ---------------- 进度记忆 ---------------- */

    private static final String SP = "pics_prefs";
    private int getResumeChapter() { return getSharedPreferences(SP, 0).getInt(siteKey + "#" + vodId + "_ch", 0); }
    private boolean hasResume() { return getSharedPreferences(SP, 0).contains(siteKey + "#" + vodId + "_ch"); }
    private void saveResume(int ch, int pos) { getSharedPreferences(SP, 0).edit().putInt(siteKey + "#" + vodId + "_ch", ch).apply(); }
    private void restorePosition() { rv.scrollToPosition(0); }

    /* ---------------- 工具 ---------------- */

    private void setBrightness(int b) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : (b / 100f);
        getWindow().setAttributes(lp);
    }

    private void togglePanel(View p) {
        p.setVisibility(p.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    /* ---------------- 适配器 ---------------- */

    private class PicsAdapter extends RecyclerView.Adapter<PicsAdapter.VH> {
        private List<String> data = new ArrayList<>();
        void setImages(List<String> d) { data = d; notifyDataSetChanged(); }
        @NonNull @Override public VH onCreateViewHolder(@NonNull android.view.ViewGroup p, int t) {
            PicsConfig.Mode mode = cfg.mode();
            ImageView iv = new ImageView(p.getContext());
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            float density = p.getContext().getResources().getDisplayMetrics().density;
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup_LP_MATCH, ViewGroup_LP_WRAP);
            if (mode == PicsConfig.Mode.GALLERY || mode == PicsConfig.Mode.GRID) {
                int s = (int) (2 * density);
                lp.width = ViewGroup_LP_MATCH;
                lp.height = ViewGroup_LP_MATCH;
                lp.setMargins(s, s, s, s);
                iv.setPadding(s, s, s, s);
            } else if (mode == PicsConfig.Mode.COMIC) {
                // 漫画模式：宽度满屏、高度等比，图片之间无缝（padding=0、margin=0）
                lp.width = ViewGroup_LP_MATCH;
                lp.height = ViewGroup_LP_WRAP;
                lp.setMargins(0, 0, 0, 0);
                iv.setPadding(0, 0, 0, 0);
            } else { // PAGE 翻页
                lp.width = ViewGroup_LP_MATCH;
                lp.height = ViewGroup_LP_WRAP;
                lp.setMargins(0, 0, 0, 0);
                iv.setPadding(0, 0, 0, 0);
            }
            iv.setLayoutParams(lp);
            return new VH(iv);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int p) {
            String url = data.get(p);
            RequestOptions op = new RequestOptions()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(android.R.color.darker_gray)
                    .error(android.R.drawable.ic_menu_report_image);
            Glide.with(h.iv).load(url).apply(op).into(h.iv);
            h.iv.setOnClickListener(v -> {
                if (cfg.mode() == PicsConfig.Mode.PAGE) return;
                // 九宫格/画廊点击放大可后续扩展
            });
        }
        @Override public int getItemCount() { return data.size(); }
        class VH extends RecyclerView.ViewHolder {
            final ImageView iv;
            VH(ImageView v) { super(v); iv = v; }
        }
    }

    private static final int ViewGroup_LP_MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int ViewGroup_LP_WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

    @Override
    protected void onDestroy() {
        autoRunning = false;
        autoHandler.removeCallbacks(autoTask);
        executor.shutdownNow();
        super.onDestroy();
    }
}
