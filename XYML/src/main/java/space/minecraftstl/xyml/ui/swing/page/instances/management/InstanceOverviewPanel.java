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
import space.minecraftstl.xyml.event.Event;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.VersionIconType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Font;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Shows one managed instance's identity and resolved local directories with real file operations.
///
/// Directory discovery, repository refresh, and custom-icon copying execute off the EDT. The panel
/// exposes custom icon controls only when backed by `XYMLGameRepository`, whose persistent icon API
/// is the same one used by the former launcher UI.
@NotNullByDefault
public final class InstanceOverviewPanel extends JPanel implements AutoCloseable {
    /// Repository providing the instance's root and effective running directories.
    private final GameRepository repository;

    /// XYML repository providing persistent custom-icon operations, or `null` for a generic repository.
    private final @Nullable XYMLGameRepository xymlRepository;

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

    /// Opens the chooser for a custom per-instance image.
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
        super(new MigLayout(
                "insets 16, fillx, wrap 3",
                "[][grow,fill][40!]",
                "[]12[]10[]10[]16[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.repository = Objects.requireNonNull(repository, "repository");
        this.xymlRepository = this.repository instanceof XYMLGameRepository candidate ? candidate : null;
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
        add(title, "span 3, growx");

        instanceNameValue.setName("instanceOverviewName");
        instanceNameValue.setText(instanceId);
        add(createLabel(strings.instanceNameLabel()), "aligny center");
        add(instanceNameValue, "span 2, growx");

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
                "assets/swing/icons/file-import.svg",
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
                repository.refreshVersions();
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
    /// @return resolved instance paths and custom-icon availability
    private InstanceSnapshot readSnapshot() {
        Path instanceRoot = Objects.requireNonNull(repository.getVersionRoot(instanceId), "instance root")
                .toAbsolutePath()
                .normalize();
        Path gameDirectory = Objects.requireNonNull(repository.getRunDirectory(instanceId), "game directory")
                .toAbsolutePath()
                .normalize();
        @Nullable XYMLGameRepository localXymlRepository = xymlRepository;
        boolean hasCustomIcon = localXymlRepository != null
                && localXymlRepository.getVersionIconFile(instanceId).isPresent();
        return new InstanceSnapshot(instanceRoot, gameDirectory, hasCustomIcon);
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

    /// Shows the custom icon chooser and copies an accepted image through the persistent repository API.
    private void chooseIcon() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable XYMLGameRepository localXymlRepository = xymlRepository;
        @Nullable InstanceSnapshot currentSnapshot = snapshot;
        if (localXymlRepository == null || currentSnapshot == null || closed.get()) {
            return;
        }
        @Nullable Path iconFile = interactions.chooseIcon(this, currentSnapshot.instanceRoot());
        if (iconFile == null) {
            return;
        }
        submitRepositoryOperation(
                () -> localXymlRepository.setVersionIconFile(instanceId, iconFile),
                () -> customIconChanged(localXymlRepository));
    }

    /// Confirms and deletes the stored custom icon through the persistent repository API.
    private void deleteIcon() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable XYMLGameRepository localXymlRepository = xymlRepository;
        @Nullable InstanceSnapshot currentSnapshot = snapshot;
        if (localXymlRepository == null
                || currentSnapshot == null
                || !currentSnapshot.hasCustomIcon()
                || closed.get()) {
            return;
        }
        if (!interactions.confirmDeleteIcon(this, instanceId)) {
            return;
        }
        submitRepositoryOperation(
                () -> {
                    localXymlRepository.deleteIconFile(instanceId);
                    if (localXymlRepository.getVersionIconFile(instanceId).isPresent()) {
                        throw new IllegalStateException("The custom icon could not be removed");
                    }
                },
                () -> customIconDeleted(localXymlRepository));
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

    /// Persists the default built-in icon setting and publishes a custom-icon replacement.
    ///
    /// @param iconRepository repository that accepted the custom icon
    private void customIconChanged(XYMLGameRepository iconRepository) {
        @Nullable GameSettings.Instance settings = iconRepository.getInstanceGameSettingsOrCreate(instanceId);
        if (settings != null) {
            settings.iconProperty().setValue(VersionIconType.DEFAULT);
        }
        iconRepository.onVersionIconChanged.fireEvent(new Event(this));
        requestSnapshot(false);
    }

    /// Publishes custom-icon removal and reloads icon availability.
    ///
    /// @param iconRepository repository that removed the custom icon
    private void customIconDeleted(XYMLGameRepository iconRepository) {
        iconRepository.onVersionIconChanged.fireEvent(new Event(this));
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
        boolean supportsCustomIcons = xymlRepository != null;

        refreshButton.setEnabled(idle);
        openInstanceDirectoryButton.setEnabled(idle && hasSnapshot);
        openGameDirectoryButton.setEnabled(idle && hasSnapshot);
        exploreDirectoriesButton.setEnabled(idle && hasSnapshot);
        chooseIconButton.setVisible(supportsCustomIcons);
        chooseIconButton.setEnabled(idle && hasSnapshot && supportsCustomIcons);
        deleteIconButton.setVisible(supportsCustomIcons);
        deleteIconButton.setEnabled(idle
                && hasSnapshot
                && supportsCustomIcons
                && Objects.requireNonNull(currentSnapshot, "snapshot").hasCustomIcon());
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
    /// @param hasCustomIcon whether an image file is stored for this instance
    private record InstanceSnapshot(Path instanceRoot, Path gameDirectory, boolean hasCustomIcon) {
    }

    /// Background operation that may perform repository-backed checked I/O.
    @FunctionalInterface
    private interface RepositoryOperation {
        /// Runs the operation on the caller-owned background executor.
        ///
        /// @throws Exception when the repository operation cannot complete
        void run() throws Exception;
    }
}
