package com.example.mousebattery;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.CheckboxMenuItem;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * タスクバー通知領域(システムトレイ)のアイコンとメニュー。
 * アイコン画像はバッテリー残量の数値をその場で描画する。
 */
public final class TrayController {

    private final Config config;
    private final NotificationService notifier;

    private TrayIcon trayIcon;
    private BatteryPoller poller;
    private MouseBattery state = MouseBattery.absent();

    public TrayController(Config config, NotificationService notifier) {
        this.config = config;
        this.notifier = notifier;
    }

    public void setPoller(BatteryPoller poller) {
        this.poller = poller;
    }

    public void install() {
        trayIcon = new TrayIcon(renderIcon(state), tooltip(state));
        trayIcon.setImageAutoSize(false);
        trayIcon.setPopupMenu(buildMenu());
        trayIcon.addActionListener(e -> {
            if (poller != null) {
                poller.refreshNow();
            }
        });
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            System.err.println("failed to add tray icon: " + e.getMessage());
            return;
        }
        notifier.setToast((t, m, ty) -> trayIcon.displayMessage(t, m, ty));
    }

    public void update(MouseBattery s) {
        this.state = s;
        if (trayIcon != null) {
            trayIcon.setImage(renderIcon(s));
            trayIcon.setToolTip(tooltip(s));
        }
    }

    private PopupMenu buildMenu() {
        PopupMenu menu = new PopupMenu();

        MenuItem refresh = new MenuItem("今すぐ更新");
        refresh.addActionListener(e -> {
            if (poller != null) {
                poller.refreshNow();
            }
        });
        menu.add(refresh);

        menu.addSeparator();

        CheckboxMenuItem sound = new CheckboxMenuItem("低残量で警告音", config.soundEnabled);
        sound.addItemListener(e -> {
            config.soundEnabled = sound.getState();
            config.save();
        });
        menu.add(sound);

        CheckboxMenuItem notif = new CheckboxMenuItem("低残量でトースト通知", config.notificationsEnabled);
        notif.addItemListener(e -> {
            config.notificationsEnabled = notif.getState();
            config.save();
        });
        menu.add(notif);

        menu.add(buildThresholdMenu("「残りわずか」しきい値", new int[]{10, 15, 20, 25, 30},
                () -> config.lowThreshold, v -> config.lowThreshold = v));
        menu.add(buildThresholdMenu("「危険」しきい値", new int[]{5, 7, 10, 15},
                () -> config.criticalThreshold, v -> config.criticalThreshold = v));
        menu.add(buildVolumeMenu());

        MenuItem testAlert = new MenuItem("警告音をテスト");
        testAlert.addActionListener(e -> notifier.test());
        menu.add(testAlert);

        menu.addSeparator();

        MenuItem showConfig = new MenuItem("設定ファイルの場所");
        showConfig.addActionListener(e ->
                trayIcon.displayMessage("設定ファイル", config.file().toString(), TrayIcon.MessageType.INFO));
        menu.add(showConfig);

        MenuItem quit = new MenuItem("終了");
        quit.addActionListener(e -> {
            config.save();
            System.exit(0);
        });
        menu.add(quit);

        return menu;
    }

    private interface IntGetter {
        int get();
    }

    private interface IntSetter {
        void set(int v);
    }

    private Menu buildThresholdMenu(String label, int[] values, IntGetter getter, IntSetter setter) {
        Menu m = new Menu(label);
        for (int v : values) {
            CheckboxMenuItem it = new CheckboxMenuItem(v + "%", getter.get() == v);
            it.addItemListener(e -> {
                setter.set(v);
                config.save();
                for (int i = 0; i < m.getItemCount(); i++) {
                    MenuItem mi = m.getItem(i);
                    if (mi instanceof CheckboxMenuItem c) {
                        c.setState(c.getLabel().equals(v + "%"));
                    }
                }
            });
            m.add(it);
        }
        return m;
    }

    private Menu buildVolumeMenu() {
        Menu m = new Menu("警告音の音量");
        int[] percents = {20, 40, 60, 80, 100};
        for (int p : percents) {
            float val = p / 100f;
            CheckboxMenuItem it = new CheckboxMenuItem(p + "%", Math.abs(config.soundVolume - val) < 0.01f);
            it.addItemListener(e -> {
                config.soundVolume = val;
                config.save();
                for (int i = 0; i < m.getItemCount(); i++) {
                    MenuItem mi = m.getItem(i);
                    if (mi instanceof CheckboxMenuItem c) {
                        c.setState(c.getLabel().equals(p + "%"));
                    }
                }
                notifier.test();
            });
            m.add(it);
        }
        return m;
    }

    private String tooltip(MouseBattery s) {
        if (!s.present()) {
            return "マウス未検出 — 接続 / スリープ解除を確認してください";
        }
        String suffix = switch (s.status()) {
            case CHARGING -> "(充電中)";
            case FULL -> "(満充電)";
            default -> "";
        };
        return s.deviceName() + " : " + s.percent() + "% " + suffix;
    }

    /** バッテリー残量の数値を描いたトレイアイコン画像を生成する。 */
    private BufferedImage renderIcon(MouseBattery s) {
        int size = 16;
        try {
            Dimension d = SystemTray.getSystemTray().getTrayIconSize();
            size = Math.max(16, Math.min(48, Math.max(d.width, d.height)));
        } catch (Exception ignored) {
            // デフォルト 16
        }

        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        Color fg = colorFor(s);

        if (!s.present()) {
            g.setColor(fg);
            g.setStroke(new BasicStroke(Math.max(1f, size / 12f)));
            int m = size / 5;
            g.drawLine(m, m, size - m, size - m);
            g.drawLine(size - m, m, m, size - m);
            g.dispose();
            return img;
        }

        String text = s.percent() >= 100 ? "OK" : String.valueOf(s.percent());
        float fontSize = text.length() <= 2 ? size * 0.80f : size * 0.58f;
        g.setFont(new Font("SansSerif", Font.BOLD, Math.round(fontSize)));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text);
        float x = (size - tw) / 2f;
        float y = (size - fm.getHeight()) / 2f + fm.getAscent();

        g.setColor(fg);
        g.drawString(text, x, y);

        if (s.charging()) {
            g.setColor(new Color(90, 200, 255));
            int r = Math.max(3, size / 5);
            g.fillOval(size - r, 0, r, r);
        }

        g.dispose();
        return img;
    }

    /** 残量に応じた色。10%以下=赤、25%以下=橙、それ以上=緑、充電中=水色、未検出=灰。 */
    static Color colorFor(MouseBattery b) {
        if (!b.present()) {
            return new Color(150, 150, 150);
        }
        if (b.charging()) {
            return new Color(90, 200, 255);
        }
        int p = b.percent();
        if (p <= 10) {
            return new Color(255, 80, 80);
        }
        if (p <= 25) {
            return new Color(255, 190, 60);
        }
        return new Color(120, 220, 130);
    }
}
