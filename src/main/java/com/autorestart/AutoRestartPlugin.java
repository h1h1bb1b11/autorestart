package com.autorestart;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 每天定时自动重启服务器，并提前广播公告与倒计时。 */
public final class AutoRestartPlugin extends JavaPlugin {

    private static final DateTimeFormatter LOG_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 重启标记文件名，需与 start.bat 中判断的文件名一致 */
    private static final String RESTART_FLAG = "restart.flag";

    private ZoneId zone;
    private LocalTime restartTime;
    private List<Integer> warnMinutes;
    private int finalCountdownSeconds;
    private String prefix;
    private String kickMessage;

    private BukkitTask ticker;
    private ZonedDateTime nextRestart;
    /** 上一次 tick 时剩余的整秒数，用于判断是否跨过公告阈值 */
    private long lastRemaining = Long.MAX_VALUE;
    private boolean restarting;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Lang.load(this, getConfig().getString("language", "zh"));
        loadSettings();
        computeNextRestart();
        // 每秒检查一次剩余时间，按真实时钟计算，不受服务器卡顿影响
        ticker = Bukkit.getScheduler().runTaskTimer(this, this::tick, 0L, 20L);
        getLogger().info("下次自动重启时间: " + LOG_FORMAT.format(nextRestart));
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

        warnMinutes = new ArrayList<>(getConfig().getIntegerList("warn-minutes"));
        if (warnMinutes.isEmpty()) {
            warnMinutes = new ArrayList<>(List.of(30, 10, 5, 3, 1));
        }
        warnMinutes.removeIf(minutes -> minutes <= 0);
        warnMinutes.sort(Comparator.reverseOrder());

        finalCountdownSeconds = Math.max(0, Math.min(60,
                getConfig().getInt("final-countdown-seconds", 10)));
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

    /** 计算下一次重启时刻：今天的时间点已过则顺延到明天 */
    private void computeNextRestart() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime candidate = now.toLocalDate().atTime(restartTime).atZone(zone);
        if (!candidate.isAfter(now)) {
            candidate = candidate.plusDays(1);
        }
        nextRestart = candidate;
        lastRemaining = Long.MAX_VALUE;
    }

    private void tick() {
        if (restarting) {
            return;
        }
        long remaining = Duration.between(ZonedDateTime.now(zone), nextRestart).toSeconds();
        if (remaining <= 0) {
            performRestart();
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
    }

    private void announce(String message) {
        getLogger().info(message);
        Bukkit.broadcast(Component.text(prefix, NamedTextColor.GOLD)
                .append(Component.text(" " + message, NamedTextColor.YELLOW)));
    }

    /** 踢出所有玩家，写入重启标记后正常关服，由启动脚本负责重新拉起 */
    private void performRestart() {
        restarting = true;
        getLogger().info("已到达重启时间，开始重启服务器...");
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
