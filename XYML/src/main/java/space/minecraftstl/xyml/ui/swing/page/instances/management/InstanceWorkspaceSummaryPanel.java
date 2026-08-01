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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.page.home.HomeModel;
import space.minecraftstl.xyml.ui.swing.page.home.HomeSnapshot;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Insets;
import java.util.Objects;
import java.util.function.Consumer;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Presents the selected instance identity, real version, launch status, icon, and common file commands.
///
/// The borrowed [HomeModel] remains application-owned. This panel owns only its subscription and accepts overview
/// updates already loaded by [InstanceOverviewPanel], so it never duplicates repository or image work.
@NotNullByDefault
final class InstanceWorkspaceSummaryPanel extends JPanel implements AutoCloseable {
    /// Stable repository instance identifier represented by this summary.
    private final GameInstanceID instanceId;

    /// Borrowed launcher selection and launch-state model.
    private final HomeModel homeModel;

    /// Subscription releasing future home-state invalidations.
    private final Subscription homeSubscription;

    /// Large current instance name.
    private final JLabel nameLabel = new JLabel();

    /// Minecraft version resolved by the overview background load.
    private final JLabel versionLabel = new JLabel();

    /// Current launch readiness or operation status.
    private final JLabel statusLabel = new JLabel();

    /// Fixed-size actual instance icon preview.
    private final JLabel iconLabel = new JLabel();

    /// Reloads repository metadata and the shared overview snapshot.
    private final JButton refreshButton = new JButton();

    /// Opens the exact version-root directory.
    private final JButton openFolderButton = new JButton();

    /// Opens the complete known-directory menu.
    private final JButton moreButton = new JButton();

    /// Prevents updates after the subscription has been released.
    private boolean closed;

    /// Creates one compact persistent summary with caller-owned commands.
    ///
    /// @param homeModel borrowed launcher home model
    /// @param instanceId stable non-blank repository instance identifier
    /// @param refreshCommand asynchronous repository refresh command
    /// @param openFolderCommand asynchronous version-root open command
    /// @param moreCommand command showing the known-directory menu from its invoking button
    InstanceWorkspaceSummaryPanel(
            HomeModel homeModel,
            GameInstanceID instanceId,
            Runnable refreshCommand,
            Runnable openFolderCommand,
            Consumer<Component> moreCommand) {
        super(new MigLayout(
                "insets 4 4 12 4, fillx",
                "[56!]12[grow,fill]16[40!]6[40!]6[40!]",
                "[30!]2[24!]"));
        EdtDispatcher.requireEventDispatchThread();
        this.homeModel = Objects.requireNonNull(homeModel, "homeModel");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        configureComponents(
                Objects.requireNonNull(refreshCommand, "refreshCommand"),
                Objects.requireNonNull(openFolderCommand, "openFolderCommand"),
                Objects.requireNonNull(moreCommand, "moreCommand"));
        Subscription createdSubscription = homeModel.subscribe(this::homeChanged);
        try {
            applyHomeSnapshot(homeModel.snapshot());
        } catch (RuntimeException | Error failure) {
            createdSubscription.unsubscribe();
            throw failure;
        }
        homeSubscription = createdSubscription;
    }

    /// Applies the latest overview projection after its background load completes.
    ///
    /// @param summary shared overview state for this exact instance
    void applyOverviewSummary(InstanceOverviewSummary summary) {
        EdtDispatcher.requireEventDispatchThread();
        InstanceOverviewSummary state = Objects.requireNonNull(summary, "summary");
        if (!instanceId.equals(state.instanceId())) {
            throw new IllegalArgumentException("Summary belongs to a different instance: " + state.instanceId());
        }
        if (closed) {
            return;
        }
        versionLabel.setText(state.versionDetail().isBlank()
                ? i18n("swing.instance_overview.version_unavailable")
                : state.versionDetail());
        versionLabel.setToolTipText(versionLabel.getText());
        iconLabel.setIcon(state.iconPreview());
        openFolderButton.setEnabled(true);
        moreButton.setEnabled(true);
    }

    /// Releases the borrowed model subscription without closing the model itself.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed) {
                return;
            }
            closed = true;
            homeSubscription.unsubscribe();
            refreshButton.setEnabled(false);
            openFolderButton.setEnabled(false);
            moreButton.setEnabled(false);
        });
    }

    /// Configures transparent identity rows and fixed-size icon commands.
    ///
    /// @param refreshCommand repository refresh command
    /// @param openFolderCommand version-root open command
    /// @param moreCommand known-directory menu command receiving its invoking button
    private void configureComponents(
            Runnable refreshCommand,
            Runnable openFolderCommand,
            Consumer<Component> moreCommand) {
        setName("instanceWorkspaceSummary");
        setOpaque(false);

        iconLabel.setName("instanceWorkspaceIcon");
        iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        iconLabel.setVerticalAlignment(SwingConstants.CENTER);
        iconLabel.setIcon(new FlatSVGIcon("assets/swing/icons/nav-instances.svg", 36, 36));
        iconLabel.getAccessibleContext().setAccessibleName(i18n("swing.instance_overview.icon_preview"));
        add(iconLabel, "cell 0 0 1 2, w 56!, h 56!");

        nameLabel.setName("instanceWorkspaceName");
        nameLabel.setText(instanceId.id());
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 24.0F));
        add(nameLabel, "cell 1 0, growx");

        versionLabel.setName("instanceWorkspaceVersion");
        versionLabel.setText(i18n("swing.instance_overview.loading"));

        statusLabel.setName("instanceWorkspaceStatus");
        statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
        statusLabel.setFont(statusLabel.getFont().deriveFont(12.0F));

        JPanel detailLine = new JPanel(new MigLayout(
                "insets 0, fillx, gap 12",
                "[][grow,fill]",
                "[24!]"));
        detailLine.setName("instanceWorkspaceDetails");
        detailLine.setOpaque(false);
        detailLine.add(versionLabel, "aligny center");
        detailLine.add(statusLabel, "aligny center");
        add(detailLine, "cell 1 1, growx");

        configureCommand(
                refreshButton,
                "instanceWorkspaceRefresh",
                i18n("swing.instance_overview.refresh"),
                "assets/swing/icons/refresh.svg",
                refreshCommand);
        add(refreshButton, "cell 2 0 1 2, w 40!, h 40!");

        configureCommand(
                openFolderButton,
                "instanceWorkspaceOpenFolder",
                i18n("swing.instance_overview.open_instance_folder"),
                "assets/swing/icons/folder-open.svg",
                openFolderCommand);
        openFolderButton.setEnabled(false);
        add(openFolderButton, "cell 3 0 1 2, w 40!, h 40!");

        configureCommand(
                moreButton,
                "instanceWorkspaceMore",
                i18n("settings.game.exploration"),
                "assets/swing/icons/format-list-bulleted.svg",
                () -> moreCommand.accept(moreButton));
        moreButton.setEnabled(false);
        add(moreButton, "cell 4 0 1 2, w 40!, h 40!");
    }

    /// Coalesces one home-model invalidation onto the Swing event-dispatch thread.
    ///
    /// @param change ignored transition payload whose arrival invalidates the current snapshot
    private void homeChanged(ValueChange<HomeSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                applyHomeSnapshot(homeModel.snapshot());
            }
        });
    }

    /// Applies one current instance name and launch status snapshot.
    ///
    /// @param snapshot latest immutable home state
    private void applyHomeSnapshot(HomeSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        HomeSnapshot state = Objects.requireNonNull(snapshot, "snapshot");
        nameLabel.setText(state.instanceName().isBlank() ? instanceId.id() : state.instanceName());
        nameLabel.setToolTipText(nameLabel.getText());
        statusLabel.setText(state.statusText());
        statusLabel.setToolTipText(state.statusText());
    }

    /// Configures one theme-aware fixed-size icon command.
    ///
    /// @param button target button
    /// @param name stable component name
    /// @param tooltip visible and accessible command description
    /// @param iconResource bundled SVG resource
    /// @param command EDT command
    private static void configureCommand(
            JButton button,
            String name,
            String tooltip,
            String iconResource,
            Runnable command) {
        JButton target = Objects.requireNonNull(button, "button");
        String description = requireNonBlank(tooltip, "tooltip");
        target.setName(requireNonBlank(name, "name"));
        target.setIcon(createThemeIcon(requireNonBlank(iconResource, "iconResource")));
        target.setToolTipText(description);
        target.getAccessibleContext().setAccessibleName(description);
        target.setMargin(new Insets(8, 8, 8, 8));
        target.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        target.addActionListener(event -> command.run());
    }

    /// Creates one foreground-following icon for live theme changes.
    ///
    /// @param iconResource bundled SVG resource path
    /// @return configured 18-pixel icon
    private static FlatSVGIcon createThemeIcon(String iconResource) {
        FlatSVGIcon icon = new FlatSVGIcon(iconResource, 18, 18);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(InstanceWorkspaceSummaryPanel::resolveIconColor));
        return icon;
    }

    /// Resolves the current command foreground for a theme-aware SVG.
    ///
    /// @param component icon-owning component, or `null` during standalone rendering
    /// @param originalColor SVG-authored fallback color
    /// @return current component foreground or the authored fallback
    private static Color resolveIconColor(@Nullable Component component, Color originalColor) {
        Color fallback = Objects.requireNonNull(originalColor, "originalColor");
        @Nullable Color foreground = component == null ? null : component.getForeground();
        return foreground == null ? fallback : foreground;
    }

    /// Validates one required non-blank text value.
    ///
    /// @param value source text
    /// @param name parameter name
    /// @return validated non-blank text
    private static String requireNonBlank(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }
}
