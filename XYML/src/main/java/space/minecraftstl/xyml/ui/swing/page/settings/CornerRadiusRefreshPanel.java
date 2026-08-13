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
package space.minecraftstl.xyml.ui.swing.page.settings;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.util.Objects;

/// Offers an explicit Swing component-tree refresh after live corner-radius changes.
@NotNullByDefault
final class CornerRadiusRefreshPanel extends JPanel implements AutoCloseable {
    /// Localized text for the baseline and pending-refresh states.
    private final CornerRadiusRefreshStrings strings;

    /// Refreshes all displayable launcher component trees.
    private final Runnable refreshAction;

    /// Visible explanation of the current refresh state.
    private final JLabel statusLabel = new JLabel();

    /// Explicit interface-refresh action.
    private final JButton refreshButton = new JButton();

    /// Radius represented when the component trees were last explicitly refreshed.
    private @Nullable Integer refreshedCornerRadius;

    /// Latest persisted radius supplied by the appearance model.
    private @Nullable Integer currentCornerRadius;

    /// Whether persistence currently permits appearance interaction.
    private boolean available = true;

    /// Whether the selected radius differs from the last explicit refresh baseline.
    private boolean refreshRequired;

    /// Whether this control has been detached from its owning appearance page.
    private boolean closed;

    /// Creates one corner-radius refresh row.
    ///
    /// @param strings localized refresh text
    /// @param refreshAction synchronous component-tree refresh action
    CornerRadiusRefreshPanel(CornerRadiusRefreshStrings strings, Runnable refreshAction) {
        super(new MigLayout("insets 4 0 0 0, fillx", "[grow,fill]8[]", "[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.strings = Objects.requireNonNull(strings, "strings");
        this.refreshAction = Objects.requireNonNull(refreshAction, "refreshAction");

        setOpaque(false);
        setName("cornerRadiusRefreshPanel");
        statusLabel.setName("cornerRadiusRefreshStatus");
        refreshButton.setName("cornerRadiusRefreshAction");
        refreshButton.setText(strings.actionText());
        refreshButton.setIcon(new FlatSVGIcon("assets/swing/icons/refresh.svg", 16, 16));
        refreshButton.addActionListener(event -> requestRefresh());
        add(statusLabel, "growx");
        add(refreshButton);
        updatePresentation();
    }

    /// Establishes the first observed radius as the component-tree baseline and tracks later values.
    ///
    /// @param cornerRadius current persisted corner radius
    void updateCornerRadius(int cornerRadius) {
        EdtDispatcher.requireEventDispatchThread();
        if (refreshedCornerRadius == null) {
            refreshedCornerRadius = cornerRadius;
        }
        currentCornerRadius = cornerRadius;
        updateRefreshRequired();
    }

    /// Enables or disables refresh interaction according to persistent-settings availability.
    ///
    /// @param available whether appearance settings may be changed
    void setAvailable(boolean available) {
        EdtDispatcher.requireEventDispatchThread();
        this.available = available;
        updatePresentation();
    }

    /// Returns whether the current radius may still require a component-tree refresh.
    ///
    /// @return whether refresh is pending
    boolean isRefreshRequired() {
        EdtDispatcher.requireEventDispatchThread();
        return refreshRequired;
    }

    /// Disables future interaction after detachment from the appearance page.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        closed = true;
        updatePresentation();
    }

    /// Refreshes displayable component trees and records the current radius as applied.
    private void requestRefresh() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || !available || !refreshRequired) {
            return;
        }
        refreshAction.run();
        refreshedCornerRadius = currentCornerRadius;
        updateRefreshRequired();
    }

    /// Recomputes whether the current radius differs from the explicit refresh baseline.
    private void updateRefreshRequired() {
        refreshRequired = currentCornerRadius != null
                && refreshedCornerRadius != null
                && !Objects.equals(refreshedCornerRadius, currentCornerRadius);
        updatePresentation();
    }

    /// Synchronizes visible copy and button availability with current refresh state.
    private void updatePresentation() {
        statusLabel.setText(refreshRequired ? strings.requiredText() : strings.promptText());
        refreshButton.setEnabled(!closed && available && refreshRequired);
    }
}
