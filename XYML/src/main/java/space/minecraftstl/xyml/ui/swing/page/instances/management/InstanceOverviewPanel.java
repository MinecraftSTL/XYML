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
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.InstanceIconType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Shows one managed instance's identity and resolved local directories with real file operations.
///
/// Directory discovery, repository refresh, image decoding, and icon persistence execute off the EDT.
/// The fixed-size preview and icon controls are enabled only when a persistent icon store is available.
@NotNullByDefault
public final class InstanceOverviewPanel extends JPanel implements AutoCloseable {
    /// Repository providing the instance's root and effective running directories.
    private final GameRepository repository;

    /// XYML repository providing persistent custom-icon operations, or `null` for a generic repository.
    private final @Nullable XYMLGameRepository xymlRepository;

    /// Persistent icon boundary, or `null` when the repository does not support instance icons.
    private final @Nullable InstanceIconStore iconStore;

    /// Stable identifier of the instance represented by this panel.
    private final String instanceId;

    /// Caller-owned executor for repository and filesystem work.
    private final Executor executor;

    /// Stable text bundle for visible labels and native interactions.
    private final InstanceOverviewStrings strings;

    /// Swing and desktop integration boundary.
    private final InstanceOverviewInteractions interactions;

    /// Visible immutable instance identifier.
    private final JLabel instanceNameValue = new JLabel();

    /// Fixed-size preview of the active custom or bundled instance icon.
    private final JLabel iconPreview = new IconPreviewLabel();

    /// Read-only field containing the version-root directory after loading.
    private final JTextField instanceRootValue = new JTextField();

    /// Read-only field containing the effective game directory after loading.
    private final JTextField gameDirectoryValue = new JTextField();

    /// Opens the resolved instance root with the platform desktop handler.
    private final JButton openInstanceDirectoryButton = new JButton();

    /// Opens the resolved effective game directory with the platform desktop handler.
    private final JButton openGameDirectoryButton = new JButton();

    /// Opens the restored list of well-known directories rooted at the effective game directory.
    private final JButton exploreDirectoriesButton = new JButton();

    /// Retained popup containing direct game, add-on, save, and diagnostic folder commands.
    private final JPopupMenu directoryMenu = new JPopupMenu();

    /// Refreshes the repository and recalculates displayed metadata.
    private final JButton refreshButton = new JButton();

    /// Opens the complete bundled and custom per-instance icon chooser.
    private final JButton chooseIconButton = new JButton();

    /// Deletes the stored custom per-instance image after confirmation.
    private final JButton deleteIconButton = new JButton();

    /// Prevents UI mutations after lifecycle cleanup begins.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Prevents concurrent refresh, desktop, and icon mutations from racing the UI state.
    private final AtomicBoolean operationPending = new AtomicBoolean();

    /// Last complete directory and icon availability result, or `null` while initial loading is pending.
    private @Nullable InstanceSnapshot snapshot;

    /// Creates a production overview with the normal Swing and AWT interaction implementation.
    ///
    /// @param repository repository containing the managed instance
    /// @param instanceId stable non-blank instance identifier
    /// @param executor caller-owned executor for repository and desktop operations
    public InstanceOverviewPanel(GameRepository repository, String instanceId, Executor executor) {
        this(
                repository,
                instanceId,
                executor,
                InstanceOverviewStrings.english(),
                new DefaultInstanceOverviewInteractions(InstanceOverviewStrings.english(), executor));
    }

    /// Creates an overview with explicit interaction boundaries for deterministic UI testing.
    ///
    /// @param repository repository containing the managed instance
    /// @param instanceId stable non-blank instance identifier
    /// @param executor caller-owned executor for repository operations
    /// @param strings stable visible text
    /// @param interactions Swing and desktop interaction boundary
    InstanceOverviewPanel(
            GameRepository repository,
            String instanceId,
            Executor executor,
            InstanceOverviewStrings strings,
            InstanceOverviewInteractions interactions) {
        this(
                repository,
                instanceId,
                executor,
                strings,
                interactions,
                createIconStore(repository, instanceId));
    }

    /// Creates an overview with an explicit icon store for focused persistence and preview testing.
    ///
    /// @param repository repository containing the managed instance
    /// @param instanceId stable non-blank instance identifier
    /// @param executor caller-owned executor for repository operations
    /// @param strings stable visible text
    /// @param interactions Swing and desktop interaction boundary
    /// @param iconStore persistent icon boundary, or `null` when unsupported
    InstanceOverviewPanel(
            GameRepository repository,
            String instanceId,
            Executor executor,
            InstanceOverviewStrings strings,
            InstanceOverviewInteractions interactions,
            @Nullable InstanceIconStore iconStore) {
        super(new MigLayout(
                "insets 16, fillx, wrap 3",
                "[][grow,fill][40!]",
                "[]12[]10[]10[]16[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.repository = Objects.requireNonNull(repository, "repository");
        this.xymlRepository = this.repository instanceof XYMLGameRepository candidate ? candidate : null;
        this.iconStore = iconStore;
        this.instanceId = requireNonBlank(instanceId, "instanceId");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        configureComponents();
        requestSnapshot(false);
    }

    /// Returns the visible overview tab title.
    ///
    /// @return non-blank tab title
    public String title() {
        return strings.title();
    }

    /// Returns the restored directory menu for focused integration checks.
    ///
    /// @return popup containing well-known game-directory commands
    JPopupMenu directoryMenu() {
        EdtDispatcher.requireEventDispatchThread();
        return directoryMenu;
    }

    /// Releases the panel's Swing controls and ignores late background completions.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        EdtDispatcher.executeAndWait(() -> {
            snapshot = null;
            instanceNameValue.setText("");
            iconPreview.setIcon(null);
            instanceRootValue.setText("");
            gameDirectoryValue.setText("");
            updateActionState();
            removeAll();
        });
    }

    /// Creates the information rows and fixed-size icon controls.
    private void configureComponents() {
        setName("instanceOverview");
        setOpaque(false);

        JLabel title = new JLabel(strings.title());
        title.setName("instanceOverviewTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18.0F));
        add(title, "span 2, growx");

        iconPreview.setName("instanceOverviewIconPreview");
        iconPreview.setHorizontalAlignment(JLabel.CENTER);
        iconPreview.setVerticalAlignment(JLabel.CENTER);
        iconPreview.getAccessibleContext().setAccessibleName(strings.iconPreviewAccessibleName());
        add(iconPreview, "w 40!, h 40!, spany 2");

        instanceNameValue.setName("instanceOverviewName");
        instanceNameValue.setText(instanceId);
        add(createLabel(strings.instanceNameLabel()), "aligny center");
        add(instanceNameValue, "growx");

        configureReadOnlyPathField(instanceRootValue, "instanceOverviewRootDirectory");
        configureCommand(
                openInstanceDirectoryButton,
                "instanceOverviewOpenInstanceDirectory",
                strings.openInstanceDirectoryTooltip(),
                "assets/swing/icons/folder-open.svg",
                this::openInstanceDirectory);
        add(createLabel(strings.instanceRootLabel()), "aligny center");
        add(instanceRootValue, "growx");
        add(openInstanceDirectoryButton, "w 40!, h 40!");

        configureReadOnlyPathField(gameDirectoryValue, "instanceOverviewGameDirectory");
        configureCommand(
                openGameDirectoryButton,
                "instanceOverviewOpenGameDirectory",
                strings.openGameDirectoryTooltip(),
                "assets/swing/icons/folder-open.svg",
                this::openGameDirectory);
        add(createLabel(strings.gameDirectoryLabel()), "aligny center");
        add(gameDirectoryValue, "growx");
        add(openGameDirectoryButton, "w 40!, h 40!");

        JPanel actions = new JPanel(new MigLayout("insets 0, gap 8", "[40!][40!][40!][40!]", "[40!]"));
        actions.setName("instanceOverviewActions");
        actions.setOpaque(false);
        configureCommand(
                refreshButton,
                "instanceOverviewRefresh",
                strings.refreshTooltip(),
                "assets/swing/icons/refresh.svg",
                this::refresh);
        configureCommand(
                chooseIconButton,
                "instanceOverviewChooseIcon",
                strings.chooseIconTooltip(),
                "assets/swing/icons/image.svg",
                this::chooseIcon);
        configureCommand(
                deleteIconButton,
                "instanceOverviewDeleteIcon",
                strings.deleteIconTooltip(),
                "assets/swing/icons/delete.svg",
                this::deleteIcon);
        configureCommand(
                exploreDirectoriesButton,
                "instanceOverviewExploreDirectories",
                i18n("settings.game.exploration"),
                "assets/swing/icons/folder-open.svg",
                this::showDirectoryMenu);
        actions.add(refreshButton, "w 40!, h 40!");
        actions.add(chooseIconButton, "w 40!, h 40!");
        actions.add(deleteIconButton, "w 40!, h 40!");
        actions.add(exploreDirectoriesButton, "w 40!, h 40!");
        add(actions, "span 3, right");

        configureDirectoryMenu();

        updateActionState();
    }

    /// Creates one semantic label for an overview value row.
    ///
    /// @param text non-blank visible label text
    /// @return configured label
    private static JLabel createLabel(String text) {
        JLabel label = new JLabel(requireNonBlank(text, "text"));
        label.setFont(label.getFont().deriveFont(Font.PLAIN));
        return label;
    }

    /// Makes a path field immutable while retaining native text selection and copy behavior.
    ///
    /// @param field target text field
    /// @param name stable component name
    private void configureReadOnlyPathField(JTextField field, String name) {
        field.setName(requireNonBlank(name, "name"));
        field.setEditable(false);
        field.setText(strings.loadingValue());
        field.setToolTipText(strings.loadingValue());
    }

    /// Configures one fixed-size icon command with accessible text and a bundled SVG icon.
    ///
    /// @param button target command button
    /// @param name stable component name
    /// @param tooltip visible and assistive command description
    /// @param iconResource classpath SVG resource
    /// @param action EDT command implementation
    private static void configureCommand(
            JButton button,
            String name,
            String tooltip,
            String iconResource,
            Runnable action) {
        button.setName(requireNonBlank(name, "name"));
        button.setText(null);
        button.setToolTipText(requireNonBlank(tooltip, "tooltip"));
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.setIcon(new FlatSVGIcon(requireNonBlank(iconResource, "iconResource"), 18, 18));
        button.addActionListener(event -> action.run());
    }

    /// Creates the production icon adapter only for repositories that expose persistent icon APIs.
    ///
    /// @param repository candidate repository
    /// @param instanceId target instance identifier
    /// @return repository-backed icon store, or `null` when icons are unsupported
    private static @Nullable InstanceIconStore createIconStore(GameRepository repository, String instanceId) {
        if (repository instanceof XYMLGameRepository xymlRepository) {
            return new RepositoryInstanceIconStore(xymlRepository, instanceId);
        }
        return null;
    }

    /// Starts a background metadata read, optionally refreshing the repository before reading paths.
    ///
    /// @param refreshRepository whether to rescan the repository first
    private void requestSnapshot(boolean refreshRepository) {
        EdtDispatcher.requireEventDispatchThread();
        if (!beginOperation()) {
            return;
        }
        try {
            executor.execute(() -> loadSnapshotOnExecutor(refreshRepository));
        } catch (RuntimeException failure) {
            snapshotLoadCompleted(null, failure);
        } catch (Error failure) {
            snapshotLoadCompleted(null, failure);
            throw failure;
        }
    }

    /// Reads repository state outside the EDT and posts its result back to the panel.
    ///
    /// @param refreshRepository whether to rescan the repository first
    private void loadSnapshotOnExecutor(boolean refreshRepository) {
        try {
            requireBackgroundThread();
            if (refreshRepository) {
                repository.refreshInstances();
                @Nullable XYMLGameRepository localXymlRepository = xymlRepository;
                if (localXymlRepository != null) {
                    localXymlRepository.refreshSelectedInstance();
                }
            }
            InstanceSnapshot loadedSnapshot = readSnapshot();
            EdtDispatcher.execute(() -> snapshotLoadCompleted(loadedSnapshot, null));
        } catch (RuntimeException | Error failure) {
            EdtDispatcher.execute(() -> snapshotLoadCompleted(null, failure));
        }
    }

    /// Resolves one complete metadata snapshot on the caller-owned background executor.
    ///
    /// @return resolved instance paths, persisted icon identity, and decoded 40-pixel preview
    private InstanceSnapshot readSnapshot() {
        Path instanceRoot = Objects.requireNonNull(repository.getVersionRoot(instanceId), "instance root")
                .toAbsolutePath()
                .normalize();
        Path gameDirectory = Objects.requireNonNull(repository.getRunDirectory(instanceId), "game directory")
                .toAbsolutePath()
                .normalize();
        @Nullable InstanceIconStore localIconStore = iconStore;
        InstanceIconStore.Snapshot iconState = localIconStore != null
                ? localIconStore.load()
                : new InstanceIconStore.Snapshot(InstanceIconType.DEFAULT, null);
        if (localIconStore != null) {
            InstanceIconImages.preloadBuiltIns();
        }
        ImageIcon preview = InstanceIconImages.load(iconState, 40);
        return new InstanceSnapshot(instanceRoot, gameDirectory, iconState, preview);
    }

    /// Applies a completed metadata load on the EDT.
    ///
    /// @param loadedSnapshot loaded snapshot, or `null` when loading failed
    /// @param failure loading failure, or `null` when successful
    private void snapshotLoadCompleted(
            @Nullable InstanceSnapshot loadedSnapshot,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        operationPending.set(false);
        if (closed.get()) {
            return;
        }
        if (failure != null) {
            updateActionState();
            showFailure(failure);
            return;
        }
        if (loadedSnapshot == null) {
            updateActionState();
            showFailure(new IllegalStateException("Instance metadata completed without a result"));
            return;
        }
        snapshot = loadedSnapshot;
        instanceRootValue.setText(loadedSnapshot.instanceRoot().toString());
        instanceRootValue.setToolTipText(loadedSnapshot.instanceRoot().toString());
        gameDirectoryValue.setText(loadedSnapshot.gameDirectory().toString());
        gameDirectoryValue.setToolTipText(loadedSnapshot.gameDirectory().toString());
        iconPreview.setIcon(loadedSnapshot.iconPreview());
        updateActionState();
    }

    /// Opens the current version-root directory with the platform desktop handler.
    private void openInstanceDirectory() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable InstanceSnapshot currentSnapshot = snapshot;
        if (currentSnapshot != null) {
            openDirectory(currentSnapshot.instanceRoot());
        }
    }

    /// Opens the current effective game directory with the platform desktop handler.
    private void openGameDirectory() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable InstanceSnapshot currentSnapshot = snapshot;
        if (currentSnapshot != null) {
            openDirectory(currentSnapshot.gameDirectory());
        }
    }

    /// Populates the restored directory browser with the same user-facing locations exposed by the former launcher.
    private void configureDirectoryMenu() {
        directoryMenu.setName("instanceOverviewDirectoryMenu");
        addDirectoryMenuItem("instanceOverviewBrowseGame", "folder.game", "");
        addDirectoryMenuItem("instanceOverviewBrowseMods", "folder.mod", "mods");
        addDirectoryMenuItem("instanceOverviewBrowseResourcePacks", "folder.resourcepacks", "resourcepacks");
        addDirectoryMenuItem("instanceOverviewBrowseSaves", "folder.saves", "saves");
        addDirectoryMenuItem("instanceOverviewBrowseSchematics", "folder.schematics", "schematics");
        addDirectoryMenuItem("instanceOverviewBrowseShaderPacks", "folder.shaderpacks", "shaderpacks");
        addDirectoryMenuItem("instanceOverviewBrowseScreenshots", "folder.screenshots", "screenshots");
        addDirectoryMenuItem("instanceOverviewBrowseConfig", "folder.config", "config");
        addDirectoryMenuItem("instanceOverviewBrowseLogs", "folder.logs", "logs");
        addDirectoryMenuItem("instanceOverviewBrowseCrashReports", "folder.crash-reports", "crash-reports");
    }

    /// Adds one trusted child-directory command to the restored browse menu.
    ///
    /// @param name stable Swing component name
    /// @param labelKey existing localization key
    /// @param relativeDirectory direct child of the effective game directory, or blank for that directory itself
    private void addDirectoryMenuItem(String name, String labelKey, String relativeDirectory) {
        String componentName = requireNonBlank(name, "name");
        String relative = Objects.requireNonNull(relativeDirectory, "relativeDirectory");
        JMenuItem item = new JMenuItem(i18n(requireNonBlank(labelKey, "labelKey")));
        item.setName(componentName);
        item.addActionListener(event -> openKnownDirectory(relative));
        directoryMenu.add(item);
    }

    /// Shows the directory popup only after the effective game directory has been resolved.
    private void showDirectoryMenu() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || operationPending.get() || snapshot == null) {
            return;
        }
        directoryMenu.show(exploreDirectoriesButton, 0, exploreDirectoriesButton.getHeight());
    }

    /// Opens the effective game directory or one known direct child through the existing asynchronous desktop boundary.
    ///
    /// @param relativeDirectory direct child directory, or blank for the effective game directory itself
    private void openKnownDirectory(String relativeDirectory) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable InstanceSnapshot currentSnapshot = snapshot;
        if (currentSnapshot == null || closed.get()) {
            return;
        }
        String relative = Objects.requireNonNull(relativeDirectory, "relativeDirectory");
        Path directory = relative.isBlank()
                ? currentSnapshot.gameDirectory()
                : currentSnapshot.gameDirectory().resolve(relative).normalize();
        openDirectory(directory);
    }

    /// Starts a non-blocking platform directory open for one resolved path.
    ///
    /// @param directory resolved directory to open
    private void openDirectory(Path directory) {
        if (!beginOperation()) {
            return;
        }
        try {
            CompletionStage<@Nullable Void> completion = Objects.requireNonNull(
                    interactions.openDirectory(directory),
                    "interactions.openDirectory returned null");
            completion.whenComplete((ignored, failure) ->
                    EdtDispatcher.execute(() -> operationCompleted(failure, () -> { })));
        } catch (RuntimeException failure) {
            operationCompleted(failure, () -> { });
        } catch (Error failure) {
            operationCompleted(failure, () -> { });
            throw failure;
        }
    }

    /// Rescans the repository and refreshes the effective directory values.
    private void refresh() {
        EdtDispatcher.requireEventDispatchThread();
        requestSnapshot(true);
    }

    /// Shows the complete icon chooser and persists the selected bundled type or custom image.
    private void chooseIcon() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable InstanceIconStore localIconStore = iconStore;
        @Nullable InstanceSnapshot currentSnapshot = snapshot;
        if (localIconStore == null || currentSnapshot == null || closed.get()) {
            return;
        }
        InstanceIconStore.Snapshot iconState = currentSnapshot.iconState();
        @Nullable InstanceIconChoice choice = interactions.chooseInstanceIcon(
                this,
                iconState.builtInType(),
                iconState.customImage() != null,
                currentSnapshot.instanceRoot());
        if (choice == null) {
            return;
        }
        if (choice instanceof InstanceIconChoice.BuiltIn builtIn) {
            submitRepositoryOperation(
                    () -> localIconStore.selectBuiltIn(builtIn.iconType()),
                    () -> iconChanged(localIconStore));
        } else if (choice instanceof InstanceIconChoice.Custom custom) {
            submitRepositoryOperation(
                    () -> localIconStore.selectCustom(custom.file()),
                    () -> iconChanged(localIconStore));
        }
    }

    /// Confirms and deletes the stored custom icon through the persistent repository API.
    private void deleteIcon() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable InstanceIconStore localIconStore = iconStore;
        @Nullable InstanceSnapshot currentSnapshot = snapshot;
        if (localIconStore == null
                || currentSnapshot == null
                || currentSnapshot.iconState().customImage() == null
                || closed.get()) {
            return;
        }
        if (!interactions.confirmDeleteIcon(this, instanceId)) {
            return;
        }
        submitRepositoryOperation(
                localIconStore::deleteCustom,
                () -> iconChanged(localIconStore));
    }

    /// Runs one repository mutation outside the EDT and posts its terminal state to the panel.
    ///
    /// @param operation background repository mutation
    /// @param success EDT action after the mutation succeeds
    private void submitRepositoryOperation(RepositoryOperation operation, Runnable success) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(success, "success");
        if (!beginOperation()) {
            return;
        }
        try {
            executor.execute(() -> repositoryOperationOnExecutor(operation, success));
        } catch (RuntimeException failure) {
            operationCompleted(failure, success);
        } catch (Error failure) {
            operationCompleted(failure, success);
            throw failure;
        }
    }

    /// Executes one repository mutation on the caller-owned background executor.
    ///
    /// @param operation background mutation
    /// @param success EDT action after success
    private void repositoryOperationOnExecutor(RepositoryOperation operation, Runnable success) {
        try {
            requireBackgroundThread();
            operation.run();
            EdtDispatcher.execute(() -> operationCompleted(null, success));
        } catch (Exception | Error failure) {
            EdtDispatcher.execute(() -> operationCompleted(failure, success));
        }
    }

    /// Publishes a successful icon transition and reloads its persisted 40-pixel preview.
    ///
    /// @param changedIconStore persistent store that completed the transition
    private void iconChanged(InstanceIconStore changedIconStore) {
        changedIconStore.publishChanged(this);
        requestSnapshot(false);
    }

    /// Completes one non-snapshot operation on the EDT.
    ///
    /// @param failure operation failure, or `null` when successful
    /// @param success EDT action after success
    private void operationCompleted(@Nullable Throwable failure, Runnable success) {
        EdtDispatcher.requireEventDispatchThread();
        operationPending.set(false);
        if (closed.get()) {
            return;
        }
        if (failure != null) {
            updateActionState();
            showFailure(failure);
            return;
        }
        success.run();
        if (!operationPending.get()) {
            updateActionState();
        }
    }

    /// Marks a single user operation pending and synchronizes all action enablement.
    ///
    /// @return whether the operation acquired the panel's single-operation slot
    private boolean beginOperation() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || !operationPending.compareAndSet(false, true)) {
            return false;
        }
        updateActionState();
        return true;
    }

    /// Synchronizes control visibility and enablement with current lifecycle and metadata state.
    private void updateActionState() {
        EdtDispatcher.requireEventDispatchThread();
        boolean idle = !closed.get() && !operationPending.get();
        @Nullable InstanceSnapshot currentSnapshot = snapshot;
        boolean hasSnapshot = currentSnapshot != null;
        boolean supportsIcons = iconStore != null;

        refreshButton.setEnabled(idle);
        openInstanceDirectoryButton.setEnabled(idle && hasSnapshot);
        openGameDirectoryButton.setEnabled(idle && hasSnapshot);
        exploreDirectoriesButton.setEnabled(idle && hasSnapshot);
        chooseIconButton.setVisible(supportsIcons);
        chooseIconButton.setEnabled(idle && hasSnapshot && supportsIcons);
        deleteIconButton.setVisible(supportsIcons);
        deleteIconButton.setEnabled(idle
                && hasSnapshot
                && supportsIcons
                && Objects.requireNonNull(currentSnapshot, "snapshot").iconState().customImage() != null);
    }

    /// Shows one terminal operation failure without leaking a null or blank message to the UI.
    ///
    /// @param failure terminal operation failure
    private void showFailure(Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        interactions.showFailure(this, strings.operationFailedTitle(), failureDetail(failure));
    }

    /// Produces a stable human-readable detail from a throwable.
    ///
    /// @param failure non-null failure
    /// @return non-blank message for the failure dialog
    private static String failureDetail(Throwable failure) {
        Throwable source = Objects.requireNonNull(failure, "failure");
        @Nullable String message = source.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return source.getClass().getSimpleName();
    }

    /// Rejects accidental repository or filesystem execution on the Swing event-dispatch thread.
    private static void requireBackgroundThread() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Repository and file-system work must not run on the EDT");
        }
    }

    /// Validates one required non-blank text value.
    ///
    /// @param value source text
    /// @param name parameter name
    /// @return validated text
    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /// Immutable local state resolved from the repository outside the EDT.
    ///
    /// @param instanceRoot resolved version-root directory
    /// @param gameDirectory resolved effective game running directory
    /// @param iconState persisted custom and bundled icon identity
    /// @param iconPreview exact-size decoded Swing preview
    @NotNullByDefault
    private record InstanceSnapshot(
            Path instanceRoot,
            Path gameDirectory,
            InstanceIconStore.Snapshot iconState,
            ImageIcon iconPreview) {
    }

    /// Preview label whose contrast surface follows live FlatLaf theme changes.
    @NotNullByDefault
    private static final class IconPreviewLabel extends JLabel {
        /// Refreshes the preview background after the application switches light or dark mode.
        @Override
        public void updateUI() {
            super.updateUI();
            @Nullable Color selectedBackground = UIManager.getColor("ToggleButton.selectedBackground");
            setOpaque(selectedBackground != null);
            setBackground(selectedBackground);
        }
    }

    /// Background operation that may perform repository-backed checked I/O.
    @FunctionalInterface
    @NotNullByDefault
    private interface RepositoryOperation {
        /// Runs the operation on the caller-owned background executor.
        ///
        /// @throws Exception when the repository operation cannot complete
        void run() throws Exception;
    }
}
