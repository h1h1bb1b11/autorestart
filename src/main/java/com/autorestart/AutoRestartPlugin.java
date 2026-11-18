package com.autorestart;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 自动重启插件：
 * 1. 每周在固定时间重启一次（restart-day + restart-time）；
 * 2. 服务器持续卡顿（TPS 低于阈值）时先公告，再强制重启；
 * 3. 两次重启间隔不足 min-restart-interval-hours 时跳过本次重启。
 */
public final class AutoRestartPlugin extends JavaPlugin {

    private static final DateTimeFormatter LOG_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 重启标记文件名，需与 start.bat 中判断的文件名一致 */
    private static final String RESTART_FLAG = "restart.flag";
    /** 记录上次重启时间，重启后仍能延续冷却判断 */
    private static final String DATA_FILE = "data.yml";

    private ZoneId zone;
    private LocalTime restartTime;
    /** null 表示每天重启 */
    private DayOfWeek restartDay;
    private List<Integer> warnMinutes;
    private int finalCountdownSeconds;
    private String prefix;
    private String kickMessage;
    private int minIntervalHours;

    // 卡顿检测
    private boolean lagEnabled;
    private double lagTpsThreshold;
    private int lagSustainSeconds;
    private int lagSampleSeconds;
    private int lagCountdownSeconds;
    private long lagGraceUntil;
    private double lagLowSeconds;
    private long lagNextSampleAt;
    private int lagCountdownLeft;
    private long lastTickAt;

    private BukkitTask ticker;
    private ZonedDateTime nextRestart;
    /** 上一次 tick 时剩余的整秒数，用于判断是否跨过公告阈值 */
    private long lastRemaining = Long.MAX_VALUE;
    /** 上一次真正执行重启的时间 */
    private Instant lastRestart;
    private boolean restarting;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Lang.load(this, getConfig().getString("language", "zh"));
        loadSettings();
        loadData();
        computeNextRestart();
        // 每秒检查一次：定时重启倒计时、卡顿采样
        ticker = Bukkit.getScheduler().runTaskTimer(this, this::tick, 0L, 20L);
        getLogger().info("下次自动重启时间: " + LOG_FORMAT.format(nextRestart));
        if (lagEnabled) {
            getLogger().info("卡顿重启已启用: TPS < " + lagTpsThreshold + " 持续 "
                    + lagSustainSeconds + " 秒后重启");
        }
    }

    @Override
    public void onDisable() {
        if (ticker != null) {
            ticker.cancel();
        }
    }

    /** 读取 config.yml 配置，格式有误时回退到默认值 */
    private void loadSettings() {
        String timeStr = getConfig().getString("restart-time", "03:00");
        try {
            restartTime = LocalTime.parse(timeStr == null ? "03:00" : timeStr.trim());
        } catch (Exception e) {
            getLogger().warning("restart-time 格式错误（应为 HH:mm），已使用默认值 03:00");
            restartTime = LocalTime.of(3, 0);
        }

        String zoneStr = getConfig().getString("time-zone", "");
        if (zoneStr == null || zoneStr.isBlank()) {
            zone = ZoneId.systemDefault();
        } else {
            try {
                zone = ZoneId.of(zoneStr.trim());
            } catch (Exception e) {
                getLogger().warning("time-zone 无效（" + zoneStr + "），已使用系统时区");
                zone = ZoneId.systemDefault();
            }
        }

        // restart-day: MONDAY..SUNDAY / 1..7，daily 表示每天
        String dayStr = getConfig().getString("restart-day", "MONDAY");
        restartDay = null;
        if (dayStr != null && !dayStr.isBlank() && !dayStr.trim().equalsIgnoreCase("daily")) {
            try {
                String value = dayStr.trim().toUpperCase(Locale.ROOT);
                int index = parseDayNumber(value);
                restartDay = index > 0 ? DayOfWeek.of(index) : DayOfWeek.valueOf(value);
            } catch (Exception e) {
                getLogger().warning("restart-day 无效（" + dayStr + "），已改为每天重启");
                restartDay = null;
            }
        }

        warnMinutes = new ArrayList<>(getConfig().getIntegerList("warn-minutes"));
        if (warnMinutes.isEmpty()) {
            warnMinutes = new ArrayList<>(List.of(30, 10, 5, 3, 1));
        }
        warnMinutes.removeIf(minutes -> minutes <= 0);
        warnMinutes.sort(Comparator.reverseOrder());

        finalCountdownSeconds = Math.max(0, Math.min(60,
                getConfig().getInt("final-countdown-seconds", 10)));

        minIntervalHours = Math.max(0, getConfig().getInt("min-restart-interval-hours", 5));

        lagEnabled = getConfig().getBoolean("lag-restart.enabled", true);
        lagTpsThreshold = getConfig().getDouble("lag-restart.tps-threshold", 15.0);
        lagSampleSeconds = Math.max(1, getConfig().getInt("lag-restart.sample-interval-seconds", 10));
        lagSustainSeconds = Math.max(lagSampleSeconds,
                getConfig().getInt("lag-restart.sustain-seconds", 60));
        lagCountdownSeconds = Math.max(1, Math.min(120,
                getConfig().getInt("lag-restart.countdown-seconds", 15)));
        lagGraceUntil = System.currentTimeMillis()
                + Math.max(0, getConfig().getInt("lag-restart.grace-seconds", 120)) * 1000L;

        // 未单独配置时使用语言文件中的文案
        prefix = getConfig().getString("prefix", "");
        if (prefix == null || prefix.isBlank()) {
            prefix = Lang.tr("prefix");
        }
        kickMessage = getConfig().getString("kick-message", "");
        if (kickMessage == null || kickMessage.isBlank()) {
            kickMessage = Lang.tr("kick-message");
        }
    }

    private static int parseDayNumber(String value) {
        try {
            int n = Integer.parseInt(value);
            return (n >= 1 && n <= 7) ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void loadData() {
        File file = new File(getDataFolder(), DATA_FILE);
        if (!file.isFile()) {
            return;
        }
        long millis = YamlConfiguration.loadConfiguration(file).getLong("last-restart", 0L);
        if (millis > 0L) {
            lastRestart = Instant.ofEpochMilli(millis);
        }
    }

    private void saveLastRestart(Instant instant) {
        YamlConfiguration data = new YamlConfiguration();
        data.set("last-restart", instant.toEpochMilli());
        try {
            data.save(new File(getDataFolder(), DATA_FILE));
        } catch (IOException e) {
            getLogger().warning("保存上次重启时间失败: " + e.getMessage());
        }
    }

    /** 计算下一次定时重启：每周模式取下一个指定星期，daily 模式取下一个时间点 */
    private void computeNextRestart() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime candidate = now.toLocalDate().atTime(restartTime).atZone(zone);
        if (restartDay == null) {
            if (!candidate.isAfter(now)) {
                candidate = candidate.plusDays(1);
            }
        } else {
            for (int i = 0; i < 14; i++) {
                if (candidate.getDayOfWeek() == restartDay && candidate.isAfter(now)) {
                    break;
                }
                candidate = candidate.plusDays(1);
            }
        }
        nextRestart = candidate;
        lastRemaining = Long.MAX_VALUE;
    }

    private void tick() {
        if (restarting) {
            return;
        }
        long nowMs = System.currentTimeMillis();

        // 卡顿重启倒计时：每秒播报，结束后直接重启
        if (lagCountdownLeft > 0) {
            announce(Lang.tr("lag-warning-seconds", "seconds", lagCountdownLeft));
            lagCountdownLeft--;
            lastTickAt = nowMs;
            if (lagCountdownLeft == 0) {
                performRestart();
            }
            return;
        }

        long remaining = Duration.between(ZonedDateTime.now(zone), nextRestart).toSeconds();
        if (remaining <= 0) {
            if (canRestartNow()) {
                performRestart();
                return;
            }
            getLogger().info("到达定时重启时间，但距上次重启不足 " + minIntervalHours
                    + " 小时，跳过本次重启");
            computeNextRestart();
            getLogger().info("下次自动重启时间: " + LOG_FORMAT.format(nextRestart));
            return;
        }

        long previous = lastRemaining == Long.MAX_VALUE ? remaining + 1 : lastRemaining;

        // 分钟级公告：只在本秒跨过阈值时播报一次
        for (int minutes : warnMinutes) {
            long threshold = minutes * 60L;
            if (previous > threshold && remaining <= threshold) {
                announce(Lang.tr("restart-warning-minutes", "minutes", minutes));
            }
        }

        // 最后几秒逐秒倒计时
        if (finalCountdownSeconds > 0 && remaining <= finalCountdownSeconds && remaining < previous) {
            announce(Lang.tr("restart-warning-seconds", "seconds", remaining));
        }

        lastRemaining = remaining;

        checkLag(nowMs);
        lastTickAt = nowMs;
    }

    /** 采样 TPS，持续低于阈值达到设定时长后进入重启倒计时 */
    private void checkLag(long nowMs) {
        if (!lagEnabled || nowMs < lagGraceUntil || nowMs < lagNextSampleAt) {
            return;
        }
        lagNextSampleAt = nowMs + lagSampleSeconds * 1000L;
        double tps = readTps(nowMs);
        if (tps >= lagTpsThreshold) {
            lagLowSeconds = 0;
            return;
        }

        lagLowSeconds += lagSampleSeconds;
        getLogger().info(String.format(Locale.ROOT, "服务器负载偏低: TPS %.1f（%d/%d 秒）",
                tps, (int) lagLowSeconds, lagSustainSeconds));
        if (lagLowSeconds < lagSustainSeconds) {
            return;
        }

        lagLowSeconds = 0;
        if (!canRestartNow()) {
            getLogger().info("负载过低但距上次重启不足 " + minIntervalHours + " 小时，忽略本次重启");
            return;
        }
        lagCountdownLeft = lagCountdownSeconds;
        announce(Lang.tr("lag-warning",
                "tps", String.format(Locale.ROOT, "%.1f", tps),
                "seconds", lagCountdownLeft));
    }

    /** 读取服务器 TPS；不可用时用调度器的实际间隔估算 */
    private double readTps(long nowMs) {
        try {
            double[] tps = Bukkit.getServer().getTPS();
            if (tps != null && tps.length > 0) {
                return Math.max(0.0, Math.min(20.0, tps[0]));
            }
        } catch (Throwable ignored) {
            // 非 Paper 服务端没有 getTPS()，走下面的估算
        }
        if (lastTickAt <= 0L) {
            return 20.0;
        }
        double seconds = (nowMs - lastTickAt) / 1000.0;
        if (seconds <= 0.05 || seconds > 10.0) {
            return 20.0;
        }
        return Math.max(0.0, Math.min(20.0, 20.0 / seconds));
    }

    private boolean canRestartNow() {
        if (lastRestart == null) {
            return true;
        }
        return Duration.between(lastRestart, Instant.now()).toMinutes() >= minIntervalHours * 60L;
    }

    private void announce(String message) {
        getLogger().info(message);
        Bukkit.broadcast(Component.text(prefix, NamedTextColor.GOLD)
                .append(Component.text(" " + message, NamedTextColor.YELLOW)));
    }

    /** 踢出所有玩家，写入重启标记后正常关服，由启动脚本负责重新拉起 */
    private void performRestart() {
        restarting = true;
        lastRestart = Instant.now();
        saveLastRestart(lastRestart);
        getLogger().info("开始重启服务器...");
        Component kick = Component.text(kickMessage, NamedTextColor.RED);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.kick(kick);
        }
        // 延迟 2 秒再关服，给玩家数据保存留出时间
        Bukkit.getScheduler().runTaskLater(this, () -> {
            // 先写标记文件，启动脚本看到它才会重新开服；手动 /stop 不会触发重启
            File flag = new File(System.getProperty("user.dir"), RESTART_FLAG);
            try {
                Files.writeString(flag.toPath(), "auto-restart");
            } catch (IOException e) {
                getLogger().warning("写入重启标记失败（服务器可能不会自动拉起）: " + e.getMessage());
            }
            // 只做正常关服，不能调用 restart()，否则会和启动脚本的循环重复启动两个进程
            Bukkit.shutdown();
        }, 40L);
    }
}
