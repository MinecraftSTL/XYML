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
package space.minecraftstl.xyml.ui.swing.page.mods;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.mod.ModManager;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingHorizontalScrollPane;
import space.minecraftstl.xyml.ui.swing.SwingTextAreas;
import space.minecraftstl.xyml.ui.swing.SwingTextFields;
import space.minecraftstl.xyml.ui.swing.SwingTransparency;
import space.minecraftstl.xyml.ui.swing.choice.RichChoiceListCellRenderer;
import space.minecraftstl.xyml.ui.swing.choice.RowBoundsCheckedList;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;
import space.minecraftstl.xyml.ui.swing.page.instances.management.ViewportTrackingPanel;
import space.minecraftstl.xyml.ui.swing.shell.ShellFileDropHandler;
import space.minecraftstl.xyml.util.io.DeletionMode;
import space.minecraftstl.xyml.util.io.TrashMoveException;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Independent Swing page for one installed-Mod catalog.
///
/// The page owns its model and viewport list lifecycle. Construction and every component mutation
/// occur on the EDT; all disk and desktop operations are delegated to background-aware model and
/// interaction contracts. No JavaFX type or network-capable service is referenced.
@NotNullByDefault
public final class ModCatalogPanel extends JPanel implements AutoCloseable {
    /// Shared row icon that remains available in headless and high-DPI Swing sessions.
    private static final Icon MOD_ROW_ICON = new FlatSVGIcon(
            "assets/swing/icons/format-list-bulleted.svg",
            32,
            32);

    /// Toolkit-neutral installed-Mod model owned by this page.
    private final ModCatalogModel model;

    /// Localized page labels.
    private final ModCatalogStrings strings;

    /// Localized action presentation.
    private final ModCatalogActionStrings actionStrings;

    /// Dialog and asynchronous desktop interactions.
    private final ModCatalogInteractions interactions;

    /// Stable managed Mod directory used by import and directory-open commands.
    private final Path modsDirectory;

    /// Viewport-driven multi-choice list.
    private final ViewportChoiceList<ModCatalogItem> choiceList;

    /// Responsive split that avoids first-layout preferred-width overflow on narrow hosts.
    private final JComponent catalogSplit;

    /// Search field applied to the in-memory index.
    private final JTextField searchField = new JTextField();

    /// Enabled-state filter selector.
    private final JComboBox<ModCatalogFilter> filterBox = new JComboBox<>(ModCatalogFilter.values());

    /// Full-index refresh command.
    private final JButton refreshButton = new JButton();

    /// Opens the configured remote Mod catalog.
    private final JButton downloadButton = new JButton();

    /// Opens the instance update checker.
    private final JButton checkUpdatesButton = new JButton();

    /// Local Mod import command.
    private final JButton importButton = new JButton();

    /// Managed-directory open command.
    private final JButton openDirectoryButton = new JButton();

    /// Selected-file reveal command.
    private final JButton revealButton = new JButton();

    /// Selected-file deletion command.
    private final JButton deleteButton = new JButton();

    /// Selects every logical row in the current filtered index.
    private final JButton selectAllButton = new JButton();

    /// Enables every selected rename-stable Mod key.
    private final JButton enableSelectedButton = new JButton();

    /// Disables every selected rename-stable Mod key.
    private final JButton disableSelectedButton = new JButton();

    /// Permanently deletes every selected rename-stable Mod key.
    private final JButton deleteSelectedButton = new JButton();

    /// Selected enabled-state binary control.
    private final JCheckBox enabledToggle;

    /// Current index lifecycle text.
    private final JLabel statusLabel = new JLabel();

    /// Current mutation lifecycle text.
    private final JLabel writeStatusLabel = new JLabel();

    /// Selected Mod primary title or empty-selection placeholder.
    private final JTextArea detailTitle = SwingTextAreas.wrappingValue();

    /// Selected Mod identifier value.
    private final JTextArea idValue = SwingTextAreas.wrappingToken();

    /// Selected Mod version value.
    private final JTextArea versionValue = SwingTextAreas.wrappingToken();

    /// Selected target game version value.
    private final JTextArea gameVersionValue = SwingTextAreas.wrappingToken();

    /// Selected detected loader value.
    private final JTextArea loaderValue = SwingTextAreas.wrappingToken();

    /// Selected authors value.
    private final JTextArea authorsValue = SwingTextAreas.wrappingValue();

    /// Selected current file value.
    private final JTextArea fileValue = SwingTextAreas.wrappingToken();

    /// Selected complete plain-text description.
    private final JTextArea descriptionArea = new JTextArea();

    /// Search-document listener removed during closure.
    private final DocumentListener searchListener;

    /// Sparse-list data listener removed during closure.
    private final ListDataListener listDataListener;

    /// User selection listener removed during closure.
    private final ListSelectionListener selectionListener;

    /// Owned model subscription.
    private final Subscription modelSubscription;

    /// Page-scoped filtered Mod file-list route.
    private final ShellFileDropHandler.RouteRegistration dropRegistration;

    /// Last snapshot rendered by this panel.
    private ModCatalogSnapshot displayedSnapshot;

    /// Revision already applied to the viewport model.
    private long appliedContentRevision = -1L;

    /// Prevents programmatic control synchronization from issuing commands.
    private boolean synchronizing;

    /// Whether lifecycle teardown has completed.
    private boolean closed;

    /// Command opening the remote Mod catalog.
    private Runnable openDownloadsCommand = () -> { };

    /// Command opening the instance update checker.
    private Runnable checkUpdatesCommand = () -> { };

    /// Creates a production page backed by the real repository and default platform interactions.
    ///
    /// The caller owns the executor and must keep it available until this panel is closed.
    ///
    /// @param repository real game repository
    /// @param instanceId managed instance identifier
    /// @param executor caller-owned background executor
    /// @param strings localized page labels
    /// @param statusStrings localized model statuses
    /// @param actionStrings localized commands
    public ModCatalogPanel(
            GameRepository repository,
            GameInstanceID instanceId,
            Executor executor,
            ModCatalogStrings strings,
            ModCatalogStatusStrings statusStrings,
            ModCatalogActionStrings actionStrings) {
        this(
                new DefaultModCatalogModel(
                        Objects.requireNonNull(repository, "repository"),
                        Objects.requireNonNull(instanceId, "instanceId"),
                        Objects.requireNonNull(executor, "executor"),
                        Objects.requireNonNull(statusStrings, "statusStrings")),
                strings,
                actionStrings,
                new DefaultModCatalogInteractions(actionStrings, executor));
    }

    /// Creates a page with injected model and interaction boundaries for headless tests.
    ///
    /// The panel owns and closes the supplied model.
    ///
    /// @param model installed-Mod model
    /// @param strings localized page labels
    /// @param actionStrings localized commands
    /// @param interactions dialog and desktop interactions
    public ModCatalogPanel(
            ModCatalogModel model,
            ModCatalogStrings strings,
            ModCatalogActionStrings actionStrings,
            ModCatalogInteractions interactions) {
        this(model, strings, actionStrings, interactions, () -> { }, () -> { });
    }

    /// Creates a page with upstream-compatible remote content commands.
    ///
    /// @param model installed-Mod model
    /// @param strings localized page labels
    /// @param actionStrings localized commands
    /// @param interactions dialog and desktop interactions
    /// @param openDownloadsCommand command opening the remote Mod catalog
    /// @param checkUpdatesCommand command opening the instance update checker
    public ModCatalogPanel(
            ModCatalogModel model,
            ModCatalogStrings strings,
            ModCatalogActionStrings actionStrings,
            ModCatalogInteractions interactions,
            Runnable openDownloadsCommand,
            Runnable checkUpdatesCommand) {
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.actionStrings = Objects.requireNonNull(actionStrings, "actionStrings");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.openDownloadsCommand = Objects.requireNonNull(openDownloadsCommand, "openDownloadsCommand");
        this.checkUpdatesCommand = Objects.requireNonNull(checkUpdatesCommand, "checkUpdatesCommand");
        modsDirectory = model.modsDirectory().toAbsolutePath().normalize();
        displayedSnapshot = model.snapshot();
        enabledToggle = new JCheckBox(strings.enabledLabel());
        choiceList = new ViewportChoiceList<>(
                model,
                new RichChoiceListCellRenderer<>(
                        ModCatalogItem::displayText,
                        item -> modRowDetail(item, strings),
                        item -> "",
                        ModCatalogPanel::modRowIcon,
                        ModCatalogItem::description,
                        item -> !item.enabled()), RowBoundsCheckedList.BlankClickPolicy.CLEAR);
        searchListener = createSearchListener();
        listDataListener = createListDataListener();
        selectionListener = this::selectionChanged;

        setName("modsCatalogPage");
        setLayout(new BorderLayout());
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());
        add(createHeadingBand(), BorderLayout.NORTH);
        catalogSplit = createCatalogSplit();
        add(catalogSplit, BorderLayout.CENTER);
        add(createStatusBand(), BorderLayout.SOUTH);
        configureList();
        configureControls();
        showDetails(null);

        modelSubscription = model.subscribe(change -> {
            @Nullable ModCatalogSnapshot current = change.currentValue();
            if (current != null) {
                EdtDispatcher.execute(() -> applySnapshot(current));
            }
        });
        applySnapshot(displayedSnapshot);
        dropRegistration = ShellFileDropHandler.registerFiles(
                this,
                this::supportsDroppedMod,
                this::importDroppedMods);
        model.loadIfNeeded();
    }

    /// Returns the viewport list for host integration and deterministic tests.
    ///
    /// @return owned viewport list
    public ViewportChoiceList<ModCatalogItem> choiceList() {
        return choiceList;
    }

    /// Returns the latest snapshot rendered by the panel.
    ///
    /// @return displayed snapshot
    public ModCatalogSnapshot displayedSnapshot() {
        return displayedSnapshot;
    }

    /// Formats the installed Mod's compact metadata line without reading the file system.
    ///
    /// @param item loaded Mod row
    /// @param strings localized field labels
    /// @return one-line description, identifier, version, and author metadata
    private static String modRowDetail(ModCatalogItem item, ModCatalogStrings strings) {
        List<String> values = new ArrayList<>();
        String description = firstNonBlankLine(item.description());
        if (!description.isBlank()) {
            values.add(description);
        }
        if (!item.modId().isBlank()) {
            values.add(strings.idLabel() + ": " + item.modId());
        }
        if (!item.version().isBlank()) {
            values.add(strings.versionLabel() + ": " + item.version());
        }
        if (!item.authors().isBlank()) {
            values.add(strings.authorsLabel() + ": " + item.authors());
        }
        if (!item.gameVersion().isBlank()) {
            values.add(strings.gameVersionLabel() + ": " + item.gameVersion());
        }
        if (values.isEmpty()) {
            values.add(item.fileName());
        }
        return String.join(" | ", values);
    }

    /// Returns the embedded icon associated with the local Mod archive.
    ///
    /// @param item loaded Mod row
    /// @return embedded archive icon, or the generic catalog icon
    private static Icon modRowIcon(ModCatalogItem item) {
        @Nullable String logoBase64 = item.logoBase64();
        if (logoBase64 == null) {
            return MOD_ROW_ICON;
        }
        try {
            ImageIcon icon = new ImageIcon(Base64.getDecoder().decode(logoBase64));
            return icon.getIconWidth() <= 0 || icon.getIconHeight() <= 0
                    ? MOD_ROW_ICON
                    : icon;
        } catch (IllegalArgumentException ignored) {
            return MOD_ROW_ICON;
        }
    }

    /// Returns the first meaningful line from a potentially multiline description.
    ///
    /// @param text complete description
    /// @return trimmed first line, or an empty string
    private static String firstNonBlankLine(String text) {
        return text.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("");
    }

    /// Creates the title and global icon-command band.
    ///
    /// @return unframed heading component
    private JComponent createHeadingBand() {
        JPanel headingBand = new JPanel(new MigLayout(
                "insets 16 16 8 16, fillx",
                "[grow,fill][]8[]8[]",
                "[40!]"));
        headingBand.setOpaque(false);
        JLabel heading = new JLabel(strings.title());
        heading.setName("modsPageTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 26.0F));
        headingBand.add(heading, "growx");
        configureIconButton(
                refreshButton,
                "modsRefresh",
                "assets/swing/icons/refresh.svg",
                actionStrings.refreshAction(),
                actionStrings.refreshTooltip(),
                model::refresh);
        headingBand.add(refreshButton, "w 40!, h 40!");
        configureIconButton(
                importButton,
                "modsImport",
                "assets/swing/icons/file-import.svg",
                actionStrings.importAction(),
                actionStrings.importTooltip(),
                this::chooseAndImport);
        headingBand.add(importButton, "w 40!, h 40!");
        configureIconButton(
                openDirectoryButton,
                "modsOpenDirectory",
                "assets/swing/icons/folder-open.svg",
                actionStrings.openDirectoryAction(),
                actionStrings.openDirectoryTooltip(),
                this::openDirectory);
        headingBand.add(openDirectoryButton, "w 40!, h 40!");
        configureIconButton(
                checkUpdatesButton,
                "modsCheckUpdates",
                "assets/swing/icons/refresh.svg",
                i18n("addon.check_update.button"),
                i18n("addon.check_update.button"),
                () -> checkUpdatesCommand.run());
        headingBand.add(checkUpdatesButton, "w 40!, h 40!");
        configureIconButton(
                downloadButton,
                "modsDownload",
                "assets/swing/icons/nav-downloads.svg",
                i18n("mods.download"),
                i18n("mods.download"),
                () -> openDownloadsCommand.run());
        headingBand.add(downloadButton, "w 40!, h 40!");
        return headingBand;
    }

    /// Creates list controls and single-selection details in one stable split.
    ///
    /// @return borderless split pane
    private JComponent createCatalogSplit() {
        JPanel listSurface = new JPanel(new BorderLayout(0, 8));
        listSurface.setOpaque(false);
        listSurface.setBorder(BorderFactory.createEmptyBorder(8, 16, 12, 8));
        listSurface.setMinimumSize(new Dimension(0, 0));
        JPanel filters = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[][grow,fill]8[][140!]",
                "[32!]"));
        filters.setOpaque(false);
        JLabel searchLabel = new JLabel(strings.searchLabel());
        filters.add(searchLabel);
        searchField.setName("modsSearch");
        SwingTextFields.showClearButton(searchField);
        searchField.getAccessibleContext().setAccessibleName(strings.searchLabel());
        filters.add(searchField, "growx");
        JLabel filterLabel = new JLabel(strings.filterLabel());
        filters.add(filterLabel);
        filterBox.setName("modsFilter");
        filterBox.setRenderer(new FilterRenderer(strings));
        filterBox.getAccessibleContext().setAccessibleName(strings.filterLabel());
        filters.add(filterBox, "growx");
        JComponent batchToolbar = createBatchToolbar();
        JPanel listControls = new JPanel(new BorderLayout(0, 6));
        listControls.setOpaque(false);
        listControls.add(filters, BorderLayout.NORTH);
        listControls.add(batchToolbar, BorderLayout.SOUTH);
        listSurface.add(listControls, BorderLayout.NORTH);
        choiceList.setName("modsChoiceList");
        SwingTransparency.revealBackgroundThroughScrollPane(choiceList);
        choiceList.getList().setName("modsList");
        choiceList.getList().setOpaque(false);
        listSurface.add(choiceList, BorderLayout.CENTER);
        int filterMinimumWidth = searchLabel.getPreferredSize().width
                + SwingTextAreas.minimumTextWidth(searchField)
                + 8
                + filterLabel.getPreferredSize().width
                + 140
                + 16;
        int toolbarMinimumWidth = selectAllButton.getMinimumSize().width
                + enableSelectedButton.getMinimumSize().width
                + disableSelectedButton.getMinimumSize().width
                + deleteSelectedButton.getMinimumSize().width
                + 18;
        Insets listInsets = listSurface.getInsets();
        listSurface.setMinimumSize(new Dimension(
                Math.max(filterMinimumWidth, toolbarMinimumWidth)
                        + listInsets.left
                        + listInsets.right,
                0));

        ResponsiveCatalogSplitPane split = new ResponsiveCatalogSplitPane(
                listSurface,
                createDetailsSurface());
        return new SwingHorizontalScrollPane(
                split,
                "modsCatalogScroll",
                split.requiredMinimumWidth());
    }

    /// Creates compact logical-selection commands without materializing off-screen rows.
    ///
    /// @return transparent batch command toolbar
    private JComponent createBatchToolbar() {
        JPanel toolbar = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill]6[grow,fill]6[grow,fill]6[grow,fill]",
                "[36!]"));
        toolbar.setName("modsBatchToolbar");
        toolbar.setOpaque(false);
        configureTextButton(
                selectAllButton,
                "modsSelectAll",
                i18n("button.select_all"),
                this::selectAllMods);
        configureTextButton(
                enableSelectedButton,
                "modsEnableSelected",
                i18n("mods.enable"),
                () -> setSelectedModsEnabled(true));
        configureTextButton(
                disableSelectedButton,
                "modsDisableSelected",
                i18n("mods.disable"),
                () -> setSelectedModsEnabled(false));
        configureTextButton(
                deleteSelectedButton,
                "modsDeleteSelected",
                i18n("button.remove"),
                this::deleteSelectedMods);
        toolbar.add(selectAllButton, "growx, h 36!");
        toolbar.add(enableSelectedButton, "growx, h 36!");
        toolbar.add(disableSelectedButton, "growx, h 36!");
        toolbar.add(deleteSelectedButton, "growx, h 36!");
        return toolbar;
    }

    /// Creates the unframed selected-Mod details surface.
    ///
    /// @return compact details panel with an as-needed transparent vertical scrollbar
    private JComponent createDetailsSurface() {
        JPanel details = new ViewportTrackingPanel(new MigLayout(
                "insets 8 16 8 12, fillx, wrap 2",
                "[110!][grow,fill]",
                "[]8[][][][][][]8[]8[]"));
        details.setName("modsDetails");
        details.setOpaque(false);
        detailTitle.setName("modsDetailTitle");
        detailTitle.setFont(detailTitle.getFont().deriveFont(Font.BOLD, 20.0F));
        details.add(detailTitle, "span 2, growx, wmin 0");
        addDetailRow(details, strings.idLabel(), idValue, "modsDetailId");
        addDetailRow(details, strings.versionLabel(), versionValue, "modsDetailVersion");
        addDetailRow(details, strings.gameVersionLabel(), gameVersionValue, "modsDetailGameVersion");
        addDetailRow(details, strings.loaderLabel(), loaderValue, "modsDetailLoader");
        addDetailRow(details, strings.authorsLabel(), authorsValue, "modsDetailAuthors");
        addDetailRow(details, strings.fileLabel(), fileValue, "modsDetailFile");

        JLabel descriptionLabel = new JLabel(strings.descriptionLabel());
        details.add(descriptionLabel, "aligny top");
        descriptionArea.setName("modsDetailDescription");
        descriptionArea.setEditable(false);
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        descriptionArea.setRows(4);
        descriptionArea.setOpaque(false);
        JScrollPane descriptionScroll = new JScrollPane(descriptionArea);
        descriptionScroll.setName("modsDescriptionScroll");
        descriptionScroll.setBorder(BorderFactory.createEmptyBorder());
        descriptionScroll.setMinimumSize(new Dimension(0, 0));
        SwingTransparency.revealBackgroundThroughScrollPane(descriptionScroll);
        details.add(descriptionScroll, "growx, wmin 0");

        JPanel actions = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill][]8[]",
                "[40!]"));
        actions.setOpaque(false);
        actions.setMinimumSize(new Dimension(0, 0));
        enabledToggle.setName("modsEnabled");
        actions.add(enabledToggle, "growx, h 40!");
        configureIconButton(
                revealButton,
                "modsReveal",
                "assets/swing/icons/folder-open.svg",
                actionStrings.revealAction(),
                actionStrings.revealTooltip(),
                this::revealSelected);
        actions.add(revealButton, "w 40!, h 40!");
        configureIconButton(
                deleteButton,
                "modsDelete",
                "assets/swing/icons/delete.svg",
                actionStrings.deleteAction(),
                actionStrings.deleteTooltip(),
                this::deleteSelected);
        actions.add(deleteButton, "w 40!, h 40!");
        details.add(actions, "span 2, growx, wmin 0");

        JScrollPane scroll = new JScrollPane(details);
        scroll.setName("modsDetailsScroll");
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        int valueMinimumWidth = maximumMinimumTextWidth(
                idValue, versionValue, gameVersionValue,
                loaderValue, authorsValue, fileValue);
        int labeledContentWidth = 110 + 8 + valueMinimumWidth;
        int actionMinimumWidth = enabledToggle.getMinimumSize().width
                + 8
                + revealButton.getMinimumSize().width
                + 8
                + deleteButton.getMinimumSize().width;
        int detailsMinimumWidth = Math.max(
                Math.max(labeledContentWidth, actionMinimumWidth),
                valueMinimumWidth) + 28;
        int scrollBarWidth = scroll.getVerticalScrollBar().getPreferredSize().width;
        scroll.setMinimumSize(new Dimension(detailsMinimumWidth + scrollBarWidth, 0));
        SwingTransparency.revealBackgroundThroughScrollPane(scroll);
        return scroll;
    }

    /// Creates compact index and mutation status text.
    ///
    /// @return status band
    private JComponent createStatusBand() {
        JPanel statusBand = new JPanel(new MigLayout(
                "insets 4 16 12 16, fillx",
                "[grow,fill][grow,fill]",
                "[]"));
        statusBand.setOpaque(false);
        statusLabel.setName("modsStatus");
        writeStatusLabel.setName("modsWriteStatus");
        statusBand.add(statusLabel, "growx");
        statusBand.add(writeStatusLabel, "growx, alignx right");
        return statusBand;
    }

    /// Returns the largest sixteen-character minimum among the supplied detail values.
    ///
    /// @param values detail values participating in the right-column minimum
    /// @return largest minimum content width
    private static int maximumMinimumTextWidth(JTextArea... values) {
        int maximum = 0;
        for (JTextArea value : values) {
            maximum = Math.max(maximum, SwingTextAreas.minimumTextWidth(value));
        }
        return maximum;
    }

    /// Adds one read-only details label and wrapping value row.
    ///
    /// @param panel target details panel
    /// @param labelText localized label
    /// @param value reusable wrapping value area
    /// @param valueName deterministic component name
    private static void addDetailRow(
            JPanel panel,
            String labelText,
            JTextArea value,
            String valueName) {
        panel.add(new JLabel(labelText));
        value.setName(valueName);
        value.getAccessibleContext().setAccessibleName(labelText);
        panel.add(value, "growx, wmin 0");
    }

    /// Installs list listeners used for sparse loading and stable selection.
    private void configureList() {
        choiceList.getList().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        choiceList.getList().addListSelectionListener(selectionListener);
        choiceList.getChoiceModel().addListDataListener(listDataListener);
    }

    /// Installs search, filter, and enabled-state command listeners.
    private void configureControls() {
        searchField.getDocument().addDocumentListener(searchListener);
        filterBox.addActionListener(event -> {
            if (!closed && !synchronizing) {
                @Nullable ModCatalogFilter selected = (ModCatalogFilter) filterBox.getSelectedItem();
                if (selected != null) {
                    model.setFilter(selected);
                }
            }
        });
        enabledToggle.addActionListener(event -> toggleSelected());
    }

    /// Creates search callbacks after all listener-dependent fields are initialized.
    ///
    /// @return document listener
    private DocumentListener createSearchListener() {
        return new DocumentListener() {
            /// Applies inserted query text.
            @Override
            public void insertUpdate(DocumentEvent event) {
                searchChanged();
            }

            /// Applies removed query text.
            @Override
            public void removeUpdate(DocumentEvent event) {
                searchChanged();
            }

            /// Applies attribute changes as ordinary query changes.
            @Override
            public void changedUpdate(DocumentEvent event) {
                searchChanged();
            }
        };
    }

    /// Creates sparse row callbacks that reconcile a selected loading placeholder.
    ///
    /// @return list data listener
    private ListDataListener createListDataListener() {
        return new ListDataListener() {
            /// Reconciles inserted sparse rows.
            @Override
            public void intervalAdded(ListDataEvent event) {
                loadedRowsChanged();
            }

            /// Reconciles removed sparse rows.
            @Override
            public void intervalRemoved(ListDataEvent event) {
                loadedRowsChanged();
            }

            /// Reconciles loading, error, and loaded transitions.
            @Override
            public void contentsChanged(ListDataEvent event) {
                loadedRowsChanged();
            }
        };
    }

    /// Applies current search text unless controls are synchronizing.
    private void searchChanged() {
        if (!synchronizing && !closed) {
            model.setSearchQuery(searchField.getText());
        }
    }

    /// Delegates one loaded single selection and keeps multi-selection panel-owned.
    ///
    /// @param event list selection event
    private void selectionChanged(ListSelectionEvent event) {
        if (closed || synchronizing || event.getValueIsAdjusting()) {
            return;
        }
        int selectedCount = choiceList.getList().getSelectedIndices().length;
        if (selectedCount == 0) {
            model.clearSelection();
            showDetails(null);
            updateSelectionActions();
            return;
        }
        if (selectedCount > 1) {
            model.clearSelection();
            showDetails(null);
            updateSelectionActions();
            return;
        }
        @Nullable ModCatalogItem selected = currentSingleSelectedItem();
        if (selected != null) {
            model.selectMod(selected.localKey());
        }
        showDetails(selected);
        updateSelectionActions();
    }

    /// Rechecks whether the selected placeholder has become a loaded row.
    private void loadedRowsChanged() {
        if (closed || synchronizing) {
            return;
        }
        @Nullable ModCatalogItem selected = currentSingleSelectedItem();
        if (selected != null) {
            model.selectMod(selected.localKey());
        }
        showDetails(selected);
        updateSelectionActions();
    }

    /// Returns the loaded single selection only when it still matches the model's current row identity.
    ///
    /// A background mutation can commit a replacement index before the EDT applies its snapshot. Any
    /// page completion already queued on the EDT still belongs to the old visual generation and must
    /// not submit its stale key back to the strict model selection API.
    ///
    /// @return current loaded selection, or `null` after clearing a stale selection
    private @Nullable ModCatalogItem currentSingleSelectedItem() {
        JList<?> list = choiceList.getList();
        int selectedIndex = list.getSelectedIndex();
        @Nullable ModCatalogItem selected = singleSelectedItem();
        if (selected == null) {
            return null;
        }
        @Unmodifiable List<String> currentKeys = model.filteredLocalKeys();
        if (selectedIndex < 0
                || selectedIndex >= currentKeys.size()
                || !currentKeys.get(selectedIndex).equals(selected.localKey())) {
            list.clearSelection();
            return null;
        }
        return selected;
    }

    /// Applies one immutable model snapshot to Swing state.
    ///
    /// @param snapshot latest model snapshot
    private void applySnapshot(ModCatalogSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        synchronizing = true;
        try {
            displayedSnapshot = snapshot;
            boolean contentChanged = appliedContentRevision != snapshot.contentRevision();
            if (contentChanged) {
                appliedContentRevision = snapshot.contentRevision();
                choiceList.getList().clearSelection();
                choiceList.reloadData();
            }
            if (!searchField.getText().equals(snapshot.searchQuery())) {
                searchField.setText(snapshot.searchQuery());
            }
            filterBox.setSelectedItem(snapshot.filter());
            choiceList.getList().setEnabled(snapshot.listEnabled());
            restoreSelection(snapshot, contentChanged);
            statusLabel.setText(snapshot.statusText());
            writeStatusLabel.setText(snapshot.writeStatusText());
            boolean mutationIdle = snapshot.writeStatus() != ModCatalogWriteStatus.BUSY;
            searchField.setEnabled(mutationIdle);
            filterBox.setEnabled(mutationIdle);
            refreshButton.setEnabled(snapshot.refreshEnabled());
            importButton.setEnabled(snapshot.status() == ModCatalogStatus.READY && mutationIdle);
            openDirectoryButton.setEnabled(mutationIdle);
            checkUpdatesButton.setEnabled(mutationIdle);
            downloadButton.setEnabled(mutationIdle);
        } finally {
            synchronizing = false;
        }
        @Nullable ModCatalogItem selected = singleSelectedItem();
        showDetails(selected);
        updateSelectionActions();
    }

    /// Restores a model-owned single selection without collapsing a current panel-owned multi-selection.
    ///
    /// @param snapshot latest model state
    /// @param contentChanged whether logical list indexes were replaced
    private void restoreSelection(ModCatalogSnapshot snapshot, boolean contentChanged) {
        JList<?> list = choiceList.getList();
        if (snapshot.selectedIndex().isPresent()) {
            int selectedIndex = snapshot.selectedIndex().getAsInt();
            int itemCount = snapshot.itemCount().orElse(0);
            if (selectedIndex >= 0 && selectedIndex < itemCount) {
                if (!contentChanged
                        && list.getSelectedIndices().length > 1
                        && list.isSelectedIndex(selectedIndex)) {
                    return;
                }
                list.setSelectedIndex(selectedIndex);
                return;
            }
        }
        if (contentChanged || list.getSelectedIndices().length <= 1) {
            list.clearSelection();
        }
    }

    /// Renders one loaded selection or the empty-selection state.
    ///
    /// @param item selected loaded row, or `null`
    private void showDetails(@Nullable ModCatalogItem item) {
        synchronizing = true;
        try {
            detailTitle.setText(item == null ? strings.emptySelectionText() : item.displayText());
            idValue.setText(item == null ? "" : item.modId());
            versionValue.setText(item == null ? "" : item.version());
            gameVersionValue.setText(item == null ? "" : item.gameVersion());
            loaderValue.setText(item == null ? "" : item.loaderType().name());
            authorsValue.setText(item == null ? "" : item.authors());
            fileValue.setText(item == null ? "" : item.path().toString());
            descriptionArea.setText(item == null ? "" : item.description());
            descriptionArea.setCaretPosition(0);
            enabledToggle.setSelected(item != null && item.enabled());
        } finally {
            synchronizing = false;
        }
    }

    /// Updates selected-row commands from loaded selection and model state.
    private void updateSelectionActions() {
        @Nullable ModCatalogSnapshot writable = currentWritableSnapshot();
        int selectedCount = choiceList.getList().getSelectedIndices().length;
        int visibleCount = model.filteredLocalKeys().size();
        @Nullable ModCatalogItem selected = singleSelectedItem();
        boolean singleMutable = writable != null && selected != null;
        enabledToggle.setEnabled(singleMutable);
        revealButton.setEnabled(singleMutable);
        deleteButton.setEnabled(singleMutable);
        selectAllButton.setEnabled(writable != null
                && visibleCount > 0
                && selectedCount < visibleCount);
        enableSelectedButton.setEnabled(writable != null && selectedCount > 0);
        disableSelectedButton.setEnabled(writable != null && selectedCount > 0);
        deleteSelectedButton.setEnabled(writable != null && selectedCount > 0);
    }

    /// Returns the loaded row only when exactly one logical list index is selected.
    ///
    /// @return exact loaded single selection, or null for none, multiple, or a placeholder
    private @Nullable ModCatalogItem singleSelectedItem() {
        return choiceList.getList().getSelectedIndices().length == 1
                ? choiceList.getSelectedValue()
                : null;
    }

    /// Returns a stable page snapshot that may accept a mutation regardless of visible row count.
    ///
    /// @return ready writable snapshot, or null when stale, loading, closed, or busy
    private @Nullable ModCatalogSnapshot currentReadyWritableSnapshot() {
        ModCatalogSnapshot current = model.snapshot();
        return !closed
                && current.contentRevision() == displayedSnapshot.contentRevision()
                && current.status() == ModCatalogStatus.READY
                && current.writeStatus() != ModCatalogWriteStatus.BUSY
                ? current
                : null;
    }

    /// Returns a stable writable snapshot that also contains selectable rows.
    ///
    /// @return writable selection snapshot, or null when stale, loading, empty, or busy
    private @Nullable ModCatalogSnapshot currentWritableSnapshot() {
        @Nullable ModCatalogSnapshot ready = currentReadyWritableSnapshot();
        return ready != null && ready.listEnabled() ? ready : null;
    }

    /// Captures selected logical indexes as immutable rename-stable keys without loading rows.
    ///
    /// @return immutable selected keys in filtered list order, or empty for stale indexes
    private @Unmodifiable List<String> selectedLocalKeys() {
        int[] selectedIndices = choiceList.getList().getSelectedIndices();
        @Unmodifiable List<String> visibleKeys = model.filteredLocalKeys();
        List<String> selectedKeys = new ArrayList<>(selectedIndices.length);
        for (int selectedIndex : selectedIndices) {
            if (selectedIndex < 0 || selectedIndex >= visibleKeys.size()) {
                return List.of();
            }
            selectedKeys.add(visibleKeys.get(selectedIndex));
        }
        return List.copyOf(selectedKeys);
    }

    /// Revalidates a stable-key selection after a potentially modal interaction.
    ///
    /// @param expectedRevision captured content revision
    /// @param expectedKeys captured stable keys
    /// @return whether the exact writable selection remains current
    private boolean isBatchSelectionCurrent(
            long expectedRevision,
            @Unmodifiable List<String> expectedKeys) {
        @Nullable ModCatalogSnapshot current = currentWritableSnapshot();
        return current != null
                && current.contentRevision() == expectedRevision
                && selectedLocalKeys().equals(expectedKeys);
    }

    /// Selects every logical row without accessing sparse row values.
    private void selectAllMods() {
        @Nullable ModCatalogSnapshot snapshot = currentWritableSnapshot();
        if (snapshot == null) {
            return;
        }
        int itemCount = snapshot.itemCount().orElse(0);
        if (itemCount > 0) {
            choiceList.getList().setSelectionInterval(0, itemCount - 1);
        }
        updateSelectionActions();
    }

    /// Submits one enabled state for the exact selected stable-key batch.
    ///
    /// @param enabled desired enabled state
    private void setSelectedModsEnabled(boolean enabled) {
        @Nullable ModCatalogSnapshot snapshot = currentWritableSnapshot();
        if (snapshot == null) {
            return;
        }
        @Unmodifiable List<String> selectedKeys = selectedLocalKeys();
        if (!selectedKeys.isEmpty()
                && isBatchSelectionCurrent(snapshot.contentRevision(), selectedKeys)) {
            submitModsEnabled(selectedKeys, enabled);
        }
    }

    /// Chooses a mode and deletes the exact selected stable-key batch.
    private void deleteSelectedMods() {
        @Nullable ModCatalogSnapshot snapshot = currentWritableSnapshot();
        if (snapshot == null) {
            return;
        }
        @Unmodifiable List<String> selectedKeys = selectedLocalKeys();
        if (selectedKeys.isEmpty()) {
            return;
        }
        int selectedCount = selectedKeys.size();
        @Nullable DeletionMode mode = interactions.chooseDeleteModeSelected(this, selectedCount);
        if (mode == null) {
            return;
        }
        if (isBatchSelectionCurrent(snapshot.contentRevision(), selectedKeys)) {
            observeDeletion(
                    () -> model.deleteMods(selectedKeys, mode),
                    mode,
                    () -> model.deleteMods(selectedKeys, DeletionMode.PERMANENT),
                    () -> interactions.confirmPermanentFallbackSelected(this, selectedCount));
        }
    }

    /// Opens the chooser and submits selected archives as one serialized import.
    private void chooseAndImport() {
        List<Path> sources = interactions.chooseImportFiles(this, modsDirectory);
        resolveAndSubmitImport(sources);
    }

    /// Resolves known conflicts and submits one immutable source batch.
    ///
    /// @param sources selected or dropped Mod sources
    private void resolveAndSubmitImport(@Unmodifiable List<Path> sources) {
        if (sources.isEmpty()) {
            return;
        }
        try {
            @Unmodifiable List<Path> conflicts = model.findImportConflicts(sources);
            Map<Path, ModImportConflictAction> conflictActions = new LinkedHashMap<>();
            for (Path conflict : conflicts) {
                if (conflictActions.containsKey(conflict)) {
                    continue;
                }
                @Nullable ModImportConflictAction action = interactions.resolveImportConflict(
                        this, conflict);
                if (action == null) {
                    return;
                }
                conflictActions.put(conflict, action);
            }
            submitImport(sources, conflictActions);
        } catch (RuntimeException failure) {
            interactions.showFailure(this, actionStrings.errorTitle(), failureDetail(failure));
        }
    }

    /// Submits one resolved import and prompts again if disk state introduced a later conflict.
    ///
    /// @param sources original import sources
    /// @param conflictActions conflict decisions collected so far
    private void submitImport(
            @Unmodifiable List<Path> sources,
            @Unmodifiable Map<Path, ModImportConflictAction> conflictActions) {
        @Unmodifiable List<Path> capturedSources = List.copyOf(sources);
        @Unmodifiable Map<Path, ModImportConflictAction> capturedActions = Map.copyOf(conflictActions);
        model.importMods(capturedSources, capturedActions).whenComplete(
                (@Nullable ModCatalogSnapshot ignored, @Nullable Throwable failure) -> {
                    if (failure == null) {
                        return;
                    }
                    Throwable cause = unwrapFailure(failure);
                    EdtDispatcher.execute(() -> handleImportFailure(
                            capturedSources, capturedActions, cause));
                });
    }

    /// Resolves a late disk conflict or presents an ordinary import failure.
    ///
    /// @param sources original import sources
    /// @param conflictActions conflict decisions collected so far
    /// @param failure unwrapped import failure
    private void handleImportFailure(
            @Unmodifiable List<Path> sources,
            @Unmodifiable Map<Path, ModImportConflictAction> conflictActions,
            Throwable failure) {
        if (closed) {
            return;
        }
        if (failure instanceof ModImportConflictException conflict
                && !conflictActions.containsKey(conflict.source())) {
            @Nullable ModImportConflictAction action = interactions.resolveImportConflict(
                    this, conflict.source());
            if (action != null) {
                Map<Path, ModImportConflictAction> replacement = new LinkedHashMap<>(conflictActions);
                replacement.put(conflict.source(), action);
                submitImport(sources, replacement);
            }
            return;
        }
        interactions.showRetryableFailure(
                this,
                actionStrings.errorTitle(),
                failureDetail(failure),
                () -> submitImport(sources, conflictActions));
    }

    /// Returns whether this writable page accepts one dropped Mod path.
    ///
    /// @param source normalized dropped path
    /// @return whether the path has a supported Mod suffix and the catalog can write
    private boolean supportsDroppedMod(Path source) {
        return currentReadyWritableSnapshot() != null && ModManager.isFileNameMod(source);
    }

    /// Imports all supported Mod paths delivered by the page-scoped drop route.
    ///
    /// @param sources immutable supported paths in transfer order
    private void importDroppedMods(@Unmodifiable List<Path> sources) {
        EdtDispatcher.requireEventDispatchThread();
        if (!sources.isEmpty() && currentReadyWritableSnapshot() != null) {
            @Unmodifiable List<Path> capturedSources = List.copyOf(sources);
            SwingUtilities.invokeLater(() -> {
                if (!closed && currentReadyWritableSnapshot() != null) {
                    resolveAndSubmitImport(capturedSources);
                }
            });
        }
    }

    /// Schedules creation and opening of the managed Mod directory.
    private void openDirectory() {
        observeFailure(interactions.openDirectory(modsDirectory));
    }

    /// Schedules revealing the exact selected Mod file.
    private void revealSelected() {
        @Nullable ModCatalogItem selected = singleSelectedItem();
        if (selected != null) {
            observeFailure(interactions.reveal(selected.path()));
        }
    }

    /// Chooses a mode and deletes the exact selected Mod.
    private void deleteSelected() {
        @Nullable ModCatalogItem selected = singleSelectedItem();
        if (selected == null) {
            return;
        }
        @Nullable DeletionMode mode = interactions.chooseDeleteMode(this, selected);
        if (mode == null) {
            return;
        }
        observeDeletion(
                () -> model.deleteMod(selected.localKey(), mode),
                mode,
                () -> model.deleteMod(selected.localKey(), DeletionMode.PERMANENT),
                () -> interactions.confirmPermanentFallback(this, selected));
    }

    /// Submits one enabled-state change from the checkbox.
    private void toggleSelected() {
        if (closed || synchronizing) {
            return;
        }
        @Nullable ModCatalogItem selected = singleSelectedItem();
        if (selected != null) {
            submitModEnabled(selected.localKey(), enabledToggle.isSelected());
        }
    }

    /// Submits one exact enabled-state batch and captures the same request for retry.
    ///
    /// @param localKeys immutable stable keys
    /// @param enabled desired enabled state
    private void submitModsEnabled(@Unmodifiable List<String> localKeys, boolean enabled) {
        @Unmodifiable List<String> capturedKeys = List.copyOf(localKeys);
        observeFailure(
                model.setModsEnabled(capturedKeys, enabled),
                () -> submitModsEnabled(capturedKeys, enabled));
    }

    /// Submits one exact enabled-state change and captures the same request for retry.
    ///
    /// @param localKey stable target key
    /// @param enabled desired enabled state
    private void submitModEnabled(String localKey, boolean enabled) {
        String capturedKey = Objects.requireNonNull(localKey, "localKey");
        observeFailure(
                model.setModEnabled(capturedKey, enabled),
                () -> submitModEnabled(capturedKey, enabled));
    }

    /// Observes one deletion and preserves recycle-bin fallback plus exact retry behavior.
    ///
    /// @param operation initial deletion operation
    /// @param mode mode used by the initial operation
    /// @param permanentRetry permanent deletion retry
    /// @param confirmFallback fallback confirmation
    private void observeDeletion(
            Supplier<CompletionStage<?>> operation,
            DeletionMode mode,
            Supplier<CompletionStage<?>> permanentRetry,
            BooleanSupplier confirmFallback) {
        Supplier<CompletionStage<?>> capturedOperation = Objects.requireNonNull(operation, "operation");
        CompletionStage<?> stage;
        try {
            stage = Objects.requireNonNull(capturedOperation.get(), "deletion returned null");
        } catch (RuntimeException failure) {
            handleDeletionFailure(capturedOperation, mode, permanentRetry, confirmFallback, failure);
            return;
        }
        stage.whenComplete((@Nullable Object ignored, @Nullable Throwable failure) -> {
            if (failure != null) {
                Throwable cause = unwrapFailure(failure);
                EdtDispatcher.execute(() -> handleDeletionFailure(
                        capturedOperation,
                        mode,
                        permanentRetry,
                        confirmFallback,
                        cause));
            }
        });
    }

    /// Handles one deletion failure after restoring the Swing failure boundary.
    ///
    /// @param operation exact deletion retry operation
    /// @param mode mode used by the failed operation
    /// @param permanentRetry permanent deletion retry
    /// @param confirmFallback fallback confirmation
    /// @param failure original deletion failure
    private void handleDeletionFailure(
            Supplier<CompletionStage<?>> operation,
            DeletionMode mode,
            Supplier<CompletionStage<?>> permanentRetry,
            BooleanSupplier confirmFallback,
            Throwable failure) {
        if (closed) {
            return;
        }
        if (mode == DeletionMode.RECYCLE_BIN_FIRST && hasTrashMoveFailure(failure)) {
            if (confirmFallback.getAsBoolean()) {
                observeDeletion(
                        permanentRetry,
                        DeletionMode.PERMANENT,
                        permanentRetry,
                        confirmFallback);
            }
            return;
        }
        interactions.showRetryableFailure(
                this,
                actionStrings.errorTitle(),
                failureDetail(failure),
                () -> observeDeletion(operation, mode, permanentRetry, confirmFallback));
    }

    /// Detects a recycle-bin failure through asynchronous wrappers.
    ///
    /// @param failure unwrapped operation failure
    /// @return whether a [TrashMoveException] exists in the cause chain
    private static boolean hasTrashMoveFailure(Throwable failure) {
        @Nullable Throwable current = failure;
        while (current != null) {
            if (current instanceof TrashMoveException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /// Shows asynchronous model or desktop failures exactly once while open.
    ///
    /// @param stage observed asynchronous operation
    private void observeFailure(CompletionStage<?> stage) {
        observeFailure(stage, null);
    }

    /// Shows one asynchronous failure with an optional exact retry request.
    ///
    /// @param stage observed asynchronous operation
    /// @param retryAction captured retry request, or null for a terminal failure
    private void observeFailure(
            CompletionStage<?> stage,
            @Nullable Runnable retryAction) {
        stage.whenComplete((@Nullable Object ignored, @Nullable Throwable failure) -> {
            if (failure != null) {
                EdtDispatcher.execute(() -> {
                    if (!closed) {
                        String detail = failureDetail(failure);
                        if (retryAction == null) {
                            interactions.showFailure(
                                    this,
                                    actionStrings.errorTitle(),
                                    detail);
                        } else {
                            interactions.showRetryableFailure(
                                    this,
                                    actionStrings.errorTitle(),
                                    detail,
                                    retryAction);
                        }
                    }
                });
            }
        });
    }

    /// Returns concise detail after removing asynchronous wrapper exceptions.
    ///
    /// @param failure asynchronous failure
    /// @return original message or type name
    private static String failureDetail(Throwable failure) {
        Throwable current = unwrapFailure(failure);
        if (current instanceof AccessDeniedException accessDenied) {
            @Nullable String targetFile = accessDenied.getOtherFile();
            if (targetFile != null && !targetFile.isBlank()) {
                return i18n("exception.file_in_use", targetFile);
            }
            @Nullable String affectedFile = accessDenied.getFile();
            return i18n(
                    "exception.access_denied",
                    affectedFile == null ? current.getClass().getSimpleName() : affectedFile);
        }
        @Nullable String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    /// Removes asynchronous completion wrappers while preserving the original failure object.
    ///
    /// @param failure asynchronous failure
    /// @return first non-completion cause
    private static Throwable unwrapFailure(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = Objects.requireNonNull(current.getCause());
        }
        return current;
    }

    /// Configures one fixed-size bundled SVG icon command.
    ///
    /// @param button target button
    /// @param name deterministic component name
    /// @param iconPath bundled icon path
    /// @param accessibleName localized command name
    /// @param tooltip localized tooltip
    /// @param action command callback
    private static void configureIconButton(
            JButton button,
            String name,
            String iconPath,
            String accessibleName,
            String tooltip,
            Runnable action) {
        button.setName(name);
        button.setIcon(new FlatSVGIcon(iconPath, 18, 18));
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(accessibleName);
        button.setPreferredSize(new Dimension(40, 40));
        button.addActionListener(event -> action.run());
    }

    /// Configures one localized text command with matching accessibility text.
    ///
    /// @param button target command button
    /// @param name deterministic component name
    /// @param text localized visible and accessible text
    /// @param action command callback
    private static void configureTextButton(
            JButton button,
            String name,
            String text,
            Runnable action) {
        button.setName(Objects.requireNonNull(name, "name"));
        button.setText(Objects.requireNonNull(text, "text"));
        button.setToolTipText(text);
        button.getAccessibleContext().setAccessibleName(text);
        button.getAccessibleContext().setAccessibleDescription(text);
        Runnable checkedAction = Objects.requireNonNull(action, "action");
        button.addActionListener(event -> checkedAction.run());
    }

    /// Detaches listeners, cancels sparse loads, and closes the owned model.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        closed = true;
        dropRegistration.close();
        searchField.setEnabled(false);
        filterBox.setEnabled(false);
        refreshButton.setEnabled(false);
        checkUpdatesButton.setEnabled(false);
        downloadButton.setEnabled(false);
        importButton.setEnabled(false);
        openDirectoryButton.setEnabled(false);
        enabledToggle.setEnabled(false);
        revealButton.setEnabled(false);
        deleteButton.setEnabled(false);
        selectAllButton.setEnabled(false);
        enableSelectedButton.setEnabled(false);
        disableSelectedButton.setEnabled(false);
        deleteSelectedButton.setEnabled(false);
        searchField.getDocument().removeDocumentListener(searchListener);
        choiceList.getChoiceModel().removeListDataListener(listDataListener);
        choiceList.getList().removeListSelectionListener(selectionListener);
        modelSubscription.unsubscribe();
        choiceList.close();
        model.close();
    }

    /// Keeps the Mod catalog split horizontal while preserving user-adjustable minimum widths.
    @NotNullByDefault
    private static final class ResponsiveCatalogSplitPane extends JSplitPane {
        /// Original responsive breakpoint retained from the pre-existing page layout.
        private static final int WIDE_LAYOUT_MINIMUM_WIDTH = 720;

        /// Whether the divider ratio has been initialized.
        private boolean dividerInitialized;

        /// Whether the configured side minima currently fit the allocated width.
        private boolean minimumsApplied;

        /// List surface whose minimum width is applied when space permits.
        private final JComponent leftComponent;

        /// Details surface whose minimum width is applied when space permits.
        private final JComponent rightComponent;

        /// Computed minimum width of the list surface.
        private final int leftMinimumWidth;

        /// Computed minimum width of the details surface.
        private final int rightMinimumWidth;

        /// Creates a horizontal split whose children may shrink when the host is narrower than their minima.
        ///
        /// @param list list and filter surface
        /// @param details selected-Mod details surface
        private ResponsiveCatalogSplitPane(JComponent list, JComponent details) {
            super(JSplitPane.VERTICAL_SPLIT, list, details);
            setName("modsCatalogSplit");
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder());
            setContinuousLayout(true);
            setResizeWeight(0.44D);
            leftComponent = list;
            rightComponent = details;
            leftMinimumWidth = list.getMinimumSize().width;
            rightMinimumWidth = details.getMinimumSize().width;
            leftComponent.setMinimumSize(new Dimension(0, 0));
            rightComponent.setMinimumSize(new Dimension(0, 0));
        }

        /// Returns the nearest outer viewport width or the split width without a viewport.
        ///
        /// @return available host width
        private int availableViewportWidth() {
            Component parent = getParent();
            while (parent != null) {
                if (parent instanceof JViewport viewport
                        && viewport.getWidth() > 0) {
                    return viewport.getWidth();
                }
                if (parent instanceof SwingHorizontalScrollPane scroll) {
                    return scroll.getWidth();
                }
                parent = parent.getParent();
            }
            return getWidth();
        }

        /// Enables the page-level horizontal fallback only while this split is horizontal.
        ///
        /// @param horizontal whether the original page threshold selects horizontal presentation
        private void updateOuterHorizontalScroll(boolean horizontal) {
            Component parent = getParent();
            while (parent != null) {
                if (parent instanceof SwingHorizontalScrollPane scroll) {
                    scroll.setMinimumContentWidth(horizontal ? requiredMinimumWidth() : 0);
                    return;
                }
                parent = parent.getParent();
            }
        }

        /// Returns the width required by both columns and the divider.
        ///
        /// @return complete workspace minimum width
        private int requiredMinimumWidth() {
            return leftMinimumWidth + rightMinimumWidth + Math.max(1, getDividerSize());
        }

        /// Applies side minima when possible and clamps a user-adjusted divider without changing orientation.
        @Override
        public void doLayout() {
            int availableWidth = availableViewportWidth();
            boolean horizontal = availableWidth >= WIDE_LAYOUT_MINIMUM_WIDTH;
            int desired = horizontal ? HORIZONTAL_SPLIT : VERTICAL_SPLIT;
            if (getOrientation() != desired) {
                setOrientation(desired);
                dividerInitialized = false;
                minimumsApplied = false;
            }
            updateOuterHorizontalScroll(horizontal);
            boolean canApplyMinimums = getOrientation() == HORIZONTAL_SPLIT
                    && getWidth() >= leftMinimumWidth + rightMinimumWidth + getDividerSize();
            if (canApplyMinimums != minimumsApplied) {
                minimumsApplied = canApplyMinimums;
                leftComponent.setMinimumSize(canApplyMinimums
                        ? new Dimension(leftMinimumWidth, 0)
                        : new Dimension(0, 0));
                rightComponent.setMinimumSize(canApplyMinimums
                        ? new Dimension(rightMinimumWidth, 0)
                        : new Dimension(0, 0));
            }
            if (!dividerInitialized && getWidth() > 1) {
                setDividerLocation((int) Math.round(
                        (getWidth() - getDividerSize()) * 0.44D));
                dividerInitialized = true;
            }
            if (canApplyMinimums && getWidth() > 0) {
                int maximum = Math.max(leftMinimumWidth, getWidth() - getDividerSize() - rightMinimumWidth);
                int location = Math.max(leftMinimumWidth, Math.min(getDividerLocation(), maximum));
                if (location != getDividerLocation()) {
                    setDividerLocation(location);
                }
            }
            super.doLayout();
        }

        /// Allows the shell to constrain both children without honoring their preferred widths.
        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, 0);
        }
    }

    /// Localizes enabled-state enum values without changing model identity.
    @NotNullByDefault
    private static final class FilterRenderer extends DefaultListCellRenderer {
        /// Localized labels.
        private final ModCatalogStrings strings;

        /// Creates one reusable filter renderer.
        ///
        /// @param strings localized labels
        private FilterRenderer(ModCatalogStrings strings) {
            this.strings = strings;
        }

        /// Renders one filter using its localized label.
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                @Nullable Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            Object displayValue = value instanceof ModCatalogFilter filter
                    ? strings.filterText(filter)
                    : "";
            return super.getListCellRendererComponent(
                    list, displayValue, index, isSelected, cellHasFocus);
        }
    }
}
