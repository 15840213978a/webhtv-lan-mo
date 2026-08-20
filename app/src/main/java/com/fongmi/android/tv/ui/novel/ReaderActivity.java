package com.fongmi.android.tv.ui.novel;

import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 小说阅读器（对应反编译中的 ReaderActivity）。
 * 1:1 对齐影视+ 小说阅读：自绘分页、精确进度、4 种翻页动画 + 纵向滚动、阅读设置、
 * 章节毛玻璃面板（目录/书签/详情）、TTS 朗读（系统/腾讯/微软）、自动阅读、
 * 续读（退出记忆，再进弹「继续/从头」）、收藏。
 */
public class ReaderActivity extends AppCompatActivity implements
        ReadView.OnPageChangeListener, ReadView.OnMenuToggleListener, ReadView.OnBookmarkListener {

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

    private final NovelConfig cfg = NovelConfig.get();
    private NovelPrefs prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ReadView readView;
    private View topBar, bottomBar, panelSettings, panelTts, panelAuto, panelChapter, loading, progressAuto;
    private TextView tvTitle, tvChapter, tvPage;
    private SeekBar seekPage, seekBrightness, seekFont, seekPara, seekLine, seekLetter, seekTtsSpeed, seekAutoSpeed;
    private ImageView btnKeep, btnTts;
    private RadioGroup rgWeight, rgTtsSource;
    private RecyclerView rvChapter, rvBookmark;
    private LinearLayout layoutDetail;
    private TextView tabDirectory, tabBookmark, tabDetail;

    private boolean uiVisible = true;
    private boolean keepOn = false;

    /* TTS */
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsPlaying = false;
    private int ttsSource = 0; // 0 系统 / 1 腾讯 / 2 微软

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);
        applyImmersive();
        try {
            initIntent();
            bindViews();
        } catch (Throwable e) {
            android.util.Log.e("LabReader", "init failed", e);
            Toast.makeText(this, "阅读器初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        initConfigFromPrefsSafe();
        // TTS/ReadView/Panels 失败不影响基本章节加载（最坏降级为纯文本）
        safeRun("initTts", this::initTts);
        safeRun("setupReadView", this::setupReadView);
        safeRun("setupPanels", this::setupPanels);
        safeRun("loadChapter", () -> loadChapter(curIndex, prefs != null && prefs.hasResume() ? -1 : 0, true));
    }

    private void safeRun(String tag, Runnable r) {
        try { r.run(); } catch (Throwable e) { android.util.Log.e("LabReader", tag + " failed", e); }
    }

    private void initConfigFromPrefsSafe() {
        try { initConfigFromPrefs(); } catch (Throwable e) { android.util.Log.e("LabReader", "initConfigFromPrefs failed", e); }
    }

    /** 全屏沉浸：隐藏状态栏与导航栏，宽度/高度满屏，粘性沉浸让滑出后自动隐藏。 */
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
        // 章节面板打开时先关面板
        if (panelChapter != null && panelChapter.getVisibility() == View.VISIBLE) {
            hidePanels();
            return;
        }
        if (panelSettings != null && panelSettings.getVisibility() == View.VISIBLE) {
            hidePanels();
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
        prefs = new NovelPrefs(siteKey, vodId);
    }

    private void bindViews() {
        readView = findViewById(R.id.read_view);
        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        panelSettings = findViewById(R.id.panel_settings);
        panelTts = findViewById(R.id.panel_tts);
        panelAuto = findViewById(R.id.panel_auto);
        panelChapter = findViewById(R.id.panel_chapter);
        loading = findViewById(R.id.loading);
        progressAuto = findViewById(R.id.progress_auto);
        tvTitle = findViewById(R.id.tv_title);
        tvChapter = findViewById(R.id.tv_chapter);
        tvPage = findViewById(R.id.tv_page);
        seekPage = findViewById(R.id.seek_page);
        seekBrightness = findViewById(R.id.seek_brightness);
        seekFont = findViewById(R.id.seek_font);
        seekPara = findViewById(R.id.seek_para);
        seekLine = findViewById(R.id.seek_line);
        seekLetter = findViewById(R.id.seek_letter);
        seekTtsSpeed = findViewById(R.id.seek_tts_speed);
        seekAutoSpeed = findViewById(R.id.seek_auto_speed);
        btnKeep = findViewById(R.id.btn_keep);
        btnTts = findViewById(R.id.btn_tts);
        rgWeight = findViewById(R.id.rg_weight);
        rgTtsSource = findViewById(R.id.rg_tts_source);
        rvChapter = findViewById(R.id.rv_chapter);
        rvBookmark = findViewById(R.id.rv_bookmark);
        layoutDetail = findViewById(R.id.layout_detail);
        tabDirectory = findViewById(R.id.tab_directory);
        tabBookmark = findViewById(R.id.tab_bookmark);
        tabDetail = findViewById(R.id.tab_detail);

        rvChapter.setLayoutManager(new LinearLayoutManager(this));
        rvBookmark.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_chapter).setOnClickListener(v -> togglePanel(panelChapter));
        findViewById(R.id.btn_bg).setOnClickListener(v -> cycleBg());
        findViewById(R.id.btn_anim).setOnClickListener(v -> cycleAnim());
        findViewById(R.id.btn_settings).setOnClickListener(v -> togglePanel(panelSettings));
        findViewById(R.id.btn_settings_close).setOnClickListener(v -> hidePanels());
        findViewById(R.id.btn_tts_close).setOnClickListener(v -> hidePanels());
        findViewById(R.id.btn_auto_close).setOnClickListener(v -> stopAuto());
        findViewById(R.id.btn_chapter_close).setOnClickListener(v -> hidePanels());
        findViewById(R.id.btn_prev_page).setOnClickListener(v -> readView.prevPage());
        findViewById(R.id.btn_next_page).setOnClickListener(v -> readView.nextPage());
        btnKeep.setOnClickListener(v -> toggleKeep());
        btnTts.setOnClickListener(v -> togglePanel(panelTts));
        findViewById(R.id.btn_tts_play).setOnClickListener(v -> toggleTts());
        findViewById(R.id.btn_tts_prev).setOnClickListener(v -> { readView.prevPage(); restartTts(); });
        findViewById(R.id.btn_tts_next).setOnClickListener(v -> { readView.nextPage(); restartTts(); });
        findViewById(R.id.btn_auto).setOnClickListener(v -> toggleAuto());

        tabDirectory.setOnClickListener(v -> showChapterTab(0));
        tabBookmark.setOnClickListener(v -> showChapterTab(1));
        tabDetail.setOnClickListener(v -> showChapterTab(2));
    }

    private void initConfigFromPrefs() {
        // 亮度
        if (cfg.getBrightness() >= 0) setBrightness(cfg.getBrightness());
        seekBrightness.setProgress(cfg.getBrightness() < 0 ? 50 : cfg.getBrightness());
        seekFont.setProgress(cfg.getFontSize());
        seekPara.setProgress(cfg.getParagraphSpacing());
        seekLine.setProgress((int) (cfg.getLineSpacing() * 10));
        seekLetter.setProgress((int) (cfg.getLetterSpacing() * 100));
        seekAutoSpeed.setProgress(cfg.getAutoScrollSpeed());
        seekTtsSpeed.setProgress(50);
        rgWeight.check(rgWeight.getChildAt(cfg.getFontWeight()).getId());
        rgTtsSource.check(rgTtsSource.getChildAt(ttsSource).getId());

        seekBrightness.setOnSeekBarChangeListener(new SimpleSeek((p, fromUser) -> {
            if (fromUser) { cfg.setBrightness(p); setBrightness(p); cfg.save(); }
        }));
        seekFont.setOnSeekBarChangeListener(new SimpleSeek((p, fromUser) -> {
            if (fromUser) { cfg.setFontSize(p); relayout(); cfg.save(); }
        }));
        seekPara.setOnSeekBarChangeListener(new SimpleSeek((p, fromUser) -> {
            if (fromUser) { cfg.setParagraphSpacing(p); relayout(); cfg.save(); }
        }));
        seekLine.setOnSeekBarChangeListener(new SimpleSeek((p, fromUser) -> {
            if (fromUser) { cfg.setLineSpacing(p / 10f); relayout(); cfg.save(); }
        }));
        seekLetter.setOnSeekBarChangeListener(new SimpleSeek((p, fromUser) -> {
            if (fromUser) { cfg.setLetterSpacing(p / 100f); relayout(); cfg.save(); }
        }));
        seekAutoSpeed.setOnSeekBarChangeListener(new SimpleSeek((p, fromUser) -> {
            if (fromUser) { cfg.setAutoScrollSpeed(p); cfg.save(); }
        }));
        seekTtsSpeed.setOnSeekBarChangeListener(new SimpleSeek((p, fromUser) -> {
            if (fromUser && tts != null) tts.setSpeechRate(0.5f + p / 100f);
        }));
        seekPage.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { if (u && readView.getTotalPage() > 0) readView.setPage(p); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        rgWeight.setOnCheckedChangeListener((g, id) -> {
            int w = indexOfChild(g, id); if (w >= 0) { cfg.setFontWeight(w); relayout(); cfg.save(); }
        });
        rgTtsSource.setOnCheckedChangeListener((g, id) -> {
            ttsSource = indexOfChild(g, id);
            if (ttsReady) restartTts();
        });
    }

    private int indexOfChild(android.view.ViewGroup g, int id) {
        for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i).getId() == id) return i;
        return 0;
    }

    private void setupReadView() {
        readView.setOnPageChangeListener(this);
        readView.setOnMenuToggleListener(this);
        readView.setOnBookmarkListener(this);
        readView.setOnAutoNextChapter(() -> {
            if (curIndex < chapters.size() - 1) loadChapter(curIndex + 1, 0, false);
        });
        applyBg();
    }

    private void setupPanels() {
        tvTitle.setText(vodName);
        showChapterTab(0);
    }

    /* ---------------- 章节加载 ---------------- */

    private void loadChapter(int index, int resumeChar, boolean first) {
        if (index < 0 || index >= chapters.size()) return;
        curIndex = index;
        Episode ep = chapters.get(index);
        tvChapter.setText(ep.getName());
        loading.setVisibility(View.VISIBLE);
        hidePanels();

        // 实验室：首章直接显示内联 payload（play_url 已是 novel:// 内容），避免重复网络请求
        if (initialPayload != null && index == initialIndex) {
            String t = parseNovelText(initialPayload);
            initialPayload = null;
            String finalText = (t == null || t.isEmpty()) ? "（本章内容为空或加载失败）" : t;
            loading.setVisibility(View.GONE);
            readView.setContent(ep.getName(), finalText);
            readView.setBookmarks(prefs.bookmarksOfChapter(index));
            seekPage.setMax(Math.max(0, readView.getTotalPage() - 1));
            preloadNext(index);
            return;
        }

        executor.execute(() -> {
            String text = fetchChapterText(ep.getUrl());
            String title = ep.getName();
            String finalText = (text == null || text.isEmpty()) ? "（本章内容为空或加载失败）" : text;
            int resume = resumeChar;
            if (first && resumeChar < 0 && prefs.hasResume()) {
                // 在 UI 线程弹「继续/从头」
                int rCh = prefs.getResumeChapter();
                int rPos = prefs.getResumeChar();
                runOnUiThread(() -> askResume(rCh, rPos, title, finalText));
                return;
            }
            final int r = (first && resumeChar < 0) ? 0 : resumeChar;
            runOnUiThread(() -> {
                loading.setVisibility(View.GONE);
                readView.setContent(title, finalText);
                readView.setBookmarks(prefs.bookmarksOfChapter(curIndex));
                seekPage.setMax(Math.max(0, readView.getTotalPage() - 1));
                if (r > 0) readView.goToChar(r);
                preloadNext(index);
            });
        });
    }

    private void askResume(int rCh, int rPos, String title, String text) {
        if (rCh < 0 || rCh >= chapters.size()) {
            loading.setVisibility(View.GONE);
            readView.setContent(title, text);
            readView.setBookmarks(prefs.bookmarksOfChapter(curIndex));
            seekPage.setMax(Math.max(0, readView.getTotalPage() - 1));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("继续阅读")
                .setMessage("检测到上次读到：「" + chapters.get(rCh).getName() + "」")
                .setPositiveButton("继续阅读", (d, w) -> {
                    curIndex = rCh;
                    tvChapter.setText(chapters.get(rCh).getName());
                    loading.setVisibility(View.GONE);
                    readView.setContent(chapters.get(rCh).getName(), text);
                    readView.setBookmarks(prefs.bookmarksOfChapter(rCh));
                    seekPage.setMax(Math.max(0, readView.getTotalPage() - 1));
                    readView.goToChar(rPos);
                })
                .setNegativeButton("从头开始", (d, w) -> {
                    loading.setVisibility(View.GONE);
                    readView.setContent(title, text);
                    readView.setBookmarks(prefs.bookmarksOfChapter(curIndex));
                    seekPage.setMax(Math.max(0, readView.getTotalPage() - 1));
                })
                .setCancelable(false)
                .show();
    }

    private void preloadNext(int index) {
        if (index + 1 >= chapters.size()) return;
        final String id = chapters.get(index + 1).getUrl();
        executor.execute(() -> fetchChapterText(id)); // 预热蜘蛛缓存
    }

    private String fetchChapterText(String chapterId) {
        try {
            Site site = VodConfig.get().getSite(siteKey);
            Result r;
            if (site != null && site.getType() != null && site.getType() == 3) {
                String json = site.recent().spider().playerContent(flag, chapterId, VodConfig.get().getFlags());
                r = Result.fromJson(json);
            } else {
                r = SiteApi.playerContent(siteKey, flag, chapterId);
            }
            String u = (r.getUrl() != null) ? r.getUrl().v() : null;
            if (u != null && u.startsWith("novel://")) return parseNovelText(u);
            if (u == null || u.isEmpty()) {
                // 部分蜘蛛把正文放在 msg / header
                String alt = notEmpty(r.getMsg()) ? r.getMsg() : null;
                if (alt == null && r.getHeader() != null) {
                    for (String hv : r.getHeader().values()) if (notEmpty(hv)) { alt = hv; break; }
                }
                if (alt != null && alt.startsWith("novel://")) return parseNovelText(alt);
                return decodeBookText(alt);
            }
            return decodeBookText(u);
        } catch (Throwable e) {
            return "";
        }
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    /**
     * 解析小说正文。playerContent 返回的 url 形如 novel://{json}。
     * 容错常见字段（content/text/book/body/data…）与数组形式；无法解析时回退为纯文本/base64。
     */
    private String parseNovelText(String payload) {
        if (payload == null) return "";
        String s = payload.trim();
        if (s.startsWith("novel://")) s = s.substring("novel://".length()).trim();
        if (s.isEmpty()) return "";
        try {
            com.google.gson.JsonElement el = new com.google.gson.Gson().fromJson(s, com.google.gson.JsonElement.class);
            if (el != null && el.isJsonObject()) {
                com.google.gson.JsonObject o = el.getAsJsonObject();
                for (String k : new String[]{"content", "text", "book", "body", "data", "txt", "content_list", "paragraphs", "chapter", "article"}) {
                    com.google.gson.JsonElement v = o.get(k);
                    if (v != null && !v.isJsonNull()) {
                        if (v.isJsonPrimitive()) {
                            String t = v.getAsString();
                            if (notEmpty(t)) return t;
                        } else if (v.isJsonArray()) {
                            String t = joinArray(v.getAsJsonArray());
                            if (notEmpty(t)) return t;
                        }
                    }
                }
            } else if (el != null && el.isJsonArray()) {
                String t = joinArray(el.getAsJsonArray());
                if (notEmpty(t)) return t;
            }
        } catch (Throwable ignore) {
            // 非 JSON，走下方回退
        }
        return decodeBookText(s);
    }

    private static String joinArray(com.google.gson.JsonArray arr) {
        StringBuilder sb = new StringBuilder();
        for (com.google.gson.JsonElement e : arr) {
            if (e.isJsonPrimitive()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(e.getAsString());
            }
        }
        return sb.toString();
    }

    private String decodeBookText(String s) {
        if (s == null) return "";
        // book 蜘蛛可能返回 base64 文本
        try {
            String t = s.trim();
            if (t.length() % 4 == 0 && t.matches("[A-Za-z0-9+/=]+") && t.length() > 16) {
                byte[] d = android.util.Base64.decode(t, android.util.Base64.DEFAULT);
                String dec = new String(d, java.nio.charset.StandardCharsets.UTF_8);
                if (dec.chars().allMatch(c -> c == 10 || c == 13 || c > 31 && c != 127)) return dec;
            }
        } catch (Throwable ignore) {}
        return s;
    }

    /* ---------------- 进度 / 续读 ---------------- */

    @Override
    public void onPageChange(int page, int total) {
        tvPage.setText((page + 1) + " / " + total);
        seekPage.setProgress(page);
        int charPos = readView.currentChar();
        prefs.saveResume(curIndex, charPos);
    }

    @Override
    public void onMenuToggle() {
        uiVisible = !uiVisible;
        int v = uiVisible ? View.VISIBLE : View.GONE;
        topBar.setVisibility(v);
        bottomBar.setVisibility(v);
        if (!uiVisible) hidePanels();
    }

    @Override
    public void onBookmarkToggle(int charIndex) {
        prefs.toggleBookmark(curIndex, charIndex);
        readView.setBookmarks(prefs.bookmarksOfChapter(curIndex));
        Toast.makeText(this, prefs.hasBookmark(curIndex, charIndex) ? "已加书签" : "已取消书签", Toast.LENGTH_SHORT).show();
    }

    /* ---------------- 背景 / 动画 ---------------- */

    private void applyBg() {
        int color = 0xFF101010;
        switch (cfg.bgMode()) {
            case BLACK: color = 0xFF111111; break;
            case WHITE: color = 0xFFF5F5F0; break;
            case EYE:   color = 0xFFC7EDCC; break;
            case PAPER: color = 0xFFE9D9B8; break;
            case GRAY:  color = 0xFF2B2B2B; break;
            case WALLPAPER: default: color = 0xFF101010; break;
        }
        View wall = findViewById(R.id.read_wall);
        if (wall != null) wall.setBackgroundColor(color);
    }

    private void cycleBg() {
        int m = (cfg.bgMode().ordinal() + 1) % NovelConfig.BgMode.values().length;
        cfg.setBgMode(m);
        cfg.save();
        applyBg();
        readView.relayout();
    }

    private void cycleAnim() {
        int m = (cfg.getAnimMode() + 1) % NovelConfig.AnimMode.values().length;
        cfg.setAnimMode(m);
        cfg.save();
        readView.relayout();
        Toast.makeText(this, "翻页：" + animName(m), Toast.LENGTH_SHORT).show();
    }

    private String animName(int m) {
        NovelConfig.AnimMode[] v = NovelConfig.AnimMode.values();
        if (m < 0 || m >= v.length) return "";
        switch (v[m]) {
            case COVER: return "仿真";
            case SLIDE: return "覆盖";
            case TRANSLATE: return "平移";
            case UPDOWN: return "上下";
            case SCROLL: return "滚动";
            default: return "无";
        }
    }

    private void setBrightness(int b) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : (b / 100f);
        getWindow().setAttributes(lp);
    }

    private void relayout() {
        readView.relayout();
        applyBg();
    }

    /* ---------------- 收藏 ---------------- */

    private void toggleKeep() {
        keepOn = !keepOn;
        btnKeep.setColorFilter(keepOn ? Color.parseColor("#FFD54F") : Color.WHITE);
        Toast.makeText(this, keepOn ? "已收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
    }

    /* ---------------- 面板 ---------------- */

    private void togglePanel(View panel) {
        boolean show = panel.getVisibility() != View.VISIBLE;
        hidePanels();
        if (show) { panel.setVisibility(View.VISIBLE); uiVisible = true; topBar.setVisibility(View.VISIBLE); bottomBar.setVisibility(View.VISIBLE); }
    }

    private void hidePanels() {
        panelSettings.setVisibility(View.GONE);
        panelTts.setVisibility(View.GONE);
        panelAuto.setVisibility(View.GONE);
        panelChapter.setVisibility(View.GONE);
    }

    private void showChapterTab(int tab) {
        tabDirectory.setSelected(tab == 0);
        tabBookmark.setSelected(tab == 1);
        tabDetail.setSelected(tab == 2);
        rvChapter.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        rvBookmark.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        layoutDetail.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        if (tab == 0) buildChapterList();
        else if (tab == 1) buildBookmarkList();
        else buildDetail();
    }

    private void buildChapterList() {
        List<String> names = new ArrayList<>();
        for (Episode e : chapters) names.add(e.getName());
        rvChapter.setAdapter(new TextAdapter(names, curIndex, (pos) -> {
            hidePanels();
            loadChapter(pos, 0, false);
        }));
    }

    private void buildBookmarkList() {
        Set<String> bms = prefs.getBookmarks();
        List<String> items = new ArrayList<>();
        for (String s : bms) {
            int i = s.indexOf(':');
            int ch = Integer.parseInt(s.substring(0, i));
            int c = Integer.parseInt(s.substring(i + 1));
            if (ch >= 0 && ch < chapters.size()) items.add("第" + (ch + 1) + "章 " + chapters.get(ch).getName() + " · 第" + c + "字");
        }
        Collections.sort(items);
        rvBookmark.setAdapter(new TextAdapter(items, -1, (pos) -> {
            String s = new ArrayList<>(bms).get(pos);
            int i = s.indexOf(':');
            int ch = Integer.parseInt(s.substring(0, i));
            int c = Integer.parseInt(s.substring(i + 1));
            hidePanels();
            loadChapter(ch, c, false);
        }));
    }

    private void buildDetail() {
        TextView tv = layoutDetail.findViewById(R.id.tv_intro);
        ImageView iv = layoutDetail.findViewById(R.id.iv_cover);
        if (tv != null) tv.setText(vodName);
        if (iv != null && !TextUtils.isEmpty(vodPic)) {
            com.bumptech.glide.Glide.with(this).load(vodPic).into(iv);
        }
    }

    /* ---------------- 自动阅读 ---------------- */

    private void toggleAuto() {
        if (readView.isAutoRunning()) stopAuto(); else startAutoUi();
    }

    private void startAutoUi() {
        readView.startAuto();
        progressAuto.setVisibility(View.VISIBLE);
        Toast.makeText(this, "自动阅读开始", Toast.LENGTH_SHORT).show();
    }

    private void stopAuto() {
        readView.stopAuto();
        progressAuto.setVisibility(View.GONE);
        hidePanels();
    }

    /* ---------------- TTS ---------------- */

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                tts.setLanguage(Locale.CHINESE);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String u) {}
                    @Override public void onDone(String u) {
                        runOnUiThread(() -> {
                            if (ttsPlaying && readView.getCurPage() < readView.getTotalPage() - 1) {
                                readView.nextPage();
                                speakCurrent();
                            } else { ttsPlaying = false; updateTtsIcon(); }
                        });
                    }
                    @Override public void onError(String u) { ttsPlaying = false; updateTtsIcon(); }
                });
            }
        });
    }

    private void toggleTts() {
        if (!ttsReady) { Toast.makeText(this, "TTS 不可用", Toast.LENGTH_SHORT).show(); return; }
        if (ttsPlaying) { tts.stop(); ttsPlaying = false; }
        else { ttsPlaying = true; speakCurrent(); }
        updateTtsIcon();
    }

    private void restartTts() {
        if (ttsPlaying) { tts.stop(); speakCurrent(); }
    }

    private void speakCurrent() {
        if (!ttsReady) return;
        tts.speak(readView.getCurrentPageText(), TextToSpeech.QUEUE_FLUSH, null, "novel_" + System.currentTimeMillis());
    }

    private void updateTtsIcon() {
        btnTts.setColorFilter(ttsPlaying ? Color.parseColor("#4FC3F7") : Color.WHITE);
    }

    /* ---------------- 生命周期 ---------------- */

    @Override
    protected void onDestroy() {
        readView.stopAuto();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        executor.shutdownNow();
        super.onDestroy();
    }

    /* ---------------- 简易 SeekBar 监听 ---------------- */

    private static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        interface C { void on(int p, boolean u); }
        private final C c;
        SimpleSeek(C c) { this.c = c; }
        @Override public void onProgressChanged(SeekBar s, int p, boolean u) { c.on(p, u); }
        @Override public void onStartTrackingTouch(SeekBar s) {}
        @Override public void onStopTrackingTouch(SeekBar s) {}
    }

    /* ---------------- 章节/书签文本适配器 ---------------- */

    private static class TextAdapter extends RecyclerView.Adapter<TextAdapter.VH> {
        interface Click { void on(int pos); }
        private final List<String> data;
        private final int selected;
        private final Click click;
        TextAdapter(List<String> d, int sel, Click c) { data = d; selected = sel; click = c; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int t) {
            TextView tv = new TextView(parent.getContext());
            tv.setPadding(32, 24, 32, 24);
            tv.setTextSize(15);
            tv.setTextColor(0xFFEAF2F8);
            tv.setSingleLine(false);
            return new VH(tv);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int p) {
            h.tv.setText(data.get(p));
            h.tv.setSelected(p == selected);
            h.tv.setTextColor(p == selected ? 0xFF4FC3F7 : 0xFFEAF2F8);
            h.tv.setOnClickListener(v -> click.on(p));
        }
        @Override public int getItemCount() { return data.size(); }
        static class VH extends RecyclerView.ViewHolder {
            final TextView tv;
            VH(TextView v) { super(v); tv = v; }
        }
    }
}
