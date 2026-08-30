package com.example.mousebattery;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.SourceDataLine;
import java.awt.TrayIcon;
import java.io.File;
import java.util.concurrent.CountDownLatch;

/**
 * 低残量アラート。トースト通知(出せる時だけ)と警告音を担当する。
 *
 * <p>しきい値を「健全 → 残りわずか → 危険」と悪化したときだけ 1 回鳴らす。
 * 充電を開始したらリセットする。</p>
 *
 * <p>警告音は {@link Config#soundFile} が指定されていればその音声ファイルを、
 * 無ければ組み込みのビープ音を鳴らす。対応形式は Java 標準の
 * {@code javax.sound.sampled} が扱えるもの(WAV / AIFF / AU)。MP3 は非対応。</p>
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

    /** トレイメニューの「警告音をテスト」用。音は設定に関係なく鳴らす。 */
    public void test() {
        if (config.notificationsEnabled) {
            try {
                toast.show("テスト", "警告音と通知の確認です", TrayIcon.MessageType.INFO);
            } catch (Exception ignored) {
                // ignore
            }
        }
        playSound(2);
    }

    /**
     * 指定した音声ファイルが再生可能か確認する。
     *
     * @return 問題なければ {@code null}、駄目なら理由の文字列
     */
    public static String validateSoundFile(File file) {
        if (file == null || !file.isFile()) {
            return "ファイルが見つかりません";
        }
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(file)) {
            ais.getFormat();
            return null;
        } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
            return "対応していない形式です (WAV / AIFF / AU を使ってください)";
        } catch (Exception e) {
            return "読み込めませんでした: " + e.getMessage();
        }
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
            playSound(beeps);
        }
    }

    /** カスタム音があればそれを、無ければ組み込みビープを鳴らす。 */
    private void playSound(int beeps) {
        float vol = Math.max(0f, Math.min(1f, config.soundVolume));
        if (vol <= 0.001f) {
            return;
        }
        String path = config.soundFile;
        if (path != null && !path.isBlank() && new File(path).isFile()) {
            playFile(new File(path), vol, beeps);
        } else {
            playBeeps(beeps, vol);
        }
    }

    /**
     * 音声ファイルを再生する。失敗したら組み込みビープにフォールバック。
     *
     * <p>これは単なる音声出力なので、ゲームが排他的全画面で動いていても、
     * 「集中モード」が通知音を抑制していても鳴る。</p>
     */
    private void playFile(File file, float vol, int fallbackBeeps) {
        Thread t = new Thread(() -> {
            try {
                AudioInputStream raw = AudioSystem.getAudioInputStream(file);
                AudioInputStream ais = toPcm(raw);
                Clip clip = AudioSystem.getClip();
                clip.open(ais);
                applyGain(clip, vol);

                CountDownLatch done = new CountDownLatch(1);
                clip.addLineListener(e -> {
                    if (e.getType() == LineEvent.Type.STOP) {
                        done.countDown();
                    }
                });
                clip.start();
                done.await();
                clip.close();
                ais.close();
            } catch (Exception e) {
                playBeeps(fallbackBeeps, vol);
            }
        }, "battery-alert-sound");
        t.setDaemon(true);
        t.start();
    }

    private static AudioInputStream toPcm(AudioInputStream ais) {
        AudioFormat f = ais.getFormat();
        if (f.getEncoding() == AudioFormat.Encoding.PCM_SIGNED) {
            return ais;
        }
        float rate = f.getSampleRate() > 0 ? f.getSampleRate() : 44100f;
        int ch = f.getChannels() > 0 ? f.getChannels() : 2;
        AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, ch, ch * 2, rate, false);
        return AudioSystem.getAudioInputStream(target, ais);
    }

    private static void applyGain(Line line, float vol) {
        if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
        float dB = vol <= 0.0001f ? gain.getMinimum() : (float) (20.0 * Math.log10(vol));
        gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB)));
    }

    /** 組み込みの短いビープ音。 */
    private void playBeeps(int count, float amp) {
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
