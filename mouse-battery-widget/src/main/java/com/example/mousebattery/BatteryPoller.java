package com.example.mousebattery;

import org.hid4java.HidDevice;
import org.hid4java.HidManager;
import org.hid4java.HidServices;
import org.hid4java.HidServicesSpecification;
import org.hid4java.ScanMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 一定間隔でマウスのバッテリーを取得し、結果をリスナへ渡す。
 * 全ての HID アクセスは専用の 1 スレッド上で行う。
 */
public final class BatteryPoller {

    private final Consumer<MouseBattery> listener;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "battery-poller");
        t.setDaemon(true);
        return t;
    });

    private HidServices hidServices;
    private volatile int pollSeconds = 60;
    private volatile boolean running;

    private HidDevice openDevice;
    private HidppClient client;

    public BatteryPoller(Consumer<MouseBattery> listener) {
        this.listener = listener;
    }

    public void setPollSeconds(int seconds) {
        this.pollSeconds = Math.max(15, seconds);
    }

    public void start() {
        HidServicesSpecification spec = new HidServicesSpecification();
        spec.setAutoStart(true);
        spec.setAutoDataRead(false);   // 読み取りは自前で行う
        spec.setScanMode(ScanMode.NO_SCAN);
        hidServices = HidManager.getHidServices(spec);

        running = true;
        exec.schedule(this::cycle, 2, TimeUnit.SECONDS);
    }

    /** メニューから即時更新するとき用。 */
    public void refreshNow() {
        exec.execute(this::pollSafe);
    }

    private void cycle() {
        pollSafe();
        if (running && !exec.isShutdown()) {
            exec.schedule(this::cycle, pollSeconds, TimeUnit.SECONDS);
        }
    }

    private void pollSafe() {
        try {
            poll();
        } catch (Throwable t) {
            System.err.println("poll failed: " + t);
            listener.accept(MouseBattery.absent());
        }
    }

    private void poll() {
        // 既に開いているデバイスがあればそれを使う
        if (client != null && openDevice != null && safeIsOpen(openDevice)) {
            MouseBattery b = client.read(deviceName(openDevice));
            if (b != null) {
                listener.accept(b);
                return;
            }
            closeOpenDevice();
        }

        List<HidDevice> candidates = new ArrayList<>();
        for (HidDevice d : hidServices.getAttachedHidDevices()) {
            if (HidppClient.isLogitechHidpp(d)) {
                candidates.add(d);
            }
        }
        // 20 バイト long レポートを持つコレクション(usage 0x0002)を優先
        candidates.sort(Comparator.comparingInt(d -> (d.getUsage() & 0xFFFF) == 0x0002 ? 0 : 1));

        for (HidDevice d : candidates) {
            if (!d.open()) {
                continue;
            }
            HidppClient c = new HidppClient(d);
            MouseBattery b;
            try {
                b = c.read(deviceName(d));
            } catch (Exception e) {
                b = null;
            }
            if (b != null) {
                openDevice = d;
                client = c;
                listener.accept(b);
                return;
            }
            d.close();
        }

        listener.accept(MouseBattery.absent());
    }

    private static boolean safeIsOpen(HidDevice d) {
        try {
            return !d.isClosed();
        } catch (Exception e) {
            return false;
        }
    }

    private void closeOpenDevice() {
        try {
            if (openDevice != null) {
                openDevice.close();
            }
        } catch (Exception ignored) {
            // ignore
        }
        openDevice = null;
        client = null;
    }

    private static String deviceName(HidDevice d) {
        String p = d.getProduct();
        return (p == null || p.isBlank()) ? "Logitech Mouse" : p.trim();
    }

    public void stop() {
        running = false;
        closeOpenDevice();
        try {
            if (hidServices != null) {
                hidServices.shutdown();
            }
        } catch (Exception ignored) {
            // ignore
        }
        exec.shutdownNow();
    }
}
