package com.fongmi.android.tv.lab;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.server.Server;
import com.github.catvod.Proxy;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LabConfig {

    public static final String ROOT_LOCAL = "/storage/emulated/0/Peekpro";
    public static final int SOURCE_BUILTIN = 0;
    public static final int SOURCE_LOCAL = 1;
    public static final int SOURCE_URL = 2;

    private static final String PREF = "lab";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_URL = "url";
    private static final String KEY_ROOT = "root";
    private static final String KEY_LOCAL_PATH = "local_path";
    private static final String KEY_FOREGROUND = "foreground";
    private static final String KEY_BATTERY = "battery";
    private static final String KEY_GLOBAL_PROXY = "global_proxy";
    private static final String KEY_GLOBAL_PROXY_PORT = "global_proxy_port";
    private static final String KEY_GLOBAL_PROXY_NO_PROXY = "global_proxy_no_proxy";
    private static final String KEY_IMPORTED = "imported";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final Gson gson = new Gson();
    private LabModels.LabRoot root;
    private volatile String configRoot;
    private String sourceName = "";
    private String loadError = "";

    private static class Loader {
        static final LabConfig INSTANCE = new LabConfig();
    }

    public static LabConfig get() {
        return Loader.INSTANCE;
    }

    private SharedPreferences sp() {
        return App.get().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public int getSource() {
        return sp().getInt(KEY_SOURCE, SOURCE_LOCAL);
    }

    public void setSource(int source) {
        sp().edit().putInt(KEY_SOURCE, source).apply();
    }

    public String getUrl() {
        return sp().getString(KEY_URL, "");
    }

    public void setUrl(String url) {
        sp().edit().putString(KEY_URL, url).apply();
    }

    public String getRoot() {
        String override = sp().getString(KEY_ROOT, "");
        if (!TextUtils.isEmpty(override)) return override;
        if (configRoot != null && !configRoot.isEmpty()) return configRoot;
        return ROOT_LOCAL;
    }

    public void setRoot(String root) {
        sp().edit().putString(KEY_ROOT, root).apply();
    }

    public String getLocalPath() {
        return sp().getString(KEY_LOCAL_PATH, "");
    }

    public void setLocalPath(String path) {
        sp().edit().putString(KEY_LOCAL_PATH, path).apply();
    }

    public boolean getForeground() {
        return sp().getBoolean(KEY_FOREGROUND, false);
    }

    public void setForeground(boolean value) {
        sp().edit().putBoolean(KEY_FOREGROUND, value).apply();
    }

    public boolean getBattery() {
        return sp().getBoolean(KEY_BATTERY, false);
    }

    public void setBattery(boolean value) {
        sp().edit().putBoolean(KEY_BATTERY, value).apply();
    }

    public boolean getGlobalProxy() {
        return sp().getBoolean(KEY_GLOBAL_PROXY, false);
    }

    public void setGlobalProxy(boolean value) {
        sp().edit().putBoolean(KEY_GLOBAL_PROXY, value).apply();
    }

    public int getGlobalProxyPort() {
        return sp().getInt(KEY_GLOBAL_PROXY_PORT, 7890);
    }

    public void setGlobalProxyPort(int value) {
        sp().edit().putInt(KEY_GLOBAL_PROXY_PORT, value).apply();
    }

    public String getGlobalProxyNoProxy() {
        return sp().getString(KEY_GLOBAL_PROXY_NO_PROXY, "localhost,127.0.0.1,::1");
    }

    public void setGlobalProxyNoProxy(String value) {
        sp().edit().putString(KEY_GLOBAL_PROXY_NO_PROXY, value).apply();
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getLoadError() {
        return loadError;
    }

    public LabModels.LabRoot getLabRoot() {
        return root;
    }

    public interface LoadCallback {
        void onLoaded(LabModels.LabRoot root);

        void onError(String message);
    }

    public void reload(LoadCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                root = loadSync();
                sourceName = describeSource();
                loadError = "";
                if (callback != null) App.post(() -> callback.onLoaded(root));
            } catch (Exception e) {
                loadError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (callback != null) App.post(() -> callback.onError(loadError));
            }
        });
    }

    public LabModels.LabRoot loadSync() throws IOException {
        int source = getSource();
        LabModels.LabRoot root = null;
        if (source == SOURCE_LOCAL) {
            InputStream local = openLocalConfig();
            if (local != null) {
                try {
                    root = parse(readAll(local));
                } finally {
                    local.close();
                }
            } else if (!TextUtils.isEmpty(getLocalPath())) {
                throw new IOException("无法读取本地配置文件: " + getLocalPath());
            }
        } else if (source == SOURCE_URL) {
            String url = getUrl();
            if (!TextUtils.isEmpty(url)) {
                try {
                    String text = download(url);
                    saveConfigCache(text);
                    root = parse(text);
                } catch (IOException e) {
                    String cached = readConfigCache();
                    if (cached == null) throw e;
                    root = parse(cached);
                }
            }
        }
        if (root == null) {
            try (InputStream in = App.get().getAssets().open("lab.json")) {
                root = parse(readAll(in));
            }
        }
        mergeImported(root);
        if (root.lists != null) {
            for (LabModels.Item item : root.lists) {
                if (item != null) applyCommandOverrides(item);
            }
        }
        return root;
    }

    private File configCacheFile() {
        return new File(App.get().getFilesDir(), "lab_cache/lab_config_cache.json");
    }

    private void saveConfigCache(String text) {
        try {
            File file = configCacheFile();
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private String readConfigCache() {
        File file = configCacheFile();
        if (!file.exists()) return null;
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in);
        } catch (Exception e) {
            return null;
        }
    }

    private InputStream openLocalConfig() throws IOException {
        String localPath = getLocalPath();
        java.util.List<String> candidates = new java.util.ArrayList<>();
        if (!TextUtils.isEmpty(localPath)) candidates.add(localPath);
        candidates.add(new File(getRoot(), "lab.json").getAbsolutePath());
        candidates.add("/storage/emulated/0/Download/lab.json");
        candidates.add("/storage/emulated/0/Downloads/lab.json");
        candidates.add("/storage/emulated/0/Documents/lab.json");
        candidates.add("/storage/emulated/0/lab.json");
        for (String candidate : candidates) {
            try {
                if (candidate.startsWith("content://")) {
                    InputStream in = App.get().getContentResolver().openInputStream(Uri.parse(candidate));
                    if (in != null) return in;
                } else {
                    File file = new File(candidate);
                    if (file.exists()) return new FileInputStream(file);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void mergeImported(LabModels.LabRoot root) {
        List<LabModels.Item> imported = loadImported();
        if (imported.isEmpty()) return;
        if (root.lists == null) root.lists = new java.util.ArrayList<>();
        for (LabModels.Item item : imported) {
            boolean exists = false;
            for (LabModels.Item other : root.lists) {
                if (other.name != null && other.name.equalsIgnoreCase(item.name)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                item.show = true;
                item.available = true;
                root.lists.add(item);
            }
        }
    }

    public void saveImported(String packageJson) {
        try {
            com.google.gson.JsonObject object = com.google.gson.JsonParser.parseString(packageJson).getAsJsonObject();
            String name = object.has("name") ? object.get("name").getAsString() : String.valueOf(System.currentTimeMillis());
            Map<String, String> map = importedMap();
            map.put(name, packageJson);
            sp().edit().putString(KEY_IMPORTED, gson.toJson(map)).apply();
        } catch (Exception ignored) {
        }
    }

    public List<LabModels.Item> loadImported() {
        List<LabModels.Item> list = new java.util.ArrayList<>();
        for (String json : importedMap().values()) {
            try {
                LabModels.Item item = gson.fromJson(json, LabModels.Item.class);
                if (item != null && item.name != null) list.add(item);
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    private Map<String, String> importedMap() {
        String json = sp().getString(KEY_IMPORTED, "");
        if (json.isEmpty()) return new java.util.HashMap<>();
        try {
            Map<String, String> map = gson.fromJson(json, new com.google.gson.reflect.TypeToken<Map<String, String>>() {
            }.getType());
            return map == null ? new java.util.HashMap<>() : map;
        } catch (Exception e) {
            return new java.util.HashMap<>();
        }
    }

    public void saveUserSettings(String pkg, Map<String, String> values) {
        sp().edit().putString("user_settings_" + pkg, gson.toJson(values)).apply();
    }

    public Map<String, String> loadUserSettings(String pkg) {
        String json = sp().getString("user_settings_" + pkg, "");
        if (json.isEmpty()) return new java.util.HashMap<>();
        try {
            Map<String, String> map = gson.fromJson(json, new com.google.gson.reflect.TypeToken<Map<String, String>>() {
            }.getType());
            return map == null ? new java.util.HashMap<>() : map;
        } catch (Exception e) {
            return new java.util.HashMap<>();
        }
    }

    public void saveCommandOverride(String pkg, String id, String commandJson) {
        Map<String, String> map = commandOverrides(pkg);
        map.put(id, commandJson);
        sp().edit().putString("cmd_overrides_" + pkg, gson.toJson(map)).apply();
    }

    public String getCommandOverride(String pkg, String id) {
        return commandOverrides(pkg).get(id);
    }

    public void removeCommandOverride(String pkg, String id) {
        Map<String, String> map = commandOverrides(pkg);
        map.remove(id);
        sp().edit().putString("cmd_overrides_" + pkg, gson.toJson(map)).apply();
    }

    public void clearCommandOverrides(String pkg) {
        sp().edit().remove("cmd_overrides_" + pkg).apply();
    }

    private Map<String, String> commandOverrides(String pkg) {
        String json = sp().getString("cmd_overrides_" + pkg, "");
        if (json.isEmpty()) return new java.util.HashMap<>();
        try {
            Map<String, String> map = gson.fromJson(json, new com.google.gson.reflect.TypeToken<Map<String, String>>() {
            }.getType());
            return map == null ? new java.util.HashMap<>() : map;
        } catch (Exception e) {
            return new java.util.HashMap<>();
        }
    }

    public void applyCommandOverrides(LabModels.Item item) {
        if (item == null || item.commands == null) return;
        Map<String, String> overrides = commandOverrides(item.name);
        if (!overrides.isEmpty()) {
            for (LabModels.Command command : item.commands) {
                String json = overrides.get(command.id);
                if (json == null) continue;
                try {
                    LabModels.Command override = gson.fromJson(json, LabModels.Command.class);
                    if (override == null) continue;
                    if (override.name != null) command.name = override.name;
                    if (override.description != null) command.description = override.description;
                    if (override.command != null) command.command = override.command;
                    command.auto_execute = override.auto_execute;
                } catch (Exception ignored) {
                }
            }
        }
        Map<String, String> caches = commandCaches(item.name);
        if (!caches.isEmpty()) {
            for (LabModels.Command command : item.commands) {
                String json = caches.get(command.id);
                if (json == null) continue;
                try {
                    com.google.gson.JsonObject cache = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                    if (cache.has("command") && !cache.get("command").getAsString().isEmpty()) {
                        command.command = cache.get("command").getAsString();
                    }
                    if (cache.has("values") && cache.get("values").isJsonObject()) {
                        Map<String, String> values = gson.fromJson(cache.getAsJsonObject("values"), new TypeToken<Map<String, String>>() {
                        }.getType());
                        if (values != null) command.cachedVariableValues = values;
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void saveCommandCache(String pkg, String id, Map<String, String> values, String expandedCommand) {
        Map<String, String> map = commandCaches(pkg);
        com.google.gson.JsonObject cache = new com.google.gson.JsonObject();
        cache.addProperty("command", expandedCommand == null ? "" : expandedCommand);
        cache.add("values", gson.toJsonTree(values == null ? new java.util.HashMap<String, String>() : values));
        map.put(id, cache.toString());
        sp().edit().putString("cmd_cache_" + pkg, gson.toJson(map)).apply();
    }

    public void clearCommandCache(String pkg) {
        sp().edit().remove("cmd_cache_" + pkg).apply();
    }

    private Map<String, String> commandCaches(String pkg) {
        String json = sp().getString("cmd_cache_" + pkg, "");
        if (json.isEmpty()) return new java.util.HashMap<>();
        try {
            Map<String, String> map = gson.fromJson(json, new TypeToken<Map<String, String>>() {
            }.getType());
            return map == null ? new java.util.HashMap<>() : map;
        } catch (Exception e) {
            return new java.util.HashMap<>();
        }
    }

    public void saveInstalledVersion(String pkg, String version) {
        sp().edit().putString("installed_" + pkg, version).apply();
    }

    public String getInstalledVersion(String pkg) {
        return sp().getString("installed_" + pkg, "");
    }

    private LabModels.LabRoot parse(String text) {
        String json = extractJson(text);
        if (json == null) throw new IllegalArgumentException("配置解析失败");
        LabModels.LabRoot parsed = gson.fromJson(json, LabModels.LabRoot.class);
        if (parsed == null || parsed.lists == null) throw new IllegalArgumentException("配置解析失败");
        if (parsed.root != null && !parsed.root.isEmpty()) configRoot = parsed.root;
        else detectRoot(parsed);
        return parsed;
    }

    private static String extractJson(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```").matcher(trimmed);
        if (matcher.find()) return matcher.group(1).trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return null;
    }

    private void detectRoot(LabModels.LabRoot parsed) {
        if (parsed == null || parsed.lists == null) return;
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (LabModels.Item item : parsed.lists) {
            if (item.downloads != null) {
                for (LabModels.Download download : item.downloads) {
                    score(counts, download.url);
                    score(counts, download.liburl);
                }
            }
            if (item.commands != null) {
                for (LabModels.Command command : item.commands) {
                    score(counts, command.command);
                    if (command.download != null) score(counts, command.download.url);
                    if (command.variables != null) {
                        for (LabModels.Variable variable : command.variables) score(counts, variable.defaultValue);
                    }
                }
            }
        }
        String best = null;
        int bestScore = 0;
        for (java.util.Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestScore) {
                best = entry.getKey();
                bestScore = entry.getValue();
            }
        }
        if (best != null) configRoot = "/storage/emulated/0/" + best;
    }

    private void score(java.util.Map<String, Integer> counts, String text) {
        if (text == null) return;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:/file/|/storage/emulated/0/)([A-Za-z0-9_\\-]+)/").matcher(text);
        while (matcher.find()) {
            String base = matcher.group(1);
            if (isCommonDir(base)) continue;
            counts.put(base, counts.getOrDefault(base, 0) + 1);
        }
    }

    private boolean isCommonDir(String base) {
        return "Download".equals(base)
                || "Downloads".equals(base)
                || "Documents".equals(base)
                || "TV".equals(base)
                || "DCIM".equals(base)
                || "Android".equals(base)
                || "Pictures".equals(base)
                || "Music".equals(base)
                || "Movies".equals(base);
    }

    private String describeSource() {
        int source = getSource();
        if (source == SOURCE_LOCAL) return App.get().getString(com.fongmi.android.tv.R.string.lab_source_local);
        if (source == SOURCE_URL) return App.get().getString(com.fongmi.android.tv.R.string.lab_source_url);
        return App.get().getString(com.fongmi.android.tv.R.string.lab_source_builtin);
    }

    public static String serverPort() {
        int port = Proxy.getPort();
        if (port <= 0) {
            Server.get().startManage();
            port = Proxy.getPort();
        }
        return String.valueOf(port > 0 ? port : 9978);
    }

    public static String dataPath() {
        return LabConfig.get().getRoot();
    }

    private static String download(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "WebHTV-Lab");
        try (InputStream in = conn.getInputStream()) {
            return readAll(in);
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
