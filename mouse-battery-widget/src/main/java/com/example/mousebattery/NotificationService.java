package com.example.mousebattery;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.awt.TrayIcon;

/**
 * 低残量アラート。トースト通知(出せる時だけ)と警告音を担当する。
 *
 * <p>しきい値を「健全 → 残りわずか → 危険」と悪化したときだけ 1 回鳴らす。
 * 充電を開始したらリセットする。</p>
 */
public final class NotificationService {

    /** 2=健全, 1=残りわずか, 0=危険。悪化方向に変化したときだけ通知する。 */
    private int lastBucket = 2;

    private final Config config;

    /** トースト通知の出力先(TrayController がセットする)。 */
    public interface Toast {
        void show(String title, String message, TrayIcon.MessageType type);
    }

    private Toast toast = (t, m, ty) -> { };

    public NotificationService(Config config) {
        this.config = config;
    }

    public void setToast(Toast toast) {
        this.toast = toast;
    }

    public void onUpdate(MouseBattery b) {
        if (!b.present()) {
            return;
        }
        if (b.charging()) {
            lastBucket = 2;
            return;
        }

        int p = b.percent();
        int bucket = p <= config.criticalThreshold ? 0 : (p <= config.lowThreshold ? 1 : 2);

        if (bucket < lastBucket) {
            if (bucket == 0) {
                alert("マウスのバッテリー残量が危険域です",
                        b.deviceName() + " : " + p + "%  すぐに充電してください",
                        TrayIcon.MessageType.ERROR, 3);
            } else if (bucket == 1) {
                alert("マウスのバッテリー残量が少なくなっています",
                        b.deviceName() + " : " + p + "%",
                        TrayIcon.MessageType.WARNING, 2);
            }
        }
        lastBucket = bucket;
    }

    /** トレイメニューの「警告音をテスト」用。 */
    public void test() {
        alert("テスト", "警告音と通知の確認です", TrayIcon.MessageType.INFO, 2);
    }

    private void alert(String title, String message, TrayIcon.MessageType type, int beeps) {
        if (config.notificationsEnabled) {
            try {
                toast.show(title, message, type);
            } catch (Exception ignored) {
                // トースト非対応環境でも音は鳴らす
            }
        }
        if (config.soundEnabled) {
            playBeeps(beeps);
        }
    }

    /**
     * 短い警告音をオーディオ API から直接鳴らす。
     *
     * <p>これは単なる音声出力なので、ゲームが排他的全画面で動いていても、
     * 「集中モード」が通知音を抑制していても鳴る。</p>
     */
    private void playBeeps(int count) {
        final float amp = Math.max(0f, Math.min(1f, config.soundVolume));
        if (amp <= 0.001f) {
            return;
        }
        Thread t = new Thread(() -> {
            AudioFormat fmt = new AudioFormat(44100f, 16, 1, true, false);
            try (SourceDataLine line = AudioSystem.getSourceDataLine(fmt)) {
                line.open(fmt, 8192);
                line.start();
                for (int i = 0; i < Math.max(1, count); i++) {
                    writeTone(line, 880.0, 150, amp);
                    writeSilence(line, 90);
                }
                line.drain();
                line.stop();
            } catch (Exception e) {
                java.awt.Toolkit.getDefaultToolkit().beep();
            }
        }, "battery-alert-sound");
        t.setDaemon(true);
        t.start();
    }

    private static void writeTone(SourceDataLine line, double freq, int millis, float amp) {
        int samples = (int) (44100.0 * millis / 1000.0);
        byte[] buf = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            double fade = Math.min(1.0, Math.min(i, samples - i) / 500.0); // クリック音防止のフェード
            short v = (short) (Math.sin(2 * Math.PI * freq * i / 44100.0) * 30000 * amp * fade);
            buf[i * 2] = (byte) (v & 0xFF);
            buf[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        line.write(buf, 0, buf.length);
    }

    private static void writeSilence(SourceDataLine line, int millis) {
        int bytes = (int) (44100.0 * millis / 1000.0) * 2;
        line.write(new byte[bytes], 0, bytes);
    }
}
