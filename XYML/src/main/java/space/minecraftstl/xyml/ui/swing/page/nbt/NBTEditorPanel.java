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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.library.nbt.edit.NBTAddress;
import space.minecraftstl.xyml.library.nbt.edit.NBTEditException;
import space.minecraftstl.xyml.library.nbt.edit.NBTNode;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import space.minecraftstl.xyml.nbt.NBTDocument;
import space.minecraftstl.xyml.nbt.NBTDocumentService;
import space.minecraftstl.xyml.nbt.NBTNodeType;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.shell.ShellFileDropHandler;

import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
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
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.event.ChangeListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

/// Complete Swing editor for standalone NBT tags and fixed-slot Region documents.
///
/// The panel performs no filesystem I/O on the EDT and never receives a mutable working-tree
/// element. Every edit passes an immutable node handle to [NBTEditorController], which delegates to
/// the transactional XoyzNBT editor before this panel rebuilds the affected revision.
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

    /// Renders only rows requested by the Swing tree viewport.
    private final JTree tree = new JTree(emptyTreeModel());

    /// Switches between structured fields and the lazily loaded subtree SNBT editor.
    private final JTabbedPane editorTabs = new JTabbedPane();

    /// Edits a Compound child name and displays all other names read-only.
    private final JTextField nameField = new JTextField();

    /// Applies a validated Compound child rename.
    private final JButton renameButton = new JButton();

    /// Displays the selected stable NBT type.
    private final JTextField typeField = readOnlyField("nbtEditorNodeType");

    /// Displays the selected direct-child count.
    private final JTextField childrenField = readOnlyField("nbtEditorNodeChildren");

    /// Edits an exact scalar value.
    private final JTextArea valueArea = new JTextArea(6, 24);

    /// Applies a validated scalar mutation.
    private final JButton applyButton = new JButton();

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
    private final TreeWillExpandListener rootExpansionListener = new RootExpansionListener();

    /// Selects a right-clicked row before showing its context menu.
    private final TreePopupMouseListener treePopupMouseListener = new TreePopupMouseListener();

    /// Owned controller subscription removed during closure.
    private final Subscription stateSubscription;

    /// Caller-owned executor for subtree snapshots and serialization.
    private final Executor backgroundExecutor;

    /// Background classpath icon load, or `null` for deterministic injected panels.
    private final @Nullable CompletableFuture<@Unmodifiable Map<NBTNodeType, Icon>> iconLoad;

    /// Transfer adapter that performs only lexical payload decoding on the EDT.
    private final TransferHandler nbtTransferHandler = new NBTTransferHandler();

    /// Guards terminal teardown from any calling thread.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Document identity currently represented by the tree model.
    private @Nullable NBTDocument renderedDocument;

    /// Editor revision currently represented by the tree model.
    private long renderedEditorRevision = -1L;

    /// Current asynchronous subtree serialization, or `null` while idle.
    private @Nullable CompletableFuture<@Nullable String> snbtLoad;

    /// Monotonic identity used to reject late subtree serialization results.
    private long snbtRequestRevision;

    /// Document identity associated with the SNBT editor content.
    private @Nullable NBTDocument snbtDocument;

    /// Editor revision associated with the SNBT editor content.
    private long snbtEditorRevision = -1L;

    /// Structural address associated with the SNBT editor content.
    private @Nullable NBTAddress snbtAddress;

    /// Whether the current SNBT key has completed serialization.
    private boolean snbtLoaded;

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
        this.interactions = interactions == null
                ? new SwingNBTEditorInteractions(this, this.strings)
                : interactions;
        this.listener = Objects.requireNonNull(listener, "listener");
        this.backgroundExecutor = backgroundExecutor == null
                ? ForkJoinPool.commonPool()
                : backgroundExecutor;
        treeCellRenderer = new NBTTreeCellRenderer(this.strings);

        setName("nbtEditorPage");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());
        add(createHeadingBand(), BorderLayout.NORTH);
        add(createEditorSurface(), BorderLayout.CENTER);
        add(createStatusBand(), BorderLayout.SOUTH);
        configureTree();
        configureEditors();
        configureKeyboardActions();
        setTransferHandler(nbtTransferHandler);
        stateSubscription = this.controller.subscribe(change -> {
            @Nullable NBTEditorSnapshot current = change.currentValue();
            if (current != null && !closed.get()) {
                render(current);
            }
        });
        render(this.controller.snapshot());
        iconLoad = backgroundExecutor == null ? null : preloadTreeIcons(backgroundExecutor);
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
        if (closed.get() || controller.snapshot().busy() || !confirmReplacement()) {
            return;
        }
        controller.open(Objects.requireNonNull(file, "file").toAbsolutePath().normalize());
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
                "[]10[][grow,fill]8[]4[]8[]4[]4[]",
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
                controller::save);
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
                "insets 16, fillx",
                "[96!,fill][grow,fill][]",
                "[][][]10[]8[]8[]4[]push"));
        details.setName("nbtEditorDetails");

        nameField.setName("nbtEditorNodeName");
        details.add(detailLabel(strings.nameLabel(), nameField));
        details.add(nameField, "growx");
        renameButton.setName("nbtEditorRename");
        renameButton.setText(strings.applyText());
        renameButton.addActionListener(event -> renameSelected());
        details.add(renameButton, "w 84!, h 34!, wrap");

        details.add(detailLabel(strings.typeLabel(), typeField));
        details.add(typeField, "span 2, growx, wrap");
        details.add(detailLabel(strings.childrenLabel(), childrenField));
        details.add(childrenField, "span 2, growx, wrap");

        details.add(detailLabel(strings.valueLabel(), valueArea), "top");
        JScrollPane valueScroll = new JScrollPane(valueArea);
        valueScroll.setName("nbtEditorValueScroll");
        details.add(valueScroll, "span 2, growx, h 120:180:280, wrap");

        details.add(new JLabel(), "skip");
        applyButton.setName("nbtEditorApply");
        applyButton.setText(strings.applyText());
        applyButton.addActionListener(event -> applySelectedValue());
        details.add(applyButton, "span 2, right, w 84!, h 34!, wrap");

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
        valueArea.setLineWrap(true);
        valueArea.setWrapStyleWord(true);
        nameField.setEnabled(false);
        valueArea.setEnabled(false);
        snbtArea.setEnabled(false);
        listTypeCombo.setName("nbtEditorListType");
        listTypeCombo.addItem(strings.tagEndText());
        for (TagType<?> type : NBTTagInput.types()) {
            listTypeCombo.addItem(type.name());
        }
    }

    /// Installs Save, Undo, Redo, Delete, Rename, Copy, and Paste shortcuts.
    private void configureKeyboardActions() {
        bind(this, "save", KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), controller::save);
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
        menu.add(menuItem(strings.moveUpText(), null, () -> moveSelected(-1)));
        menu.add(menuItem(strings.moveDownText(), null, () -> moveSelected(1)));
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

    /// Opens a constrained new-tag form for the current insertion target.
    private void addTag() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable InsertionTarget target = insertionTarget();
        if (target == null || !mutationsAllowed()) {
            return;
        }
        JTextField tagName = new JTextField(target.nameRequired() ? uniqueChildName(target.parent()) : "");
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
            controller.createAndInsertAsync(target.parent(), target.index(), selectedType,
                    tagName.getText(), tagValue.getText()).thenAccept(result -> {
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
            });
            return;
        }
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

    /// Applies one exact selected scalar value.
    private void applySelectedValue() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected == null || !selected.editable() || !mutationsAllowed()) {
            return;
        }
        String draft = valueArea.getText();
        handleAsyncResult(controller.applyValueEditAsync(selected, draft), () -> valueArea.setText(draft));
    }

    /// Applies a Compound child rename.
    private void renameSelected() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected != null && nameEditable(selected) && mutationsAllowed()) {
            String draft = nameField.getText();
            handleAsyncResult(controller.renameAsync(selected, draft), () -> nameField.setText(draft));
        }
    }

    /// Replaces the selected tag from complete strict SNBT.
    private void replaceSelectedSnbt() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected != null && selected.node().getType() != null && mutationsAllowed()) {
            String draft = snbtArea.getText();
            handleAsyncResult(controller.replaceSnbtAsync(selected, draft), () -> snbtArea.setText(draft));
        }
    }

    /// Applies the declared type of one empty List.
    private void applyListType() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected == null || !emptyListSelected(selected) || !mutationsAllowed()) {
            return;
        }
        int selectedIndex = listTypeCombo.getSelectedIndex();
        @Nullable TagType<?> type = selectedIndex <= 0
                ? null
                : NBTTagInput.types().get(selectedIndex - 1);
        handleAsyncResult(controller.setListElementTypeAsync(selected, type),
                () -> listTypeCombo.setSelectedIndex(selectedIndex));
    }

    /// Copies the selected tag into the controller clipboard.
    private void copySelected() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected == null || selected.node().getType() == null || !mutationsAllowed()) {
            return;
        }
        controller.copyAsync(selected).thenAccept(result -> {
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
        if (target != null && controller.hasClipboard() && mutationsAllowed()) {
            controller.pasteAsync(target.parent(), target.index()).thenAccept(this::handleResult);
        }
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
        controller.deleteAsync(selected).thenAccept(this::handleResult);
    }

    /// Moves the selected ordered child by one position.
    ///
    /// @param offset `-1` for up or `1` for down
    private void moveSelected(int offset) {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected != null && canMove(selected, offset) && mutationsAllowed()) {
            controller.moveAsync(selected, offset).thenAccept(this::handleResult);
        }
    }

    /// Undoes one transaction and restores the nearest surviving selection.
    private void undo() {
        if (mutationsAllowed() && controller.canUndo()) {
            controller.undoAsync(selectedAddress()).thenAccept(this::handleResult);
        }
    }

    /// Redoes one transaction and restores the nearest surviving selection.
    private void redo() {
        if (mutationsAllowed() && controller.canRedo()) {
            controller.redoAsync(selectedAddress()).thenAccept(this::handleResult);
        }
    }

    /// Focuses and selects the Compound child name when rename is available.
    private void focusNameEditor() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected != null && nameEditable(selected) && mutationsAllowed()) {
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
    /// @param future result completed by the controller on its UI dispatcher
    /// @param restoreDraft restores the exact submitted field content after rejection
    private void handleAsyncResult(CompletableFuture<NBTEditResult> future, Runnable restoreDraft) {
        Runnable draftRestorer = Objects.requireNonNull(restoreDraft, "restoreDraft");
        Objects.requireNonNull(future, "future").thenAccept(result -> {
            if (!result.applied()) {
                draftRestorer.run();
            }
            handleResult(result);
        });
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
        snbtStatusLabel.setText(snbtLoad == null ? " " : strings.loadingSnbtText());
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
        statusLabel.setText(statusText(current));
        statusLabel.setToolTipText(current.message());
        progressBar.setVisible(current.busy());
        boolean active = current.status() != NBTEditorStatus.CLOSED;
        openButton.setEnabled(active && !current.busy());
        reloadButton.setEnabled(active && !current.busy() && document != null);
        saveButton.setEnabled(active
                && !current.busy()
                && document != null
                && current.requiresSave()
                && current.status() != NBTEditorStatus.CONFLICT
                && current.status() != NBTEditorStatus.EDIT_UNCERTAIN
                && current.status() != NBTEditorStatus.COMMIT_UNCERTAIN);
        undoButton.setEnabled(active && mutationsAllowed() && controller.canUndo());
        redoButton.setEnabled(active && mutationsAllowed() && controller.canRedo());
        backButton.setEnabled(active && !current.busy());
        tree.setEnabled(active && !current.busy() && document != null
                && current.status() != NBTEditorStatus.EDIT_UNCERTAIN);
        if (current.status() == NBTEditorStatus.EDITING || current.status() == NBTEditorStatus.EDIT_UNCERTAIN) {
            if (current.status() == NBTEditorStatus.EDIT_UNCERTAIN) {
                invalidateSnbtLoad();
            }
            disableEditingControls();
        } else {
            updateSelectedNodeDetails();
        }
    }

    /// Returns localized lifecycle status text.
    /// @param current current state
    /// @return visible status text
    private String statusText(NBTEditorSnapshot current) {
        return switch (current.status()) {
            case EMPTY, CLOSED -> strings.emptyText();
            case OPENING -> strings.openingText();
            case READY -> current.dirty() ? strings.modifiedText() : strings.readyText();
            case EDITING -> strings.editingText();
            case EDIT_UNCERTAIN -> strings.editUncertainText();
            case SAVING -> strings.savingText();
            case CONFLICT -> strings.conflictText();
            case PARTIAL_SAVE -> strings.partialSaveText();
            case COMMIT_UNCERTAIN -> strings.commitUncertainText();
            case ERROR -> strings.errorText();
        };
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
        typeField.setText(strings.nodeType(selected));
        childrenField.setText(strings.entries(selected.childCount()));
        @Nullable String scalar = selected.currentScalarValue();
        valueArea.setText(scalar == null ? "" : scalar);

        boolean mutable = mutationsAllowed();
        boolean scalarEditable = mutable && selected.editable();
        boolean renameEditable = mutable && nameEditable(selected);
        boolean snbtEditable = mutable && tagType != null;
        nameField.setEnabled(renameEditable);
        nameField.setEditable(renameEditable);
        renameButton.setEnabled(renameEditable);
        valueArea.setEnabled(scalarEditable);
        valueArea.setEditable(scalarEditable);
        applyButton.setEnabled(scalarEditable);
        updateSnbtEditor(selected, tagType, mutable);

        boolean emptyList = mutable && emptyListSelected(selected);
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
        moveUpButton.setEnabled(mutable && canMove(selected, -1));
        moveDownButton.setEnabled(mutable && canMove(selected, 1));
        if (!scalarEditable && !renameEditable && !snbtEditable && !emptyList) {
            editStatusLabel.setText(strings.readOnlyText());
        }
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
        long revision = currentSelection.node().getRevision();
        NBTAddress address = currentSelection.address();
        if (document != snbtDocument
                || revision != snbtEditorRevision
                || !Objects.equals(address, snbtAddress)) {
            resetSnbtSelection(document, revision, address);
        }

        boolean supported = document != null
                && currentSelection.belongsTo(document)
                && tagType != null;
        if (supported
                && editorTabs.getSelectedIndex() == 1
                && !snbtLoaded
                && snbtLoad == null) {
            startSnbtLoad(currentSelection, document, revision, address);
        }
        boolean editable = supported && snbtLoaded && mutable;
        snbtArea.setEnabled(editable);
        snbtArea.setEditable(editable);
        replaceButton.setEnabled(editable);
    }

    /// Starts one detached snapshot and pretty serialization on the caller-owned executor.
    ///
    /// @param selected immutable selected row
    /// @param document exact document identity
    /// @param revision exact editor revision
    /// @param address exact selected address
    private void startSnbtLoad(
            NBTEditorTreeNode selected,
            NBTDocument document,
            long revision,
            NBTAddress address) {
        long request = ++snbtRequestRevision;
        snbtStatusLabel.setText(strings.loadingSnbtText());
        snbtStatusLabel.setToolTipText(null);
        snbtArea.setToolTipText(null);
        snbtArea.putClientProperty("JComponent.outline", null);
        try {
            CompletableFuture<@Nullable String> future = CompletableFuture.supplyAsync(
                    () -> controller.subtreeSnbt(selected),
                    backgroundExecutor);
            snbtLoad = future;
            future.whenComplete((@Nullable String snbt, @Nullable Throwable failure) ->
                    EdtDispatcher.execute(() -> finishSnbtLoad(
                            request,
                            document,
                            revision,
                            address,
                            snbt,
                            failure)));
        } catch (RuntimeException failure) {
            showSnbtLoadFailure(failureDetail(failure));
        }
    }

    /// Publishes a subtree serialization only when its complete selection key is still current.
    ///
    /// @param request request identity
    /// @param document exact document identity
    /// @param revision exact editor revision
    /// @param address exact selected address
    /// @param snbt serialized subtree, or `null` when unavailable
    /// @param failure asynchronous failure, or `null`
    private void finishSnbtLoad(
            long request,
            NBTDocument document,
            long revision,
            NBTAddress address,
            @Nullable String snbt,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (!acceptsSnbtRequest(request, document, revision, address)) {
            return;
        }
        if (failure != null || snbt == null) {
            snbtLoad = null;
            String detail = failure == null
                    ? "The selected NBT subtree is no longer available"
                    : failureDetail(failure);
            showSnbtLoadFailure(detail);
            return;
        }
        snbtArea.setText("");
        snbtArea.setCaretPosition(0);
        appendSnbtChunk(request, document, revision, address, snbt, 0);
    }

    /// Appends bounded SNBT chunks across EDT turns so large arrays cannot monopolize input.
    ///
    /// @param request request identity
    /// @param document exact document identity
    /// @param revision exact editor revision
    /// @param address exact selected address
    /// @param snbt complete serialized subtree
    /// @param offset first character not yet inserted
    private void appendSnbtChunk(
            long request,
            NBTDocument document,
            long revision,
            NBTAddress address,
            String snbt,
            int offset) {
        EdtDispatcher.requireEventDispatchThread();
        if (!acceptsSnbtRequest(request, document, revision, address)) {
            return;
        }
        int end = Math.min(offset + SNBT_INSERT_CHUNK_SIZE, snbt.length());
        if (end > offset) {
            snbtArea.append(snbt.substring(offset, end));
        }
        if (end < snbt.length()) {
            EdtDispatcher.executeLater(() ->
                    appendSnbtChunk(request, document, revision, address, snbt, end));
            return;
        }
        snbtLoad = null;
        snbtLoaded = true;
        snbtArea.setCaretPosition(0);
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

    /// Returns whether a serialized subtree still belongs to the exact visible selection.
    ///
    /// @param request request identity
    /// @param document exact document identity
    /// @param revision exact editor revision
    /// @param address exact selected address
    /// @return whether the completion may update Swing state
    private boolean acceptsSnbtRequest(
            long request,
            NBTDocument document,
            long revision,
            NBTAddress address) {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        return !closed.get()
                && request == snbtRequestRevision
                && document == snbtDocument
                && revision == snbtEditorRevision
                && address.equals(snbtAddress)
                && selected != null
                && selected.belongsTo(document)
                && selected.node().getRevision() == revision
                && address.equals(selected.address());
    }

    /// Clears one obsolete SNBT key and invalidates any completion still in flight.
    ///
    /// @param document new document identity, or `null`
    /// @param revision new editor revision, or `-1`
    /// @param address new selected address, or `null`
    private void resetSnbtSelection(
            @Nullable NBTDocument document,
            long revision,
            @Nullable NBTAddress address) {
        invalidateSnbtLoad();
        snbtDocument = document;
        snbtEditorRevision = revision;
        snbtAddress = address;
        snbtLoaded = false;
        snbtArea.setText("");
        snbtArea.setEnabled(false);
        snbtArea.setEditable(false);
        replaceButton.setEnabled(false);
        snbtStatusLabel.setText(" ");
        snbtStatusLabel.setToolTipText(null);
        snbtArea.setToolTipText(null);
        snbtArea.putClientProperty("JComponent.outline", null);
    }

    /// Invalidates and requests cancellation of the current subtree serialization.
    private void invalidateSnbtLoad() {
        snbtRequestRevision++;
        @Nullable CompletableFuture<@Nullable String> current = snbtLoad;
        snbtLoad = null;
        if (current != null) {
            current.cancel(true);
        }
    }

    /// Displays a localized background-load failure without marking user input invalid.
    ///
    /// @param detail technical hover detail
    private void showSnbtLoadFailure(String detail) {
        snbtLoaded = false;
        snbtArea.setEnabled(false);
        snbtArea.setEditable(false);
        replaceButton.setEnabled(false);
        snbtStatusLabel.setText(strings.snbtLoadFailedText());
        snbtStatusLabel.setToolTipText(Objects.requireNonNull(detail, "detail"));
        snbtArea.setToolTipText(detail);
    }

    /// Returns concise non-empty detail for one asynchronous failure.
    ///
    /// @param failure asynchronous failure
    /// @return technical failure detail
    private static String failureDetail(Throwable failure) {
        Throwable cause = Objects.requireNonNull(failure, "failure");
        @Nullable String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /// Clears details when no model row is selected.
    private void clearDetails() {
        nameField.setText("");
        typeField.setText("");
        childrenField.setText("");
        valueArea.setText("");
        resetSnbtSelection(null, -1L, null);
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
        clearEditFailure();
    }

    /// Disables contextual mutations without replacing the user's submitted field values.
    private void disableEditingControls() {
        nameField.setEnabled(false);
        nameField.setEditable(false);
        valueArea.setEnabled(false);
        valueArea.setEditable(false);
        listTypeCombo.setEnabled(false);
        snbtArea.setEnabled(false);
        snbtArea.setEditable(false);
        for (AbstractButton button : List.of(
                renameButton, applyButton, listTypeButton, replaceButton, addButton, copyButton,
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
        if (selected == null) {
            return null;
        }
        @Nullable InsertionTarget direct = targetForParent(selected, selected.childCount());
        if (direct != null) {
            return direct;
        }
        @Nullable NBTEditorTreeNode parent = selectedParentNode();
        return parent == null ? null : targetForParent(parent, selected.parentIndex() + 1);
    }

    /// Builds insertion constraints for one candidate parent.
    ///
    /// @param parent candidate parent row
    /// @param index requested insertion index
    /// @return constrained target, or `null`
    private @Nullable InsertionTarget targetForParent(NBTEditorTreeNode parent, int index) {
        @Nullable TagType<?> type = parent.node().getType();
        if (type == TagType.COMPOUND) {
            return new InsertionTarget(parent, index, NBTTagInput.types(), true);
        }
        if (type == TagType.LIST) {
            @Nullable TagType<?> elementType = parent.childCount() > 0
                    ? parent.childAt(0).node().getType()
                    : controller.listElementType(parent);
            return new InsertionTarget(
                    parent,
                    index,
                    elementType == null ? NBTTagInput.types() : List.of(elementType),
                    false);
        }
        if (type == TagType.BYTE_ARRAY) {
            return new InsertionTarget(parent, index, List.of(TagType.BYTE), false);
        }
        if (type == TagType.INT_ARRAY) {
            return new InsertionTarget(parent, index, List.of(TagType.INT), false);
        }
        if (type == TagType.LONG_ARRAY) {
            return new InsertionTarget(parent, index, List.of(TagType.LONG), false);
        }
        if (type == null && isRegionSlot(parent.address()) && parent.childCount() == 0) {
            return new InsertionTarget(parent, 0, List.of(TagType.COMPOUND), false);
        }
        return null;
    }

    /// Returns whether the selected row is an empty List.
    ///
    /// @param selected selected row
    /// @return whether its declared element type can change
    private static boolean emptyListSelected(NBTEditorTreeNode selected) {
        return selected.node().getType() == TagType.LIST && selected.childCount() == 0;
    }

    /// Returns whether the selected address is a Compound name segment.
    ///
    /// @param selected selected row
    /// @return whether rename is structurally valid
    private static boolean nameEditable(NBTEditorTreeNode selected) {
        @Unmodifiable List<NBTAddress.Segment> segments = selected.address().segments();
        return !segments.isEmpty() && segments.get(segments.size() - 1) instanceof NBTAddress.NameSegment;
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

    /// Returns whether an address identifies a fixed Region chunk slot.
    ///
    /// @param address candidate address
    /// @return whether its final segment is a region slot
    private static boolean isRegionSlot(NBTAddress address) {
        @Unmodifiable List<NBTAddress.Segment> segments = address.segments();
        return !segments.isEmpty()
                && segments.get(segments.size() - 1) instanceof NBTAddress.RegionChunkSegment;
    }

    /// Returns whether an address identifies a fixed chunk root.
    ///
    /// @param address candidate address
    /// @return whether its final segment is a chunk-root slot
    private static boolean isChunkRoot(NBTAddress address) {
        @Unmodifiable List<NBTAddress.Segment> segments = address.segments();
        return !segments.isEmpty()
                && segments.get(segments.size() - 1) instanceof NBTAddress.ChunkRootSegment;
    }

    /// Finds the owning Region local index for a chunk-root address.
    ///
    /// @param address chunk-root address
    /// @return local index, or `-1` when absent
    private static int chunkLocalIndex(NBTAddress address) {
        for (NBTAddress.Segment segment : address.segments()) {
            if (segment instanceof NBTAddress.RegionChunkSegment chunk) {
                return chunk.localIndex();
            }
        }
        return -1;
    }

    /// Generates a readable unused default Compound child name.
    ///
    /// @param parent Compound parent
    /// @return unused default name
    private String uniqueChildName(NBTEditorTreeNode parent) {
        String base = strings.defaultTagName();
        for (int suffix = 1; ; suffix++) {
            String candidate = suffix == 1 ? base : base + '_' + suffix;
            boolean used = false;
            for (int index = 0; index < parent.childCount(); index++) {
                if (candidate.equals(parent.childAt(index).node().getName())) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return candidate;
            }
        }
    }

    /// Returns a safe initial structured value for one selected type.
    ///
    /// @param type selected type
    /// @return initial form value
    private static String defaultValue(TagType<?> type) {
        TagType<?> selected = Objects.requireNonNull(type, "type");
        if (selected == TagType.BYTE
                || selected == TagType.SHORT
                || selected == TagType.INT
                || selected == TagType.LONG
                || selected == TagType.FLOAT
                || selected == TagType.DOUBLE) {
            return "0";
        }
        return "";
    }

    /// Performs terminal Swing teardown on the EDT.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        stateSubscription.close();
        tree.removeTreeSelectionListener(treeSelectionListener);
        tree.removeTreeWillExpandListener(rootExpansionListener);
        tree.removeMouseListener(treePopupMouseListener);
        editorTabs.removeChangeListener(editorTabListener);
        invalidateSnbtLoad();
        setTransferHandler(null);
        tree.setModel(emptyTreeModel());
        renderedDocument = null;
        renderedEditorRevision = -1L;
        if (iconLoad != null) {
            iconLoad.cancel(true);
        }
        controller.close();
    }

    /// Starts one background classpath read and injects decoded icons on the EDT.
    ///
    /// @param executor caller-owned background executor
    /// @return started future, or `null` when submission is rejected
    private @Nullable CompletableFuture<@Unmodifiable Map<NBTNodeType, Icon>> preloadTreeIcons(
            Executor executor) {
        try {
            CompletableFuture<@Unmodifiable Map<NBTNodeType, Icon>> future = CompletableFuture.supplyAsync(
                    NBTTreeCellRenderer::loadIcons,
                    Objects.requireNonNull(executor, "executor"));
            future.whenComplete((
                    @Nullable @Unmodifiable Map<NBTNodeType, Icon> loaded,
                    @Nullable Throwable failure) -> EdtDispatcher.execute(() -> {
                        if (!closed.get() && failure == null && loaded != null) {
                            treeCellRenderer.installIcons(loaded);
                            tree.repaint();
                        }
                    }));
            return future;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /// Configures one fixed-size familiar-symbol icon command.
    ///
    /// @param button target button
    /// @param name stable UI-audit name
    /// @param iconResource classpath SVG resource
    /// @param tooltip localized accessible text
    /// @param action command action
    private static void configureIconButton(
            JButton button,
            String name,
            String iconResource,
            String tooltip,
            Runnable action) {
        JButton target = Objects.requireNonNull(button, "button");
        target.setName(Objects.requireNonNull(name, "name"));
        target.setIcon(themeIcon(iconResource));
        configureToolButton(target, tooltip, action);
    }

    /// Configures one fixed-size text-symbol tool button.
    ///
    /// @param button target button
    /// @param name stable UI-audit name
    /// @param symbol familiar symbol
    /// @param tooltip localized accessible text
    /// @param action command action
    private static void configureSymbolButton(
            JButton button,
            String name,
            String symbol,
            String tooltip,
            Runnable action) {
        JButton target = Objects.requireNonNull(button, "button");
        target.setName(Objects.requireNonNull(name, "name"));
        target.setText(Objects.requireNonNull(symbol, "symbol"));
        configureToolButton(target, tooltip, action);
    }

    /// Applies shared accessible behavior to one tool button.
    ///
    /// @param button target button
    /// @param tooltip localized accessible text
    /// @param action command action
    private static void configureToolButton(JButton button, String tooltip, Runnable action) {
        String text = Objects.requireNonNull(tooltip, "tooltip");
        button.setToolTipText(text);
        button.getAccessibleContext().setAccessibleName(text);
        button.getAccessibleContext().setAccessibleDescription(text);
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.addActionListener(event -> Objects.requireNonNull(action, "action").run());
    }

    /// Creates one context-menu command.
    ///
    /// @param text localized command
    /// @param iconResource optional classpath icon
    /// @param action command action
    /// @return configured menu item
    private static JMenuItem menuItem(String text, @Nullable String iconResource, Runnable action) {
        JMenuItem item = new JMenuItem(Objects.requireNonNull(text, "text"));
        if (iconResource != null) {
            item.setIcon(themeIcon(iconResource));
        }
        item.addActionListener(event -> Objects.requireNonNull(action, "action").run());
        return item;
    }

    /// Installs one keyboard command on a component's focused ancestry.
    ///
    /// @param component binding owner
    /// @param key action-map key
    /// @param stroke keyboard gesture
    /// @param action command action
    private static void bind(JComponent component, String key, KeyStroke stroke, Runnable action) {
        JComponent target = Objects.requireNonNull(component, "component");
        String actionKey = Objects.requireNonNull(key, "key");
        target.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(Objects.requireNonNull(stroke, "stroke"), actionKey);
        target.getActionMap().put(actionKey, new RunnableAction(action));
    }

    /// Creates one detail label associated with its editor component.
    ///
    /// @param text localized label
    /// @param component associated component
    /// @return configured label
    private static JLabel detailLabel(String text, JComponent component) {
        JLabel label = new JLabel(Objects.requireNonNull(text, "text"));
        label.setLabelFor(Objects.requireNonNull(component, "component"));
        return label;
    }

    /// Creates one stable read-only detail field.
    ///
    /// @param name UI-audit component name
    /// @return configured field
    private static JTextField readOnlyField(String name) {
        JTextField field = new JTextField();
        field.setName(Objects.requireNonNull(name, "name"));
        field.setEditable(false);
        return field;
    }

    /// Creates an empty tree model without a synthetic placeholder node.
    ///
    /// @return empty model
    private static TreeModel emptyTreeModel() {
        return new DefaultTreeModel(null);
    }

    /// Creates a bundled SVG icon that follows component foreground.
    ///
    /// @param iconResource classpath SVG resource
    /// @return theme-aware icon
    private static FlatSVGIcon themeIcon(String iconResource) {
        FlatSVGIcon icon = new FlatSVGIcon(Objects.requireNonNull(iconResource, "iconResource"), 18, 18);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(NBTEditorPanel::resolveIconColor));
        return icon;
    }

    /// Resolves icon color from its owner and current theme.
    ///
    /// @param component owning component, or `null`
    /// @param originalColor authored fallback
    /// @return current foreground or fallback
    private static Color resolveIconColor(@Nullable Component component, Color originalColor) {
        Color authored = Objects.requireNonNull(originalColor, "originalColor");
        @Nullable Color foreground = component == null ? null : component.getForeground();
        if (foreground != null) {
            return foreground;
        }
        @Nullable Color themeForeground = UIManager.getColor("Button.foreground");
        return themeForeground == null ? authored : themeForeground;
    }

    /// Decodes Java file-list transfers without filesystem access.
    ///
    /// @param transferable transfer payload
    /// @return immutable paths, or `null` when unsupported
    private static @Nullable @Unmodifiable List<Path> transferredPaths(Transferable transferable) {
        Transferable payload = Objects.requireNonNull(transferable, "transferable");
        if (!payload.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            return null;
        }
        try {
            Object transferData = payload.getTransferData(DataFlavor.javaFileListFlavor);
            if (!(transferData instanceof List<?> files)) {
                return null;
            }
            List<Path> paths = new ArrayList<>(files.size());
            for (Object value : files) {
                if (!(value instanceof File file)) {
                    return null;
                }
                paths.add(file.toPath().toAbsolutePath().normalize());
            }
            return List.copyOf(paths);
        } catch (UnsupportedFlavorException | IOException | RuntimeException failure) {
            return null;
        }
    }

    /// Parent-owned navigation operation emitted by this routable page.
    @NotNullByDefault
    @FunctionalInterface
    public interface Listener {
        /// Requests removal of this page and return to its parent route.
        void closeRequested();
    }

    /// Immutable constraints for one Add or Paste destination.
    ///
    /// @param parent destination parent row
    /// @param index insertion index
    /// @param types permitted tag types
    /// @param nameRequired whether a non-empty Compound name is required
    @NotNullByDefault
    private record InsertionTarget(
            NBTEditorTreeNode parent,
            int index,
            @Unmodifiable List<TagType<?>> types,
            boolean nameRequired) {
        /// Validates and snapshots insertion constraints.
        private InsertionTarget {
            Objects.requireNonNull(parent, "parent");
            types = List.copyOf(Objects.requireNonNull(types, "types"));
            if (index < 0 || index > parent.childCount() || types.isEmpty()) {
                throw new IllegalArgumentException("Invalid insertion target");
            }
        }
    }

    /// Swing action that delegates to one prevalidated command.
    @NotNullByDefault
    private static final class RunnableAction extends AbstractAction {
        /// Serialization identifier for the Swing action superclass.
        @Serial
        private static final long serialVersionUID = 1L;

        /// Command executed on the EDT.
        private final Runnable command;

        /// Creates one action.
        ///
        /// @param command command to execute
        private RunnableAction(Runnable command) {
            this.command = Objects.requireNonNull(command, "command");
        }

        /// Runs the command.
        ///
        /// @param event Swing action event
        @Override
        public void actionPerformed(ActionEvent event) {
            Objects.requireNonNull(event, "event");
            command.run();
        }
    }

    /// Selects a popup-trigger row before Swing opens its context menu.
    @NotNullByDefault
    private final class TreePopupMouseListener extends MouseAdapter {
        /// Selects the row under a platform popup trigger.
        ///
        /// @param event mouse event
        @Override
        public void mousePressed(MouseEvent event) {
            selectPopupRow(event);
        }

        /// Selects the row under a platform popup trigger.
        ///
        /// @param event mouse event
        @Override
        public void mouseReleased(MouseEvent event) {
            selectPopupRow(event);
        }

        /// Selects the event row when it triggers a popup.
        ///
        /// @param event mouse event
        private void selectPopupRow(MouseEvent event) {
            MouseEvent mouseEvent = Objects.requireNonNull(event, "event");
            if (!mouseEvent.isPopupTrigger()) {
                return;
            }
            @Nullable TreePath path = tree.getPathForLocation(mouseEvent.getX(), mouseEvent.getY());
            if (path != null) {
                tree.setSelectionPath(path);
            }
        }
    }

    /// Activates deferred root children exactly when expansion begins.
    @NotNullByDefault
    private final class RootExpansionListener implements TreeWillExpandListener {
        /// Reveals root children before Swing enumerates the expanding path.
        ///
        /// @param event pending expansion event
        /// @throws ExpandVetoException never thrown
        @Override
        public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
            TreeExpansionEvent expansion = Objects.requireNonNull(event, "event");
            @Nullable Object component = expansion.getPath().getLastPathComponent();
            JTree source = (JTree) expansion.getSource();
            if (!installingTreeModel
                    && source.getModel() instanceof NBTLazyTreeModel model
                    && component == model.getRoot()) {
                model.revealRootChildren();
            }
        }

        /// Accepts collapse without changing model visibility.
        ///
        /// @param event pending collapse event
        /// @throws ExpandVetoException never thrown
        @Override
        public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
            Objects.requireNonNull(event, "event");
        }
    }

    /// Swing transfer adapter that forwards only decoded immutable paths.
    @NotNullByDefault
    private final class NBTTransferHandler extends TransferHandler {
        /// Serialization identifier for the Swing transfer superclass.
        @Serial
        private static final long serialVersionUID = 1L;

        /// Reports whether file-list input can be considered.
        ///
        /// @param support Swing transfer context
        /// @return whether decoding may proceed
        @Override
        public boolean canImport(TransferSupport support) {
            TransferSupport transferSupport = Objects.requireNonNull(support, "support");
            return ShellFileDropHandler.canImportAncestorText(transferSupport)
                    || (!closed.get()
                    && !controller.snapshot().busy()
                    && transferSupport.isDataFlavorSupported(DataFlavor.javaFileListFlavor));
        }

        /// Decodes and forwards one file-list transfer.
        ///
        /// @param support Swing transfer context
        /// @return whether one source was accepted
        @Override
        public boolean importData(TransferSupport support) {
            TransferSupport transferSupport = Objects.requireNonNull(support, "support");
            if (ShellFileDropHandler.importAncestorText(transferSupport)) {
                return true;
            }
            if (!canImport(transferSupport)) {
                return false;
            }
            @Nullable @Unmodifiable List<Path> paths = transferredPaths(transferSupport.getTransferable());
            return paths != null && openDroppedPaths(paths);
        }
    }
}
