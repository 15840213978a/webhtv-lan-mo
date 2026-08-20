package com.fongmi.android.tv.ui.novel;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.ComicSourceConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.NovelSourceConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.ui.web.WebReaderActivity;
import com.fongmi.android.tv.utils.Sniffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 集数点击 / 播放入口路由器：决定是「小说/漫画阅读」还是「普通播放」。
 *
 * 两种拦截点：
 * 1) route()        —— 详情页 selectInlineEpisode：内容驱动判定。漫画 url 以 pics:// / manga:// 开头
 *                      直接进漫画；其余异步取一次 playerContent，按返回内容（url/playUrl/msg/header）
 *                      判定（novel:// 或非视频文本 → 小说，否则回退播放）。
 * 2) guardInlinePlay() —— 详情页 startInlinePlayer（内联播放器汇聚点）：按 play_url 协议前缀
 *                      硬编码路由到 WebReaderActivity(reader.html)；其他 → 原 player 流程。
 */
public final class NovelRouter {

    private static final ExecutorService executor = Executors.newFixedThreadPool(2);
    private static final Handler main = new Handler(Looper.getMainLooper());

    public interface Fallback { void run(); }

    /** @return true 表示本方法已接管（含异步判定），调用方应 return。 */
    public static boolean route(Context ctx, String siteKey, Episode episode, Vod vod, Flag flag, Fallback fallback) {
        String url = episode == null ? null : episode.getUrl();
        if (isComic(url)) {
            openPics(ctx, siteKey, flag, vod, episode, url);
            return true;
        }
        if (isNovel(url)) {
            openReader(ctx, siteKey, flag, vod, episode, url);
            return true;
        }
        int preferredKind = ComicSourceConfig.isEnabledByKey(siteKey) ? 2
                : NovelSourceConfig.isEnabledByKey(siteKey) ? 1 : 0;
        if (preferredKind != 0) {
            openPendingChapter(ctx, preferredKind, siteKey, flag, vod, episode);
            return true;
        }
        ProgressDialog pd = new ProgressDialog(ctx);
        pd.setMessage("正在识别内容…");
        pd.setCancelable(false);
        pd.show();
        executor.execute(() -> {
            boolean launched = false;
            try {
                Result r = SiteApi.playerContent(siteKey, flag == null ? "" : flag.getFlag(), episode.getUrl());
                String u = content(r);
                if (isComic(u)) {
                    openPics(ctx, siteKey, flag, vod, episode, u);
                    launched = true;
                } else if (isNovel(u)) {
                    openReader(ctx, siteKey, flag, vod, episode, u);
                    launched = true;
                } else if (u != null && !u.isEmpty() && !Sniffer.isVideoFormat(u)) {
                    openReader(ctx, siteKey, flag, vod, episode, u);
                    launched = true;
                }
            } catch (Throwable ignore) {
                launched = false;
            }
            boolean finalLaunched = launched;
            main.post(() -> {
                pd.dismiss();
                if (!finalLaunched && fallback != null) fallback.run();
            });
        });
        return true;
    }

    /* ---------------- 播放入口拦截：按 play_url 协议前缀路由到阅读器 ---------------- */

    /**
     * 详情页 startInlinePlayer 前的硬编码拦截：按 play_url 协议前缀路由到 WebReaderActivity(reader.html)。
     * 命中 → 启动 WebReaderActivity 并返回 true（调用方必须停止内联播放器并 return）。
     * 不命中 → 返回 false（继续走原 player 流程）。
     */
    public static boolean guardInlinePlay(Context ctx, Result result,
                                          String siteKey, String flag,
                                          String vodId, String vodName, String vodPic,
                                          Episode currentEpisode, List<Episode> chapters) {
        if (ctx == null || result == null) return false;
        int kind = readerUrlKind(result);
        if (kind == 0) return false;

        String payload = content(result);
        ArrayList<Episode> ch = new ArrayList<>();
        if (chapters != null) ch.addAll(chapters);
        int index = 0;
        if (currentEpisode != null) {
            int i = ch.indexOf(currentEpisode);
            if (i >= 0) index = i;
        }
        openResolved(ctx, kind, payload, siteKey, flag, vodId, vodName, vodPic, ch, index);
        return true;
    }

    public static void openResolved(Context ctx, int kind, String payload,
                                    String siteKey, String flag, String vodId,
                                    String vodName, String vodPic,
                                    List<Episode> chapters, int index) {
        if (ctx == null) return;
        ArrayList<Episode> ch = new ArrayList<>();
        if (chapters != null) ch.addAll(chapters);
        String sk = siteKey == null ? "" : siteKey;
        int atIdx = sk.indexOf("@@@");
        if (atIdx > 0) sk = sk.substring(0, atIdx);
        Intent it = new Intent(ctx, WebReaderActivity.class);
        it.putExtra(WebReaderActivity.EXTRA_KIND, kind == 2 ? 2 : 1);
        it.putExtra(WebReaderActivity.EXTRA_PAYLOAD, payload == null ? "" : payload);
        it.putExtra(WebReaderActivity.EXTRA_SITE_KEY, sk);
        it.putExtra(WebReaderActivity.EXTRA_FLAG, flag == null ? "" : flag);
        it.putExtra(WebReaderActivity.EXTRA_VOD_ID, vodId == null ? "" : vodId);
        it.putExtra(WebReaderActivity.EXTRA_VOD_NAME, vodName == null ? "" : vodName);
        it.putExtra(WebReaderActivity.EXTRA_VOD_PIC, vodPic == null ? "" : vodPic);
        it.putExtra(WebReaderActivity.EXTRA_CHAPTERS, ch);
        it.putExtra(WebReaderActivity.EXTRA_INDEX, Math.max(0, Math.min(index, Math.max(0, ch.size() - 1))));
        ctx.startActivity(it);
    }

    /** 卡片点击快速入口：先打开阅读页，再在阅读页后台请求详情和首章。 */
    public static void openPendingSite(Context ctx, int kind, String siteKey, String vodId,
                                       String vodName, String vodPic, String mark) {
        if (ctx == null) return;
        Intent it = new Intent(ctx, WebReaderActivity.class);
        it.putExtra(WebReaderActivity.EXTRA_KIND, kind == 2 ? 2 : 1);
        it.putExtra(WebReaderActivity.EXTRA_PAYLOAD, "");
        it.putExtra(WebReaderActivity.EXTRA_SITE_KEY, siteKey == null ? "" : siteKey);
        it.putExtra(WebReaderActivity.EXTRA_VOD_ID, vodId == null ? "" : vodId);
        it.putExtra(WebReaderActivity.EXTRA_VOD_NAME, vodName == null ? "" : vodName);
        it.putExtra(WebReaderActivity.EXTRA_VOD_PIC, vodPic == null ? "" : vodPic);
        it.putExtra(WebReaderActivity.EXTRA_MARK, mark == null ? "" : mark);
        it.putExtra(WebReaderActivity.EXTRA_RESOLVE_DETAIL, true);
        ctx.startActivity(it);
    }

    /** 已有章节列表的快速入口：先打开阅读页，再只解析当前章节。 */
    public static void openPendingChapter(Context ctx, int kind, String siteKey, Flag flag,
                                          Vod vod, Episode episode) {
        if (ctx == null || episode == null) return;
        ArrayList<Episode> ch = chaptersOf(flag, episode);
        Intent it = new Intent(ctx, WebReaderActivity.class);
        it.putExtra(WebReaderActivity.EXTRA_KIND, kind == 2 ? 2 : 1);
        it.putExtra(WebReaderActivity.EXTRA_PAYLOAD, "");
        it.putExtra(WebReaderActivity.EXTRA_SITE_KEY, siteKey == null ? "" : siteKey);
        it.putExtra(WebReaderActivity.EXTRA_FLAG, flag == null ? "" : flag.getFlag());
        it.putExtra(WebReaderActivity.EXTRA_VOD_ID, vod == null ? "" : vod.getId());
        it.putExtra(WebReaderActivity.EXTRA_VOD_NAME, vod == null ? "" : vod.getName());
        it.putExtra(WebReaderActivity.EXTRA_VOD_PIC, vod == null ? "" : vod.getPic());
        it.putExtra(WebReaderActivity.EXTRA_CHAPTERS, ch);
        it.putExtra(WebReaderActivity.EXTRA_INDEX, indexOf(ch, episode));
        it.putExtra(WebReaderActivity.EXTRA_CHAPTER_URL, episode.getUrl());
        ctx.startActivity(it);
    }

    /* ---------------- 内容判定 ---------------- */

    private static boolean isComic(String url) {
        if (url == null) return false;
        String u = url.trim();
        return u.startsWith("pics://") || u.startsWith("manga://");
    }

    private static boolean isNovel(String url) {
        if (url == null) return false;
        return url.trim().startsWith("novel://");
    }

    /** 从 Result 多字段中提取首个有效 play_url。优先返回带 novel:///pics:///manga:// 协议前缀的字段，避免内容藏在 msg/header 时漏判。 */
    public static String content(Result r) {
        if (r == null) return null;
        String playUrl = r.getPlayUrl();
        String urlV = null;
        try { if (r.getUrl() != null) urlV = r.getUrl().v(); } catch (Throwable ignore) {}
        String a = playUrl != null ? playUrl.trim() : "";
        String b = urlV != null ? urlV.trim() : "";
        if (isReaderPrefix(a)) return a;
        if (isReaderPrefix(b)) return b;
        if (!a.isEmpty()) return a;
        if (!b.isEmpty()) return b;
        if (notEmpty(r.getMsg())) return r.getMsg();
        Map<String, String> header = r.getHeader();
        if (header != null) {
            for (String v : header.values()) if (notEmpty(v)) return v;
        }
        return null;
    }

    private static boolean isReaderPrefix(String s) {
        if (s == null || s.isEmpty()) return false;
        return s.startsWith("novel://") || s.startsWith("pics://") || s.startsWith("manga://");
    }

    /** 判定 play_url 协议类型：0=非阅读/普通视频；1=novel:// 小说；2=pics:///manga:// 漫画。 */
    public static int readerUrlKind(Result r) {
        String u = content(r);
        if (u == null) return 0;
        u = u.trim();
        if (u.startsWith("novel://")) return 1;
        if (u.startsWith("pics://") || u.startsWith("manga://")) return 2;
        return 0;
    }

    /** 是否应路由到阅读器（按 play_url 协议前缀）。供 PlaybackActivity.startPlayer 等汇聚点调用。 */
    public static boolean isReaderUrl(Result result) {
        return readerUrlKind(result) != 0;
    }

    /** 当前前台的阅读器实例（用于切换章节后回传解析结果，避免重复启动）。 */
    public static volatile WebReaderActivity currentReader;
    /** 播放器宿主（VideoActivity 实现 NovelReaderHost，负责执行解析任务）。 */
    public static volatile NovelReaderHost host;
    /** 阅读器关闭时间戳，用于拦截「返回后残留 playerContent 回调又重新拉起阅读器」。 */
    public static volatile long readerClosedAt = 0L;

    /**
     * 播放入口汇聚点（PlaybackActivity.startPlayer）调用：
     * playerContent 已返回 novel:// / pics:// / manga:// 这类「阅读内容协议」时，
     * 把 JSON 内容注入到本地阅读器 Web 模板（WebReaderActivity）渲染，全屏阅读。
     *
     * 切换章节时播放器会再次走到这里，此时阅读器已在前台 → 直接回传结果，不再启动新实例。
     *
     * @param activity 当前 Activity（VideoActivity / TmdbDetailActivity 等）
     * @param result   playerContent 返回的 Result，getRealUrl() 即阅读内容协议
     * @param key      PlaybackActivity.startPlayer 传入的 key（可能是 getHistoryKey 含 @@@，需提取纯 siteKey）
     * @param vod      当前 Vod（含整本书章节列表）；为 null 时阅读器仍显示当前章内容（无章节导航）
     */
    public static boolean routeReaderEngine(Activity activity, Result result, String key, Vod vod) {
        if (activity == null || result == null) return false;
        int kind = readerUrlKind(result);
        if (kind == 0) return false;

        String payload = result.getRealUrl();
        String flag = result.getFlag() == null ? "" : result.getFlag();

        // 关键修复：startPlayer 传入的 key 是 getHistoryKey()（siteKey@@@vodId@@@1），
        // 而 SiteApi.playerContent 需要纯 siteKey。这里提取纯 siteKey 供阅读器切章时使用。
        String siteKey = key == null ? "" : key;
        int atIdx = siteKey.indexOf("@@@");
        if (atIdx > 0) siteKey = siteKey.substring(0, atIdx);

        if (activity instanceof NovelReaderHost) host = (NovelReaderHost) activity;

        // 整本书章节列表（跨所有线路合并）
        ArrayList<Episode> ch = new ArrayList<>();
        if (vod != null && vod.getFlags() != null) {
            for (Flag f : vod.getFlags()) {
                if (f != null && f.getEpisodes() != null) ch.addAll(f.getEpisodes());
            }
        }

        // 当前章节：用 payload 中的 title 与章节名匹配
        int index = 0;
        String payloadTitle = extractTitle(payload);
        if (payloadTitle != null && !ch.isEmpty()) {
            for (int i = 0; i < ch.size(); i++) {
                if (payloadTitle.equals(ch.get(i).getName())) { index = i; break; }
            }
        }

        // 阅读器已在前台 → 回传解析结果，不重复启动（解决「切换章节回不到播放器」）
        WebReaderActivity reader = currentReader;
        if (reader != null && !reader.isFinishing() && !reader.isDestroyed()) {
            reader.onEpisodeResolved(kind, payload, extractTitle(payload));
            return true;
        }

        // 用户刚关闭阅读器（1.5 秒内），说明这是返回后残留的 playerContent 回调，
        // 不再拉起阅读器，让播放器页面正常展示。
        if (readerClosedAt > 0 && System.currentTimeMillis() - readerClosedAt < 1500) {
            return false;
        }

        Intent it = new Intent(activity, WebReaderActivity.class);
        it.putExtra(WebReaderActivity.EXTRA_KIND, kind); // 1=小说 2=漫画
        it.putExtra(WebReaderActivity.EXTRA_PAYLOAD, payload == null ? "" : payload);
        it.putExtra(WebReaderActivity.EXTRA_SITE_KEY, siteKey);
        it.putExtra(WebReaderActivity.EXTRA_FLAG, flag);
        it.putExtra(WebReaderActivity.EXTRA_VOD_ID, vod == null ? "" : vod.getId());
        it.putExtra(WebReaderActivity.EXTRA_VOD_NAME, vod == null ? "" : vod.getName());
        it.putExtra(WebReaderActivity.EXTRA_VOD_PIC, vod == null ? "" : vod.getPic());
        it.putExtra(WebReaderActivity.EXTRA_CHAPTERS, ch);
        it.putExtra(WebReaderActivity.EXTRA_INDEX, index);
        activity.startActivity(it);
        return true;
    }

    /** 从 novel:// / pics:// payload 中提取 title（用于匹配当前章节）。 */
    private static String extractTitle(String payload) {
        if (payload == null) return null;
        String s = payload.trim();
        if (s.startsWith("novel://")) s = s.substring("novel://".length()).trim();
        else if (s.startsWith("pics://") || s.startsWith("manga://")) s = s.substring(s.indexOf("://") + 3);
        try {
            com.google.gson.JsonElement el = new com.google.gson.Gson().fromJson(s, com.google.gson.JsonElement.class);
            if (el != null && el.isJsonObject()) {
                com.google.gson.JsonElement t = el.getAsJsonObject().get("title");
                if (t != null && t.isJsonPrimitive()) return t.getAsString();
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static ArrayList<Episode> chaptersOf(Flag flag, Episode current) {
        ArrayList<Episode> list = new ArrayList<>();
        if (flag != null && flag.getEpisodes() != null) list.addAll(flag.getEpisodes());
        if (list.isEmpty() && current != null) list.add(current);
        return list;
    }

    private static int indexOf(ArrayList<Episode> list, Episode ep) {
        int i = list.indexOf(ep);
        return i < 0 ? 0 : i;
    }

    public static void openReader(Context ctx, String siteKey, Flag flag, Vod vod, Episode ep, String payload) {
        ArrayList<Episode> ch = chaptersOf(flag, ep);
        Intent it = new Intent(ctx, WebReaderActivity.class);
        it.putExtra(WebReaderActivity.EXTRA_KIND, 1);
        it.putExtra(WebReaderActivity.EXTRA_PAYLOAD, payload == null ? "" : payload);
        it.putExtra(WebReaderActivity.EXTRA_SITE_KEY, siteKey);
        it.putExtra(WebReaderActivity.EXTRA_FLAG, flag == null ? "" : flag.getFlag());
        it.putExtra(WebReaderActivity.EXTRA_VOD_ID, vod == null ? "" : vod.getId());
        it.putExtra(WebReaderActivity.EXTRA_VOD_NAME, vod == null ? "" : vod.getName());
        it.putExtra(WebReaderActivity.EXTRA_VOD_PIC, vod == null ? "" : vod.getPic());
        it.putExtra(WebReaderActivity.EXTRA_CHAPTERS, ch);
        it.putExtra(WebReaderActivity.EXTRA_INDEX, indexOf(ch, ep));
        ctx.startActivity(it);
    }

    public static void openPics(Context ctx, String siteKey, Flag flag, Vod vod, Episode ep, String payload) {
        ArrayList<Episode> ch = chaptersOf(flag, ep);
        Intent it = new Intent(ctx, WebReaderActivity.class);
        it.putExtra(WebReaderActivity.EXTRA_KIND, 2);
        it.putExtra(WebReaderActivity.EXTRA_PAYLOAD, payload == null ? "" : payload);
        it.putExtra(WebReaderActivity.EXTRA_SITE_KEY, siteKey);
        it.putExtra(WebReaderActivity.EXTRA_FLAG, flag == null ? "" : flag.getFlag());
        it.putExtra(WebReaderActivity.EXTRA_VOD_ID, vod == null ? "" : vod.getId());
        it.putExtra(WebReaderActivity.EXTRA_VOD_NAME, vod == null ? "" : vod.getName());
        it.putExtra(WebReaderActivity.EXTRA_VOD_PIC, vod == null ? "" : vod.getPic());
        it.putExtra(WebReaderActivity.EXTRA_CHAPTERS, ch);
        it.putExtra(WebReaderActivity.EXTRA_INDEX, indexOf(ch, ep));
        ctx.startActivity(it);
    }
}
