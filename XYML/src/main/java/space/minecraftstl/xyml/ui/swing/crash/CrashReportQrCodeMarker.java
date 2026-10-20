/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package space.minecraftstl.xyml.ui.swing.crash;

import io.nayuki.qrcodegen.QrCode;
import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.image.BufferedImage;

/// Displays the fixed crash-report marker command as a compact QR-code label.
@NotNullByDefault
final class CrashReportQrCodeMarker extends JLabel {
    /// Command encoded by the crash-report marker.
    private static final String CONTENT = "/ban @s 1h";

    /// Number of image pixels used for each QR module.
    private static final int MODULE_SIZE = 4;

    /// Required white quiet zone around the QR symbol, measured in modules.
    private static final int QUIET_ZONE_MODULES = 4;

    /// Creates the marker label around the generated QR image.
    CrashReportQrCodeMarker() {
        super(new ImageIcon(createImage()), SwingConstants.CENTER);
        getAccessibleContext().setAccessibleName(CONTENT);
    }

    /// Renders the command QR code with an integer module scale and a white quiet zone.
    ///
    /// @return fixed-size black-and-white QR image
    private static BufferedImage createImage() {
        QrCode qrCode = QrCode.encodeText(CONTENT, QrCode.Ecc.MEDIUM);
        int imageSize = (qrCode.size + QUIET_ZONE_MODULES * 2) * MODULE_SIZE;
        BufferedImage image = new BufferedImage(imageSize, imageSize, BufferedImage.TYPE_INT_RGB);
        for (int pixelY = 0; pixelY < imageSize; pixelY++) {
            int moduleY = pixelY / MODULE_SIZE - QUIET_ZONE_MODULES;
            for (int pixelX = 0; pixelX < imageSize; pixelX++) {
                int moduleX = pixelX / MODULE_SIZE - QUIET_ZONE_MODULES;
                boolean black = moduleX >= 0
                        && moduleX < qrCode.size
                        && moduleY >= 0
                        && moduleY < qrCode.size
                        && qrCode.getModule(moduleX, moduleY);
                image.setRGB(pixelX, pixelY, black ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
            }
        }
        return image;
    }
}
