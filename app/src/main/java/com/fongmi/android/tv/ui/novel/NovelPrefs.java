package com.fongmi.android.tv.ui.novel;

import android.content.SharedPreferences;

import com.fongmi.android.tv.App;

import java.util.HashSet;
import java.util.Set;

/**
 * 小说阅读进度 / 书签本地存储（续读走此处，不污染 History/Keep）。
 * key 形如 siteKey + "#" + vodId。
 */
public final class NovelPrefs {

    private static final String SP = "novel_prefs";

    private final SharedPreferences sp;
    private final String key;

    public NovelPrefs(String siteKey, String vodId) {
        this.sp = App.get().getSharedPreferences(SP, 0);
        this.key = siteKey + "#" + vodId;
    }

    /* 续读位置：章节下标 + 章节内全局字符下标 */
    public void saveResume(int chapter, int charIndex) {
        sp.edit().putInt(key + "_ch", chapter).putInt(key + "_pos", charIndex).apply();
    }

    public int getResumeChapter() { return sp.getInt(key + "_ch", -1); }
    public int getResumeChar() { return sp.getInt(key + "_pos", 0); }
    public boolean hasResume() { return sp.contains(key + "_ch"); }

    /* 书签：章节下标:字符下标 */
    public void toggleBookmark(int chapter, int charIndex) {
        Set<String> set = new HashSet<>(getBookmarks());
        String k = chapter + ":" + charIndex;
        if (set.contains(k)) set.remove(k); else set.add(k);
        sp.edit().putStringSet(key + "_bm", set).apply();
    }

    public boolean hasBookmark(int chapter, int charIndex) {
        return getBookmarks().contains(chapter + ":" + charIndex);
    }

    public Set<String> getBookmarks() {
        return new HashSet<>(sp.getStringSet(key + "_bm", new HashSet<>()));
    }

    /** 某章节内的书签字符下标集合（供 ReadView 高亮）。 */
    public Set<Integer> bookmarksOfChapter(int chapter) {
        Set<Integer> out = new HashSet<>();
        for (String s : getBookmarks()) {
            int i = s.indexOf(':');
            if (i > 0 && Integer.parseInt(s.substring(0, i)) == chapter) {
                out.add(Integer.parseInt(s.substring(i + 1)));
            }
        }
        return out;
    }
}
