package com.fongmi.android.tv.content;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.ui.web.GameWebActivity;
import com.github.catvod.net.OkHttp;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 实验室：影视+ VodPlus「动作卡片」执行器。
 *
 * 兼容上游 Vod.action 原生字段与 vod_tag:'action' + vod_id JSON 两种协议，
 * 由 TypeFragment.onItemClick 的 isAction 分支调用，替代上游只 Toast msg 的简陋处理。
 *
 * 流程：点击动作卡 → 异步调 spider.action(卡片JSON) → 按响应类型分发：
 *  - type:"browser"（含嵌套在 action 字段里的变体）→ GameWebActivity 内置 WebView 打开
 *  - type:"input" → 弹输入框（title/tip/value）→ 确认后把输入填回 value 字段再次调用，
 *    形成影视+ 的链式输入（如「访问网址」→输入→开网页、「磁力链接设置」→输入→保存）
 *  - list 非空 → 对话框显示结果卡片（vod_name + vod_remarks）
 *  - msg 非空 → Toast 提示
 */
public final class ActionCardHelper {

    private static final ExecutorService executor = Executors.newFixedThreadPool(2);
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** 点击动作卡片入口：siteKey 为站点 key，actionJson 为卡片 vod_id JSON 或原生 action 字段内容。 */
    public static void handleAction(Activity activity, String siteKey, String actionJson) {
        if (activity == null || TextUtils.isEmpty(actionJson)) return;
        executor.execute(() -> {
            String resp = callSpider(siteKey, actionJson);
            main.post(() -> dispatch(activity, siteKey, resp));
        });
    }

    /* ---------------- spider 调用（对齐 SiteApi.action） ---------------- */

    private static String callSpider(String siteKey, String actionJson) {
        try {
            Site site = VodConfig.get().getSite(siteKey);
            if (site == null) return "";
            if (site.getType() == 3) return site.recent().spider().action(actionJson);
            if (site.getType() == 4) return OkHttp.string(actionJson);
        } catch (Throwable e) {
            return error(e.getMessage());
        }
        return "";
    }

    private static String error(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("msg", TextUtils.isEmpty(message) ? "动作执行失败" : message);
        return obj.toString();
    }

    /* ---------------- 响应分发 ---------------- */

    private static void dispatch(Activity activity, String siteKey, String resp) {
        if (activity == null || activity.isFinishing()) return;
        JsonObject obj = parse(resp);
        if (obj == null) {
            Toast.makeText(activity, "动作无响应", Toast.LENGTH_SHORT).show();
            return;
        }
        // 1. browser 动作：开内置 WebView（兼容顶层与嵌套 action 变体）
        JsonObject browser = browserAction(obj);
        if (browser != null) {
            GameWebActivity.start(activity, stringOf(browser, "url"), stringOf(browser, "title"),
                    null, null);
            return;
        }
        // 2. input 动作：弹输入框，输入后带 value 回传（链式输入）
        if ("input".equals(stringOf(obj, "type"))) {
            showInput(activity, siteKey, obj);
            return;
        }
        // 3. 结果列表：对话框显示
        JsonElement list = obj.get("list");
        if (list != null && list.isJsonArray() && list.getAsJsonArray().size() > 0) {
            showResult(activity, list);
            return;
        }
        // 4. msg 提示
        String msg = stringOf(obj, "msg");
        Toast.makeText(activity, TextUtils.isEmpty(msg) ? "操作完成" : msg, Toast.LENGTH_LONG).show();
    }

    /** 解析响应中的 browser 动作对象：顶层 type:browser，或嵌套在 action 字段里。 */
    private static JsonObject browserAction(JsonObject obj) {
        if ("browser".equals(stringOf(obj, "type"))) return hasUrl(obj) ? obj : null;
        JsonElement nested = obj.get("action");
        if (nested != null && nested.isJsonObject()) {
            JsonObject inner = nested.getAsJsonObject();
            if ("browser".equals(stringOf(inner, "type")) && hasUrl(inner)) return inner;
        }
        return null;
    }

    private static boolean hasUrl(JsonObject obj) {
        return !TextUtils.isEmpty(stringOf(obj, "url"));
    }

    /* ---------------- 输入弹窗 ---------------- */

    private static void showInput(Activity activity, String siteKey, JsonObject input) {
        String title = emptyTo(stringOf(input, "title"), "请输入");
        String tip = stringOf(input, "tip");
        String value = stringOf(input, "value");

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_action_input, null);
        EditText edit = view.findViewById(R.id.action_input);
        if (!TextUtils.isEmpty(value)) edit.setText(value);
        if (!TextUtils.isEmpty(tip)) edit.setHint(tip.replace("\\n", "\n"));

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(view)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = edit.getText().toString().trim();
            if (TextUtils.isEmpty(text)) {
                Toast.makeText(activity, "内容不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            // 把输入填回 value 字段后再次调用（py 端支持 str 与 {text:...} 两种结构）
            input.addProperty("value", text);
            handleAction(activity, siteKey, input.toString());
        });
    }

    /* ---------------- 结果展示 ---------------- */

    private static void showResult(Activity activity, JsonElement list) {
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : list.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject vod = el.getAsJsonObject();
            String name = stringOf(vod, "vod_name");
            if (TextUtils.isEmpty(name)) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(name);
            String remarks = stringOf(vod, "vod_remarks");
            if (!TextUtils.isEmpty(remarks)) sb.append("\n").append(remarks);
        }
        if (sb.length() == 0) sb.append("操作完成");
        new MaterialAlertDialogBuilder(activity)
                .setTitle("执行结果")
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /* ---------------- 工具 ---------------- */

    private static String stringOf(JsonObject obj, String key) {
        if (obj == null) return null;
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return null;
        String value = el.getAsString();
        return value == null ? null : value.trim();
    }

    private static String emptyTo(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private static JsonObject parse(String json) {
        if (TextUtils.isEmpty(json)) return null;
        String s = json.trim();
        if (!s.startsWith("{")) return null;
        try {
            JsonElement el = JsonParser.parseString(s);
            if (el != null && el.isJsonObject()) return el.getAsJsonObject();
        } catch (Throwable ignore) {
        }
        return null;
    }
}
