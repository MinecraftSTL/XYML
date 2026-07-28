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
import space.minecraftstl.xyml.ui.swing.page.home.HomeModel;
import space.minecraftstl.xyml.ui.swing.page.home.HomeSnapshot;
import space.minecraftstl.xyml.ui.swing.page.home.HomeStrings;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesModel;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementEntry;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementService;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementSnapshot;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.Objects;
import java.util.function.Consumer;

/// Renders account, directory, navigation, instance, and launch controls inside the window title bar.
@NotNullByDefault
final class ShellToolbarPanel extends JPanel implements AutoCloseable {
    /// Stable account-management command at the far left of Windows and Linux title bars.
    private final JButton accountButton = new JButton();

    /// Cheap in-memory selector for configured game/version directories.
    private final JComboBox<GameDirectoryManagementEntry> gameDirectoryBox = new JComboBox<>();

    /// Lazy single-instance selector aligned immediately before the launch command.
    private final LazyInstanceSelector instanceSelector;

    /// Primary game launch command immediately preceding native window buttons.
    private final JButton launchButton = new JButton();

    /// Compact navigation buttons for the persistent base and two main overlays.
    private final EnumMap<ShellPageId, ShellNavigationButton> navigationButtons =
            new EnumMap<>(ShellPageId.class);

    /// macOS native traffic-light button placeholder at the platform-defined leading side.
    private final JPanel macWindowButtonsPlaceholder = new JPanel();

    /// Windows and Linux minimize/maximize/close button placeholder at the trailing side.
    private final JPanel winWindowButtonsPlaceholder = new JPanel();

    /// Launcher selection and launch-state model.
    private final HomeModel homeModel;

    /// Current configured game-directory selection service.
    private final GameDirectoryManagementService gameDirectories;

    /// Localized home control labels reused by the title-bar controls.
    private final HomeStrings homeStrings;

    /// Shell navigation callback.
    private final Consumer<ShellPageId> navigateCommand;

    /// Owned launcher-state subscription.
    private final Subscription homeSubscription;

    /// Owned directory-state subscription.
    private final Subscription gameDirectorySubscription;

    /// Whether programmatic combo replacement suppresses selection commands.
    private boolean applyingDirectories;

    /// Whether owned subscriptions and the lazy popup have been released.
    private boolean closed;

    /// Creates the stable title-bar control hierarchy.
    ///
    /// @param homeModel launcher selection and launch command model
    /// @param instancesModel selected-directory lazy instance model
    /// @param gameDirectories configured directory list and selection service
    /// @param homeStrings localized launcher control text
    /// @param pagePresentations localized overlay navigation text
    /// @param navigateCommand shell navigation callback
    ShellToolbarPanel(
            HomeModel homeModel,
            InstancesModel instancesModel,
            GameDirectoryManagementService gameDirectories,
            HomeStrings homeStrings,
            ShellPagePresentations pagePresentations,
            Consumer<ShellPageId> navigateCommand) {
        super(new MigLayout(
                "insets 0 0 0 0, fillx",
                "[]0[148!,shrink 60]8[190!,shrink 70]push[]push[238!,shrink 80]8[132!,shrink 40]2[]",
                "[grow,fill]"));
        EdtDispatcher.requireEventDispatchThread();
        this.homeModel = Objects.requireNonNull(homeModel, "homeModel");
        this.gameDirectories = Objects.requireNonNull(gameDirectories, "gameDirectories");
        this.homeStrings = Objects.requireNonNull(homeStrings, "homeStrings");
        this.navigateCommand = Objects.requireNonNull(navigateCommand, "navigateCommand");
        Objects.requireNonNull(pagePresentations, "pagePresentations");
        instanceSelector = new LazyInstanceSelector(
                Objects.requireNonNull(instancesModel, "instancesModel"),
                navigateCommand);

        configureComponents(pagePresentations);
        homeSubscription = homeModel.subscribe(this::homeChanged);
        gameDirectorySubscription = gameDirectories.subscribe(this::gameDirectoriesChanged);
        applyHomeSnapshot(homeModel.snapshot());
        applyGameDirectories(gameDirectories.snapshot());
        setSelectedPage(ShellPageId.INSTANCES);
    }

    /// Synchronizes compact navigation selection with the active base or overlay page.
    ///
    /// @param selectedPage selected shell page
    void setSelectedPage(ShellPageId selectedPage) {
        EdtDispatcher.requireEventDispatchThread();
        ShellPageId page = Objects.requireNonNull(selectedPage, "selectedPage");
        accountButton.putClientProperty("JButton.selectedState",
                page == ShellPageId.ACCOUNTS ? "selected" : null);
        for (ShellNavigationButton button : navigationButtons.values()) {
            button.setSelected(button.page() == page);
        }
    }

    /// Returns the account-management button for focused ordering and accessibility tests.
    ///
    /// @return stable account button
    JButton accountButton() {
        return accountButton;
    }

    /// Returns the game-directory selector for focused ordering and behavior tests.
    ///
    /// @return stable directory combo box
    JComboBox<GameDirectoryManagementEntry> gameDirectoryBox() {
        return gameDirectoryBox;
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

    /// Returns one compact navigation control.
    ///
    /// @param page represented base or overlay page
    /// @return matching compact navigation button
    ShellNavigationButton navigationButton(ShellPageId page) {
        return Objects.requireNonNull(navigationButtons.get(Objects.requireNonNull(page, "page")));
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

    /// Releases subscriptions and lazy popup requests on the EDT.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed) {
                return;
            }
            closed = true;
            homeSubscription.unsubscribe();
            gameDirectorySubscription.unsubscribe();
            instanceSelector.close();
            accountButton.setEnabled(false);
            gameDirectoryBox.setEnabled(false);
            launchButton.setEnabled(false);
            for (ShellNavigationButton button : navigationButtons.values()) {
                button.setEnabled(false);
            }
        });
    }

    /// Configures title-bar order, native-control placeholders, and explicit commands.
    ///
    /// @param pagePresentations localized navigation labels
    private void configureComponents(ShellPagePresentations pagePresentations) {
        setName("shellToolbar");
        setOpaque(true);
        setBorder(ShellSeparatorBorder.bottom());
        putClientProperty(FlatClientProperties.COMPONENT_TITLE_BAR_CAPTION, true);

        configurePlaceholder(macWindowButtonsPlaceholder, "shellMacWindowButtons", "mac horizontal zeroInFullScreen");
        configurePlaceholder(winWindowButtonsPlaceholder, "shellWinWindowButtons", "win horizontal");
        add(macWindowButtonsPlaceholder, "growy");

        accountButton.setName("shellAccountManagement");
        accountButton.setIcon(new FlatSVGIcon("assets/swing/icons/nav-accounts.svg", 18, 18));
        accountButton.setHorizontalAlignment(SwingConstants.LEFT);
        accountButton.setIconTextGap(8);
        accountButton.setMargin(new Insets(4, 10, 4, 10));
        accountButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        accountButton.putClientProperty("JButton.buttonType", "toolBarButton");
        accountButton.addActionListener(event -> navigateCommand.accept(ShellPageId.ACCOUNTS));
        accountButton.getAccessibleContext().setAccessibleName(homeStrings.accountLabel());
        add(accountButton, "grow, h 40!");

        gameDirectoryBox.setName("shellGameDirectory");
        gameDirectoryBox.setRenderer(new GameDirectoryRenderer());
        gameDirectoryBox.setMaximumRowCount(Integer.MAX_VALUE);
        gameDirectoryBox.setToolTipText(space.minecraftstl.xyml.util.i18n.I18n.i18n("game_directory.title"));
        gameDirectoryBox.getAccessibleContext().setAccessibleName(gameDirectoryBox.getToolTipText());
        gameDirectoryBox.addActionListener(event -> selectGameDirectory());
        add(gameDirectoryBox, "grow, h 36!");

        JPanel navigation = new JPanel(new MigLayout("insets 0, gap 2", "[][][]", "[40!]"));
        navigation.setName("shellOverlayNavigation");
        navigation.setOpaque(false);
        for (ShellPageId page : new ShellPageId[] {
                ShellPageId.INSTANCES,
                ShellPageId.DOWNLOADS,
                ShellPageId.SETTINGS}) {
            ShellNavigationButton button = new ShellNavigationButton(page, pagePresentations.get(page));
            button.setText(null);
            button.setHorizontalAlignment(SwingConstants.CENTER);
            button.setMargin(new Insets(8, 10, 8, 10));
            button.setPreferredSize(new Dimension(40, 40));
            button.setToolTipText(pagePresentations.get(page).label());
            button.addActionListener(event -> navigateCommand.accept(page));
            navigationButtons.put(page, button);
            navigation.add(button, "w 40!, h 40!");
        }
        add(navigation, "h 40!");

        add(instanceSelector, "grow, h 36!");

        launchButton.setName("shellLaunch");
        launchButton.setIcon(new FlatSVGIcon("assets/swing/icons/rocket-launch.svg", 18, 18));
        launchButton.setIconTextGap(8);
        launchButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        launchButton.putClientProperty("JButton.buttonType", "roundRect");
        launchButton.addActionListener(event -> {
            if (!closed) {
                homeModel.launch();
            }
        });
        launchButton.getAccessibleContext().setAccessibleName(homeStrings.launchAction());
        add(launchButton, "grow, h 40!");
        add(winWindowButtonsPlaceholder, "growy");
    }

    /// Configures one platform-aware native-window-controls placeholder.
    ///
    /// @param placeholder target placeholder panel
    /// @param name stable automation name
    /// @param policy FlatLaf placeholder policy
    private static void configurePlaceholder(JPanel placeholder, String name, String policy) {
        placeholder.setName(name);
        placeholder.setOpaque(false);
        placeholder.putClientProperty(
                FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER,
                policy);
    }

    /// Applies one launcher selection or launch-state transition on the EDT.
    ///
    /// @param change transition invalidating title-bar text or commands
    private void homeChanged(ValueChange<HomeSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                applyHomeSnapshot(homeModel.snapshot());
            }
        });
    }

    /// Applies one launcher snapshot without retaining the removed home-page status band.
    ///
    /// @param snapshot current launcher selection and launch state
    private void applyHomeSnapshot(HomeSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        HomeSnapshot state = Objects.requireNonNull(snapshot, "snapshot");
        String accountName = state.accountName().isBlank()
                ? homeStrings.missingAccountLabel()
                : state.accountName();
        accountButton.setText(accountName);
        accountButton.setToolTipText(state.accountDetail().isBlank()
                ? accountName
                : accountName + " - " + state.accountDetail());
        accountButton.setEnabled(state.selectionCommandsEnabled());

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
    ///
    /// @param change transition invalidating directory options or selection
    private void gameDirectoriesChanged(ValueChange<GameDirectoryManagementSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                applyGameDirectories(gameDirectories.snapshot());
            }
        });
    }

    /// Replaces directory options from cheap persisted descriptors and restores the selected entry.
    ///
    /// @param snapshot current configured directory state
    private void applyGameDirectories(GameDirectoryManagementSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        GameDirectoryManagementSnapshot state = Objects.requireNonNull(snapshot, "snapshot");
        @Nullable GameDirectoryManagementEntry selected = null;
        for (GameDirectoryManagementEntry entry : state.entries()) {
            if (entry.selected()) {
                selected = entry;
                break;
            }
        }
        applyingDirectories = true;
        try {
            gameDirectoryBox.setModel(new DefaultComboBoxModel<>(
                    state.entries().toArray(GameDirectoryManagementEntry[]::new)));
            gameDirectoryBox.setSelectedItem(selected);
        } finally {
            applyingDirectories = false;
        }
        gameDirectoryBox.setEnabled(!closed && !state.entries().isEmpty());
    }

    /// Selects the exact configured directory chosen by the user.
    private void selectGameDirectory() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || applyingDirectories) {
            return;
        }
        @Nullable GameDirectoryManagementEntry selected =
                (GameDirectoryManagementEntry) gameDirectoryBox.getSelectedItem();
        if (selected != null && !selected.selected()) {
            gameDirectories.select(selected.id());
            navigateCommand.accept(ShellPageId.INSTANCES);
        }
    }

    /// Renders one directory by its localized display name without filesystem access.
    @NotNullByDefault
    private static final class GameDirectoryRenderer extends DefaultListCellRenderer {
        /// Renders one configured directory option.
        ///
        /// @param list owning combo popup list
        /// @param value entry being rendered, or null while the model is empty
        /// @param index source index
        /// @param selected whether the row is selected
        /// @param focused whether the row owns focus
        /// @return configured standard label
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                @Nullable Object value,
                int index,
                boolean selected,
                boolean focused) {
            Component component = super.getListCellRendererComponent(
                    list,
                    value,
                    index,
                    selected,
                    focused);
            if (component instanceof JLabel label) {
                @Nullable GameDirectoryManagementEntry entry = value instanceof GameDirectoryManagementEntry item
                        ? item
                        : null;
                label.setText(entry == null ? "" : entry.displayName());
                label.setToolTipText(entry == null ? null : entry.path().getPath());
            }
            return component;
        }
    }
}
