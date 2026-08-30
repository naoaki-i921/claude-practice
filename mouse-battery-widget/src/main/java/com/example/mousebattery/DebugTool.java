package com.example.mousebattery;

import org.hid4java.HidDevice;
import org.hid4java.HidManager;
import org.hid4java.HidServices;
import org.hid4java.HidServicesSpecification;
import org.hid4java.ScanMode;

/**
 * {@code --debug} で起動したときの診断出力。
 *
 * <p>出力はコンソールの文字コード差を避けるため英語。GUI(トレイのメニューや通知)は日本語のまま。</p>
 */
public final class DebugTool {

    public static void run() {
        HidServicesSpecification spec = new HidServicesSpecification();
        spec.setAutoStart(true);
        spec.setAutoDataRead(false);
        spec.setScanMode(ScanMode.NO_SCAN);
        HidServices services = HidManager.getHidServices(spec);

        System.out.println("=== Attached HID devices ===");
        for (HidDevice d : services.getAttachedHidDevices()) {
            String mark = HidppClient.isLogitechHidpp(d) ? "   <- Logitech HID++" : "";
            System.out.printf("VID=%04X PID=%04X usagePage=%04X usage=%04X  %-22s | %s%s%n",
                    d.getVendorId() & 0xFFFF, d.getProductId() & 0xFFFF,
                    d.getUsagePage() & 0xFFFF, d.getUsage() & 0xFFFF,
                    nz(d.getManufacturer()), nz(d.getProduct()), mark);
        }

        System.out.println();
        System.out.println("=== Trying to read battery from Logitech HID++ devices ===");
        boolean any = false;
        for (HidDevice d : services.getAttachedHidDevices()) {
            if (!HidppClient.isLogitechHidpp(d) || (d.getUsage() & 0xFFFF) != 0x0002) {
                continue;
            }
            any = true;
            System.out.printf("-> open: %s (PID %04X)%n", nz(d.getProduct()), d.getProductId() & 0xFFFF);
            if (!d.open()) {
                System.out.println("   open failed: " + d.getLastErrorMessage());
                continue;
            }
            try {
                MouseBattery b = new HidppClient(d).read(nz(d.getProduct()));
                System.out.println("   result: " + (b == null ? "no data" : b));
            } finally {
                d.close();
            }
        }
        if (!any) {
            System.out.println("No Logitech HID++ interface (usage 0x0002) found.");
            System.out.println("Check that the mouse (or its Lightspeed receiver) is connected.");
        }

        services.shutdown();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "?" : s.trim();
    }

    private DebugTool() {
    }
}
