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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.launch.LaunchSession;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.property.ReadOnlyProperty;
import space.minecraftstl.xyml.observable.property.SimpleObjectProperty;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.MotionPolicy;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingDesignTokens;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.SystemThemeDetector;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountListCellRenderer;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountListItem;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsModel;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsSnapshot;
import space.minecraftstl.xyml.ui.swing.page.home.HomeModel;
import space.minecraftstl.xyml.ui.swing.page.home.HomeSnapshot;
import space.minecraftstl.xyml.ui.swing.page.home.HomeStrings;
import space.minecraftstl.xyml.ui.swing.page.instances.InstanceListItem;
import space.minecraftstl.xyml.ui.swing.page.instances.InstanceSearchEntry;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesModel;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesSnapshot;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementEdit;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementEntry;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementService;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementSnapshot;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;
import space.minecraftstl.xyml.util.PortablePath;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies persistent instance management, lazy overlays, title-bar order, cleanup, and rendering.
@NotNullByDefault
public final class AppShellPanelTest {
    /// Fixed screenshot width matching the shell's preferred width.
    private static final int RENDER_WIDTH = AppShellPanel.PREFERRED_WIDTH;

    /// Fixed screenshot height matching the shell's preferred height.
    private static final int RENDER_HEIGHT = AppShellPanel.PREFERRED_HEIGHT;

    /// Instance management is created first, retained below overlays, and reused after returning.
    @Test
    public void retainsPersistentInstancesPageAcrossOverlays() {
        EnumMap<ShellPageId, AtomicInteger> creationCounts = creationCounts();
        AppShellPanel panel = createPanel(creationCounts);
        AtomicReference<@Nullable JComponent> initialInstancesPage = new AtomicReference<>();
        AtomicReference<@Nullable JComponent> returnedInstancesPage = new AtomicReference<>();
        AtomicReference<@Nullable JComponent> downloadsPage = new AtomicReference<>();

        try {
            EdtDispatcher.executeAndWait(() -> {
                initialInstancesPage.set(panel.activePage());
                panel.navigateTo(ShellPageId.DOWNLOADS);
                downloadsPage.set(panel.activePage());
                panel.navigateTo(ShellPageId.INSTANCES);
                returnedInstancesPage.set(panel.activePage());
            });

            assertAll(
                    () -> assertEquals(ShellPageId.INSTANCES, panel.selectedPage()),
                    () -> assertSame(initialInstancesPage.get(), returnedInstancesPage.get()),
                    () -> assertFalse(initialInstancesPage.get() == downloadsPage.get()),
                    () -> assertEquals(1, creationCounts.get(ShellPageId.INSTANCES).get()),
                    () -> assertEquals(1, creationCounts.get(ShellPageId.DOWNLOADS).get()),
                    () -> assertEquals(2, panel.cachedPageCount()),
                    () -> assertFalse(panel.isPageCached(ShellPageId.SETTINGS)));
        } finally {
            panel.close();
        }
    }

    /// Transient pages hide the persistent instance surface so transparent headings cannot paint together.
    @Test
    public void hidesPersistentInstancesPageBehindEveryTransientPage() {
        AppShellPanel panel = createPanel(creationCounts());

        try {
            EdtDispatcher.executeAndWait(() -> {
                JComponent instancesPage = Objects.requireNonNull(panel.activePage(), "instances page");
                for (ShellPageId overlay : List.of(
                        ShellPageId.ACCOUNTS,
                        ShellPageId.DOWNLOADS,
                        ShellPageId.SETTINGS)) {
                    panel.navigateTo(overlay);
                    assertAll(
                            () -> assertFalse(instancesPage.isVisible()),
                            () -> assertTrue(Objects.requireNonNull(panel.activePage()).isVisible()));
                    panel.navigateTo(ShellPageId.INSTANCES);
                    assertTrue(instancesPage.isVisible());
                }
            });
        } finally {
            panel.close();
        }
    }

    /// The title bar restores brand identity before directory, account, instance, launch, and native controls.
    @Test
    public void laysOutTitleBarWorkflowInStableOrder() {
        TestHomeModel homeModel = new TestHomeModel();
        AppShellPanel panel = createPanel(creationCounts(), homeModel);

        try {
            EdtDispatcher.executeAndWait(() -> {
                panel.setSize(new Dimension(RENDER_WIDTH, RENDER_HEIGHT));
                layoutTree(panel);

                ShellToolbarPanel toolbar = panel.toolbar();
                Component[] components = toolbar.getComponents();
                assertAll(
                        () -> assertEquals(7, components.length),
                        () -> assertSame(toolbar.macWindowButtonsPlaceholder(), components[0]),
                        () -> assertSame(toolbar.brandLabel(), components[1]),
                        () -> assertSame(toolbar.gameDirectorySelector(), components[2]),
                        () -> assertSame(toolbar.accountSelector(), components[3]),
                        () -> assertSame(toolbar.instanceSelector(), components[4]),
                        () -> assertSame(toolbar.launchButton(), components[5]),
                        () -> assertSame(toolbar.winWindowButtonsPlaceholder(), components[6]),
                        () -> assertTrue(rightEdge(toolbar.brandLabel()) <= toolbar.gameDirectorySelector().getX()),
                        () -> assertTrue(rightEdge(toolbar.gameDirectorySelector()) <= toolbar.accountSelector().getX()),
                        () -> assertTrue(rightEdge(toolbar.accountSelector()) <= toolbar.instanceSelector().getX()),
                        () -> assertTrue(rightEdge(toolbar.instanceSelector()) <= toolbar.launchButton().getX()),
                        () -> assertTrue(toolbar.instanceSelector().getX() > toolbar.getWidth() / 2),
                        () -> assertTrue(toolbar.brandLabel().getX() >= 12),
                        () -> assertEquals(Boolean.TRUE, toolbar.getClientProperty(
                                FlatClientProperties.COMPONENT_TITLE_BAR_CAPTION)),
                        () -> assertEquals("mac horizontal zeroInFullScreen", placeholderPolicy(
                                toolbar.macWindowButtonsPlaceholder())),
                        () -> assertEquals("win horizontal", placeholderPolicy(
                                toolbar.winWindowButtonsPlaceholder())),
                        () -> assertEquals(testHomeStrings().accountLabel(),
                                toolbar.accountSelector().valueButton()
                                        .getAccessibleContext().getAccessibleName()),
                        () -> assertEquals(
                                AccountListCellRenderer.ROW_HEIGHT
                                        + LazyAccountSelector.ADD_HEADER_HEIGHT
                                        + LazyAccountSelector.MANAGEMENT_FOOTER_HEIGHT,
                                toolbar.accountSelector().preparePopupSize().height),
                        () -> assertEquals("XYML", toolbar.brandLabel().getText()),
                        () -> assertNotNull(toolbar.brandLabel().getIcon()),
                        () -> assertEquals(testHomeStrings().launchAction(),
                                toolbar.launchButton().getAccessibleContext().getAccessibleName()),
                        () -> assertNull(
                                toolbar.gameDirectorySelector().valueButton()
                                        .getClientProperty(FlatClientProperties.BUTTON_TYPE)),
                        () -> assertNull(
                                toolbar.accountSelector().valueButton()
                                        .getClientProperty(FlatClientProperties.BUTTON_TYPE)),
                        () -> assertNull(
                                toolbar.instanceSelector().valueButton()
                                        .getClientProperty(FlatClientProperties.BUTTON_TYPE)),
                        () -> assertEquals(0,
                                toolbar.gameDirectorySelector().valueButton().getBackground().getAlpha()),
                        () -> assertEquals(0,
                                toolbar.accountSelector().valueButton().getBackground().getAlpha()),
                        () -> assertEquals(0,
                                toolbar.instanceSelector().valueButton().getBackground().getAlpha()),
                        () -> assertFalse(toolbar.gameDirectorySelector().valueButton().isOpaque()),
                        () -> assertFalse(toolbar.accountSelector().valueButton().isOpaque()),
                        () -> assertFalse(toolbar.instanceSelector().valueButton().isOpaque()),
                        () -> assertTrue(toolbar.gameDirectorySelector().valueButton().isContentAreaFilled()),
                        () -> assertTrue(toolbar.accountSelector().valueButton().isContentAreaFilled()),
                        () -> assertTrue(toolbar.instanceSelector().valueButton().isContentAreaFilled()),
                        () -> assertNull(toolbar.launchButton().getClientProperty("JButton.buttonType")),
                        () -> assertEquals(1, toolbar.accountSelector().getComponentCount()),
                        () -> assertEquals(1, toolbar.instanceSelector().getComponentCount()),
                        () -> assertEquals(testHomeStrings().addInstanceAction(),
                                toolbar.instanceSelector().addButton().getText()),
                        () -> assertEquals(
                                space.minecraftstl.xyml.util.i18n.I18n.i18n("account.create"),
                                toolbar.accountSelector().addButton().getText()),
                        () -> assertEquals(
                                space.minecraftstl.xyml.util.i18n.I18n.i18n("game_directory.manage"),
                                toolbar.gameDirectorySelector().manageButton().getText()),
                        () -> assertEquals(
                                2,
                                toolbar.gameDirectorySelector().manageButton().getParent().getComponentCount()),
                        () -> assertEquals(
                                ShellPageId.INSTANCES,
                                panel.navigationButton(ShellPageId.INSTANCES).page()));

                FlatSVGIcon accountIcon = assertInstanceOf(
                        FlatSVGIcon.class,
                        toolbar.accountSelector().valueButton().getIcon());
                FlatSVGIcon launchIcon = assertInstanceOf(FlatSVGIcon.class, toolbar.launchButton().getIcon());
                assertAll(
                        () -> assertTrue(accountIcon.hasFound()),
                        () -> assertTrue(launchIcon.hasFound()));

                toolbar.launchButton().doClick();
                toolbar.gameDirectorySelector().manageButton().doClick();
                assertEquals(ShellPageId.SETTINGS, panel.selectedPage());
                toolbar.accountSelector().manageButton().doClick();
                assertAll(
                        () -> assertEquals(1, homeModel.launchCount()),
                        () -> assertEquals(ShellPageId.ACCOUNTS, panel.selectedPage()));
                panel.navigationButton(ShellPageId.ACCOUNTS).doClick();
                assertAll(
                        () -> assertEquals(ShellPageId.INSTANCES, panel.selectedPage()),
                        () -> assertTrue(panel.navigationButton(ShellPageId.INSTANCES).isSelected()));
            });
        } finally {
            panel.close();
        }
    }

    /// Empty account state keeps the full selector and management route reachable.
    @Test
    public void keepsEmptyAccountSelectorReachable() {
        AppShellPanel panel = createPanel(
                pageFactories(creationCounts()),
                new TestHomeModel(),
                emptyAccountsModel());

        try {
            EdtDispatcher.executeAndWait(() -> {
                LazyAccountSelector selector = panel.toolbar().accountSelector();
                Dimension popupSize = selector.preparePopupSize();
                assertAll(
                        () -> assertTrue(selector.valueButton().isEnabled()),
                        () -> assertTrue(selector.manageButton().isEnabled()),
                        () -> assertFalse(selector.choiceList().isEnabled()),
                        () -> assertTrue(selector.emptyLabel().isVisible()),
                        () -> assertEquals(testHomeStrings().missingAccountLabel(),
                                selector.emptyLabel().getText()),
                        () -> assertEquals(
                                AccountListCellRenderer.ROW_HEIGHT
                                        + LazyAccountSelector.ADD_HEADER_HEIGHT
                                        + LazyAccountSelector.MANAGEMENT_FOOTER_HEIGHT,
                                popupSize.height));
                selector.manageButton().doClick();
                assertEquals(ShellPageId.ACCOUNTS, panel.selectedPage());
            });
        } finally {
            panel.close();
        }
    }

    /// Launcher selection lock disables account switching and its management footer together.
    @Test
    public void disablesAccountSelectorWithSelectionCommands() {
        AppShellPanel panel = createPanel(creationCounts(), new TestHomeModel(false));

        try {
            EdtDispatcher.executeAndWait(() -> {
                LazyAccountSelector selector = panel.toolbar().accountSelector();
                assertAll(
                        () -> assertFalse(selector.valueButton().isEnabled()),
                        () -> assertFalse(selector.manageButton().isEnabled()),
                        () -> assertFalse(selector.choiceList().isEnabled()));
            });
        } finally {
            panel.close();
        }
    }

    /// The preferred layout paints varied pixels while keeping the persistent base and toolbar bounded.
    @Test
    public void rendersFixedSizeShellWithoutOverflow() throws IOException {
        AppShellPanel panel = createPanel(creationCounts());
        BufferedImage image = new BufferedImage(RENDER_WIDTH, RENDER_HEIGHT, BufferedImage.TYPE_INT_ARGB);

        try {
            EdtDispatcher.executeAndWait(() -> {
                panel.setSize(new Dimension(RENDER_WIDTH, RENDER_HEIGHT));
                layoutTree(panel);
                Graphics2D graphics = image.createGraphics();
                try {
                    panel.paint(graphics);
                } finally {
                    graphics.dispose();
                }
            });

            Set<Integer> sampledColors = sampledColors(image);
            assertAll(
                    () -> assertEquals(RENDER_WIDTH, panel.getWidth()),
                    () -> assertEquals(RENDER_HEIGHT, panel.getHeight()),
                    () -> assertEquals(ShellPageId.INSTANCES, panel.selectedPage()),
                    () -> assertTrue(countOpaquePixels(image) > (long) RENDER_WIDTH * RENDER_HEIGHT * 9 / 10),
                    () -> assertTrue(sampledColors.size() >= 8));

            ShellNavigationButton accountButton = panel.navigationButton(ShellPageId.ACCOUNTS);
            ShellNavigationButton instancesButton = panel.navigationButton(ShellPageId.INSTANCES);
            ShellNavigationButton downloadsButton = panel.navigationButton(ShellPageId.DOWNLOADS);
            ShellNavigationButton settingsButton = panel.navigationButton(ShellPageId.SETTINGS);
            ShellNavigationRail navigationRail = assertInstanceOf(
                    ShellNavigationRail.class,
                    accountButton.getParent().getParent());
            JButton officialGroupButton = navigationRail.officialGroupButton();
            JButton helpButton = navigationRail.helpButton();
            int accountY = SwingUtilities.convertPoint(accountButton, 0, 0, navigationRail).y;
            int instancesY = SwingUtilities.convertPoint(instancesButton, 0, 0, navigationRail).y;
            int downloadsY = SwingUtilities.convertPoint(downloadsButton, 0, 0, navigationRail).y;
            int settingsY = SwingUtilities.convertPoint(settingsButton, 0, 0, navigationRail).y;
            int officialGroupY = SwingUtilities.convertPoint(
                    officialGroupButton,
                    0,
                    0,
                    navigationRail).y;
            int helpY = SwingUtilities.convertPoint(
                    helpButton,
                    0,
                    0,
                    navigationRail).y;
            assertAll(
                    () -> assertTrue(accountY < instancesY),
                    () -> assertTrue(instancesY < downloadsY),
                    () -> assertTrue(settingsY > navigationRail.getHeight() / 2),
                    () -> assertTrue(officialGroupY > settingsY),
                    () -> assertTrue(helpY > officialGroupY),
                    () -> assertTrue(
                            navigationRail.getHeight()
                                    - helpY
                                    - helpButton.getHeight() <= 10));

            for (ShellPageId page : List.of(
                    ShellPageId.ACCOUNTS,
                    ShellPageId.INSTANCES,
                    ShellPageId.DOWNLOADS,
                    ShellPageId.SETTINGS)) {
                ShellNavigationButton button = panel.navigationButton(page);
                FlatSVGIcon icon = assertInstanceOf(FlatSVGIcon.class, button.getIcon());
                assertAll(
                        () -> assertTrue(button.isFocusable()),
                        () -> assertEquals(ShellPagePresentations.englishFallback().get(page).mnemonic(),
                                button.getMnemonic()),
                        () -> assertTrue(icon.hasFound(), page + " navigation SVG was not found"));
            }

            Path report = Path.of("build", "reports", "swing-shell", "app-shell.png").toAbsolutePath();
            Files.createDirectories(report.getParent());
            assertTrue(ImageIO.write(image, "png", report.toFile()));
        } finally {
            panel.close();
        }
    }

    /// Closing releases the persistent base and created overlays once and rejects later navigation.
    @Test
    public void closesCreatedPagesExactlyOnce() {
        AtomicInteger closes = new AtomicInteger();
        EnumMap<ShellPageId, ShellPageFactory<? extends JComponent>> factories =
                new EnumMap<>(ShellPageId.class);
        for (ShellPageId page : ShellPageId.values()) {
            factories.put(page, () -> new CloseablePanel(closes));
        }
        AppShellPanel panel = createPanel(factories, new TestHomeModel());

        EdtDispatcher.executeAndWait(() -> {
            panel.navigateTo(ShellPageId.SETTINGS);
            panel.setTransferHandler(new ShellFileDropHandler(path -> true, ignored -> { }));
            panel.close();
            panel.close();
            assertEquals(0, panel.cachedPageCount());
            assertNull(panel.getTransferHandler());
            assertThrows(IllegalStateException.class, () -> panel.navigateTo(ShellPageId.DOWNLOADS));
        });

        assertEquals(2, closes.get());
    }

    /// Creates a shell with deterministic test models and sample pages.
    ///
    /// @param creationCounts mutable factory call counters
    /// @return initialized shell panel
    private static AppShellPanel createPanel(EnumMap<ShellPageId, AtomicInteger> creationCounts) {
        return createPanel(creationCounts, new TestHomeModel());
    }

    /// Creates a shell with a caller-owned home model for command assertions.
    ///
    /// @param creationCounts mutable factory call counters
    /// @param homeModel caller-owned launcher model
    /// @return initialized shell panel
    private static AppShellPanel createPanel(
            EnumMap<ShellPageId, AtomicInteger> creationCounts,
            TestHomeModel homeModel) {
        return createPanel(pageFactories(creationCounts), homeModel);
    }

    /// Creates a shell around caller-selected factories and deterministic supporting models.
    ///
    /// @param factories complete page factories
    /// @param homeModel caller-owned launcher model
    /// @return initialized shell panel
    private static AppShellPanel createPanel(
            Map<ShellPageId, ? extends ShellPageFactory<? extends JComponent>> factories,
            HomeModel homeModel) {
        return createPanel(factories, homeModel, testAccountsModel());
    }

    /// Creates a shell around caller-selected factories, launcher state, and account source.
    ///
    /// @param factories complete page factories
    /// @param homeModel caller-owned launcher model
    /// @param accountsModel caller-owned lazy account model
    /// @return initialized shell panel
    private static AppShellPanel createPanel(
            Map<ShellPageId, ? extends ShellPageFactory<? extends JComponent>> factories,
            HomeModel homeModel,
            AccountsModel accountsModel) {
        AtomicReference<@Nullable AppShellPanel> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            SwingThemeManager themeManager = new SwingThemeManager(
                    ThemeBrightnessPreference.LIGHT,
                    new SwingDesignTokens(8),
                    SystemThemeDetector.lightFallback());
            themeManager.initialize();
            result.set(new AppShellPanel(
                    "XYML",
                    factories,
                    ShellPagePresentations.englishFallback(),
                    new ShellToolbarModels(
                            homeModel,
                            testInstancesModel(),
                            accountsModel,
                            testGameDirectories(),
                            ShellRecentSelections.transientSelections()),
                    testHomeStrings(),
                    TaskProgressStrings.english(),
                    new SwingAnimator(MotionPolicy.OFF, 16),
                    Duration.ZERO,
                    Duration.ZERO));
        });
        return Objects.requireNonNull(result.get());
    }

    /// Creates one factory counter for every destination.
    ///
    /// @return complete zero-valued counters
    static EnumMap<ShellPageId, AtomicInteger> creationCounts() {
        EnumMap<ShellPageId, AtomicInteger> counts = new EnumMap<>(ShellPageId.class);
        for (ShellPageId page : ShellPageId.values()) {
            counts.put(page, new AtomicInteger());
        }
        return counts;
    }

    /// Creates complete lazy sample-page factories for shell and frame tests.
    ///
    /// @param creationCounts counters incremented by the corresponding factory
    /// @return one factory for every destination
    static EnumMap<ShellPageId, ShellPageFactory<? extends JComponent>> pageFactories(
            EnumMap<ShellPageId, AtomicInteger> creationCounts) {
        EnumMap<ShellPageId, ShellPageFactory<? extends JComponent>> factories =
                new EnumMap<>(ShellPageId.class);
        for (ShellPageId page : ShellPageId.values()) {
            factories.put(page, () -> {
                creationCounts.get(page).incrementAndGet();
                return samplePage(page);
            });
        }
        return factories;
    }

    /// Creates the deterministic launcher model shared by frame tests.
    ///
    /// @return enabled launcher model
    static HomeModel testHomeModel() {
        return new TestHomeModel();
    }

    /// Creates the deterministic empty instance source shared by shell tests.
    ///
    /// @return empty enabled instance model
    static InstancesModel testInstancesModel() {
        return new TestInstancesModel();
    }

    /// Creates the deterministic one-account source shared by shell and frame tests.
    ///
    /// @return one-account lazy selection model
    static AccountsModel testAccountsModel() {
        return new TestAccountsModel(true);
    }

    /// Creates the deterministic empty account source used by empty-state tests.
    ///
    /// @return empty lazy selection model
    private static AccountsModel emptyAccountsModel() {
        return new TestAccountsModel(false);
    }

    /// Creates the deterministic selected game-directory service shared by shell tests.
    ///
    /// @return one-entry directory service
    static GameDirectoryManagementService testGameDirectories() {
        return new TestGameDirectoryService();
    }

    /// Creates stable English toolbar strings for focused shell tests.
    ///
    /// @return localized launcher workflow text
    static HomeStrings testHomeStrings() {
        return new HomeStrings(
                "Launcher",
                "Account management",
                "No account",
                "Instance",
                "No instance",
                "Add instance",
                "Generate launch script",
                "Launch game",
                "Launching",
                "Back");
    }

    /// Creates a compact operational page used by layout rendering tests.
    ///
    /// @param page represented destination
    /// @return unframed sample page
    private static JComponent samplePage(ShellPageId page) {
        JPanel pagePanel = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 1",
                "[grow,fill]",
                "[]16[]18[grow,fill]"));
        JLabel heading = new JLabel(ShellPagePresentations.englishFallback().get(page).label());
        heading.setFont(heading.getFont().deriveFont(24.0f));

        JPanel toolbar = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", "[]"));
        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Search");
        JButton action = new JButton(page == ShellPageId.INSTANCES ? "Add" : "Open");
        toolbar.add(search, "wmin 180");
        toolbar.add(action);

        JPanel rows = new JPanel(new MigLayout("insets 0, fillx, wrap 1", "[grow,fill]", "[]10[]10[]"));
        rows.add(sampleRow("Minecraft 1.21", "Fabric"), "growx, h 68!");
        rows.add(sampleRow("Creative World", "Local"), "growx, h 68!");
        rows.add(sampleRow("Modded Profile", "Ready"), "growx, h 68!");

        pagePanel.add(heading);
        pagePanel.add(toolbar, "growx");
        pagePanel.add(new JScrollPane(rows), "grow");
        return pagePanel;
    }

    /// Creates one bounded sample row for the screenshot fixture.
    ///
    /// @param title row title
    /// @param status short row status
    /// @return un-nested row panel
    private static JComponent sampleRow(String title, String status) {
        JPanel row = new JPanel(new MigLayout("insets 10 14, fill", "[grow][]", "[grow,fill]"));
        row.add(new JLabel(title));
        row.add(new JLabel(status));
        return row;
    }

    /// Returns the trailing x-coordinate of one component in its direct parent.
    ///
    /// @param component component to measure
    /// @return right edge in parent coordinates
    private static int rightEdge(Component component) {
        return component.getX() + component.getWidth();
    }

    /// Returns one FlatLaf native-window placeholder policy.
    ///
    /// @param placeholder configured placeholder component
    /// @return platform placeholder policy string
    private static Object placeholderPolicy(JComponent placeholder) {
        return placeholder.getClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER);
    }

    /// Recursively lays out a non-displayable Swing tree after assigning its fixed test size.
    ///
    /// @param container tree root to lay out
    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container childContainer) {
                layoutTree(childContainer);
            }
        }
    }

    /// Counts non-transparent pixels in a rendered image.
    ///
    /// @param image rendered shell image
    /// @return number of pixels with non-zero alpha
    private static long countOpaquePixels(BufferedImage image) {
        long count = 0L;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    /// Samples rendered RGB values on a regular grid to detect blank output.
    ///
    /// @param image rendered shell image
    /// @return distinct sampled RGB values
    private static Set<Integer> sampledColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y += 8) {
            for (int x = 0; x < image.getWidth(); x += 8) {
                colors.add(image.getRGB(x, y));
            }
        }
        return colors;
    }

    /// Minimal enabled launcher model for title-bar and frame tests.
    @NotNullByDefault
    private static final class TestHomeModel implements HomeModel {
        /// Observable empty launch-session property required by the task overlay.
        private final SimpleObjectProperty<Optional<LaunchSession>> launchSession =
                new SimpleObjectProperty<>(this, "launchSession", Optional.empty());

        /// Number of launch commands delegated by the toolbar.
        private final AtomicInteger launches = new AtomicInteger();

        /// Whether this fixture permits account and instance selection commands.
        private final boolean selectionCommandsEnabled;

        /// Creates the default selection-enabled launcher state.
        private TestHomeModel() {
            this(true);
        }

        /// Creates a launcher state with caller-selected command availability.
        ///
        /// @param selectionCommandsEnabled whether selection commands are enabled
        private TestHomeModel(boolean selectionCommandsEnabled) {
            this.selectionCommandsEnabled = selectionCommandsEnabled;
        }

        /// Returns a launch-ready toolbar state.
        @Override
        public HomeSnapshot snapshot() {
            return new HomeSnapshot(
                    "Player",
                    "Offline",
                    "Fabric 1.21",
                    "Ready",
                    "Ready to launch",
                    true,
                    false,
                    selectionCommandsEnabled);
        }

        /// Registers a no-op test snapshot listener.
        ///
        /// @param listener required listener
        /// @return independently cancellable no-op registration
        @Override
        public Subscription subscribe(ValueChangeListener<HomeSnapshot> listener) {
            Objects.requireNonNull(listener, "listener");
            return Subscription.create(() -> { });
        }

        /// Returns the stable empty launch-session property.
        @Override
        public ReadOnlyProperty<Optional<LaunchSession>> launchSessionProperty() {
            return launchSession;
        }

        /// Records no state for the unused account-selection command.
        @Override
        public void selectAccount() {
        }

        /// Records no state for the unused direct instance-selection command.
        @Override
        public void selectInstance() {
        }

        /// Records no state for the unused add-instance command.
        @Override
        public void addInstance() {
        }

        /// Counts one toolbar launch command.
        @Override
        public void launch() {
            launches.incrementAndGet();
        }

        /// Returns the current toolbar launch count.
        ///
        /// @return launch invocation count
        private int launchCount() {
            return launches.get();
        }
    }

    /// Empty exact-count instance source that never performs filesystem access.
    @NotNullByDefault
    private static final class TestInstancesModel implements InstancesModel {
        /// Returns an enabled empty instance-page snapshot.
        @Override
        public InstancesSnapshot snapshot() {
            return new InstancesSnapshot(
                    OptionalInt.empty(),
                    0,
                    0L,
                    "Ready",
                    false,
                    true,
                    true,
                    true,
                    false);
        }

        /// Registers a no-op instance listener.
        ///
        /// @param listener required listener
        /// @return independently cancellable no-op registration
        @Override
        public Subscription subscribe(ValueChangeListener<InstancesSnapshot> listener) {
            Objects.requireNonNull(listener, "listener");
            return Subscription.create(() -> { });
        }

        /// Reports the exact empty item count.
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(0);
        }

        /// Returns the exact empty instance search index.
        @Override
        public @Unmodifiable List<InstanceSearchEntry> searchEntries() {
            return List.of();
        }

        /// Completes an empty clamped range immediately.
        ///
        /// @param desiredRange requested viewport range
        /// @param cancellation cooperative cancellation signal
        /// @return completed empty exact page
        @Override
        public CompletionStage<ChoicePage<InstanceListItem>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            Objects.requireNonNull(cancellation, "cancellation");
            IndexRange emptyRange = Objects.requireNonNull(desiredRange, "desiredRange").clampToItemCount(0);
            return CompletableFuture.completedFuture(
                    new ChoicePage<>(emptyRange, List.of(), OptionalInt.of(0), true));
        }

        /// Ignores selection because the source is empty.
        ///
        /// @param instanceId required but absent test identifier
        @Override
        public void selectInstance(String instanceId) {
            Objects.requireNonNull(instanceId, "instanceId");
        }

        /// Performs no refresh for the immutable empty source.
        @Override
        public void refreshInstances() {
        }

        /// Performs no add action for the focused shell test.
        @Override
        public void addInstance() {
        }

        /// Performs no management action because no instance is selected.
        @Override
        public void manageSelectedInstance() {
        }
    }

    /// One-account exact-count source that performs no network or filesystem access.
    @NotNullByDefault
    private static final class TestAccountsModel implements AccountsModel {
        /// Stable selected account row.
        private static final AccountListItem ACCOUNT = new AccountListItem(
                "account-1",
                "Player",
                "Offline",
                "00000000-0000-0000-0000-000000000001");

        /// Immutable exact source rows.
        private final @Unmodifiable List<AccountListItem> accounts;

        /// Creates either a one-account or empty exact source.
        ///
        /// @param populated whether to expose the stable account row
        private TestAccountsModel(boolean populated) {
            accounts = populated ? List.of(ACCOUNT) : List.of();
        }

        /// Returns the stable one-account selection snapshot.
        @Override
        public AccountsSnapshot snapshot() {
            OptionalInt selection = accounts.isEmpty() ? OptionalInt.empty() : OptionalInt.of(0);
            return new AccountsSnapshot(selection, accounts.size(), 0L);
        }

        /// Registers a no-op account listener.
        ///
        /// @param listener required listener
        /// @return independently cancellable no-op registration
        @Override
        public Subscription subscribe(ValueChangeListener<AccountsSnapshot> listener) {
            Objects.requireNonNull(listener, "listener");
            return Subscription.create(() -> { });
        }

        /// Reports the exact one-row account count.
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(accounts.size());
        }

        /// Completes the requested clamped account range immediately.
        ///
        /// @param desiredRange requested viewport range
        /// @param cancellation cooperative cancellation signal
        /// @return completed exact account page
        @Override
        public CompletionStage<ChoicePage<AccountListItem>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            Objects.requireNonNull(cancellation, "cancellation");
            IndexRange range = Objects.requireNonNull(desiredRange, "desiredRange")
                    .clampToItemCount(accounts.size());
            @Unmodifiable List<AccountListItem> items = List.copyOf(
                    accounts.subList(range.startInclusive(), range.endExclusive()));
            return CompletableFuture.completedFuture(
                    new ChoicePage<>(
                            range,
                            items,
                            OptionalInt.of(accounts.size()),
                            range.endExclusive() == accounts.size()));
        }

        /// Accepts the stable account identifier without mutating the fixture.
        ///
        /// @param accountId selected stable identifier
        @Override
        public void selectAccount(String accountId) {
            if (accounts.isEmpty()) {
                throw new AssertionError("Empty account fixture cannot accept selection");
            }
            assertEquals(ACCOUNT.accountId(), accountId);
        }

        /// Performs no add-account action in the focused shell fixture.
        @Override
        public void addAccount() {
        }

        /// Performs no removal in the immutable focused shell fixture.
        ///
        /// @param accountId stable identifier
        /// @param allowReadOnlyOverwrite ignored overwrite permission
        @Override
        public void removeAccount(String accountId, boolean allowReadOnlyOverwrite) {
            Objects.requireNonNull(accountId, "accountId");
        }

        /// Completes refresh immediately without external authentication.
        ///
        /// @param accountId stable identifier
        /// @return already-completed refresh stage
        @Override
        public CompletionStage<Void> refreshAccount(String accountId) {
            Objects.requireNonNull(accountId, "accountId");
            return CompletableFuture.completedFuture(null);
        }
    }

    /// One-entry game-directory service that performs no persistence.
    @NotNullByDefault
    private static final class TestGameDirectoryService implements GameDirectoryManagementService {
        /// Stable selected-directory snapshot rendered by the title-bar combo box.
        private final GameDirectoryManagementSnapshot snapshot = new GameDirectoryManagementSnapshot(
                0L,
                List.of(new GameDirectoryManagementEntry(
                        GameDirectoryID.NIL,
                        "Default",
                        PortablePath.of(".minecraft"),
                        true)));

        /// Returns the stable selected-directory snapshot.
        @Override
        public GameDirectoryManagementSnapshot snapshot() {
            return snapshot;
        }

        /// Registers a no-op directory listener.
        ///
        /// @param listener required listener
        /// @return independently cancellable no-op registration
        @Override
        public Subscription subscribe(ValueChangeListener<GameDirectoryManagementSnapshot> listener) {
            Objects.requireNonNull(listener, "listener");
            return Subscription.create(() -> { });
        }

        /// Accepts the stable selected identifier without mutating the fixture.
        ///
        /// @param id selected identifier
        @Override
        public void select(GameDirectoryID id) {
            Objects.requireNonNull(id, "id");
        }

        /// Rejects no values because persistence is outside the shell test.
        ///
        /// @param edit required directory edit
        /// @param allowReadOnlyOverwrite ignored overwrite permission
        @Override
        public void add(GameDirectoryManagementEdit edit, boolean allowReadOnlyOverwrite) {
            Objects.requireNonNull(edit, "edit");
        }

        /// Rejects no values because persistence is outside the shell test.
        ///
        /// @param id required directory identifier
        /// @param edit required directory edit
        /// @param allowReadOnlyOverwrite ignored overwrite permission
        @Override
        public void update(
                GameDirectoryID id,
                GameDirectoryManagementEdit edit,
                boolean allowReadOnlyOverwrite) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(edit, "edit");
        }

        /// Accepts removal without mutating the stable fixture.
        ///
        /// @param id required directory identifier
        /// @param allowReadOnlyOverwrite ignored overwrite permission
        @Override
        public void remove(GameDirectoryID id, boolean allowReadOnlyOverwrite) {
            Objects.requireNonNull(id, "id");
        }

        /// Releases no resources because registrations own their cancellation.
        @Override
        public void close() {
        }
    }

    /// Closeable page panel used to verify shell-owned resource cleanup.
    @NotNullByDefault
    private static final class CloseablePanel extends JPanel implements AutoCloseable {
        /// Shared close invocation counter.
        private final AtomicInteger closes;

        /// Creates one closeable test page.
        ///
        /// @param closes shared close counter
        private CloseablePanel(AtomicInteger closes) {
            this.closes = closes;
        }

        /// Records one shell-owned close invocation.
        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
