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
package space.minecraftstl.xyml.ui.swing.shell;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsModel;
import space.minecraftstl.xyml.ui.swing.page.home.HomeModel;
import space.minecraftstl.xyml.ui.swing.page.home.HomeSnapshot;
import space.minecraftstl.xyml.ui.swing.page.home.HomeStrings;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesModel;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementEntry;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementService;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementSnapshot;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Cursor;
import java.awt.Font;
import java.util.Objects;
import java.util.function.Consumer;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Renders brand identity and launch workflow controls inside the full-window title bar.
@NotNullByDefault
final class ShellToolbarPanel extends JPanel implements AutoCloseable {
    /// Logical gap between the launch command and trailing native window controls.
    static final int LAUNCH_WINDOW_CONTROLS_GAP = 8;

    /// Self-drawn launcher icon and native-window title replacement.
    private final JLabel brandLabel = new JLabel();

    /// Compact current-directory selector with an explicit complete-list route.
    private final LazyGameDirectorySelector gameDirectorySelector;

    /// Lazy current-account selector.
    private final LazyAccountSelector accountSelector;

    /// Lazy current-instance selector aligned immediately before launch.
    private final LazyInstanceSelector instanceSelector;

    /// Primary game launch command immediately preceding native window buttons.
    private final JButton launchButton = new JButton();

    /// macOS native traffic-light button placeholder at the platform-defined leading side.
    private final JPanel macWindowButtonsPlaceholder = new JPanel();

    /// Windows and Linux minimize/maximize/close button placeholder at the trailing side.
    private final JPanel winWindowButtonsPlaceholder = new JPanel();

    /// Launcher selection and launch-state model.
    private final HomeModel homeModel;

    /// Current configured game-directory selection service.
    private final GameDirectoryManagementService gameDirectories;

    /// Localized home control labels reused by title-bar controls.
    private final HomeStrings homeStrings;

    /// Owned launcher-state subscription.
    private final Subscription homeSubscription;

    /// Owned directory-state subscription.
    private final Subscription gameDirectorySubscription;

    /// Whether owned subscriptions and selector popups have been released.
    private boolean closed;

    /// Creates the stable title-bar control hierarchy.
    ///
    /// @param windowTitle visible launcher title beside the bundled icon
    /// @param homeModel launcher selection and launch command model
    /// @param instancesModel selected-directory lazy instance model
    /// @param accountsModel lazy account selection model
    /// @param gameDirectories configured directory list and selection service
    /// @param recentSelections persistent compact-selector histories
    /// @param homeStrings localized launcher control text
    /// @param navigateCommand shell navigation callback
    /// @param manageDirectoriesCommand command opening the complete directory list
    /// @param revealDefaultPageCommand command exposing persistent instance management after directory selection
    ShellToolbarPanel(
            String windowTitle,
            HomeModel homeModel,
            InstancesModel instancesModel,
            AccountsModel accountsModel,
            GameDirectoryManagementService gameDirectories,
            ShellRecentSelections recentSelections,
            HomeStrings homeStrings,
            Consumer<ShellPageId> navigateCommand,
            Runnable manageDirectoriesCommand,
            Runnable revealDefaultPageCommand) {
        super(new MigLayout(
                "insets 0, fillx",
                "[]12[pref!]8[190!,shrink 90]8[168!,shrink 80]push"
                        + "[238!,shrink 100]8[pref!,shrink 60]"
                        + LAUNCH_WINDOW_CONTROLS_GAP + "[]",
                "[grow,fill]"));
        EdtDispatcher.requireEventDispatchThread();
        this.homeModel = Objects.requireNonNull(homeModel, "homeModel");
        this.gameDirectories = Objects.requireNonNull(gameDirectories, "gameDirectories");
        this.homeStrings = Objects.requireNonNull(homeStrings, "homeStrings");
        Consumer<ShellPageId> navigation = Objects.requireNonNull(navigateCommand, "navigateCommand");
        ShellRecentSelections histories = Objects.requireNonNull(recentSelections, "recentSelections");
        instanceSelector = new LazyInstanceSelector(
                Objects.requireNonNull(instancesModel, "instancesModel"),
                histories,
                homeStrings.instanceLabel(),
                homeStrings.missingInstanceLabel(),
                homeStrings.addInstanceAction(),
                i18n("swing.shell.instance_add_detail"),
                i18n("instance.manage"),
                i18n("swing.shell.instance_manage_detail"),
                navigation);
        accountSelector = new LazyAccountSelector(
                Objects.requireNonNull(accountsModel, "accountsModel"),
                histories,
                homeStrings.accountLabel(),
                homeStrings.missingAccountLabel(),
                i18n("account.create"),
                i18n("swing.shell.account_add_detail"),
                i18n("account.manage"),
                i18n("swing.shell.account_manage_detail"),
                navigation);
        gameDirectorySelector = new LazyGameDirectorySelector(
                gameDirectories,
                histories,
                i18n("game_directory.title"),
                i18n("game_directory.manage"),
                i18n("swing.shell.directory_manage_detail"),
                Objects.requireNonNull(manageDirectoriesCommand, "manageDirectoriesCommand"),
                Objects.requireNonNull(revealDefaultPageCommand, "revealDefaultPageCommand"));

        configureComponents(Objects.requireNonNull(windowTitle, "windowTitle"));
        homeSubscription = homeModel.subscribe(this::homeChanged);
        gameDirectorySubscription = gameDirectories.subscribe(this::gameDirectoriesChanged);
        applyHomeSnapshot(homeModel.snapshot());
        applyGameDirectories(gameDirectories.snapshot());
    }

    /// Marks the account-management footer while its side page is active.
    ///
    /// @param selectedPage selected side page, or `null` for persistent instance management
    void setSelectedPage(@Nullable ShellPageId selectedPage) {
        accountSelector.setManagementSelected(
                selectedPage == ShellPageId.ACCOUNTS);
    }

    /// Returns the visible brand label for focused icon and title tests.
    ///
    /// @return stable brand label
    JLabel brandLabel() {
        return brandLabel;
    }

    /// Returns the lazy account selector for focused ordering and accessibility tests.
    ///
    /// @return stable account selector
    LazyAccountSelector accountSelector() {
        return accountSelector;
    }

    /// Returns the game-directory selector for focused ordering and behavior tests.
    ///
    /// @return stable directory selector
    LazyGameDirectorySelector gameDirectorySelector() {
        return gameDirectorySelector;
    }

    /// Returns the lazy instance selector for focused geometry tests.
    ///
    /// @return stable lazy selector
    LazyInstanceSelector instanceSelector() {
        return instanceSelector;
    }

    /// Returns the launch command for focused ordering and behavior tests.
    ///
    /// @return stable launch button
    JButton launchButton() {
        return launchButton;
    }

    /// Returns the macOS native-window-controls placeholder.
    ///
    /// @return leading platform placeholder
    JPanel macWindowButtonsPlaceholder() {
        return macWindowButtonsPlaceholder;
    }

    /// Returns the Windows/Linux native-window-controls placeholder.
    ///
    /// @return trailing platform placeholder
    JPanel winWindowButtonsPlaceholder() {
        return winWindowButtonsPlaceholder;
    }

    /// Releases subscriptions and selector popups on the EDT.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed) {
                return;
            }
            closed = true;
            homeSubscription.unsubscribe();
            gameDirectorySubscription.unsubscribe();
            gameDirectorySelector.close();
            accountSelector.close();
            instanceSelector.close();
            launchButton.setEnabled(false);
        });
    }

    /// Configures title-bar identity, workflow order, and native-control placeholders.
    private void configureComponents(String windowTitle) {
        setName("shellToolbar");
        setOpaque(false);
        setBorder(ShellSeparatorBorder.bottom());
        putClientProperty(FlatClientProperties.COMPONENT_TITLE_BAR_CAPTION, true);

        configurePlaceholder(macWindowButtonsPlaceholder, "shellMacWindowButtons", "mac horizontal zeroInFullScreen");
        configurePlaceholder(winWindowButtonsPlaceholder, "shellWinWindowButtons", "win horizontal");
        add(macWindowButtonsPlaceholder, "growy");

        brandLabel.setName("shellBrand");
        brandLabel.setText(windowTitle);
        brandLabel.setIcon(LauncherIconImages.headerIcon());
        brandLabel.setIconTextGap(8);
        brandLabel.setFont(brandLabel.getFont().deriveFont(Font.BOLD));
        brandLabel.setToolTipText(windowTitle);
        brandLabel.getAccessibleContext().setAccessibleName(windowTitle);
        add(brandLabel, "growx, h 40!");

        add(gameDirectorySelector, "grow, h 36!");
        add(accountSelector, "grow, h 36!");
        add(instanceSelector, "grow, h 36!");

        launchButton.setName("shellLaunch");
        launchButton.setIcon(new FlatSVGIcon("assets/swing/icons/rocket-launch.svg", 18, 18));
        launchButton.setIconTextGap(8);
        launchButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        launchButton.addActionListener(event -> {
            if (!closed) {
                homeModel.launch();
            }
        });
        launchButton.getAccessibleContext().setAccessibleName(homeStrings.launchAction());
        add(launchButton, "grow, h 36!");
        add(winWindowButtonsPlaceholder, "growy");
    }

    /// Configures one platform-aware native-window-controls placeholder.
    private static void configurePlaceholder(JPanel placeholder, String name, String policy) {
        placeholder.setName(name);
        placeholder.setOpaque(false);
        placeholder.putClientProperty(
                FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER,
                policy);
    }

    /// Applies one launcher selection or launch-state transition on the EDT.
    private void homeChanged(ValueChange<HomeSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                applyHomeSnapshot(homeModel.snapshot());
            }
        });
    }

    /// Applies one launcher snapshot to account, instance, and launch controls.
    private void applyHomeSnapshot(HomeSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        HomeSnapshot state = Objects.requireNonNull(snapshot, "snapshot");
        String accountName = state.accountName().isBlank()
                ? homeStrings.missingAccountLabel()
                : state.accountName();
        accountSelector.setSelectedText(accountName, state.accountDetail());
        accountSelector.setInteractionEnabled(state.selectionCommandsEnabled());

        String instanceName = state.instanceName().isBlank()
                ? homeStrings.missingInstanceLabel()
                : state.instanceName();
        instanceSelector.setSelectedText(instanceName, state.instanceDetail());

        launchButton.setText(state.launching()
                ? homeStrings.launchingAction()
                : homeStrings.launchAction());
        launchButton.setToolTipText(state.statusText());
        launchButton.setEnabled(state.launchEnabled());
    }

    /// Receives one configured-directory transition and coalesces it onto the EDT.
    private void gameDirectoriesChanged(ValueChange<GameDirectoryManagementSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                applyGameDirectories(gameDirectories.snapshot());
            }
        });
    }

    /// Applies directory options and qualifies instance history by the exact selected directory.
    private void applyGameDirectories(GameDirectoryManagementSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        GameDirectoryManagementSnapshot state = Objects.requireNonNull(snapshot, "snapshot");
        gameDirectorySelector.applySnapshot(state);
        @Nullable GameDirectoryManagementEntry selected = null;
        for (GameDirectoryManagementEntry entry : state.entries()) {
            if (entry.selected()) {
                selected = entry;
                break;
            }
        }
        if (selected != null) {
            instanceSelector.setDirectoryContext(selected.id().toString());
        }
    }
}
