package com.example.mousebattery;

import javax.swing.SwingUtilities;
import java.awt.SystemTray;

/**
 * エントリポイント。
 *
 * <p>ウィンドウを持たない常駐アプリで、タスクバーの通知領域(システムトレイ)に
 * マウスのバッテリー残量を数値で表示する。低残量になるとトースト通知と警告音を出す。</p>
 *
 * <ul>
 *   <li>{@code (引数なし)} … 常駐起動</li>
 *   <li>{@code --debug} … 接続中の HID デバイス一覧とバッテリー取得の詳細ログを出して終了</li>
 * </ul>
 */
public final class App {

    public static void main(String[] args) {
        for (String a : args) {
            if ("--debug".equals(a) || "-d".equals(a)) {
                HidppClient.VERBOSE = true;
                DebugTool.run();
                return;
            }
            if ("--help".equals(a) || "-h".equals(a)) {
                System.out.println("usage: java -jar mouse-battery-widget.jar [--debug]");
                return;
            }
        }

        // ヘッドレス扱いされるとトレイもサウンドも使えないので明示的に無効化
        System.setProperty("java.awt.headless", "false");

        Config config = Config.load();

        NotificationService notifier = new NotificationService(config);
        TrayController tray = new TrayController(config, notifier);

        BatteryPoller poller = new BatteryPoller(state -> SwingUtilities.invokeLater(() -> {
            tray.update(state);
            notifier.onUpdate(state);
        }));
        tray.setPoller(poller);

        SwingUtilities.invokeLater(() -> {
            if (!SystemTray.isSupported()) {
                System.err.println("System tray is not available on this platform.");
                System.exit(1);
            }
            tray.install();
        });

        poller.setPollSeconds(config.pollSeconds);
        poller.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            config.save();
            poller.stop();
        }));
    }

    private App() {
    }
}
