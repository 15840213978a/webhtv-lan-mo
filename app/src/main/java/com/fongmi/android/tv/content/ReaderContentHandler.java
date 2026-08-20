package com.fongmi.android.tv.content;

import android.app.Activity;
import android.text.TextUtils;

import com.fongmi.android.tv.bean.ComicSourceConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.NovelSourceConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.ui.novel.NovelRouter;
import com.fongmi.android.tv.utils.Sniffer;

import java.util.List;

public class ReaderContentHandler implements ContentHandler {

    @Override
    public boolean canHandleSite(String key, String name) {
        return NovelSourceConfig.isSiteEnabled(key, name) || ComicSourceConfig.isSiteEnabled(key, name);
    }

    @Override
    public boolean canHandleUrl(String url) {
        return readerKind(url) != 0;
    }

    @Override
    public boolean handleSite(Activity activity, String key, String id, String name, String pic, String mark) {
        int preferredKind = ComicSourceConfig.isSiteEnabled(key, name) ? 2 : 1;
        if (!canHandleSite(key, name)) return false;
        NovelRouter.openPendingSite(activity, preferredKind, key, id, name, pic, mark);
        return true;
    }

    @Override
    public boolean handleUrl(Activity activity, String url, String title) {
        int kind = readerKind(url);
        if (kind == 0) return false;
        NovelRouter.openResolved(activity, kind, url, "", "", "", title, "", null, 0);
        return true;
    }

    @Override
    public boolean handleResult(Activity activity, String historyKey, String siteKey, String flag,
                                String vodName, String vodPic, List<Episode> episodes, int position,
                                Result result, long timeout) {
        String payload = NovelRouter.content(result);
        int kind = NovelRouter.readerUrlKind(result);
        if (kind == 0 && NovelSourceConfig.isEnabledByKey(siteKey) && isReadable(payload)) kind = 1;
        if (kind == 0 && ComicSourceConfig.isEnabledByKey(siteKey) && isReadable(payload)) kind = 2;
        if (kind == 0) return false;
        NovelRouter.openResolved(activity, kind, payload, siteKey, flag, "", vodName, vodPic,
                episodes, position);
        return true;
    }

    private static int readerKind(String value) {
        if (value == null) return 0;
        String url = value.trim();
        if (url.startsWith("novel://")) return 1;
        if (url.startsWith("pics://") || url.startsWith("manga://")) return 2;
        return 0;
    }

    private static boolean isReadable(String payload) {
        return !TextUtils.isEmpty(payload) && (readerKind(payload) != 0 || !Sniffer.isVideoFormat(payload));
    }
}
