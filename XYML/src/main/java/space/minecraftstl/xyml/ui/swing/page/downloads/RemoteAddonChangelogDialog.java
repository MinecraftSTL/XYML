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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.event.HyperlinkEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;

import static space.minecraftstl.xyml.util.StringUtils.convertToHtml;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Displays one remote add-on version changelog without restoring the removed JavaFX renderer.
@NotNullByDefault
public final class RemoteAddonChangelogDialog {
    /// Prevents utility-class construction.
    private RemoteAddonChangelogDialog() {
    }

    /// Shows a safe changelog fragment with copy and exact-version-page actions.
    ///
    /// @param parent owner component for the modal dialog
    /// @param title dialog title
    /// @param markdown provider Markdown, or null when no changelog exists
    /// @param versionPage exact provider version page
    public static void show(Component parent, String title, @Nullable String markdown, URI versionPage) {
        String html = convertToHtml(markdown, true);
        if (html == null || html.isBlank()) {
            html = "<html><body><p>" + escape(i18n("addon.changelog.empty")) + "</p></body></html>";
        }
        JEditorPane editor = new JEditorPane("text/html", html);
        editor.setEditable(false);
        editor.setCaretPosition(0);
        editor.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        editor.addHyperlinkListener(event -> openLink(event));

        JScrollPane scrollPane = new JScrollPane(editor);
        scrollPane.setPreferredSize(new Dimension(700, 420));
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(scrollPane, BorderLayout.CENTER);

        JButton copyButton = new JButton(i18n("button.copy"));
        copyButton.addActionListener(event -> copyToClipboard(markdown == null ? "" : markdown));
        JButton pageButton = new JButton(i18n("download.external_link"));
        pageButton.addActionListener(event -> browse(versionPage));
        JPanel actions = new JPanel();
        actions.add(copyButton);
        actions.add(pageButton);
        content.add(actions, BorderLayout.SOUTH);

        JOptionPane.showMessageDialog(parent, content, Objects.requireNonNull(title, "title"),
                JOptionPane.INFORMATION_MESSAGE);
    }

    /// Opens one activated safe hyperlink from the editor pane.
    ///
    /// @param event editor hyperlink event
    private static void openLink(HyperlinkEvent event) {
        if (event.getEventType() != HyperlinkEvent.EventType.ACTIVATED || event.getURL() == null) {
            return;
        }
        URI target = URI.create(event.getURL().toExternalForm());
        browse(target);
    }

    /// Delegates one HTTP or HTTPS target to the desktop browser when available.
    ///
    /// @param target candidate page URI
    private static void browse(URI target) {
        String scheme = target.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return;
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            return;
        }
        try {
            Desktop.getDesktop().browse(target);
        } catch (IOException ignored) {
            // A browser may be unavailable in headless environments; the dialog remains usable.
        }
    }

    /// Copies provider Markdown to the system clipboard.
    ///
    /// @param value source Markdown
    private static void copyToClipboard(String value) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
    }

    /// Escapes fallback text for HTML rendering.
    ///
    /// @param value localized fallback text
    /// @return escaped text
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
