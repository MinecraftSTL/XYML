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
package space.minecraftstl.xyml.ui.swing.page.instances.management.export;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.game.export.ModpackExportFileSelection;
import space.minecraftstl.xyml.game.export.ModpackExportFormat;
import space.minecraftstl.xyml.game.export.ModpackExportMetadata;
import space.minecraftstl.xyml.game.export.ModpackExportRequest;
import space.minecraftstl.xyml.game.export.ModpackExportTaskFactory;
import space.minecraftstl.xyml.game.export.RepositoryModpackExportTaskFactory;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.task.presentation.TaskExecutorPresentationModel;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressHostPanel;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Exports one managed instance into an offline modpack archive without using JavaFX or remote providers.
///
/// The page waits until its host selects the tab before listing the run directory. Directories load only when
/// expanded, symbolic links are deliberately omitted, and no file is selected by default. The existing core
/// exporter receives an immutable request and owns archive creation; this panel only owns native Swing input,
/// progress presentation, and lifecycle cleanup.
@NotNullByDefault
public final class ModpackExportPanel extends JPanel implements AutoCloseable {
    /// Stable repository identifier for the instance being exported.
    private final String instanceId;

    /// Resolves the effective instance run directory only when the user opens this page.
    private final RunDirectoryResolver runDirectoryResolver;

    /// Creates one stopped offline export task for a validated immutable request.
    private final ModpackExportTaskFactory exportTaskFactory;

    /// Native save-file chooser boundary, injectable so focused Swing tests never open dialogs.
    private final OutputFileChooser outputFileChooser;

    /// Caller-owned background executor used exclusively for shallow directory enumeration.
    private final Executor directoryExecutor;

    /// Empty invisible root shown before the first lazy directory enumeration completes.
    private final DefaultMutableTreeNode emptyRoot = new DefaultMutableTreeNode();

    /// Tree model retaining only loaded directory nodes rather than recursively indexing the run directory.
    private final DefaultTreeModel fileTreeModel = new DefaultTreeModel(emptyRoot);

    /// Native sparse-on-demand file and directory selection surface.
    private final JTree fileTree = new JTree(fileTreeModel);

    /// Lets the user select an archive format with its conventional suffix.
    private final JComboBox<ModpackExportFormat> formatBox = new JComboBox<>(ModpackExportFormat.values());

    /// Editable exported manifest name, seeded from the actual instance identifier.
    private final JTextField nameField = new JTextField();

    /// Required user-authored manifest version with no invented default value.
    private final JTextField versionField = new JTextField();

    /// Optional exported author metadata.
    private final JTextField authorField = new JTextField();

    /// Optional exported description metadata.
    private final JTextArea descriptionArea = new JTextArea(4, 24);

    /// Whether compatible manifests should request an updated pack from a configured server.
    private final JCheckBox forceUpdateCheck = new JCheckBox(i18n("modpack.wizard.step.initialization.force_update"));

    /// Optional minimum heap size represented as zero when no minimum is requested.
    private final JSpinner minimumMemorySpinner = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 128));

    /// Read-only display of the user-chosen archive target, or empty before selection.
    private final JTextField outputField = new JTextField();

    /// Opens the native save-file chooser for an archive target.
    private final JButton chooseOutputButton = new JButton();

    /// Starts a new export after visible fields and file selection form a complete request.
    private final JButton exportButton = new JButton(i18n("button.export"));

    /// Explicitly discards loaded file-tree state and reads only the run-directory root again.
    private final JButton refreshFilesButton = new JButton();

    /// Displays concise request validation and terminal task feedback.
    private final JLabel statusLabel = new JLabel();

    /// Owns exactly one current export progress presentation.
    private final TaskProgressHostPanel progressHost;

    /// Receives file-tree expansion events and schedules child enumeration away from the EDT.
    private final TreeWillExpandListener expansionListener = new FileTreeExpansionListener();

    /// Reconciles export eligibility after the user changes selected files or directories.
    private final TreeSelectionListener selectionListener = this::selectionChanged;

    /// Reconciles export eligibility after the user changes required manifest fields.
    private final DocumentListener metadataListener = new MetadataDocumentListener(this::updateControls);

    /// Prevents later file enumeration or task completion callbacks from mutating closed components.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Currently mounted real root node, or null before activation and while a root reload is pending.
    private @Nullable ExportTreeNode loadedRoot;

    /// Archive target selected through the native save chooser, or null until one is chosen.
    private @Nullable Path outputFile;

    /// Executor currently running an archive export, or null while the form is editable.
    private @Nullable TaskExecutor activeExecutor;

    /// Presentation model currently bound to the progress host, or null before the first export.
    private @Nullable TaskExecutorPresentationModel activePresentation;

    /// Listener registration associated with the active executor, or null while idle.
    private @Nullable Subscription activeCompletionSubscription;

    /// Advances whenever a root reload replaces the tree and invalidates older directory results.
    private long treeRevision;

    /// Indicates that the root listing is in flight and must not accept stale selection interaction.
    private boolean rootLoading;

    /// Records whether the host has selected this page at least once.
    private boolean activated;

    /// Creates a production export page backed by the selected XYML repository.
    ///
    /// @param repository repository containing the source instance
    /// @param instanceId stable non-blank source instance identifier
    /// @param directoryExecutor caller-owned executor for lazy local directory enumeration
    /// @param taskProgressStrings localized task-progress labels
    /// @param animator optional shared motion-aware progress animator
    /// @param progressAnimationDuration non-negative determinate progress animation duration
    public ModpackExportPanel(
            XYMLGameRepository repository,
            String instanceId,
            Executor directoryExecutor,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        this(
                Objects.requireNonNull(repository, "repository")::getRunDirectory,
                instanceId,
                new RepositoryModpackExportTaskFactory(repository),
                new NativeOutputFileChooser(),
                directoryExecutor,
                taskProgressStrings,
                animator,
                progressAnimationDuration);
    }

    /// Creates a page with explicit filesystem and task seams for deterministic Swing verification.
    ///
    /// @param runDirectoryResolver deferred effective run-directory resolver
    /// @param instanceId stable non-blank source instance identifier
    /// @param exportTaskFactory offline export task factory
    /// @param outputFileChooser native output-file chooser boundary
    /// @param directoryExecutor caller-owned executor for lazy directory enumeration
    /// @param taskProgressStrings localized task-progress labels
    /// @param animator optional shared motion-aware progress animator
    /// @param progressAnimationDuration non-negative determinate progress animation duration
    ModpackExportPanel(
            RunDirectoryResolver runDirectoryResolver,
            String instanceId,
            ModpackExportTaskFactory exportTaskFactory,
            OutputFileChooser outputFileChooser,
            Executor directoryExecutor,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.runDirectoryResolver = Objects.requireNonNull(runDirectoryResolver, "runDirectoryResolver");
        this.instanceId = requireNonBlank(instanceId, "instanceId");
        this.exportTaskFactory = Objects.requireNonNull(exportTaskFactory, "exportTaskFactory");
        this.outputFileChooser = Objects.requireNonNull(outputFileChooser, "outputFileChooser");
        this.directoryExecutor = Objects.requireNonNull(directoryExecutor, "directoryExecutor");
        progressHost = new TaskProgressHostPanel(
                Objects.requireNonNull(taskProgressStrings, "taskProgressStrings"),
                animator,
                Objects.requireNonNull(progressAnimationDuration, "progressAnimationDuration"));
        if (progressAnimationDuration.isNegative()) {
            throw new IllegalArgumentException("progressAnimationDuration must not be negative");
        }

        configureComponents();
        nameField.setText(this.instanceId);
        setStatus(i18n("modpack.wizard.step.2.title"));
        updateControls();
    }

    /// Returns the visible tab title used by the instance-management host.
    ///
    /// @return localized modpack export title
    public String title() {
        return i18n("modpack.export");
    }

    /// Starts the first root-only run-directory enumeration after this tab becomes visible.
    ///
    /// Repeated activation does not rescan the directory. The explicit refresh command remains the only
    /// way to invalidate already loaded tree data.
    public void activate() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || activated) {
            return;
        }
        activated = true;
        refreshFileTree();
    }

    /// Cancels a running export, closes its presentation, and rejects all later asynchronous callbacks.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        EdtDispatcher.executeAndWait(this::closeOnEventDispatchThread);
    }

    /// Builds the heading, local file tree, metadata form, and task-progress footer.
    private void configureComponents() {
        setName("modpackExportPage");
        setOpaque(false);
        add(createHeading(), BorderLayout.NORTH);
        add(createContent(), BorderLayout.CENTER);
        add(createFooter(), BorderLayout.SOUTH);

        formatBox.setName("modpackExportFormat");
        formatBox.setRenderer(new ExportFormatRenderer());
        formatBox.addActionListener(event -> formatChanged());
        nameField.setName("modpackExportName");
        nameField.getDocument().addDocumentListener(metadataListener);
        versionField.setName("modpackExportVersion");
        versionField.getDocument().addDocumentListener(metadataListener);
        authorField.setName("modpackExportAuthor");
        descriptionArea.setName("modpackExportDescription");
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        outputField.setName("modpackExportOutput");
        outputField.setEditable(false);
        forceUpdateCheck.setName("modpackExportForceUpdate");
        minimumMemorySpinner.setName("modpackExportMinimumMemory");
        exportButton.setName("modpackExportStart");
        exportButton.addActionListener(event -> startExport());
        configureIconButton(
                refreshFilesButton,
                "modpackExportRefreshFiles",
                "assets/swing/icons/refresh.svg",
                i18n("button.refresh"),
                this::refreshFileTree);
        configureIconButton(
                chooseOutputButton,
                "modpackExportChooseOutput",
                "assets/swing/icons/folder-open.svg",
                i18n("modpack.wizard.step.initialization.save"),
                this::chooseOutput);

        fileTree.setName("modpackExportFiles");
        fileTree.setRootVisible(false);
        fileTree.setShowsRootHandles(true);
        fileTree.getSelectionModel().setSelectionMode(
                javax.swing.tree.TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        fileTree.addTreeWillExpandListener(expansionListener);
        fileTree.addTreeSelectionListener(selectionListener);
    }

    /// Creates the unframed title row and fixed-size refresh command.
    ///
    /// @return configured heading component
    private JComponent createHeading() {
        JPanel heading = new JPanel(new MigLayout(
                "insets 12 16 8 16, fillx",
                "[grow,fill][]",
                "[40!]"));
        heading.setOpaque(false);
        JLabel title = new JLabel(title());
        title.setName("modpackExportTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26.0F));
        heading.add(title, "growx");
        heading.add(refreshFilesButton, "w 40!, h 40!");
        return heading;
    }

    /// Creates a stable split between lazy file selection and manifest/output configuration.
    ///
    /// @return configured main content component
    private JComponent createContent() {
        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                createFileSelectionSurface(),
                createMetadataSurface());
        split.setName("modpackExportSplit");
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);
        split.setResizeWeight(0.52D);
        split.setDividerLocation(0.52D);
        return split;
    }

    /// Creates the native multi-selection tree with no eager recursive traversal.
    ///
    /// @return configured file-selection surface
    private JComponent createFileSelectionSurface() {
        JPanel files = new JPanel(new BorderLayout(0, 8));
        files.setBorder(BorderFactory.createEmptyBorder(8, 16, 12, 8));
        files.setOpaque(false);
        JLabel heading = new JLabel(i18n("modpack.wizard.step.2"));
        heading.setName("modpackExportFilesTitle");
        files.add(heading, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(fileTree);
        scroll.setName("modpackExportFilesScroll");
        scroll.setBorder(BorderFactory.createEmptyBorder());
        files.add(scroll, BorderLayout.CENTER);
        return files;
    }

    /// Creates the format, common metadata, and output-file fields.
    ///
    /// @return configured metadata surface
    private JComponent createMetadataSurface() {
        JPanel metadata = new JPanel(new MigLayout(
                "insets 8 16 12 12, fill, wrap 2",
                "[120!][grow,fill]",
                "[][][][][80!][][][][]"));
        metadata.setName("modpackExportMetadata");
        metadata.setOpaque(false);
        addLabeledField(metadata, i18n("modpack.wizard.step.3"), formatBox, "h 40!");
        addLabeledField(metadata, i18n("modpack.name"), nameField, "h 40!");
        addLabeledField(metadata, i18n("archive.version"), versionField, "h 40!");
        addLabeledField(metadata, i18n("about.author"), authorField, "h 40!");
        addLabeledField(
                metadata,
                i18n("settings.memory.lower_bound") + " (MiB)",
                minimumMemorySpinner,
                "h 40!");
        metadata.add(new JLabel(i18n("modpack.description")));
        JScrollPane descriptionScroll = new JScrollPane(descriptionArea);
        descriptionScroll.setName("modpackExportDescriptionScroll");
        metadata.add(descriptionScroll, "grow, h 84!");
        metadata.add(new JLabel(""));
        metadata.add(forceUpdateCheck, "growx");
        metadata.add(new JLabel(i18n("modpack.wizard.step.initialization.save")));
        JPanel outputRow = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", "[40!]"));
        outputRow.setOpaque(false);
        outputRow.add(outputField, "growx, h 40!");
        outputRow.add(chooseOutputButton, "w 40!, h 40!");
        metadata.add(outputRow, "growx");
        return metadata;
    }

    /// Adds one label and reusable form control row to the metadata grid.
    ///
    /// @param panel metadata grid receiving the row
    /// @param labelText visible label text
    /// @param field input component paired with the label
    /// @param constraints MigLayout constraints for the input component
    private static void addLabeledField(
            JPanel panel,
            String labelText,
            JComponent field,
            String constraints) {
        panel.add(new JLabel(Objects.requireNonNull(labelText, "labelText")));
        panel.add(Objects.requireNonNull(field, "field"), Objects.requireNonNull(constraints, "constraints"));
    }

    /// Creates concise request feedback and a task-owned progress surface.
    ///
    /// @return configured footer component
    private JComponent createFooter() {
        JPanel footer = new JPanel(new MigLayout(
                "insets 4 16 12 16, fillx, wrap 1",
                "[grow,fill][]",
                "[]8[grow,fill]"));
        footer.setOpaque(false);
        statusLabel.setName("modpackExportStatus");
        footer.add(statusLabel, "growx");
        exportButton.setPreferredSize(new Dimension(128, 40));
        footer.add(exportButton, "right, h 40!, wrap");
        progressHost.setName("modpackExportProgress");
        footer.add(progressHost, "span 2, growx");
        return footer;
    }

    /// Starts a fresh root-only local file enumeration after an explicit refresh or first activation.
    private void refreshFileTree() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || activeExecutor != null || rootLoading) {
            return;
        }
        treeRevision++;
        long requestedRevision = treeRevision;
        rootLoading = true;
        loadedRoot = null;
        emptyRoot.removeAllChildren();
        fileTreeModel.setRoot(emptyRoot);
        fileTreeModel.reload();
        fileTree.clearSelection();
        setStatus(i18n("modpack.scan"));
        updateControls();
        try {
            directoryExecutor.execute(() -> loadRootDirectory(requestedRevision));
        } catch (RuntimeException schedulingFailure) {
            rootLoadFailed(requestedRevision, schedulingFailure);
        }
    }

    /// Resolves and lists only the run-directory root away from the Swing event dispatch thread.
    ///
    /// @param requestedRevision root-tree revision captured before scheduling
    private void loadRootDirectory(long requestedRevision) {
        if (closed.get()) {
            return;
        }
        try {
            Path rootDirectory = Objects.requireNonNull(
                            runDirectoryResolver.resolve(instanceId),
                            "run directory")
                    .toAbsolutePath()
                    .normalize();
            if (closed.get()) {
                return;
            }
            @Unmodifiable List<FileTreeEntry> entries = readDirectory(rootDirectory, rootDirectory);
            if (closed.get()) {
                return;
            }
            SwingUiDispatcher.INSTANCE.dispatchOrRun(
                    () -> rootLoaded(requestedRevision, rootDirectory, entries));
        } catch (IOException | RuntimeException failure) {
            SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> rootLoadFailed(requestedRevision, failure));
        }
    }

    /// Installs a freshly loaded run-directory root when the request remains current.
    ///
    /// @param requestedRevision root-tree revision captured before scheduling
    /// @param rootDirectory normalized effective run directory
    /// @param entries shallow child entries read from the directory
    private void rootLoaded(
            long requestedRevision,
            Path rootDirectory,
            @Unmodifiable List<FileTreeEntry> entries) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || requestedRevision != treeRevision) {
            return;
        }
        ExportTreeNode root = ExportTreeNode.root(rootDirectory);
        root.loaded = true;
        for (FileTreeEntry entry : entries) {
            root.add(ExportTreeNode.fromEntry(entry));
        }
        loadedRoot = root;
        rootLoading = false;
        fileTreeModel.setRoot(root);
        fileTreeModel.reload();
        fileTree.expandPath(new TreePath(root.getPath()));
        setStatus(i18n("modpack.wizard.step.2.title"));
        updateControls();
    }

    /// Restores an editable empty tree after a root-directory enumeration failure.
    ///
    /// @param requestedRevision root-tree revision captured before scheduling
    /// @param failure original filesystem or scheduling failure
    private void rootLoadFailed(long requestedRevision, Throwable failure) {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            EdtDispatcher.requireEventDispatchThread();
            if (closed.get() || requestedRevision != treeRevision) {
                return;
            }
            rootLoading = false;
            setStatus(failureStatus(failure));
            updateControls();
        });
    }

    /// Schedules one unexpanded directory's direct children without recursively scanning descendants.
    ///
    /// @param node selected directory node
    private void loadChildren(ExportTreeNode node) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || !node.directory || node.loaded || node.loading || activeExecutor != null) {
            return;
        }
        @Nullable ExportTreeNode root = loadedRoot;
        if (root == null || node.getRoot() != root) {
            return;
        }
        node.loading = true;
        fileTreeModel.nodeChanged(node);
        long requestedRevision = treeRevision;
        Path rootDirectory = root.path;
        try {
            directoryExecutor.execute(() -> loadDirectoryChildren(requestedRevision, rootDirectory, node));
        } catch (RuntimeException schedulingFailure) {
            childrenLoadFailed(requestedRevision, node, schedulingFailure);
        }
    }

    /// Enumerates one expanded directory's immediate child entries away from the EDT.
    ///
    /// @param requestedRevision root-tree revision captured before scheduling
    /// @param rootDirectory normalized effective run directory
    /// @param node node whose children are being read
    private void loadDirectoryChildren(long requestedRevision, Path rootDirectory, ExportTreeNode node) {
        if (closed.get()) {
            return;
        }
        try {
            @Unmodifiable List<FileTreeEntry> entries = readDirectory(rootDirectory, node.path);
            if (closed.get()) {
                return;
            }
            SwingUiDispatcher.INSTANCE.dispatchOrRun(
                    () -> childrenLoaded(requestedRevision, node, entries));
        } catch (IOException | RuntimeException failure) {
            SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> childrenLoadFailed(requestedRevision, node, failure));
        }
    }

    /// Installs one directory's child nodes when no refresh replaced its root in the meantime.
    ///
    /// @param requestedRevision root-tree revision captured before scheduling
    /// @param node directory node receiving children
    /// @param entries direct child entries read from the directory
    private void childrenLoaded(
            long requestedRevision,
            ExportTreeNode node,
            @Unmodifiable List<FileTreeEntry> entries) {
        EdtDispatcher.requireEventDispatchThread();
        if (!isCurrentNode(requestedRevision, node)) {
            return;
        }
        node.removeAllChildren();
        for (FileTreeEntry entry : entries) {
            node.add(ExportTreeNode.fromEntry(entry));
        }
        node.loading = false;
        node.loaded = true;
        node.failed = false;
        fileTreeModel.nodeStructureChanged(node);
    }

    /// Marks one still-current directory node unreadable after a background enumeration failure.
    ///
    /// @param requestedRevision root-tree revision captured before scheduling
    /// @param node directory node whose read failed
    /// @param failure original filesystem or scheduling failure
    private void childrenLoadFailed(long requestedRevision, ExportTreeNode node, Throwable failure) {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            EdtDispatcher.requireEventDispatchThread();
            if (!isCurrentNode(requestedRevision, node)) {
                return;
            }
            node.loading = false;
            node.failed = true;
            fileTreeModel.nodeChanged(node);
            setStatus(failureStatus(failure));
            updateControls();
        });
    }

    /// Returns whether a node still belongs to the active root and revision.
    ///
    /// @param requestedRevision root-tree revision captured before scheduling
    /// @param node candidate active tree node
    /// @return true when the callback may mutate the node
    private boolean isCurrentNode(long requestedRevision, ExportTreeNode node) {
        @Nullable ExportTreeNode root = loadedRoot;
        return !closed.get()
                && requestedRevision == treeRevision
                && root != null
                && node.getRoot() == root;
    }

    /// Reads only direct regular files and real directories, omitting symbolic links from export selection.
    ///
    /// @param rootDirectory normalized effective run directory
    /// @param directory direct directory to enumerate
    /// @return stable directories-first immutable child entry list
    /// @throws IOException when the directory cannot be listed
    private static @Unmodifiable List<FileTreeEntry> readDirectory(
            Path rootDirectory,
            Path directory) throws IOException {
        Path normalizedRoot = Objects.requireNonNull(rootDirectory, "rootDirectory").toAbsolutePath().normalize();
        Path normalizedDirectory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (!normalizedDirectory.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Directory escapes the instance run directory: " + directory);
        }
        List<FileTreeEntry> entries = new ArrayList<>();
        try (var stream = Files.list(normalizedDirectory)) {
            stream.filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(path -> entries.add(new FileTreeEntry(
                            path,
                            toPortablePath(normalizedRoot.relativize(path)),
                            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))));
        }
        entries.sort(Comparator.comparing(FileTreeEntry::directory).reversed()
                .thenComparing(FileTreeEntry::displayName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(entries);
    }

    /// Opens a native save-file chooser and records an archive path with the selected format's suffix.
    private void chooseOutput() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || activeExecutor != null) {
            return;
        }
        ModpackExportFormat format = selectedFormat();
        Path suggested = outputFile == null ? Path.of(suggestedFileName(format)) : outputFile;
        @Nullable Path chosenOutput;
        try {
            chosenOutput = outputFileChooser.chooseOutput(this, format, suggested);
        } catch (RuntimeException chooserFailure) {
            LOG.warning("Failed to choose a local modpack export destination", chooserFailure);
            setStatus(failureStatus(chooserFailure));
            return;
        }
        if (chosenOutput == null) {
            return;
        }
        Path selectedOutput = ensureFormatSuffix(chosenOutput, format);
        outputFile = selectedOutput;
        outputField.setText(selectedOutput.toString());
        setStatus("");
        updateControls();
    }

    /// Rewrites an existing chosen target's conventional suffix after the user changes export format.
    private void formatChanged() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable Path selectedOutput = outputFile;
        if (selectedOutput != null && activeExecutor == null && !closed.get()) {
            Path adjustedOutput = ensureFormatSuffix(selectedOutput, selectedFormat());
            outputFile = adjustedOutput;
            outputField.setText(adjustedOutput.toString());
        }
        updateControls();
    }

    /// Validates visible request fields, creates an immutable core request, and starts its task presentation.
    private void startExport() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || activeExecutor != null) {
            return;
        }
        @Unmodifiable List<String> selectedPaths = selectedRelativePaths();
        if (selectedPaths.isEmpty()) {
            setStatus(i18n("modpack.wizard.step.2.title"));
            updateControls();
            return;
        }
        @Nullable Path selectedOutput = outputFile;
        if (selectedOutput == null) {
            setStatus(i18n("modpack.wizard.step.initialization.save"));
            updateControls();
            return;
        }

        final ModpackExportRequest request;
        try {
            request = new ModpackExportRequest(
                    selectedFormat(),
                    instanceId,
                    createMetadata(),
                    ModpackExportFileSelection.of(selectedPaths),
                    selectedOutput);
        } catch (IllegalArgumentException validationFailure) {
            setStatus(validationFailure.getMessage());
            updateControls();
            return;
        }

        releaseCompletedPresentation();
        final Task<Path> task;
        try {
            task = exportTaskFactory.create(request);
        } catch (RuntimeException preparationFailure) {
            LOG.warning("Failed to create a local modpack export task", preparationFailure);
            setStatus(failureStatus(preparationFailure));
            updateControls();
            return;
        }
        TaskExecutor executor = task.executor();
        TaskExecutorPresentationModel presentation = new TaskExecutorPresentationModel(
                executor,
                i18n("modpack.export"),
                i18n("modpack.wizard.step.2"));
        Subscription completionSubscription = executor.subscribeTaskListener(
                new ExportCompletionListener(executor, task));
        activeExecutor = executor;
        activePresentation = presentation;
        activeCompletionSubscription = completionSubscription;
        setStatus(i18n("modpack.export"));
        updateControls();
        try {
            progressHost.bind(presentation);
            executor.start();
        } catch (RuntimeException | Error startFailure) {
            LOG.warning("Failed to start a local modpack export task", startFailure);
            cleanupFailedTaskStart(presentation, completionSubscription);
            setStatus(failureStatus(startFailure));
            updateControls();
        }
    }

    /// Creates immutable common manifest metadata from the visible fields without network-derived defaults.
    ///
    /// @return validated immutable metadata for one export task
    private ModpackExportMetadata createMetadata() {
        String name = nameField.getText().trim();
        String version = versionField.getText().trim();
        Number minimumMemory = (Number) minimumMemorySpinner.getValue();
        return new ModpackExportMetadata(
                name,
                version,
                authorField.getText().trim(),
                descriptionArea.getText(),
                "",
                "",
                forceUpdateCheck.isSelected(),
                minimumMemory.intValue(),
                List.of(),
                "",
                "",
                "",
                List.of());
    }

    /// Publishes the terminal archive result and restores editable local controls.
    ///
    /// @param executor exact executor reaching a terminal state
    /// @param task exact task yielding the completed output path
    /// @param succeeded whether the complete export task succeeded
    private void exportCompleted(TaskExecutor executor, Task<Path> task, boolean succeeded) {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed.get() || activeExecutor != executor) {
                return;
            }
            unsubscribe(activeCompletionSubscription);
            activeCompletionSubscription = null;
            activeExecutor = null;
            if (succeeded) {
                @Nullable Path result = task.getResult();
                setStatus(result == null ? i18n("message.success") : i18n("message.success") + ": " + result);
            } else if (executor.isCancelled()) {
                setStatus(i18n("message.cancelled"));
            } else {
                @Nullable Exception failure = executor.getException();
                setStatus(failure == null ? i18n("message.failed") : failureStatus(failure));
            }
            updateControls();
        });
    }

    /// Clears a completed presentation before a later export creates a different task executor.
    private void releaseCompletedPresentation() {
        EdtDispatcher.requireEventDispatchThread();
        if (activeExecutor != null) {
            return;
        }
        @Nullable TaskExecutorPresentationModel previousPresentation = activePresentation;
        activePresentation = null;
        progressHost.clear();
        if (previousPresentation != null) {
            previousPresentation.close();
        }
    }

    /// Cleans up a task presentation that failed before executor startup reached its terminal listener.
    ///
    /// @param presentation presentation created for the failed start
    /// @param completionSubscription listener registration created for the failed start
    private void cleanupFailedTaskStart(
            TaskExecutorPresentationModel presentation,
            Subscription completionSubscription) {
        unsubscribe(completionSubscription);
        activeCompletionSubscription = null;
        activeExecutor = null;
        if (activePresentation == presentation) {
            activePresentation = null;
        }
        progressHost.clear();
        presentation.close();
    }

    /// Collects selected non-root tree paths and removes children already covered by selected directories.
    ///
    /// @return immutable portable relative paths accepted by the core selection value object
    private @Unmodifiable List<String> selectedRelativePaths() {
        @Nullable TreePath[] selectionPaths = fileTree.getSelectionPaths();
        if (selectionPaths == null || selectionPaths.length == 0) {
            return List.of();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (TreePath selectionPath : selectionPaths) {
            Object lastComponent = selectionPath.getLastPathComponent();
            if (lastComponent instanceof ExportTreeNode node && !node.relativePath.isEmpty()) {
                candidates.add(node.relativePath);
            }
        }
        List<String> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
        List<String> roots = new ArrayList<>();
        for (String candidate : ordered) {
            boolean covered = roots.stream().anyMatch(root -> candidate.startsWith(root + "/"));
            if (!covered) {
                roots.add(candidate);
            }
        }
        return List.copyOf(roots);
    }

    /// Reconciles all editable controls and the export command from current lifecycle and visible data.
    private void updateControls() {
        EdtDispatcher.requireEventDispatchThread();
        boolean inputsEnabled = !closed.get() && activeExecutor == null;
        formatBox.setEnabled(inputsEnabled);
        nameField.setEnabled(inputsEnabled);
        versionField.setEnabled(inputsEnabled);
        authorField.setEnabled(inputsEnabled);
        descriptionArea.setEnabled(inputsEnabled);
        forceUpdateCheck.setEnabled(inputsEnabled);
        minimumMemorySpinner.setEnabled(inputsEnabled);
        chooseOutputButton.setEnabled(inputsEnabled);
        refreshFilesButton.setEnabled(inputsEnabled && !rootLoading);
        fileTree.setEnabled(inputsEnabled && !rootLoading);
        exportButton.setEnabled(canStartExport());
    }

    /// Returns whether a complete visible request may begin a new export task.
    ///
    /// @return true when no task is running and required metadata, output, and selection are present
    private boolean canStartExport() {
        return !closed.get()
                && activeExecutor == null
                && !rootLoading
                && !nameField.getText().trim().isEmpty()
                && !versionField.getText().trim().isEmpty()
                && outputFile != null
                && !selectedRelativePaths().isEmpty();
    }

    /// Updates only export eligibility after selection changes without replacing status feedback.
    ///
    /// @param event selection transition delivered on the EDT
    private void selectionChanged(TreeSelectionEvent event) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(event, "event");
        if (closed.get()) {
            return;
        }
        updateControls();
    }

    /// Returns the currently selected non-null export format.
    ///
    /// @return selected archive format
    private ModpackExportFormat selectedFormat() {
        return Objects.requireNonNull(
                (ModpackExportFormat) formatBox.getSelectedItem(),
                "selected export format");
    }

    /// Builds an output filename suggestion without choosing an external directory automatically.
    ///
    /// @param format selected archive format
    /// @return portable archive filename suggestion
    private String suggestedFileName(ModpackExportFormat format) {
        String source = nameField.getText().trim();
        String sanitized = source.isEmpty() ? instanceId : source.replaceAll("[\\\\/:*?\"<>|]", "_");
        return sanitized + format.fileSuffix();
    }

    /// Applies one selected format's suffix while preserving the chosen directory.
    ///
    /// @param target candidate archive target
    /// @param format selected archive format
    /// @return normalized target ending in the selected format's suffix
    private static Path ensureFormatSuffix(Path target, ModpackExportFormat format) {
        Path normalized = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        Objects.requireNonNull(format, "format");
        Path fileName = Objects.requireNonNull(normalized.getFileName(), "target file name");
        String name = fileName.toString();
        String lowerName = name.toLowerCase(java.util.Locale.ROOT);
        if (lowerName.endsWith(".zip")) {
            name = name.substring(0, name.length() - ".zip".length());
        } else if (lowerName.endsWith(".mrpack")) {
            name = name.substring(0, name.length() - ".mrpack".length());
        }
        Path parent = normalized.getParent();
        Path withSuffix = Path.of(name + format.fileSuffix());
        return parent == null ? withSuffix : parent.resolve(withSuffix);
    }

    /// Converts one relative platform path to the portable separator representation used by exporter whitelists.
    ///
    /// @param path relative platform path
    /// @return slash-separated relative path
    private static String toPortablePath(Path path) {
        return Objects.requireNonNull(path, "path").toString().replace(File.separatorChar, '/');
    }

    /// Formats concise terminal feedback without exposing a full exception trace inside the page.
    ///
    /// @param failure original failure
    /// @return localized failed prefix followed by concise error detail
    private static String failureStatus(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        @Nullable String message = current.getMessage();
        String detail = message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
        return i18n("message.failed") + ": " + detail;
    }

    /// Updates the visible status label with non-null feedback text.
    ///
    /// @param status visible status text
    private void setStatus(String status) {
        statusLabel.setText(Objects.requireNonNull(status, "status"));
    }

    /// Removes one task-listener registration when one is owned.
    ///
    /// @param subscription registration, or null while no task is active
    private static void unsubscribe(@Nullable Subscription subscription) {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /// Closes tree and task resources on the EDT after the public close gate has been set.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable TaskExecutor executor = activeExecutor;
        if (executor != null) {
            executor.cancel();
        }
        unsubscribe(activeCompletionSubscription);
        activeCompletionSubscription = null;
        activeExecutor = null;
        @Nullable TaskExecutorPresentationModel presentation = activePresentation;
        activePresentation = null;
        progressHost.close();
        if (presentation != null) {
            presentation.close();
        }
        fileTree.removeTreeWillExpandListener(expansionListener);
        fileTree.removeTreeSelectionListener(selectionListener);
        nameField.getDocument().removeDocumentListener(metadataListener);
        versionField.getDocument().removeDocumentListener(metadataListener);
        formatBox.setEnabled(false);
        nameField.setEnabled(false);
        versionField.setEnabled(false);
        authorField.setEnabled(false);
        descriptionArea.setEnabled(false);
        forceUpdateCheck.setEnabled(false);
        minimumMemorySpinner.setEnabled(false);
        chooseOutputButton.setEnabled(false);
        refreshFilesButton.setEnabled(false);
        exportButton.setEnabled(false);
        fileTree.setEnabled(false);
        removeAll();
    }

    /// Configures one bundled icon-only command with a stable visual footprint and accessible name.
    ///
    /// @param button target button
    /// @param name deterministic component name
    /// @param iconPath bundled SVG icon path
    /// @param tooltip visible and assistive command description
    /// @param action EDT action callback
    private static void configureIconButton(
            JButton button,
            String name,
            String iconPath,
            String tooltip,
            Runnable action) {
        button.setName(Objects.requireNonNull(name, "name"));
        button.setText(null);
        button.setIcon(new FlatSVGIcon(Objects.requireNonNull(iconPath, "iconPath"), 18, 18));
        button.setToolTipText(Objects.requireNonNull(tooltip, "tooltip"));
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.setPreferredSize(new Dimension(40, 40));
        button.addActionListener(event -> Objects.requireNonNull(action, "action").run());
    }

    /// Requires one non-null, non-blank identifier.
    ///
    /// @param value candidate identifier
    /// @param fieldName diagnostic field name
    /// @return original non-blank identifier
    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    /// Maps one source instance identifier to its effective local run directory.
    @FunctionalInterface
    @NotNullByDefault
    interface RunDirectoryResolver {
        /// Resolves one existing or yet-to-be-created effective run directory.
        ///
        /// @param instanceId source instance identifier
        /// @return effective source run directory
        Path resolve(String instanceId);
    }

    /// Opens one native local save-file chooser without exposing it to task or filesystem code.
    @FunctionalInterface
    @NotNullByDefault
    interface OutputFileChooser {
        /// Returns a selected archive target, or null when the user cancels the native dialog.
        ///
        /// @param owner Swing owner for the native dialog
        /// @param format selected archive format
        /// @param suggestedFile initial local filename or path suggestion
        /// @return selected local archive target, or null after cancellation
        @Nullable Path chooseOutput(Component owner, ModpackExportFormat format, Path suggestedFile);
    }

    /// Production native chooser implementation used only after an explicit output-selection command.
    @NotNullByDefault
    private static final class NativeOutputFileChooser implements OutputFileChooser {
        /// Opens a local save dialog constrained to the selected archive suffix.
        ///
        /// @param owner Swing owner for the native dialog
        /// @param format selected archive format
        /// @param suggestedFile initial local filename or path suggestion
        /// @return selected local archive target, or null after cancellation
        @Override
        public @Nullable Path chooseOutput(Component owner, ModpackExportFormat format, Path suggestedFile) {
            ModpackExportFormat selectedFormat = Objects.requireNonNull(format, "format");
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle(i18n("modpack.wizard.step.initialization.save"));
            chooser.setDialogType(JFileChooser.SAVE_DIALOG);
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new FileNameExtensionFilter(
                    formatDisplayName(selectedFormat) + " (*" + selectedFormat.fileSuffix() + ")",
                    selectedFormat.fileSuffix().substring(1)));
            chooser.setSelectedFile(Objects.requireNonNull(suggestedFile, "suggestedFile").toFile());
            return chooser.showSaveDialog(Objects.requireNonNull(owner, "owner")) == JFileChooser.APPROVE_OPTION
                    ? chooser.getSelectedFile().toPath()
                    : null;
        }
    }

    /// Represents one direct local filesystem entry visible in the lazy export tree.
    ///
    /// @param path absolute local entry path
    /// @param relativePath portable non-root path relative to the run directory
    /// @param directory whether this entry may load child entries
    @NotNullByDefault
    private record FileTreeEntry(Path path, String relativePath, boolean directory) {
        /// Validates one immutable filesystem entry description.
        public FileTreeEntry {
            path = Objects.requireNonNull(path, "path");
            relativePath = requireNonBlank(relativePath, "relativePath");
        }

        /// Returns the platform display name derived from the local path.
        ///
        /// @return visible leaf or directory name
        String displayName() {
            return Objects.requireNonNull(path.getFileName(), "entry file name").toString();
        }
    }

    /// Stores one visible file or lazy directory node without carrying an eager recursive snapshot.
    @NotNullByDefault
    private static final class ExportTreeNode extends DefaultMutableTreeNode {
        /// Absolute local filesystem path represented by this node.
        private final Path path;

        /// Portable non-root path accepted by the core exporter selection object.
        private final String relativePath;

        /// Whether this node is a directory eligible for lazy child enumeration.
        private final boolean directory;

        /// Whether the immediate child set has been loaded successfully.
        private boolean loaded;

        /// Whether one background child enumeration is currently in flight.
        private boolean loading;

        /// Whether the latest child enumeration failed.
        private boolean failed;

        /// Creates one concrete filesystem tree node.
        ///
        /// @param path absolute local filesystem path
        /// @param relativePath portable non-root path, or empty for the invisible root
        /// @param directory whether this node may have lazy child entries
        /// @param displayName visible node label
        private ExportTreeNode(Path path, String relativePath, boolean directory, String displayName) {
            super(Objects.requireNonNull(displayName, "displayName"), directory);
            this.path = Objects.requireNonNull(path, "path");
            this.relativePath = Objects.requireNonNull(relativePath, "relativePath");
            this.directory = directory;
        }

        /// Creates the invisible run-directory root.
        ///
        /// @param directory effective normalized run directory
        /// @return new invisible root node
        static ExportTreeNode root(Path directory) {
            Path path = Objects.requireNonNull(directory, "directory");
            return new ExportTreeNode(path, "", true, path.toString());
        }

        /// Creates one visible child node from an immutable directory-entry description.
        ///
        /// @param entry direct local entry
        /// @return visible file or directory node
        static ExportTreeNode fromEntry(FileTreeEntry entry) {
            FileTreeEntry source = Objects.requireNonNull(entry, "entry");
            return new ExportTreeNode(
                    source.path(),
                    source.relativePath(),
                    source.directory(),
                    source.displayName());
        }

        /// Returns whether this entry is a terminal file rather than a lazily expandable directory.
        ///
        /// Swing otherwise treats an unloaded directory with zero materialized children as a leaf and never
        /// delivers the expansion event that starts its first child enumeration.
        ///
        /// @return `true` only for files
        @Override
        public boolean isLeaf() {
            return !directory;
        }

        /// Formats in-flight and failed state without replacing the underlying filesystem name.
        ///
        /// @return visible node label
        @Override
        public String toString() {
            String displayName = Objects.requireNonNull(getUserObject(), "user object").toString();
            if (loading) {
                return displayName + "...";
            }
            return failed ? displayName + " (unreadable)" : displayName;
        }
    }

    /// Converts format enum values into the existing localized format names where available.
    @NotNullByDefault
    private static final class ExportFormatRenderer extends DefaultListCellRenderer {
        /// Renders one format value using concise localized text.
        ///
        /// @param list owning format list
        /// @param value candidate enum value
        /// @param index row index, or minus one for the closed combo value
        /// @param isSelected whether the row is selected
        /// @param cellHasFocus whether the row owns focus
        /// @return configured list cell component
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                @Nullable Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            Component component = super.getListCellRendererComponent(
                    list,
                    value,
                    index,
                    isSelected,
                    cellHasFocus);
            if (value instanceof ModpackExportFormat format) {
                setText(formatDisplayName(format));
            }
            return component;
        }
    }

    /// Returns the existing localized visible name for one supported archive format.
    ///
    /// @param format supported export format
    /// @return localized concise format label
    private static String formatDisplayName(ModpackExportFormat format) {
        return switch (Objects.requireNonNull(format, "format")) {
            case MCBBS -> i18n("modpack.type.mcbbs");
            case MULTIMC -> i18n("modpack.type.multimc");
            case SERVER -> i18n("modpack.type.server");
            case MODRINTH -> i18n("modpack.type.modrinth");
        };
    }

    /// Routes directory expansion events to the lazy enumerator and never vetoes a user request.
    @NotNullByDefault
    private final class FileTreeExpansionListener implements TreeWillExpandListener {
        /// Starts direct-child enumeration for an expanded directory node.
        ///
        /// @param event expansion event
        /// @throws ExpandVetoException never thrown because loading does not veto expansion
        @Override
        public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
            Object component = Objects.requireNonNull(event, "event").getPath().getLastPathComponent();
            if (component instanceof ExportTreeNode node) {
                loadChildren(node);
            }
        }

        /// Leaves already loaded children intact when a user collapses a directory.
        ///
        /// @param event collapse event
        /// @throws ExpandVetoException never thrown because collapsing has no side effect
        @Override
        public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
            Objects.requireNonNull(event, "event");
        }
    }

    /// Reconciles export eligibility after required text-document transitions.
    @NotNullByDefault
    private static final class MetadataDocumentListener implements DocumentListener {
        /// Callback updating form controls on the Swing event dispatch thread.
        private final Runnable update;

        /// Creates one document listener.
        ///
        /// @param update form-reconciliation callback
        private MetadataDocumentListener(Runnable update) {
            this.update = Objects.requireNonNull(update, "update");
        }

        /// Reconciles inserted text.
        ///
        /// @param event document mutation
        @Override
        public void insertUpdate(DocumentEvent event) {
            update.run();
        }

        /// Reconciles removed text.
        ///
        /// @param event document mutation
        @Override
        public void removeUpdate(DocumentEvent event) {
            update.run();
        }

        /// Reconciles attribute-only document changes.
        ///
        /// @param event document mutation
        @Override
        public void changedUpdate(DocumentEvent event) {
            update.run();
        }
    }

    /// Routes one precise executor terminal transition back to the Swing event dispatch thread.
    @NotNullByDefault
    private final class ExportCompletionListener extends TaskListener {
        /// Exact task executor represented by this listener registration.
        private final TaskExecutor executor;

        /// Exact export task whose result path should become visible after completion.
        private final Task<Path> task;

        /// Creates one terminal task listener.
        ///
        /// @param executor exact task executor
        /// @param task exact export task
        private ExportCompletionListener(TaskExecutor executor, Task<Path> task) {
            this.executor = Objects.requireNonNull(executor, "executor");
            this.task = Objects.requireNonNull(task, "task");
        }

        /// Publishes terminal state only for the exact active executor.
        ///
        /// @param succeeded whether every task in the export chain succeeded
        /// @param sourceExecutor executor reporting the terminal state
        @Override
        public void onStop(boolean succeeded, TaskExecutor sourceExecutor) {
            if (sourceExecutor == executor) {
                exportCompleted(executor, task, succeeded);
            }
        }
    }
}
