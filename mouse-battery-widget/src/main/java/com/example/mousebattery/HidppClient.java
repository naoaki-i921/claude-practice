package com.example.mousebattery;

import org.hid4java.HidDevice;

/**
 * Logitech(Logicool) マウスのバッテリー残量を読む最小限の HID++ 2.0 クライアント。
 *
 * <p>Lightspeed / Unifying レシーバー経由でも、USB ケーブル直結でも動作する。
 * 送信は 20 バイトの「long レポート」(reportId 0x11) のみを使う。現行の
 * Logitech レシーバー / ゲーミングマウスは usage page 0xFF00 / usage 0x0002 の
 * HID コレクションでこれを必ず公開している。</p>
 *
 * <p>参考にした一般公開情報:
 * <ul>
 *   <li>HID++ 2.0 feature 0x1000 batteryLevelStatus</li>
 *   <li>HID++ 2.0 feature 0x1004 unifiedBattery</li>
 *   <li>libratbag / Solaar のプロトコル実装</li>
 * </ul>
 * </p>
 */
public final class HidppClient {

    /** true にすると送受信バイト列を標準エラーへ出す。 */
    public static volatile boolean VERBOSE = false;

    static final int VENDOR_LOGITECH = 0x046D;

    private static final byte REPORT_LONG = 0x11;   // 20 バイト。送受信に使う
    private static final byte REPORT_SHORT = 0x10;  // 7 バイト。受信のみ考慮
    private static final int LONG_PAYLOAD = 19;     // reportId を除いた本体長

    /**
     * 応答照合用の 4bit ソフトウェア ID。呼び出しごとに 1..15 で回す。
     *
     * <p>root.getFeature の応答は「どの featureId を尋ねたか」を含まないため、
     * 連続で問い合わせると前の応答を次の応答と取り違える。SW ID を毎回変えることで
     * 遅延して届いた古い応答を確実に読み飛ばせる。</p>
     */
    private int swidCounter = 0;

    private static final int FEATURE_ROOT = 0x0000;
    private static final int FEATURE_DEVICE_NAME = 0x0005;      // deviceNameAndType
    private static final int FEATURE_BATTERY_STATUS = 0x1000;   // batteryLevelStatus
    private static final int FEATURE_UNIFIED_BATTERY = 0x1004;  // unifiedBattery

    /** 探索するデバイスインデックス。レシーバー経由(1..6)を先に、USB 直結(0xFF)を後に。 */
    private static final int[] DEVICE_INDICES = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0xFF};

    private final HidDevice device;

    // 一度成功した組み合わせを覚えて、次回以降は 1 往復で済ませる
    private int cachedDeviceIndex = -1;
    private int cachedBatteryFeatureId = -1;
    private int cachedBatteryFeatureIndex = -1;
    private String cachedName;

    public HidppClient(HidDevice device) {
        this.device = device;
    }

    public static boolean isLogitechHidpp(HidDevice d) {
        return d.getVendorId() == VENDOR_LOGITECH && (d.getUsagePage() & 0xFFFF) >= 0xFF00;
    }

    /**
     * バッテリー情報を取得する。
     *
     * @return 取得結果。この HID デバイスが応答しない場合は {@code null}
     */
    public MouseBattery read(String deviceName) {
        int[] indices = cachedDeviceIndex >= 0 ? new int[]{cachedDeviceIndex} : DEVICE_INDICES;
        for (int di : indices) {
            try {
                MouseBattery b = readForIndex(di, deviceName);
                if (b != null) {
                    cachedDeviceIndex = di;
                    return b;
                }
            } catch (Exception e) {
                log("device index " + di + " failed: " + e);
            }
        }
        // 応答がなければキャッシュを捨てて次回に再探索させる
        cachedDeviceIndex = -1;
        cachedBatteryFeatureId = -1;
        cachedBatteryFeatureIndex = -1;
        return null;
    }

    private MouseBattery readForIndex(int di, String fallbackName) {
        // --- 従来の batteryLevelStatus (0x1000): 現行ゲーミングマウスの多くが対応 ---
        int bls = batteryFeatureIndex(di, FEATURE_BATTERY_STATUS);
        if (bls > 0) {
            byte[] r = call(di, bls, 0x00, null); // getBatteryLevelStatus()
            if (r != null) {
                int level = r[4] & 0xFF;    // % (デバイスによっては段階値)
                int status = r[6] & 0xFF;   // 0:放電 1:充電 2:ほぼ満充電 3:満充電 4:低速充電
                remember(FEATURE_BATTERY_STATUS, bls);
                return MouseBattery.of(level, legacyState(status), resolveName(di, fallbackName));
            }
        }

        // --- 新しめのデバイス: unifiedBattery (0x1004) ---
        int uni = batteryFeatureIndex(di, FEATURE_UNIFIED_BATTERY);
        if (uni > 0) {
            byte[] r = call(di, uni, 0x01, null); // getStatus()
            if (r != null) {
                int soc = r[4] & 0xFF;              // state of charge (%)
                int chargingStatus = r[6] & 0xFF;   // 0:放電 1:充電 2:充電(ほぼ完了) 3:完了
                remember(FEATURE_UNIFIED_BATTERY, uni);
                return MouseBattery.of(soc, unifiedState(chargingStatus), resolveName(di, fallbackName));
            }
        }

        return null;
    }

    /** feature 0x0005 でデバイス名(= マウス名)を取得する。失敗時は fallback。結果はキャッシュ。 */
    private String resolveName(int di, String fallback) {
        if (cachedName != null) {
            return cachedName;
        }
        try {
            int idx = featureIndex(di, FEATURE_DEVICE_NAME);
            if (idx > 0) {
                byte[] cnt = call(di, idx, 0x00, null); // getDeviceNameCount()
                if (cnt != null) {
                    int length = cnt[4] & 0xFF;
                    StringBuilder sb = new StringBuilder(length);
                    for (int offset = 0; offset < length && offset < 128; offset += 15) {
                        byte[] part = call(di, idx, 0x01, new byte[]{(byte) offset}); // getDeviceName(offset)
                        if (part == null) {
                            break;
                        }
                        for (int i = 4; i < 20 && sb.length() < length; i++) {
                            int ch = part[i] & 0xFF;
                            if (ch == 0) {
                                break;
                            }
                            sb.append((char) ch);
                        }
                    }
                    String name = sb.toString().trim();
                    if (!name.isEmpty()) {
                        cachedName = name;
                        return name;
                    }
                }
            }
        } catch (Exception e) {
            log("device name lookup failed: " + e);
        }
        cachedName = fallback;
        return fallback;
    }

    private int batteryFeatureIndex(int di, int featureId) {
        if (cachedBatteryFeatureId == featureId && cachedBatteryFeatureIndex > 0) {
            return cachedBatteryFeatureIndex;
        }
        return featureIndex(di, featureId);
    }

    private void remember(int featureId, int featureIndex) {
        cachedBatteryFeatureId = featureId;
        cachedBatteryFeatureIndex = featureIndex;
    }

    /** root feature 経由で feature id をデバイス内 feature index に解決する。未対応なら -1。 */
    private int featureIndex(int di, int featureId) {
        byte[] params = {(byte) ((featureId >> 8) & 0xFF), (byte) (featureId & 0xFF)};
        byte[] r = call(di, FEATURE_ROOT, 0x00, params); // root.getFeature(featureId)
        if (r == null) {
            return -1;
        }
        int idx = r[4] & 0xFF;
        return idx == 0 ? -1 : idx;
    }

    /**
     * HID++ 2.0 のファンクション呼び出しを long レポートで送り、対応する応答を待つ。
     *
     * @return 20 バイトの応答(index 4 以降が戻り値)。タイムアウトまたは HID++ エラー時は {@code null}
     */
    private byte[] call(int deviceIndex, int featureIndex, int function, byte[] params) {
        synchronized (device) {
            drainPending();
            byte[] r = callOnce(deviceIndex, featureIndex, function, params, 700);
            if (r == null) {
                // 1 回目を取りこぼす個体があるので 1 度だけ再送
                drainPending();
                r = callOnce(deviceIndex, featureIndex, function, params, 1000);
            }
            return r;
        }
    }

    /** 前の問い合わせから遅れて届いた応答を読み捨てる。 */
    private void drainPending() {
        byte[] buf = new byte[64];
        for (int i = 0; i < 32; i++) {
            int n = device.read(buf, 2);
            if (n <= 0) {
                return;
            }
            log("drain: " + hex(buf, n));
        }
    }

    private int nextSwid() {
        swidCounter = (swidCounter % 15) + 1;
        return swidCounter;
    }

    private byte[] callOnce(int deviceIndex, int featureIndex, int function, byte[] params, int budgetMillis) {
        int sw = nextSwid();
        byte[] msg = new byte[LONG_PAYLOAD];
        msg[0] = (byte) deviceIndex;
        msg[1] = (byte) featureIndex;
        msg[2] = (byte) (((function & 0x0F) << 4) | (sw & 0x0F));
        if (params != null) {
            for (int i = 0; i < params.length && i < 16; i++) {
                msg[3 + i] = params[i];
            }
        }

        {
            int written = device.write(msg, msg.length, REPORT_LONG);
            if (written < 0) {
                log("write failed: " + device.getLastErrorMessage());
                return null;
            }
            log(String.format("> dev=%02X feat=%02X fn=%X  %s", deviceIndex, featureIndex, function, hex(msg)));

            byte[] buf = new byte[64];
            long deadline = System.currentTimeMillis() + budgetMillis;
            while (System.currentTimeMillis() < deadline) {
                int n = device.read(buf, 250);
                if (n <= 0) {
                    continue;
                }
                int reportId = buf[0] & 0xFF;
                if (reportId != (REPORT_LONG & 0xFF) && reportId != (REPORT_SHORT & 0xFF)) {
                    continue; // DJ レポートや他の通知は無視
                }
                int rDev = buf[1] & 0xFF;
                int rFeat = buf[2] & 0xFF;
                int rFuncSw = buf[3] & 0xFF;

                if (rDev != (deviceIndex & 0xFF)) {
                    continue;
                }

                // HID++ 2.0 エラー応答: feature index バイトが 0xFF
                if (rFeat == 0xFF) {
                    int failedFuncSw = buf[4] & 0xFF;
                    if ((failedFuncSw & 0x0F) == sw) {
                        log("< HID++ error code=" + (buf[5] & 0xFF));
                        return null;
                    }
                    continue;
                }

                if (rFeat != (featureIndex & 0xFF)) {
                    continue;
                }
                if ((rFuncSw & 0x0F) != sw) {
                    continue;
                }
                if (((rFuncSw >> 4) & 0x0F) != (function & 0x0F)) {
                    continue;
                }

                byte[] out = new byte[20];
                System.arraycopy(buf, 0, out, 0, Math.min(out.length, n));
                log("< " + hex(out));
                return out;
            }
            log("< timeout");
            return null;
        }
    }

    private static MouseBattery.Status unifiedState(int chargingStatus) {
        return switch (chargingStatus) {
            case 1, 2 -> MouseBattery.Status.CHARGING;
            case 3 -> MouseBattery.Status.FULL;
            default -> MouseBattery.Status.DISCHARGING;
        };
    }

    private static MouseBattery.Status legacyState(int status) {
        return switch (status) {
            case 1, 4 -> MouseBattery.Status.CHARGING;
            case 2, 3 -> MouseBattery.Status.FULL;
            default -> MouseBattery.Status.DISCHARGING;
        };
    }

    private static String hex(byte[] b) {
        return hex(b, b.length);
    }

    private static String hex(byte[] b, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len && i < b.length; i++) {
            sb.append(String.format("%02X ", b[i]));
        }
        return sb.toString().trim();
    }

    private static void log(String s) {
        if (VERBOSE) {
            System.err.println("[hidpp] " + s);
        }
    }
}
