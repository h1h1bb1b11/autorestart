package com.autorestart;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 语言文件加载与 {placeholder} 占位符替换。 */
public final class Lang {

    private static final String DEFAULT_LANGUAGE = "zh";
    private static final String[] SUPPORTED = {"zh", "en"};

    private static YamlConfiguration messages = new YamlConfiguration();

    private Lang() {
    }

    /** 加载指定语言；只接受 zh / en，其他值回退到 zh。同时释放两种语言文件供服主编辑。 */
    public static void load(JavaPlugin plugin, String requested) {
        String code = requested == null ? "" : requested.trim().toLowerCase();
        if (!isSupported(code)) {
            if (!code.isEmpty()) {
                plugin.getLogger().warning("language 仅支持 zh / en，已回退到 " + DEFAULT_LANGUAGE);
            }
            code = DEFAULT_LANGUAGE;
        }

        File dir = new File(plugin.getDataFolder(), "lang");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            plugin.getLogger().warning("无法创建语言目录: " + dir.getAbsolutePath());
            return;
        }
        for (String supported : SUPPORTED) {
            File file = new File(dir, supported + ".yml");
            if (!file.isFile()) {
                plugin.saveResource("lang/" + supported + ".yml", false);
            }
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(dir, code + ".yml"));
        // 数据目录中的语言文件缺少某个键时，回退到 jar 内置文本
        try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
            if (in != null) {
                config.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("读取内置语言文件失败: " + e.getMessage());
        }
        messages = config;
    }

    /** 读取文本并按 pairs（key, value 交替）替换 {key} 占位符，缺失时返回 key 本身。 */
    public static String tr(String key, Object... pairs) {
        String text = messages.getString(key, key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
        }
        return text;
    }

    private static boolean isSupported(String code) {
        for (String supported : SUPPORTED) {
            if (supported.equals(code)) {
                return true;
            }
        }
        return false;
    }
}
