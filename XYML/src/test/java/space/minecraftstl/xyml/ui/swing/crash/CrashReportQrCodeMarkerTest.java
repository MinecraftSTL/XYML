// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.crash;

import io.nayuki.qrcodegen.QrCode;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.swing.ImageIcon;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/// Verifies the crash-report marker content and fixed QR image.
@NotNullByDefault
class CrashReportQrCodeMarkerTest {
    /// Exposes the required command through a centered fixed-size marker label.
    @Test
    void exposesCenteredMarkerImage() {
        CrashReportQrCodeMarker marker = new CrashReportQrCodeMarker();

        ImageIcon icon = assertInstanceOf(ImageIcon.class, marker.getIcon());
        assertEquals(SwingConstants.CENTER, marker.getHorizontalAlignment());
        assertEquals("/ban @s 1h", marker.getAccessibleContext().getAccessibleName());
        assertEquals(icon.getIconWidth(), marker.getPreferredSize().width);
        assertEquals(icon.getIconHeight(), marker.getPreferredSize().height);
    }

    /// Matches every rendered module center against the QR matrix for the required command.
    @Test
    void encodesRequiredBanCommand() {
        CrashReportQrCodeMarker marker = new CrashReportQrCodeMarker();
        ImageIcon icon = assertInstanceOf(ImageIcon.class, marker.getIcon());
        BufferedImage image = assertInstanceOf(BufferedImage.class, icon.getImage());
        QrCode expected = QrCode.encodeText("/ban @s 1h", QrCode.Ecc.MEDIUM);
        int quietZoneModules = 4;
        int moduleSize = 4;
        int expectedImageSize = (expected.size + quietZoneModules * 2) * moduleSize;

        assertEquals(expectedImageSize, image.getWidth());
        assertEquals(expectedImageSize, image.getHeight());
        for (int moduleY = -quietZoneModules; moduleY < expected.size + quietZoneModules; moduleY++) {
            for (int moduleX = -quietZoneModules; moduleX < expected.size + quietZoneModules; moduleX++) {
                boolean black = moduleX >= 0
                        && moduleX < expected.size
                        && moduleY >= 0
                        && moduleY < expected.size
                        && expected.getModule(moduleX, moduleY);
                int pixelX = (moduleX + quietZoneModules) * moduleSize + moduleSize / 2;
                int pixelY = (moduleY + quietZoneModules) * moduleSize + moduleSize / 2;
                assertEquals(
                        black ? Color.BLACK.getRGB() : Color.WHITE.getRGB(),
                        image.getRGB(pixelX, pixelY),
                        "module at (" + moduleX + ", " + moduleY + ")");
            }
        }
    }
}
