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

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.countly.CrashReport;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Displays a launcher failure and its complete diagnostic report with native Swing components.
@NotNullByDefault
public final class SwingCrashReportWindow {
    /// Prevents construction of this window factory.
    private SwingCrashReportWindow() {
    }

    /// Opens a non-modal crash report window on the Swing event thread.
    ///
    /// Headless validation environments intentionally skip presentation while retaining crash logging.
    ///
    /// @param report report to display
    /// @param updateAvailable whether a newer launcher version was reported by the native update service
    public static void show(CrashReport report, boolean updateAvailable) {
        EdtDispatcher.requireEventDispatchThread();
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        JFrame frame = new JFrame(i18n("message.error"));
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setContentPane(createContent(report, updateAvailable));
        frame.setSize(800, 480);
        frame.setMinimumSize(new java.awt.Dimension(560, 360));
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    /// Builds the report content without displaying its owning frame.
    ///
    /// @param report report to render
    /// @param updateAvailable whether a newer launcher version was reported by the native update service
    /// @return complete crash report content
    static JPanel createContent(CrashReport report, boolean updateAvailable) {
        EdtDispatcher.requireEventDispatchThread();

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JTextArea headline = new JTextArea(headline(report, updateAvailable));
        headline.setEditable(false);
        headline.setOpaque(false);
        headline.setLineWrap(true);
        headline.setWrapStyleWord(true);
        headline.setFocusable(false);
        headline.setFont(headline.getFont().deriveFont(Font.BOLD));
        content.add(headline, BorderLayout.NORTH);

        JTextArea details = new JTextArea(report.getDisplayText());
        details.setEditable(false);
        details.setCaretPosition(0);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, details.getFont().getSize()));
        SwingThemeManager.preserveExplicitFontFamily(details);
        content.add(new JScrollPane(details), BorderLayout.CENTER);

        JButton contact = new JButton(i18n("launcher.contact"));
        contact.addActionListener(event -> openContact(frameOwner(contact)));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
        actions.add(contact);
        content.add(actions, BorderLayout.SOUTH);
        return content;
    }

    /// Chooses the same failure headline precedence as the removed JavaFX crash window.
    ///
    /// @param report report whose throwable determines the primary message
    /// @param outdated whether a newer launcher version is known
    /// @return localized failure headline
    static String headline(CrashReport report, boolean outdated) {
        if (report.getThrowable() instanceof InternalError) {
            return i18n("launcher.crash.java_internal_error");
        }
        if (outdated) {
            return i18n("launcher.crash.xyml_outdated");
        }
        return i18n("launcher.crash");
    }

    /// Resolves the current window ancestor lazily after the contact button is attached.
    ///
    /// @param component component inside the crash window
    /// @return component suitable as an error-dialog owner
    private static Component frameOwner(Component component) {
        Component current = component;
        while (current.getParent() != null) {
            current = current.getParent();
        }
        return current;
    }

    /// Opens the project contact page or exposes the destination when desktop integration is unavailable.
    ///
    /// @param owner crash window used to own a fallback error dialog
    private static void openContact(Component owner) {
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new UnsupportedOperationException("Desktop integration is unavailable");
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                throw new UnsupportedOperationException("Desktop browsing is unavailable");
            }
            desktop.browse(URI.create(Metadata.CONTACT_URL));
        } catch (IOException | RuntimeException failure) {
            JOptionPane.showMessageDialog(
                    owner,
                    Metadata.CONTACT_URL,
                    i18n("message.error"),
                    JOptionPane.ERROR_MESSAGE);
        }
    }
}
