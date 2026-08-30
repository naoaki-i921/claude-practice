package com.example.mousebattery;

import java.time.Instant;

/**
 * 1 回の取得結果を表す不変オブジェクト。
 */
public record MouseBattery(
        boolean present,
        int percent,
        Status status,
        String deviceName,
        Instant updatedAt) {

    public enum Status {
        /** 状態不明 */
        UNKNOWN,
        /** 放電中(通常使用) */
        DISCHARGING,
        /** 充電中 */
        CHARGING,
        /** 満充電 */
        FULL
    }

    /** マウスが見つからなかった場合。 */
    public static MouseBattery absent() {
        return new MouseBattery(false, -1, Status.UNKNOWN, null, Instant.now());
    }

    public static MouseBattery of(int percent, Status status, String deviceName) {
        int clamped = Math.max(0, Math.min(100, percent));
        return new MouseBattery(true, clamped, status, deviceName, Instant.now());
    }

    public boolean charging() {
        return status == Status.CHARGING;
    }

    @Override
    public String toString() {
        if (!present) {
            return "MouseBattery{未検出}";
        }
        return "MouseBattery{" + deviceName + " " + percent + "% " + status + "}";
    }
}
