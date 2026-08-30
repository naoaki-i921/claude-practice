package com.example.mousebattery;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 設定。{@code %APPDATA%\MouseBatteryWidget\config.properties} に読み書きする。
 * トレイメニューから変更した内容はここに保存される。
 */
public final class Config {

    private final Path file;
    private final Properties props = new Properties();

    /** 「残りわずか」通知のしきい値(%)。 */
    public int lowThreshold = 15;
    /** 「危険」通知のしきい値(%)。 */
    public int criticalThreshold = 7;
    /** 低残量時に警告音を鳴らすか。 */
    public boolean soundEnabled = true;
    /** 警告音の音量(0.0〜1.0)。 */
    public float soundVolume = 0.8f;
    /** 警告音のファイル(WAV/AIFF/AU)。空なら組み込みのビープ音。 */
    public String soundFile = "";
    /** 低残量時にトースト通知を出すか。 */
    public boolean notificationsEnabled = true;
    /** 取得間隔(秒)。 */
    public int pollSeconds = 60;

    private Config(Path file) {
        this.file = file;
    }

    public static Config load() {
        String appData = System.getenv("APPDATA");
        Path base = Paths.get(
                appData != null && !appData.isBlank() ? appData : System.getProperty("user.home"),
                "MouseBatteryWidget");
        try {
            Files.createDirectories(base);
        } catch (IOException ignored) {
            // 生成できなくても後段の save で握りつぶす
        }

        Config c = new Config(base.resolve("config.properties"));
        if (Files.exists(c.file)) {
            try (InputStream in = Files.newInputStream(c.file)) {
                c.props.load(in);
            } catch (IOException e) {
                System.err.println("failed to load config: " + e.getMessage());
            }
            c.lowThreshold = clampPercent(getInt(c.props, "lowThreshold", c.lowThreshold), 5, 90);
            c.criticalThreshold = clampPercent(getInt(c.props, "criticalThreshold", c.criticalThreshold), 1, 50);
            c.soundEnabled = getBool(c.props, "soundEnabled", c.soundEnabled);
            c.soundVolume = (float) Math.max(0.0, Math.min(1.0, getDouble(c.props, "soundVolume", c.soundVolume)));
            c.soundFile = c.props.getProperty("soundFile", c.soundFile).trim();
            c.notificationsEnabled = getBool(c.props, "notificationsEnabled", c.notificationsEnabled);
            c.pollSeconds = Math.max(15, getInt(c.props, "pollSeconds", c.pollSeconds));
        } else {
            c.save();
        }
        return c;
    }

    public synchronized void save() {
        props.setProperty("lowThreshold", Integer.toString(lowThreshold));
        props.setProperty("criticalThreshold", Integer.toString(criticalThreshold));
        props.setProperty("soundEnabled", Boolean.toString(soundEnabled));
        props.setProperty("soundVolume", Float.toString(soundVolume));
        props.setProperty("soundFile", soundFile == null ? "" : soundFile);
        props.setProperty("notificationsEnabled", Boolean.toString(notificationsEnabled));
        props.setProperty("pollSeconds", Integer.toString(pollSeconds));
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "Mouse Battery Widget settings");
        } catch (IOException e) {
            System.err.println("failed to save config: " + e.getMessage());
        }
    }

    public Path file() {
        return file;
    }

    private static int clampPercent(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int getInt(Properties p, String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key, "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double getDouble(Properties p, String key, double fallback) {
        try {
            return Double.parseDouble(p.getProperty(key, "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean getBool(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        return v == null ? fallback : Boolean.parseBoolean(v.trim());
    }
}
