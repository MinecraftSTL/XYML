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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.library.nbt.edit.NBTAddress;
import space.minecraftstl.xyml.library.nbt.edit.NBTEditException;
import space.minecraftstl.xyml.library.nbt.edit.NBTNode;
import space.minecraftstl.xyml.library.nbt.io.NBTReadReport;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import space.minecraftstl.xyml.library.nbt.tag.ValueTag;
import space.minecraftstl.xyml.nbt.NBTDocument;
import space.minecraftstl.xyml.nbt.NBTDocumentService;
import space.minecraftstl.xyml.nbt.NBTNodeType;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.event.ChangeListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.Serial;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static space.minecraftstl.xyml.ui.swing.page.nbt.NBTEditorSwingSupport.bind;
import static space.minecraftstl.xyml.ui.swing.page.nbt.NBTEditorSwingSupport.configureIconButton;
import static space.minecraftstl.xyml.ui.swing.page.nbt.NBTEditorSwingSupport.configureSymbolButton;
import static space.minecraftstl.xyml.ui.swing.page.nbt.NBTEditorSwingSupport.detailLabel;
import static space.minecraftstl.xyml.ui.swing.page.nbt.NBTEditorSwingSupport.menuItem;
import static space.minecraftstl.xyml.ui.swing.page.nbt.NBTEditorSwingSupport.readOnlyField;
import static space.minecraftstl.xyml.ui.swing.page.nbt.NBTEditorPanelSupport.chunkLocalIndex;
import static space.minecraftstl.xyml.ui.swing.page.nbt.NBTEditorPanelSupport.defaultValue;
import static space.minecraftstl.xyml.ui.swing.page.nbt.NBTEditorPanelSupport.emptyTreeModel;
import static space.minecraftstl.xyml.ui.swing.page.nbt.NBTEditorPanelSupport.isChunkRoot;
import static space.minecraftstl.xyml.ui.swing.page.nbt.NBTEditorPanelSupport.isRegionSlot;
import static space.minecraftstl.xyml.ui.swing.page.nbt.NBTEditorPanelSupport.preloadTreeIcons;
import static space.minecraftstl.xyml.ui.swing.page.nbt.NBTEditorPanelSupport.uniqueChildName;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Complete Swing editor for standalone NBT tags and fixed-slot Region documents.
///
/// The panel performs no filesystem I/O on the EDT and never receives a mutable working-tree
/// element. Every edit passes an immutable node handle to [NBTEditorController], which delegates to
/// the transactional XoyzNBT editor before this panel rebuilds the affected revision. Tolerant-read
/// diagnostics remain visible in a persistent warning band and must be explicitly approved before
/// a strict repair save is started.
@NotNullByDefault
public final class NBTEditorPanel extends JPanel implements AutoCloseable {
    /// Serialization identifier for the Swing component superclass contract.
    @Serial
    private static final long serialVersionUID = 1L;
    /// Maximum SNBT characters inserted during one EDT turn.
    private static final int SNBT_INSERT_CHUNK_SIZE = 16_384;
    /// Controller that serializes document state on the EDT.
    private final NBTEditorController controller;
    /// Stable localized visible text.
    private final NBTEditorStrings strings;
    /// File chooser, drop policy, and confirmation boundary.
    private final NBTEditorInteractions interactions;
    /// Parent-owned navigation callback.
    private final Listener listener;
    /// Tree renderer that receives already-decoded icons on the EDT.
    private final NBTTreeCellRenderer treeCellRenderer;
    /// Opens the native source chooser.
    private final JButton openButton = new JButton();
    /// Selects an absent target for a new NBT document.
    private final JButton newButton = new JButton();
    /// Reloads the selected source from disk.
    private final JButton reloadButton = new JButton();
    /// Safely saves the dirty document.
    private final JButton saveButton = new JButton();
    /// Undoes the most recent transaction.
    private final JButton undoButton = new JButton();
    /// Redoes the most recently undone transaction.
    private final JButton redoButton = new JButton();
    /// Requests return navigation after dirty-state confirmation.
    private final JButton backButton = new JButton();
    /// Adds a constrained child tag.
    private final JButton addButton = new JButton();
    /// Copies a detached selected tag.
    private final JButton copyButton = new JButton();
    /// Pastes a detached copied tag.
    private final JButton pasteButton = new JButton();
    /// Deletes the selected non-root tag.
    private final JButton deleteButton = new JButton();
    /// Moves an ordered child toward index zero.
    private final JButton moveUpButton = new JButton();
    /// Moves an ordered child away from index zero.
    private final JButton moveDownButton = new JButton();
    /// Displays the exact selected source path.
    private final JLabel pathLabel = new JLabel();
    /// Persistent warning band for tolerant-read recovery diagnostics.
    private final NBTReadWarningView readWarningView;
    /// Renders only rows requested by the Swing tree viewport.
    private final JTree tree = new JTree(emptyTreeModel());
    /// Switches between structured fields and the lazily loaded subtree SNBT editor.
    private final JTabbedPane editorTabs = new JTabbedPane();

    /// Edits a Compound child name and displays all other names read-only.
    private final JTextField nameField = new JTextField();

    /// Applies a validated Compound child rename.
    private final JButton renameButton = new JButton();

    /// Selects one conversion target permitted for the selected NBT type.
    private final JComboBox<String> typeCombo = new JComboBox<>();

    /// Applies an explicit, potentially lossy type conversion.
    private final JButton typeButton = new JButton();

    /// Displays the selected direct-child count.
    private final JTextField childrenField = readOnlyField("nbtEditorNodeChildren");

    /// Edits an exact scalar or compact aggregate value with optional in-place String formatting.
    private final NBTStringValueEditor valueArea = new NBTStringValueEditor();

    /// Applies a validated scalar mutation.
    private final JButton applyButton = new JButton();

    /// Inserts a Minecraft formatting section sign at the value-field caret.
    private final JButton sectionSignButton = new JButton("\u00a7");

    /// Controls whether a selected String draft is rendered with Minecraft formatting.
    private final JCheckBox formattingPreviewCheck = new JCheckBox();

    /// Selects decimal or hexadecimal display and input for numeric values.
    private final JComboBox<String> numberRadixCombo = new JComboBox<>();

    /// Selects the declared type of an empty List.
    private final JComboBox<String> listTypeCombo = new JComboBox<>();

    /// Applies an empty-List declared type.
    private final JButton listTypeButton = new JButton();

    /// Edits a complete selected subtree as strict SNBT.
    private final JTextArea snbtArea = new JTextArea(18, 32);

    /// Replaces the selected subtree from strict SNBT.
    private final JButton replaceButton = new JButton();

    /// Displays read-only or invalid-input detail for the selected node.
    private final JLabel editStatusLabel = new JLabel(" ");

    /// Displays localized validation state beside the subtree replacement command.
    private final JLabel snbtStatusLabel = new JLabel(" ");

    /// Displays lifecycle and recovery state.
    private final JLabel statusLabel = new JLabel();

    /// Indicates asynchronous open, reload, and save work.
    private final JProgressBar progressBar = new JProgressBar();

    /// Owned tree selection listener removed during closure.
    private final TreeSelectionListener treeSelectionListener = this::selectionChanged;

    /// Starts lazy SNBT loading when its editor tab first becomes visible.
    private final ChangeListener editorTabListener = this::editorTabChanged;

    /// Reveals dormant root children only when a real expansion begins.
    private final TreeWillExpandListener rootExpansionListener;

    /// Selects a right-clicked row before showing its context menu.
    private final NBTTreePopupMouseListener treePopupMouseListener;

    /// Owned controller subscription removed during closure.
    private final Subscription stateSubscription;

    /// Caller-owned executor for subtree snapshots and serialization.
    private final Executor backgroundExecutor;

    /// Background classpath icon load, or `null` for deterministic injected panels.
    private final @Nullable CompletableFuture<@Unmodifiable Map<NBTNodeType, Icon>> iconLoad;

    /// Transfer adapter that performs only lexical payload decoding on the EDT.
    private final NBTFileTransferHandler nbtTransferHandler;

    /// Context command hidden when index zero is already selected.
    private final JMenuItem moveUpMenuItem;

    /// Context command hidden when the final child is already selected.
    private final JMenuItem moveDownMenuItem;

    /// Guards terminal teardown from any calling thread.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Latest normalized route source retained until the controller becomes idle.
    private @Nullable Path pendingRouteOpen;

    /// Whether one pending-route drain is already queued for a later EDT turn.
    private boolean routeOpenDrainScheduled;
    /// Monotonic route identity used to reject stale modal-confirmation returns.
    private long routeOpenRevision;

    /// Document identity currently represented by the tree model.
    private @Nullable NBTDocument renderedDocument;

    /// Editor revision currently represented by the tree model.
    private long renderedEditorRevision = -1L;

    /// Bounded asynchronous subtree-SNBT loader.
    private final NBTAsyncTextLoader snbtTextLoader;

    /// Bounded asynchronous primitive-array value loader.
    private final NBTAsyncTextLoader valueTextLoader;

    /// Preserves and reformats numeric drafts when the display radix changes.
    private final NBTNumberRadixEditor numberRadixEditor;

    /// Suppresses automatic root expansion while a replacement model is installed.
    private boolean installingTreeModel;

    /// Creates a production NBT page without opening a source.
    ///
    /// @param ioExecutor caller-owned blocking executor
    /// @param listener parent-owned navigation callback
    public NBTEditorPanel(Executor ioExecutor, Listener listener) {
        this(
                new NBTEditorController(
                        new NBTDocumentService(Objects.requireNonNull(ioExecutor, "ioExecutor")),
                        SwingUiDispatcher.INSTANCE),
                NBTEditorStrings.localized(),
                null,
                listener,
                Objects.requireNonNull(ioExecutor, "ioExecutor"));
    }

    /// Creates a deterministic page with injected state and interaction boundaries.
    ///
    /// @param controller document controller
    /// @param strings stable localized text
    /// @param interactions injected interactions, or `null` for native Swing dialogs
    /// @param listener parent-owned navigation callback
    NBTEditorPanel(
            NBTEditorController controller,
            NBTEditorStrings strings,
            @Nullable NBTEditorInteractions interactions,
            Listener listener) {
        this(controller, strings, interactions, listener, null);
    }

    /// Creates a page with an optional caller-owned background executor.
    ///
    /// @param controller document controller
    /// @param strings stable localized text
    /// @param interactions injected interactions, or `null` for native Swing dialogs
    /// @param listener parent-owned navigation callback
    /// @param backgroundExecutor background icon and SNBT executor, or `null` to retain default
    /// icons and use the common pool for an explicitly opened SNBT tab
    NBTEditorPanel(
            NBTEditorController controller,
            NBTEditorStrings strings,
            @Nullable NBTEditorInteractions interactions,
            Listener listener,
            @Nullable Executor backgroundExecutor) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.controller = Objects.requireNonNull(controller, "controller");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.readWarningView = new NBTReadWarningView(this.strings);
        this.interactions = interactions == null
                ? new SwingNBTEditorInteractions(this, this.strings)
                : interactions;
        this.listener = Objects.requireNonNull(listener, "listener");
        this.backgroundExecutor = backgroundExecutor == null
                ? ForkJoinPool.commonPool()
                : backgroundExecutor;
        rootExpansionListener = new NBTTreeExpansionListener(tree, () -> installingTreeModel);
        treePopupMouseListener = new NBTTreePopupMouseListener(tree);
        snbtTextLoader = new NBTAsyncTextLoader(snbtArea, SNBT_INSERT_CHUNK_SIZE, this.backgroundExecutor);
        valueTextLoader = new NBTAsyncTextLoader(valueArea, SNBT_INSERT_CHUNK_SIZE, this.backgroundExecutor);
        numberRadixEditor = new NBTNumberRadixEditor(
                this.controller,
                numberRadixCombo,
                valueArea,
                valueTextLoader,
                this::selectedNode,
                this::isCurrentValueKey,
                () -> !closed.get(),
                this::startValueLoad,
                this::finishValueLoad,
                this::showNumberRadixFailure);
        nbtTransferHandler = new NBTFileTransferHandler(
                () -> !closed.get() && !this.controller.snapshot().busy(),
                this::openDroppedPaths);
        moveUpMenuItem = menuItem(strings.moveUpText(), null, () -> moveSelected(-1));
        moveDownMenuItem = menuItem(strings.moveDownText(), null, () -> moveSelected(1));
        treeCellRenderer = new NBTTreeCellRenderer(this.strings);

        setName("nbtEditorPage");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());
        JPanel northBand = new JPanel(new BorderLayout());
        northBand.setOpaque(false);
        northBand.add(createHeadingBand(), BorderLayout.NORTH);
        northBand.add(readWarningView.component(), BorderLayout.CENTER);
        add(northBand, BorderLayout.NORTH);
        add(createEditorSurface(), BorderLayout.CENTER);
        add(createStatusBand(), BorderLayout.SOUTH);
        configureTree();
        configureEditors();
        configureKeyboardActions();
        setTransferHandler(nbtTransferHandler);
        stateSubscription = this.controller.subscribe(change -> {
            @Nullable NBTEditorSnapshot current = change.currentValue();
            if (current != null && !closed.get()) {
                schedulePendingRouteOpen(current);
                render(current);
            }
        });
        render(this.controller.snapshot());
        iconLoad = backgroundExecutor == null
                ? null
                : preloadTreeIcons(backgroundExecutor, treeCellRenderer, tree, () -> !closed.get());
    }

    /// Returns the localized title required by a page host.
    ///
    /// @return non-blank page title
    public String title() {
        return strings.title();
    }

    /// Opens one route-supplied source asynchronously after dirty-state confirmation.
    ///
    /// @param file route-supplied source
    public void open(Path file) {
        EdtDispatcher.requireEventDispatchThread();
        Path target = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        long request = ++routeOpenRevision;
        if (closed.get()) {
            return;
        }
        if (controller.snapshot().busy()) {
            pendingRouteOpen = target;
            return;
        }
        pendingRouteOpen = null;
        if (!confirmReplacement() || request != routeOpenRevision || closed.get()) return;
        pendingRouteOpen = controller.snapshot().busy() ? target : null;
        if (pendingRouteOpen == null)
            controller.open(target);
    }

    /// Routes a decoded immutable drop payload through the interaction policy.
    ///
    /// @param candidates normalized lexical paths
    /// @return whether one source was accepted
    public boolean openDroppedPaths(@Unmodifiable List<Path> candidates) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || controller.snapshot().busy()) {
            return false;
        }
        @Unmodifiable List<Path> paths = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        @Nullable Path selected = interactions.chooseDroppedFile(paths);
        if (selected == null || !confirmReplacement()) {
            return false;
        }
        controller.open(selected.toAbsolutePath().normalize());
        return true;
    }

    /// Requests parent-owned return navigation after dirty-state confirmation.
    public void requestClose() {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed.get() && !controller.snapshot().busy() && confirmReplacement()) {
            listener.closeRequested();
        }
    }

    /// Releases listeners, clears native transfer hooks, and cancels owned work.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        EdtDispatcher.executeAndWait(this::closeOnEventDispatchThread);
    }

    /// Creates the compact title, path, history, and file command band.
    ///
    /// @return unframed heading band
    private JComponent createHeadingBand() {
        JPanel heading = new JPanel(new MigLayout(
                "insets 12 16 8 16, fillx",
                "[]10[][grow,fill]8[]4[]8[]4[]4[]4[]",
                "[40!]"));
        heading.setOpaque(false);
        configureIconButton(
                backButton,
                "nbtEditorBack",
                "assets/swing/icons/arrow-back.svg",
                strings.backTooltip(),
                this::requestClose);
        heading.add(backButton, "w 40!, h 40!");
        JLabel titleLabel = new JLabel(strings.title());
        titleLabel.setName("nbtEditorTitle");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 22.0F));
        heading.add(titleLabel);
        pathLabel.setName("nbtEditorPath");
        pathLabel.setHorizontalAlignment(SwingConstants.LEADING);
        heading.add(pathLabel, "growx");
        configureIconButton(
                undoButton,
                "nbtEditorUndo",
                "assets/swing/icons/arrow-back.svg",
                strings.undoTooltip(),
                this::undo);
        heading.add(undoButton, "w 40!, h 40!");
        configureIconButton(
                redoButton,
                "nbtEditorRedo",
                "assets/swing/icons/arrow-forward.svg",
                strings.redoTooltip(),
                this::redo);
        heading.add(redoButton, "w 40!, h 40!");
        configureIconButton(
                newButton,
                "nbtEditorNew",
                "assets/swing/icons/add.svg",
                strings.newTooltip(),
                this::chooseAndCreate);
        heading.add(newButton, "w 40!, h 40!");
        configureIconButton(
                openButton,
                "nbtEditorOpen",
                "assets/swing/icons/folder-open.svg",
                strings.openTooltip(),
                this::chooseAndOpen);
        heading.add(openButton, "w 40!, h 40!");
        configureIconButton(
                reloadButton,
                "nbtEditorReload",
                "assets/swing/icons/refresh.svg",
                strings.reloadTooltip(),
                this::reload);
        heading.add(reloadButton, "w 40!, h 40!");
        configureIconButton(
                saveButton,
                "nbtEditorSave",
                "assets/swing/icons/save.svg",
                strings.saveTooltip(),
                this::requestSave);
        heading.add(saveButton, "w 40!, h 40!");
        return heading;
    }

    /// Creates the resizable tree and selected-node editor surface.
    ///
    /// @return editor split surface
    private JComponent createEditorSurface() {
        JPanel treeSurface = new JPanel(new BorderLayout());
        treeSurface.setOpaque(false);
        treeSurface.add(createTreeToolbar(), BorderLayout.NORTH);
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setName("nbtEditorTreeScroll");
        treeScroll.setBorder(BorderFactory.createEmptyBorder());
        treeSurface.add(treeScroll, BorderLayout.CENTER);

        editorTabs.setName("nbtEditorTabs");
        editorTabs.addTab(strings.structuredTab(), createStructuredEditor());
        editorTabs.addTab(strings.snbtTab(), createSnbtEditor());
        editorTabs.addChangeListener(editorTabListener);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeSurface, editorTabs);
        splitPane.setName("nbtEditorSplitPane");
        splitPane.setBorder(BorderFactory.createEmptyBorder(4, 16, 4, 16));
        splitPane.setResizeWeight(0.43D);
        splitPane.setContinuousLayout(true);
        return splitPane;
    }

    /// Creates the fixed-size tree command toolbar.
    ///
    /// @return tree toolbar
    private JComponent createTreeToolbar() {
        JPanel toolbar = new JPanel(new MigLayout(
                "insets 0 0 6 0, fillx",
                "[]2[]2[]2[]push[]2[]",
                "[32!]"));
        toolbar.setOpaque(false);
        configureIconButton(
                addButton,
                "nbtEditorAdd",
                "assets/swing/icons/add.svg",
                strings.addText(),
                this::addTag);
        configureIconButton(
                copyButton,
                "nbtEditorCopy",
                "assets/swing/icons/content-copy.svg",
                strings.copyText(),
                this::copySelected);
        configureIconButton(
                pasteButton,
                "nbtEditorPaste",
                "assets/swing/icons/file-import.svg",
                strings.pasteText(),
                this::pasteSelected);
        configureIconButton(
                deleteButton,
                "nbtEditorDelete",
                "assets/swing/icons/delete.svg",
                strings.deleteText(),
                this::deleteSelected);
        configureSymbolButton(
                moveUpButton,
                "nbtEditorMoveUp",
                "\u2191",
                strings.moveUpText(),
                () -> moveSelected(-1));
        configureSymbolButton(
                moveDownButton,
                "nbtEditorMoveDown",
                "\u2193",
                strings.moveDownText(),
                () -> moveSelected(1));
        toolbar.add(addButton, "w 32!, h 32!");
        toolbar.add(copyButton, "w 32!, h 32!");
        toolbar.add(pasteButton, "w 32!, h 32!");
        toolbar.add(deleteButton, "w 32!, h 32!");
        toolbar.add(moveUpButton, "w 32!, h 32!");
        toolbar.add(moveDownButton, "w 32!, h 32!");
        return toolbar;
    }

    /// Creates the structured selected-node form.
    ///
    /// @return structured editor panel
    private JComponent createStructuredEditor() {
        JPanel details = new JPanel(new MigLayout(
                "insets 16, fillx, hidemode 3",
                "[96!,fill][grow,fill][]",
                "[][][]10[]8[]4[]4[]4[]push"));
        details.setName("nbtEditorDetails");

        nameField.setName("nbtEditorNodeName");
        details.add(detailLabel(strings.nameLabel(), nameField));
        details.add(nameField, "growx");
        renameButton.setName("nbtEditorRename");
        renameButton.setText(strings.applyText());
        renameButton.addActionListener(event -> renameSelected());
        details.add(renameButton, "w 84!, h 34!, wrap");

        typeCombo.setName("nbtEditorNodeType");
        details.add(detailLabel(strings.typeLabel(), typeCombo));
        details.add(typeCombo, "growx");
        typeButton.setName("nbtEditorTypeApply");
        typeButton.setText(strings.applyText());
        typeButton.addActionListener(event -> convertSelectedType());
        details.add(typeButton, "w 84!, h 34!, wrap");
        details.add(detailLabel(strings.childrenLabel(), childrenField));
        details.add(childrenField, "span 2, growx, wrap");
        details.add(detailLabel(strings.valueLabel(), valueArea), "top");
        JScrollPane valueScroll = new JScrollPane(valueArea);
        valueScroll.setName("nbtEditorValueScroll");
        details.add(valueScroll, "span 2, growx, h 120:180:280, wrap");
        JPanel valueActions = new JPanel(new MigLayout(
                "insets 0, fillx, hidemode 3",
                "[]8[]8[]8[]push[]",
                "[34!]"));
        valueActions.setOpaque(false);
        sectionSignButton.setName("nbtEditorSectionSign");
        sectionSignButton.setToolTipText("\u00a7");
        sectionSignButton.getAccessibleContext().setAccessibleName("\u00a7");
        sectionSignButton.addActionListener(event -> insertSectionSign());
        valueActions.add(sectionSignButton, "w 38!, h 32!");
        formattingPreviewCheck.setName("nbtEditorFormattingPreviewToggle");
        formattingPreviewCheck.setText(strings.formattingPreviewText());
        formattingPreviewCheck.addActionListener(event -> updateFormattingPreviewVisibility());
        valueActions.add(formattingPreviewCheck);
        numberRadixCombo.setName("nbtEditorNumberRadix");
        numberRadixCombo.getAccessibleContext().setAccessibleName(strings.numberRadixText());
        valueActions.add(numberRadixCombo, "w 132!");
        applyButton.setName("nbtEditorApply");
        applyButton.setText(strings.applyText());
        applyButton.addActionListener(event -> applySelectedValue());
        valueActions.add(applyButton, "w 84!, h 34!");
        details.add(valueActions, "skip, span 2, growx, wrap");

        JLabel listTypeLabel = detailLabel(strings.listTypeLabel(), listTypeCombo);
        listTypeLabel.setName("nbtEditorListTypeLabel");
        details.add(listTypeLabel, "span 3, wrap");
        details.add(listTypeCombo, "skip, growx");
        listTypeButton.setName("nbtEditorListTypeApply");
        listTypeButton.setText(strings.applyText());
        listTypeButton.addActionListener(event -> applyListType());
        details.add(listTypeButton, "w 84!, h 34!, wrap");

        editStatusLabel.setName("nbtEditorEditStatus");
        details.add(editStatusLabel, "skip, span 2, growx");
        return details;
    }

    /// Creates the complete selected-subtree SNBT editor.
    ///
    /// @return SNBT editor panel
    private JComponent createSnbtEditor() {
        JPanel panel = new JPanel(new MigLayout(
                "insets 12, fill",
                "[grow,fill][]",
                "[grow,fill]8[]"));
        panel.setOpaque(false);
        snbtArea.setName("nbtEditorSnbt");
        snbtArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        snbtArea.setLineWrap(false);
        JScrollPane scroll = new JScrollPane(snbtArea);
        scroll.setName("nbtEditorSnbtScroll");
        panel.add(scroll, "span 2, grow, wrap");
        snbtStatusLabel.setName("nbtEditorSnbtStatus");
        panel.add(snbtStatusLabel, "growx");
        replaceButton.setName("nbtEditorReplaceSnbt");
        replaceButton.setText(strings.replaceText());
        replaceButton.addActionListener(event -> replaceSelectedSnbt());
        panel.add(replaceButton, "right, h 34!");
        return panel;
    }

    /// Creates the compact lifecycle status band.
    ///
    /// @return unframed status band
    private JComponent createStatusBand() {
        JPanel status = new JPanel(new MigLayout(
                "insets 6 16 12 16, fillx",
                "[grow,fill][120!,fill]",
                "[]"));
        status.setOpaque(false);
        statusLabel.setName("nbtEditorStatus");
        status.add(statusLabel, "growx");
        progressBar.setName("nbtEditorProgress");
        progressBar.setIndeterminate(true);
        progressBar.getAccessibleContext().setAccessibleName(strings.openingText());
        status.add(progressBar, "h 8!");
        return status;
    }

    /// Configures selection, row rendering, context menu, and lazy expansion.
    private void configureTree() {
        tree.setName("nbtEditorTree");
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(24);
        tree.setLargeModel(true);
        tree.setCellRenderer(treeCellRenderer);
        tree.addTreeSelectionListener(treeSelectionListener);
        tree.addTreeWillExpandListener(rootExpansionListener);
        tree.addMouseListener(treePopupMouseListener);
        tree.setComponentPopupMenu(createTreePopupMenu());
        tree.getAccessibleContext().setAccessibleName(strings.title());
    }

    /// Configures exact text behavior and empty-List choices.
    private void configureEditors() {
        valueArea.setName("nbtEditorValue");
        nameField.setEnabled(false);
        valueArea.setEnabled(false);
        snbtArea.setEnabled(false);
        typeCombo.addActionListener(event -> updateTypeConversionButton());
        sectionSignButton.setVisible(false);
        formattingPreviewCheck.setVisible(false);
        numberRadixCombo.addItem(strings.decimalRadixText());
        numberRadixCombo.addItem(strings.hexadecimalRadixText());
        numberRadixCombo.setMaximumRowCount(2);
        numberRadixCombo.setVisible(false);
        numberRadixCombo.addActionListener(event -> numberRadixEditor.selectionChanged());
        listTypeCombo.setName("nbtEditorListType");
        listTypeCombo.addItem(strings.tagEndText());
        for (TagType<?> type : NBTTagInput.types()) {
            listTypeCombo.addItem(type.name());
        }
    }

    /// Installs Save, Undo, Redo, Delete, Rename, Copy, and Paste shortcuts.
    private void configureKeyboardActions() {
        bind(this, "save", KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), this::requestSave);
        bind(this, "undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), this::undo);
        bind(this, "redo", KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), this::redo);
        bind(tree, "delete", KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), this::deleteSelected);
        bind(tree, "rename", KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), this::focusNameEditor);
        bind(tree, "copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), this::copySelected);
        bind(tree, "paste", KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), this::pasteSelected);
    }

    /// Creates the tree's complete right-click command menu.
    ///
    /// @return context menu
    private JPopupMenu createTreePopupMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem(strings.addText(), "assets/swing/icons/add.svg", this::addTag));
        menu.add(menuItem(strings.copyText(), "assets/swing/icons/content-copy.svg", this::copySelected));
        menu.add(menuItem(strings.pasteText(), "assets/swing/icons/file-import.svg", this::pasteSelected));
        menu.addSeparator();
        menu.add(menuItem(strings.deleteText(), "assets/swing/icons/delete.svg", this::deleteSelected));
        menu.add(moveUpMenuItem);
        menu.add(moveDownMenuItem);
        return menu;
    }

    /// Opens the native chooser and forwards its result to the controller.
    private void chooseAndOpen() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || controller.snapshot().busy()) {
            return;
        }
        @Nullable Path selected = interactions.chooseFile(controller.snapshot().file());
        if (selected != null) {
            open(selected);
        }
    }

    /// Selects an absent target and creates a new in-memory document after replacement confirmation.
    private void chooseAndCreate() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || controller.snapshot().busy()) {
            return;
        }
        @Nullable Path selected = interactions.chooseNewFile(controller.snapshot().file());
        if (selected != null && confirmReplacement() && !closed.get()) {
            controller.create(selected.toAbsolutePath().normalize());
        }
    }

    /// Routes every visible save command through the partial-data-loss confirmation boundary.
    ///
    /// Clean and fully recovered documents delegate immediately. A document with confirmed partial data loss is
    /// never silently rewritten: the interaction policy must explicitly approve the strict repair save, and a
    /// headless/default policy therefore leaves the source untouched.
    private void requestSave() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        NBTEditorSnapshot current = controller.snapshot();
        if (current.busy() || !current.requiresSave()) {
            return;
        }
        @Nullable NBTDocument document = current.document();
        @Nullable Path file = current.file();
        if (document == null || file == null) {
            return;
        }
        NBTReadReport report = document.readReport();
        if (report.hasPartialDataLoss()
                && !interactions.confirmRepairSave(file, report, document.storageProfile())) {
            return;
        }
        controller.save();
    }

    /// Reloads after confirming a dirty replacement.
    private void reload() {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed.get()
                && !controller.snapshot().busy()
                && controller.snapshot().document() != null
                && confirmReplacement()) {
            controller.reload();
        }
    }

    /// Returns whether replacing the current state is allowed.
    ///
    /// @return whether no dirty state exists or the user confirmed its loss
    private boolean confirmReplacement() {
        NBTEditorSnapshot current = controller.snapshot();
        if (!current.requiresSave()) {
            return true;
        }
        @Nullable Path file = current.file();
        return file != null && interactions.confirmDiscardChanges(file);
    }

    /// Schedules the latest coalesced route source after the current state publication finishes.
    /// @param current latest controller state
    private void schedulePendingRouteOpen(NBTEditorSnapshot current) {
        if (current.busy() || pendingRouteOpen == null || routeOpenDrainScheduled) {
            return;
        }
        routeOpenDrainScheduled = true;
        EdtDispatcher.executeLater(this::resumePendingRouteOpen);
    }

    /// Forwards the latest retained route source when the controller remains idle.
    private void resumePendingRouteOpen() {
        EdtDispatcher.requireEventDispatchThread();
        routeOpenDrainScheduled = false;
        if (closed.get()) {
            pendingRouteOpen = null;
            return;
        }
        if (controller.snapshot().busy()) {
            return;
        }
        long request = routeOpenRevision;
        @Nullable Path target = pendingRouteOpen;
        pendingRouteOpen = null;
        if (target == null || !confirmReplacement() || request != routeOpenRevision || closed.get()) return;
        pendingRouteOpen = controller.snapshot().busy() ? target : null;
        if (pendingRouteOpen == null)
            controller.open(target);
    }

    /// Opens a constrained new-tag form for the current insertion target.
    private void addTag() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable InsertionTarget target = insertionTarget();
        if (target == null || !mutationsAllowed()) {
            return;
        }
        JTextField tagName = new JTextField(target.nameRequired()
                ? uniqueChildName(strings, target.parent())
                : "");
        tagName.setEnabled(target.nameRequired());
        JComboBox<TagType<?>> tagType = new JComboBox<>(target.types().toArray(TagType<?>[]::new));
        JTextArea tagValue = new JTextArea(5, 28);
        tagValue.setLineWrap(true);
        tagValue.setWrapStyleWord(true);
        tagValue.setText(defaultValue(Objects.requireNonNull(tagType.getItemAt(0), "tagType")));
        JLabel validationLabel = new JLabel(" ");
        validationLabel.setName("nbtEditorAddValidation");
        tagType.addActionListener(event -> {
            @Nullable TagType<?> selected = (TagType<?>) tagType.getSelectedItem();
            if (selected != null) {
                tagValue.setText(defaultValue(selected));
                validationLabel.setText(" ");
                validationLabel.setToolTipText(null);
                tagName.putClientProperty("JComponent.outline", null);
                tagValue.putClientProperty("JComponent.outline", null);
            }
        });

        JPanel form = new JPanel(new MigLayout(
                "insets 8, fillx",
                "[90!,fill][grow,fill]",
                "[][][top][]"));
        form.add(detailLabel(strings.nameLabel(), tagName));
        form.add(tagName, "wrap");
        form.add(detailLabel(strings.typeLabel(), tagType));
        form.add(tagType, "wrap");
        form.add(detailLabel(strings.valueLabel(), tagValue), "top");
        form.add(new JScrollPane(tagValue), "growx, h 110!, wrap");
        form.add(validationLabel, "skip, growx");

        showAddTagForm(target, form, tagName, tagType, tagValue, validationLabel);
    }

    /// Shows or reopens one retained Add form around a background parse-and-insert operation.
    /// @param target immutable insertion target
    /// @param form retained form panel
    /// @param tagName retained tag-name field
    /// @param tagType retained exact-type selector
    /// @param tagValue retained type-specific value field
    /// @param validationLabel retained validation feedback
    private void showAddTagForm(InsertionTarget target, JPanel form, JTextField tagName,
            JComboBox<TagType<?>> tagType, JTextArea tagValue, JLabel validationLabel) {
        Object[] options = {strings.insertText(), strings.cancelText()};
        while (mutationsAllowed()) {
            int choice = JOptionPane.showOptionDialog(
                    this,
                    form,
                    strings.addTitle(),
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE,
                    null,
                    options,
                    options[0]);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
            @Nullable TagType<?> selectedType = (TagType<?>) tagType.getSelectedItem();
            if (selectedType == null) {
                showAddTagFailure(validationLabel, tagName, tagValue, null, null);
                continue;
            }
            @Nullable NBTDocument requestDocument = controller.snapshot().document();
            NBTEditorTreeNode requestSelection = selectedNode();
            if (requestDocument == null) {
                return;
            }
            long requestRevision = target.parent().node().getRevision();
            NBTDocument document = requestDocument;
            CompletableFuture<NBTEditResult> future = controller.createAndInsertAsync(
                    target.parent(), target.index(), selectedType, tagName.getText(), tagValue.getText());
            future.whenComplete((@Nullable NBTEditResult result, @Nullable Throwable failure) ->
                    EdtDispatcher.execute(() -> {
                        if (controller.snapshot().busy()) {
                            return;
                        }
                        if (failure != null) {
                            NBTEditResult failed = NBTEditResult.failure(
                                    NBTEditorAsyncResultGuard.detail(failure));
                            if (!isCurrentInsertionResult(
                                    target.parent(), requestSelection, document, requestRevision, failed)) {
                                return;
                            }
                            Throwable cause = NBTEditorAsyncResultGuard.unwrap(failure);
                            if (cause instanceof Error error) {
                                throw error;
                            }
                            LOG.warning("Asynchronous NBT insertion failed", cause);
                            showEditFailure(null, failed.errorMessage());
                            return;
                        }
                        if (result == null
                                || !isCurrentInsertionResult(
                                target.parent(), requestSelection, document, requestRevision, result)) {
                            return;
                        }
                        if (result.applied()) {
                            clearEditFailure();
                            restoreSelection(Objects.requireNonNull(result.selection(), "selection"));
                        } else {
                            showAddTagFailure(validationLabel, tagName, tagValue,
                                    result.reason(), result.errorMessage());
                            if (mutationsAllowed()) {
                                showAddTagForm(target, form, tagName, tagType, tagValue, validationLabel);
                            }
                        }
                    }));
            return;
        }
    }

    /// Returns whether an Add/Paste result still belongs to the visible form request.
    /// @param parent insertion parent captured before submission
    /// @param selection row selected while the form was submitted
    /// @param document source document captured before submission
    /// @param revision editor revision captured before submission
    /// @param result completed insertion result
    /// @return whether the result may update the form or selection
    private boolean isCurrentInsertionResult(
            NBTEditorTreeNode parent,
            @Nullable NBTEditorTreeNode selection,
            NBTDocument document,
            long revision,
            NBTEditResult result) {
        NBTEditorSnapshot currentState = controller.snapshot();
        if (closed.get() || currentState.busy() || currentState.document() != document) {
            return false;
        }
        @Nullable NBTEditorTreeNode current = selectedNode();
        if (current == null || !current.belongsTo(document)) {
            return false;
        }
        if (!result.applied()) {
            return selection != null
                    && current == selection
                    && parent.node().getRevision() == revision
                    && document.editor().getRevision() == revision;
        }
        @Nullable NBTAddress resultAddress = result.selection();
        return resultAddress != null
                && document.editor().getRevision() >= revision
                && (resultAddress.equals(current.address())
                || parent.address().equals(current.address())
                || (selection != null && selection.address().equals(current.address())));
    }

    /// Keeps a rejected Add form populated and displays localized validation inside the dialog.
    ///
    /// @param validationLabel dialog validation label
    /// @param tagName retained name input
    /// @param tagValue retained value input
    /// @param reason stable editor reason, or `null`
    /// @param detail technical detail, or `null`
    private void showAddTagFailure(
            JLabel validationLabel,
            JTextField tagName,
            JTextArea tagValue,
            @Nullable NBTEditException.Reason reason,
            @Nullable String detail) {
        String message = strings.editFailureText(reason);
        validationLabel.setText(message);
        validationLabel.setToolTipText(detail);
        tagName.setToolTipText(detail);
        tagValue.setToolTipText(detail);
        tagName.putClientProperty("JComponent.outline", "error");
        tagValue.putClientProperty("JComponent.outline", "error");
    }

    /// Applies the explicitly selected generic type conversion.
    private void convertSelectedType() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        @Nullable TagType<?> targetType = selectedConversionType();
        if (selected == null || targetType == null || !mutationsAllowed()
                || targetType == selected.node().getType()) {
            return;
        }
        NBTEditorTreeNode submitted = selected;
        String originalType = Objects.requireNonNull(selected.node().getType(), "selectedType").name();
        handleAsyncResult(controller.convertTypeAsync(submitted, targetType), submitted,
                () -> typeCombo.setSelectedItem(originalType));
    }

    /// Applies one exact selected scalar value.
    private void applySelectedValue() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected == null || !valueEditable(selected) || !mutationsAllowed()) {
            return;
        }
        NBTEditorTreeNode submitted = selected;
        String draft = valueArea.getText();
        handleAsyncResult(controller.applyStructuredValueAsync(submitted, draft, numberRadixEditor.selectedRadix()), submitted,
                () -> valueArea.setText(draft));
    }

    /// Inserts one section sign at the current String-field selection or caret.
    private void insertSectionSign() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected == null || selected.node().getType() != TagType.STRING || !mutationsAllowed()) {
            return;
        }
        valueArea.replaceSelection("\u00a7");
        valueArea.requestFocusInWindow();
    }

    /// Updates in-place Minecraft formatting after its toggle changes.
    private void updateFormattingPreviewVisibility() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        boolean enabled = selected != null
                && selected.node().getType() == TagType.STRING
                && formattingPreviewCheck.isSelected();
        valueArea.setFormattingEnabled(enabled);
    }

    /// Applies a Compound child rename.
    private void renameSelected() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected != null && NBTEditorPanelSupport.nameEditable(selected) && mutationsAllowed()) {
            NBTEditorTreeNode submitted = selected;
            String draft = nameField.getText();
            handleAsyncResult(controller.renameAsync(submitted, draft), submitted, () -> nameField.setText(draft));
        }
    }

    /// Replaces the selected tag from complete strict SNBT.
    private void replaceSelectedSnbt() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected != null
                && selected.node().getType() != null
                && mutationsAllowed()) {
            NBTEditorTreeNode submitted = selected;
            String draft = snbtArea.getText();
            handleAsyncResult(controller.replaceSnbtAsync(submitted, draft), submitted,
                    () -> snbtArea.setText(draft));
        }
    }

    /// Applies the declared type of one empty List.
    private void applyListType() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected == null || !NBTEditorPanelSupport.emptyListSelected(selected) || !mutationsAllowed()) {
            return;
        }
        int selectedIndex = listTypeCombo.getSelectedIndex();
        @Nullable TagType<?> type = selectedIndex <= 0
                ? null
                : NBTTagInput.types().get(selectedIndex - 1);
        NBTEditorTreeNode submitted = selected;
        handleAsyncResult(controller.setListElementTypeAsync(submitted, type), submitted,
                () -> listTypeCombo.setSelectedIndex(selectedIndex));
    }

    /// Copies the selected tag into the controller clipboard.
    private void copySelected() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected == null || selected.node().getType() == null || !mutationsAllowed()) {
            return;
        }
        @Nullable NBTDocument requestDocument = controller.snapshot().document();
        if (requestDocument == null) {
            return;
        }
        NBTEditorTreeNode submitted = selected;
        NBTAddress requestAddress = submitted.address();
        long requestRevision = submitted.node().getRevision();
        handleCommandResult(
                controller.copyAsync(submitted),
                submitted,
                requestAddress,
                requestDocument,
                requestRevision,
                result -> {
                    if (result.applied()) {
                        editStatusLabel.setText(strings.copiedText());
                        editStatusLabel.setToolTipText(null);
                        updateSelectedNodeDetails();
                    } else {
                        showEditFailure(result.reason(), result.errorMessage());
                    }
                });
    }

    /// Pastes the detached controller clipboard into the current insertion target.
    private void pasteSelected() {
        @Nullable InsertionTarget target = insertionTarget();
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (target == null || selected == null || !controller.hasClipboard() || !mutationsAllowed()) {
            return;
        }
        @Nullable NBTDocument requestDocument = controller.snapshot().document();
        if (requestDocument == null) {
            return;
        }
        NBTEditorTreeNode submitted = selected;
        handleCommandResult(
                controller.pasteAsync(target.parent(), target.index()),
                submitted,
                submitted.address(),
                requestDocument,
                submitted.node().getRevision(),
                this::handleResult);
    }

    /// Deletes the selected non-root tag after any required chunk-root confirmation.
    private void deleteSelected() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected == null || selected.address().isRoot() || isRegionSlot(selected.address())
                || !mutationsAllowed()) {
            return;
        }
        if (isChunkRoot(selected.address())) {
            @Nullable Path file = controller.snapshot().file();
            int localIndex = chunkLocalIndex(selected.address());
            if (file == null || localIndex < 0 || !interactions.confirmClearChunk(file, localIndex)) {
                return;
            }
        }
        @Nullable NBTDocument requestDocument = controller.snapshot().document();
        if (requestDocument == null) {
            return;
        }
        NBTEditorTreeNode submitted = selected;
        handleCommandResult(
                controller.deleteAsync(submitted),
                submitted,
                submitted.address(),
                requestDocument,
                submitted.node().getRevision(),
                this::handleResult);
    }

    /// Moves the selected ordered child by one position.
    ///
    /// @param offset `-1` for up or `1` for down
    private void moveSelected(int offset) {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected != null && canMove(selected, offset) && mutationsAllowed()) {
            @Nullable NBTDocument requestDocument = controller.snapshot().document();
            if (requestDocument == null) {
                return;
            }
            NBTEditorTreeNode submitted = selected;
            handleCommandResult(
                    controller.moveAsync(submitted, offset),
                    submitted,
                    submitted.address(),
                    requestDocument,
                    submitted.node().getRevision(),
                    this::handleResult);
        }
    }

    /// Undoes one transaction and restores the nearest surviving selection.
    private void undo() {
        if (mutationsAllowed() && controller.canUndo()) {
            @Nullable NBTDocument requestDocument = controller.snapshot().document();
            if (requestDocument == null) {
                return;
            }
            @Nullable NBTEditorTreeNode submitted = selectedNode();
            NBTAddress requestAddress = selectedAddress();
            handleCommandResult(
                    controller.undoAsync(requestAddress),
                    submitted,
                    requestAddress,
                    requestDocument,
                    requestDocument.editor().getRevision(),
                    this::handleResult);
        }
    }

    /// Redoes one transaction and restores the nearest surviving selection.
    private void redo() {
        if (mutationsAllowed() && controller.canRedo()) {
            @Nullable NBTDocument requestDocument = controller.snapshot().document();
            if (requestDocument == null) {
                return;
            }
            @Nullable NBTEditorTreeNode submitted = selectedNode();
            NBTAddress requestAddress = selectedAddress();
            handleCommandResult(
                    controller.redoAsync(requestAddress),
                    submitted,
                    requestAddress,
                    requestDocument,
                    requestDocument.editor().getRevision(),
                    this::handleResult);
        }
    }

    /// Focuses and selects the Compound child name when rename is available.
    private void focusNameEditor() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected != null && NBTEditorPanelSupport.nameEditable(selected) && mutationsAllowed()) {
            nameField.requestFocusInWindow();
            nameField.selectAll();
        }
    }

    /// Applies one command result to validation styling and logical selection.
    ///
    /// @param result transactional command result
    private void handleResult(NBTEditResult result) {
        if (result.applied()) {
            clearEditFailure();
            restoreSelection(Objects.requireNonNull(result.selection(), "selection"));
        } else {
            showEditFailure(result.reason(), result.errorMessage());
        }
    }

    /// Applies one asynchronous result while retaining submitted input after validation failure.
    ///
    /// @param future result completed by the controller on its UI dispatcher
    /// @param submitted row that supplied the request
    /// @param restoreDraft restores the exact submitted field content after rejection
    private void handleAsyncResult(
            CompletableFuture<NBTEditResult> future,
            NBTEditorTreeNode submitted,
            Runnable restoreDraft) {
        Runnable draftRestorer = Objects.requireNonNull(restoreDraft, "restoreDraft");
        NBTEditorTreeNode requestNode = Objects.requireNonNull(submitted, "submitted");
        @Nullable NBTDocument requestDocument = controller.snapshot().document();
        if (requestDocument == null) {
            return;
        }
        long requestRevision = requestNode.node().getRevision();
        handleCommandResult(
                future,
                requestNode,
                requestNode.address(),
                requestDocument,
                requestRevision,
                result -> {
                    if (!result.applied()) {
                        draftRestorer.run();
                    }
                    handleResult(result);
                });
    }

    /// Applies one contextual command result only while its original request remains visible.
    /// Captured document and revision guard feedback after selection changes or panel closure.
    ///
    /// @param future command result future
    /// @param submitted row selected at submission, or `null` for an unselected history command
    /// @param requestAddress address visible at submission
    /// @param requestDocument document visible at submission
    /// @param requestRevision editor revision visible at submission
    /// @param resultHandler callback for an accepted result
    private void handleCommandResult(
            CompletableFuture<NBTEditResult> future,
            @Nullable NBTEditorTreeNode submitted,
            NBTAddress requestAddress,
            NBTDocument requestDocument,
            long requestRevision,
            Consumer<NBTEditResult> resultHandler) {
        NBTAddress address = Objects.requireNonNull(requestAddress, "requestAddress");
        NBTDocument document = Objects.requireNonNull(requestDocument, "requestDocument");
        Consumer<NBTEditResult> handler = Objects.requireNonNull(resultHandler, "resultHandler");
        Objects.requireNonNull(future, "future").whenComplete((
                @Nullable NBTEditResult result,
                @Nullable Throwable failure) -> EdtDispatcher.execute(() -> {
                    EdtDispatcher.requireEventDispatchThread();
                    NBTEditorSnapshot currentState = controller.snapshot();
                    if (currentState.busy()) {
                        return;
                    }
                    @Nullable NBTDocument currentDocument = currentState.document();
                    @Nullable NBTEditorTreeNode currentSelection = selectedNode();
                    if (failure != null) {
                        if (!NBTEditorAsyncResultGuard.acceptsFailure(
                                submitted,
                                address,
                                document,
                                requestRevision,
                                closed.get(),
                                currentDocument,
                                currentSelection)) {
                            return;
                        }
                        Throwable cause = NBTEditorAsyncResultGuard.unwrap(failure);
                        if (cause instanceof Error error) {
                            throw error;
                        }
                        LOG.warning("Asynchronous NBT command failed", cause);
                        handler.accept(NBTEditResult.failure(NBTEditorAsyncResultGuard.detail(cause)));
                    } else if (result != null
                            && NBTEditorAsyncResultGuard.accepts(
                            submitted,
                            address,
                            document,
                            requestRevision,
                            closed.get(),
                            currentDocument,
                            currentSelection,
                            result)) {
                        handler.accept(result);
                    }
                }));
    }

    /// Shows a localized edit error while retaining technical detail only as a tooltip.
    ///
    /// @param reason stable library reason, or `null`
    /// @param detail technical detail, or `null`
    private void showEditFailure(
            @Nullable NBTEditException.Reason reason,
            @Nullable String detail) {
        String message = strings.editFailureText(reason);
        editStatusLabel.setText(message);
        editStatusLabel.setToolTipText(detail);
        snbtStatusLabel.setText(message);
        snbtStatusLabel.setToolTipText(detail);
        valueArea.setToolTipText(detail);
        snbtArea.setToolTipText(detail);
        valueArea.putClientProperty("JComponent.outline", "error");
        snbtArea.putClientProperty("JComponent.outline", "error");
    }

    /// Clears edit validation styling after a successful command or selection change.
    private void clearEditFailure() {
        editStatusLabel.setText(" ");
        editStatusLabel.setToolTipText(null);
        snbtStatusLabel.setText(snbtTextLoader.isLoading() ? strings.loadingSnbtText() : " ");
        snbtStatusLabel.setToolTipText(null);
        valueArea.setToolTipText(null);
        snbtArea.setToolTipText(null);
        valueArea.putClientProperty("JComponent.outline", null);
        snbtArea.putClientProperty("JComponent.outline", null);
    }

    /// Applies one tree selection change to the detail editors.
    ///
    /// @param event tree selection event
    private void selectionChanged(TreeSelectionEvent event) {
        Objects.requireNonNull(event, "event");
        clearEditFailure();
        updateSelectedNodeDetails();
    }

    /// Starts lazy subtree serialization only after the SNBT tab becomes visible.
    ///
    /// @param event tab selection event
    private void editorTabChanged(javax.swing.event.ChangeEvent event) {
        Objects.requireNonNull(event, "event");
        if (!closed.get()) {
            @Nullable NBTEditorTreeNode selected = selectedNode();
            if (selected != null) {
                updateSnbtEditor(selected, selected.node().getType(), mutationsAllowed());
            }
        }
    }

    /// Reconciles all controls with one immutable controller state.
    /// @param current latest state
    private void render(NBTEditorSnapshot current) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        @Nullable NBTDocument document = current.document();
        long editorRevision = document == null ? -1L : document.editor().getRevision();
        if (document != renderedDocument || editorRevision != renderedEditorRevision) {
            NBTAddress address = selectedAddress();
            renderedDocument = document;
            renderedEditorRevision = editorRevision;
            if (document == null) {
                tree.setModel(emptyTreeModel());
            } else {
                NBTLazyTreeModel model = new NBTLazyTreeModel(document, false);
                installingTreeModel = true;
                try {
                    tree.setModel(model);
                    tree.collapsePath(model.pathForAddress(NBTAddress.root()));
                } finally {
                    installingTreeModel = false;
                }
                restoreSelection(address);
            }
        }
        @Nullable Path file = current.file();
        String pathText = file == null ? "" : file.toString();
        pathLabel.setText(pathText);
        pathLabel.setToolTipText(pathText.isEmpty() ? null : pathText);
        readWarningView.render(document);
        statusLabel.setText(NBTEditorPanelSupport.statusText(strings, current));
        statusLabel.setToolTipText(current.message());
        progressBar.setVisible(current.busy());
        boolean active = current.status() != NBTEditorStatus.CLOSED;
        newButton.setEnabled(active && !current.busy());
        openButton.setEnabled(active && !current.busy());
        reloadButton.setEnabled(active && !current.busy() && document != null);
        saveButton.setEnabled(active
                && !current.busy()
                && document != null
                && current.requiresSave()
                && current.status() != NBTEditorStatus.EDIT_UNCERTAIN
                && current.status() != NBTEditorStatus.COMMIT_UNCERTAIN);
        undoButton.setEnabled(active && mutationsAllowed() && controller.canUndo());
        redoButton.setEnabled(active && mutationsAllowed() && controller.canRedo());
        backButton.setEnabled(active && !current.busy());
        tree.setEnabled(active && !current.busy() && document != null
                && current.status() != NBTEditorStatus.EDIT_UNCERTAIN);
        if (current.status() == NBTEditorStatus.EDITING || current.status() == NBTEditorStatus.EDIT_UNCERTAIN) {
            if (current.status() == NBTEditorStatus.EDIT_UNCERTAIN) {
                snbtTextLoader.reset(null);
                valueTextLoader.reset(null);
            }
            disableEditingControls();
        } else {
            updateSelectedNodeDetails();
        }
    }

    /// Updates selected-node metadata and every contextual command.
    private void updateSelectedNodeDetails() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected == null) {
            clearDetails();
            return;
        }
        NBTNode node = selected.node();
        String displayName = strings.nodeName(selected);
        nameField.setText(node.getName().isEmpty() ? displayName : node.getName());
        @Nullable TagType<?> tagType = node.getType();
        childrenField.setText(strings.entries(selected.childCount()));
        boolean mutable = mutationsAllowed();
        boolean renameEditable = mutable && NBTEditorPanelSupport.nameEditable(selected);
        boolean snbtEditable = mutable && tagType != null;
        updateTypeChoices(selected, tagType, mutable);
        boolean structuredValueEditable = updateValueEditor(selected, tagType, mutable);
        nameField.setEnabled(renameEditable);
        nameField.setEditable(renameEditable);
        renameButton.setEnabled(renameEditable);
        updateSnbtEditor(selected, tagType, mutable);
        boolean emptyList = mutable && NBTEditorPanelSupport.emptyListSelected(selected);
        listTypeCombo.setEnabled(emptyList);
        listTypeButton.setEnabled(emptyList);
        if (emptyList) {
            @Nullable TagType<?> elementType = controller.listElementType(selected);
            listTypeCombo.setSelectedIndex(elementType == null
                    ? 0
                    : NBTTagInput.types().indexOf(elementType) + 1);
        } else {
            listTypeCombo.setSelectedIndex(0);
        }
        @Nullable InsertionTarget target = insertionTarget();
        addButton.setEnabled(mutable && target != null);
        copyButton.setEnabled(mutable && tagType != null);
        pasteButton.setEnabled(mutable && target != null && controller.hasClipboard());
        deleteButton.setEnabled(mutable && !selected.address().isRoot() && !isRegionSlot(selected.address()));
        boolean canMoveUp = mutable && canMove(selected, -1);
        boolean canMoveDown = mutable && canMove(selected, 1);
        moveUpButton.setEnabled(canMoveUp);
        moveDownButton.setEnabled(canMoveDown);
        moveUpMenuItem.setVisible(canMoveUp);
        moveDownMenuItem.setVisible(canMoveDown);
        moveUpMenuItem.setEnabled(canMoveUp);
        moveDownMenuItem.setEnabled(canMoveDown);
        if (!structuredValueEditable && !renameEditable && !snbtEditable && !emptyList
                && !valueTextLoader.isLoading()) {
            editStatusLabel.setText(strings.readOnlyText());
        }
    }

    /// Populates type conversions allowed by the selected tag family and its indexed parent.
    ///
    /// @param selected current row
    /// @param tagType selected tag type, or `null` for non-tag containers
    /// @param mutable whether edits are currently allowed
    private void updateTypeChoices(
            NBTEditorTreeNode selected,
            @Nullable TagType<?> tagType,
            boolean mutable) {
        typeCombo.removeAllItems();
        if (tagType == null) {
            typeCombo.addItem(strings.nodeType(selected));
            typeCombo.setEnabled(false);
            typeButton.setEnabled(false);
            return;
        }
        @Unmodifiable List<TagType<?>> targets = controller.convertibleTypes(selected);
        for (TagType<?> target : targets) {
            typeCombo.addItem(target.name());
        }
        typeCombo.setSelectedItem(tagType.name());
        typeCombo.setEnabled(mutable && targets.size() > 1);
        updateTypeConversionButton();
    }

    /// Reconciles the complete value field and String-only formatting controls.
    ///
    /// @param selected current row
    /// @param tagType selected tag type, or `null`
    /// @param mutable whether edits are currently allowed
    /// @return whether the structured value can currently be submitted
    private boolean updateValueEditor(
            NBTEditorTreeNode selected,
            @Nullable TagType<?> tagType,
            boolean mutable) {
        boolean stringValue = tagType == TagType.STRING;
        @Nullable TagType<?> listElementType = tagType == TagType.LIST
                ? controller.listElementType(selected)
                : null;
        boolean aggregate = NBTStructuredValueCodec.isEditableAggregate(tagType, listElementType);
        boolean numeric = aggregate || NBTStructuredValueCodec.supportsRadixSwitch(tagType);
        sectionSignButton.setVisible(stringValue);
        sectionSignButton.setEnabled(stringValue && mutable);
        formattingPreviewCheck.setVisible(stringValue);
        formattingPreviewCheck.setEnabled(stringValue);
        if (!stringValue) {
            valueArea.setFormattingEnabled(false);
        }
        numberRadixCombo.setVisible(numeric);
        numberRadixCombo.setEnabled(numeric && !controller.snapshot().busy());
        if (aggregate) {
            NBTNumberRadix radix = numberRadixEditor.selectedRadix();
            ValueLoadKey key = new ValueLoadKey(selected, radix);
            valueTextLoader.reset(key);
            @Nullable NBTDocument document = controller.snapshot().document();
            boolean supported = document != null && selected.belongsTo(document);
            if (supported) {
                valueTextLoader.load(
                        key,
                        () -> controller.structuredValue(selected, radix),
                        () -> isCurrentValueKey(key),
                        this::startValueLoad,
                        () -> finishValueLoad(key),
                        this::showValueLoadFailure);
            }
            boolean loaded = valueTextLoader.isLoaded(key);
            boolean editable = loaded && mutable;
            valueArea.setEnabled(editable);
            valueArea.setEditable(editable);
            applyButton.setEnabled(editable);
            if (!loaded && !valueTextLoader.isLoading() && supported) {
                showValueLoadFailure(strings.arrayLoadFailedText());
            }
            return editable;
        }
        valueTextLoader.reset(null);
        @Nullable String scalar = selected.currentScalarValue();
        if (tagType != null && scalar != null) {
            NBTNumberRadix radix = numberRadixEditor.selectedRadix();
            valueArea.setText(NBTStructuredValueCodec.formatScalar(tagType, scalar, radix));
            numberRadixEditor.markDisplayed(radix);
            valueArea.setEnabled(mutable);
            valueArea.setEditable(mutable);
            applyButton.setEnabled(mutable);
            updateFormattingPreviewVisibility();
            return mutable;
        }
        valueArea.setText("");
        valueArea.setEnabled(false);
        valueArea.setEditable(false);
        applyButton.setEnabled(false);
        numberRadixCombo.setEnabled(false);
        return false;
    }

    /// Marks primitive-array formatting as active while the detached snapshot is computed.
    private void startValueLoad() {
        editStatusLabel.setText(strings.loadingValueText());
        valueArea.setToolTipText(null);
        valueArea.putClientProperty("JComponent.outline", null);
        valueArea.setEnabled(false);
        valueArea.setEditable(false);
        applyButton.setEnabled(false);
        numberRadixCombo.setEnabled(false);
    }

    /// Enables a complete aggregate value after its detached snapshot is loaded.
    ///
    /// @param key exact source row
    private void finishValueLoad(ValueLoadKey key) {
        if (!isCurrentValueKey(key)) {
            return;
        }
        clearEditFailure();
        boolean editable = mutationsAllowed();
        valueArea.setEnabled(editable);
        valueArea.setEditable(editable);
        applyButton.setEnabled(editable);
        numberRadixCombo.setEnabled(!controller.snapshot().busy());
    }

    /// Returns whether one array snapshot key still owns the visible selection and revision.
    ///
    /// @param key exact source row
    /// @return whether its result may update the form
    private boolean isCurrentValueKey(ValueLoadKey key) {
        ValueLoadKey selectedKey = Objects.requireNonNull(key, "key");
        @Nullable NBTDocument document = controller.snapshot().document();
        try {
            return !closed.get()
                    && selectedKey.node() == selectedNode()
                    && selectedKey.radix() == numberRadixEditor.selectedRadix()
                    && document != null
                    && selectedKey.node().belongsTo(document)
                    && selectedKey.node().node().getRevision() == document.editor().getRevision();
        } catch (IllegalStateException failure) {
            return false;
        }
    }

    /// Displays a localized primitive-array load failure without exposing a partial value.
    ///
    /// @param detail technical hover detail
    private void showValueLoadFailure(String detail) {
        valueArea.setEnabled(false);
        valueArea.setEditable(false);
        applyButton.setEnabled(false);
        editStatusLabel.setText(strings.invalidValueText());
        editStatusLabel.setToolTipText(Objects.requireNonNull(detail, "detail"));
        valueArea.setToolTipText(detail);
    }

    /// Reconciles the SNBT editor with one immutable selection without serializing on the EDT.
    ///
    /// @param selected current selected row
    /// @param tagType selected tag type, or `null` for Region and Chunk rows
    /// @param mutable whether edits are currently allowed
    private void updateSnbtEditor(
            NBTEditorTreeNode selected,
            @Nullable TagType<?> tagType,
            boolean mutable) {
        NBTEditorTreeNode currentSelection = Objects.requireNonNull(selected, "selected");
        @Nullable NBTDocument document = controller.snapshot().document();
        boolean supported = document != null
                && currentSelection.belongsTo(document)
                && tagType != null;
        snbtTextLoader.reset(supported ? currentSelection : null);
        if (supported && editorTabs.getSelectedIndex() == 1) {
            snbtTextLoader.load(
                    currentSelection,
                    () -> controller.subtreeSnbt(currentSelection),
                    () -> isCurrentSnbtSelection(currentSelection),
                    this::startSnbtLoad,
                    this::finishSnbtLoad,
                    this::showSnbtLoadFailure);
        }
        boolean editable = supported && snbtTextLoader.isLoaded(currentSelection) && mutable;
        snbtArea.setEnabled(editable);
        snbtArea.setEditable(editable);
        replaceButton.setEnabled(editable);
    }

    /// Marks subtree serialization as active.
    private void startSnbtLoad() {
        snbtStatusLabel.setText(strings.loadingSnbtText());
        snbtStatusLabel.setToolTipText(null);
        snbtArea.setToolTipText(null);
        snbtArea.putClientProperty("JComponent.outline", null);
    }

    /// Enables the subtree editor after complete bounded insertion.
    private void finishSnbtLoad() {
        snbtStatusLabel.setText(" ");
        snbtStatusLabel.setToolTipText(null);
        @Nullable NBTEditorTreeNode selected = selectedNode();
        boolean editable = selected != null
                && mutationsAllowed()
                && selected.node().getType() != null;
        snbtArea.setEnabled(editable);
        snbtArea.setEditable(editable);
        replaceButton.setEnabled(editable);
    }

    /// Returns whether one immutable row is still current at its captured editor revision.
    /// @param expected expected selected row
    /// @return whether a background result may be displayed
    private boolean isCurrentSnbtSelection(NBTEditorTreeNode expected) {
        @Nullable NBTDocument document = controller.snapshot().document();
        try {
            return !closed.get()
                    && expected == selectedNode()
                    && document != null
                    && expected.belongsTo(document)
                    && expected.node().getRevision() == document.editor().getRevision();
        } catch (IllegalStateException failure) {
            return false;
        }
    }

    /// Displays a localized background-load failure without marking user input invalid.
    ///
    /// @param detail technical hover detail
    private void showSnbtLoadFailure(String detail) {
        snbtArea.setEnabled(false);
        snbtArea.setEditable(false);
        replaceButton.setEnabled(false);
        snbtStatusLabel.setText(strings.snbtLoadFailedText());
        snbtStatusLabel.setToolTipText(Objects.requireNonNull(detail, "detail"));
        snbtArea.setToolTipText(detail);
    }

    /// Clears details when no model row is selected.
    private void clearDetails() {
        nameField.setText("");
        typeCombo.removeAllItems();
        typeCombo.setEnabled(false);
        typeButton.setEnabled(false);
        childrenField.setText("");
        valueArea.setText("");
        valueArea.setFormattingEnabled(false);
        sectionSignButton.setVisible(false);
        formattingPreviewCheck.setVisible(false);
        numberRadixCombo.setVisible(false);
        snbtTextLoader.reset(null);
        valueTextLoader.reset(null);
        nameField.setEnabled(false);
        renameButton.setEnabled(false);
        valueArea.setEnabled(false);
        valueArea.setEditable(false);
        applyButton.setEnabled(false);
        listTypeCombo.setEnabled(false);
        listTypeButton.setEnabled(false);
        addButton.setEnabled(false);
        copyButton.setEnabled(false);
        pasteButton.setEnabled(false);
        deleteButton.setEnabled(false);
        moveUpButton.setEnabled(false);
        moveDownButton.setEnabled(false);
        moveUpMenuItem.setVisible(false);
        moveDownMenuItem.setVisible(false);
        moveUpMenuItem.setEnabled(false);
        moveDownMenuItem.setEnabled(false);
        clearEditFailure();
    }

    /// Disables contextual mutations without replacing the user's submitted field values.
    private void disableEditingControls() {
        nameField.setEnabled(false);
        nameField.setEditable(false);
        typeCombo.setEnabled(false);
        valueArea.setEnabled(false);
        valueArea.setEditable(false);
        sectionSignButton.setEnabled(false);
        formattingPreviewCheck.setEnabled(false);
        numberRadixCombo.setEnabled(false);
        listTypeCombo.setEnabled(false);
        snbtArea.setEnabled(false);
        snbtArea.setEditable(false);
        moveUpMenuItem.setVisible(false);
        moveDownMenuItem.setVisible(false);
        moveUpMenuItem.setEnabled(false);
        moveDownMenuItem.setEnabled(false);
        for (AbstractButton button : List.of(
                renameButton, typeButton, applyButton, listTypeButton, replaceButton, addButton, copyButton,
                pasteButton, deleteButton, moveUpButton, moveDownButton)) {
            button.setEnabled(false);
        }
    }

    /// Returns whether a new mutation may currently be scheduled.
    /// @return whether an editable document is ready
    private boolean mutationsAllowed() {
        NBTEditorSnapshot current = controller.snapshot();
        return !closed.get()
                && !current.busy()
                && current.document() != null
                && current.status() != NBTEditorStatus.CLOSED
                && current.status() != NBTEditorStatus.EDIT_UNCERTAIN
                && current.status() != NBTEditorStatus.COMMIT_UNCERTAIN;
    }

    /// Returns the selected typed node.
    ///
    /// @return selected node, or `null`
    private @Nullable NBTEditorTreeNode selectedNode() {
        @Nullable Object value = tree.getLastSelectedPathComponent();
        return value instanceof NBTEditorTreeNode node ? node : null;
    }

    /// Returns the direct selected parent row.
    ///
    /// @return parent row, or `null` at the root or without selection
    private @Nullable NBTEditorTreeNode selectedParentNode() {
        @Nullable TreePath selectedPath = tree.getSelectionPath();
        if (selectedPath == null || selectedPath.getParentPath() == null) {
            return null;
        }
        Object parent = selectedPath.getParentPath().getLastPathComponent();
        return parent instanceof NBTEditorTreeNode node ? node : null;
    }

    /// Restores value controls and displays a rejected radix conversion.
    ///
    /// @param detail technical parse or stale-node detail
    private void showNumberRadixFailure(String detail) {
        boolean editable = mutationsAllowed();
        valueArea.setEnabled(editable);
        valueArea.setEditable(editable);
        applyButton.setEnabled(editable);
        numberRadixCombo.setEnabled(!controller.snapshot().busy());
        showEditFailure(NBTEditException.Reason.TYPE_MISMATCH, Objects.requireNonNull(detail, "detail"));
    }

    /// Resolves the type-combo display value to one standard non-END tag type.
    ///
    /// @return selected target type, or `null` for a non-tag row
    private @Nullable TagType<?> selectedConversionType() {
        @Nullable Object value = typeCombo.getSelectedItem();
        if (!(value instanceof String name)) {
            return null;
        }
        for (TagType<?> type : NBTTagInput.types()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return null;
    }

    /// Enables conversion submission only for a changed supported target.
    private void updateTypeConversionButton() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        @Nullable TagType<?> target = selectedConversionType();
        typeButton.setEnabled(selected != null
                && target != null
                && target != selected.node().getType()
                && typeCombo.isEnabled()
                && mutationsAllowed());
    }

    /// Returns whether the selected value field has a complete editable representation.
    ///
    /// @param selected selected row
    /// @return whether Apply may submit its current text
    private boolean valueEditable(NBTEditorTreeNode selected) {
        @Nullable TagType<?> type = selected.node().getType();
        if (type == null) {
            return false;
        }
        @Nullable TagType<?> elementType = type == TagType.LIST
                ? controller.listElementType(selected)
                : null;
        return NBTStructuredValueCodec.isEditableAggregate(type, elementType)
                || selected.editable() && ValueTag.class.isAssignableFrom(type.tagClass());
    }

    /// Returns the selected immutable address.
    ///
    /// @return selected address, or root when no row is selected
    private NBTAddress selectedAddress() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        return selected == null ? NBTAddress.root() : selected.address();
    }

    /// Restores one logical address, falling back through existing parent prefixes.
    ///
    /// @param address preferred address
    private void restoreSelection(NBTAddress address) {
        if (!(tree.getModel() instanceof NBTLazyTreeModel model)) {
            return;
        }
        NBTAddress candidate = Objects.requireNonNull(address, "address");
        while (true) {
            try {
                TreePath path = model.pathForAddress(candidate);
                tree.setSelectionPath(path);
                if (!candidate.isRoot()) {
                    tree.scrollPathToVisible(path);
                }
                return;
            } catch (IndexOutOfBoundsException | IllegalStateException missing) {
                if (candidate.isRoot()) {
                    tree.setSelectionPath(model.pathForAddress(NBTAddress.root()));
                    return;
                }
                candidate = candidate.parent();
            }
        }
    }

    /// Determines the parent, index, types, and naming rule for Add or Paste.
    ///
    /// @return insertion target, or `null` when the current selection cannot accept a tag
    private @Nullable InsertionTarget insertionTarget() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        return selected == null
                ? null
                : NBTEditorPanelSupport.insertionTarget(selected, selectedParentNode(), controller);
    }

    /// Returns whether one selected row can move by an offset.
    ///
    /// @param selected selected row
    /// @param offset requested offset
    /// @return whether the destination is inside an ordered mutable parent
    private boolean canMove(NBTEditorTreeNode selected, int offset) {
        if (selected.address().isRoot()
                || isRegionSlot(selected.address())
                || isChunkRoot(selected.address())) {
            return false;
        }
        @Nullable NBTEditorTreeNode parent = selectedParentNode();
        int destination = selected.parentIndex() + offset;
        return parent != null && destination >= 0 && destination < parent.childCount();
    }

    /// Performs terminal Swing teardown on the EDT.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        pendingRouteOpen = null;
        stateSubscription.close();
        tree.removeTreeSelectionListener(treeSelectionListener);
        tree.removeTreeWillExpandListener(rootExpansionListener);
        tree.removeMouseListener(treePopupMouseListener);
        editorTabs.removeChangeListener(editorTabListener);
        valueTextLoader.close();
        snbtTextLoader.close();
        setTransferHandler(null);
        tree.setModel(emptyTreeModel());
        renderedDocument = null;
        renderedEditorRevision = -1L;
        if (iconLoad != null) {
            iconLoad.cancel(true);
        }
        controller.close();
    }

    /// Parent-owned navigation operation emitted by this routable page.
    @NotNullByDefault
    @FunctionalInterface
    public interface Listener {
        /// Requests removal of this page and return to its parent route.
        void closeRequested();
    }

}
