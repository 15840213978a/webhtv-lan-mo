package com.fongmi.android.tv.ui.pics;

import android.content.SharedPreferences;

import com.fongmi.android.tv.App;

/**
 * 漫画阅读配置（对应反编译中的 v8.a）。
 * 翻页模式：翻页 / 漫画(纵向滚动) / 画廊 / 九宫格；自动滚动速度；亮度。
 */
public final class PicsConfig {

    public enum Mode {
        PAGE,    // 翻页（单图左右滑）
        COMIC,   // 漫画（纵向连续滚动）
        GALLERY, // 画廊（2 列）
        GRID     // 九宫格（3 列）
    }

    private static final String SP = "pics_config";
    private static volatile PicsConfig s;
    private final SharedPreferences sp;

    private int mode = Mode.COMIC.ordinal();
    private int autoSpeed = 50;
    private int brightness = -1;

    public static PicsConfig get() {
        if (s == null) {
            synchronized (PicsConfig.class) {
                if (s == null) s = new PicsConfig();
            }
        }
        return s;
    }

    private PicsConfig() {
        sp = App.get().getSharedPreferences(SP, 0);
        mode = sp.getInt("mode", mode);
        autoSpeed = sp.getInt("autoSpeed", autoSpeed);
        brightness = sp.getInt("brightness", brightness);
    }

    public void save() {
        sp.edit().putInt("mode", mode).putInt("autoSpeed", autoSpeed).putInt("brightness", brightness).apply();
    }

    public int getMode() { return mode; }
    public Mode mode() { return Mode.values()[Math.max(0, Math.min(mode, Mode.values().length - 1))]; }
    public void setMode(int m) { mode = m; }
    public void cycleMode() { mode = (mode + 1) % Mode.values().length; save(); }

    public int getAutoSpeed() { return autoSpeed; }
    public void setAutoSpeed(int v) { autoSpeed = v; }

    public int getBrightness() { return brightness; }
    public void setBrightness(int b) { brightness = b; }
}
