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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.util.Objects;
import java.util.function.Consumer;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Presents repeatable Microsoft authorization-link commands without rendering the full URL.
///
/// Keeping the location out of component text prevents long OAuth query strings from contributing to the dialog's
/// minimum width. The location remains available only to the explicit browser and clipboard commands.
@NotNullByDefault
final class MicrosoftAuthorizationActions extends JPanel {
    /// Fixed action-icon size matching nearby Swing command buttons.
    private static final int ACTION_ICON_SIZE = 18;

    /// Browser-launch boundary.
    private final Consumer<String> openAction;

    /// Clipboard boundary.
    private final Consumer<String> copyAction;

    /// Reopens the current Microsoft authorization page.
    private final JButton reopenButton = new JButton(
            i18n("account.methods.microsoft.authorization.reopen"),
            new FlatSVGIcon("assets/swing/icons/open-in-new.svg", ACTION_ICON_SIZE, ACTION_ICON_SIZE));

    /// Copies the current Microsoft authorization URL.
    private final JButton copyLinkButton = new JButton(
            i18n("account.methods.microsoft.authorization.copy_link"),
            new FlatSVGIcon("assets/swing/icons/content-copy.svg", ACTION_ICON_SIZE, ACTION_ICON_SIZE));

    /// Current authorization URL, or `null` while no Microsoft authorization is pending.
    private @Nullable String location;

    /// Creates an initially hidden authorization action row.
    ///
    /// @param openAction browser-launch boundary
    /// @param copyAction clipboard boundary
    MicrosoftAuthorizationActions(Consumer<String> openAction, Consumer<String> copyAction) {
        super(new MigLayout("insets 0, fillx", "[]8[][grow,fill]", "[]"));
        this.openAction = Objects.requireNonNull(openAction, "openAction");
        this.copyAction = Objects.requireNonNull(copyAction, "copyAction");
        setOpaque(false);

        configureButton(reopenButton, "microsoftAuthorizationReopen");
        configureButton(copyLinkButton, "microsoftAuthorizationCopyLink");
        reopenButton.addActionListener(event -> openCurrentLocation());
        copyLinkButton.addActionListener(event -> copyCurrentLocation());
        add(reopenButton);
        add(copyLinkButton);
        clearLocation();
    }

    /// Shows commands for one non-blank authorization URL.
    ///
    /// @param location trusted Microsoft authorization URL
    void showLocation(String location) {
        EdtDispatcher.requireEventDispatchThread();
        String validatedLocation = Objects.requireNonNull(location, "location");
        if (validatedLocation.isBlank()) {
            throw new IllegalArgumentException("Authorization location must not be blank");
        }
        this.location = validatedLocation;
        reopenButton.setEnabled(true);
        copyLinkButton.setEnabled(true);
        setVisible(true);
        revalidate();
        repaint();
    }

    /// Clears the current URL and removes both commands from layout participation.
    void clearLocation() {
        EdtDispatcher.requireEventDispatchThread();
        location = null;
        reopenButton.setEnabled(false);
        copyLinkButton.setEnabled(false);
        setVisible(false);
        revalidate();
        repaint();
    }

    /// Configures stable identity and accessibility for one URL command.
    ///
    /// @param button command button
    /// @param name stable component name
    private static void configureButton(JButton button, String name) {
        button.setName(name);
        button.setToolTipText(button.getText());
        button.getAccessibleContext().setAccessibleName(button.getText());
    }

    /// Opens the current location when authorization remains pending.
    private void openCurrentLocation() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable String currentLocation = location;
        if (currentLocation != null) {
            openAction.accept(currentLocation);
        }
    }

    /// Copies the current location when authorization remains pending.
    private void copyCurrentLocation() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable String currentLocation = location;
        if (currentLocation != null) {
            copyAction.accept(currentLocation);
        }
    }
}
