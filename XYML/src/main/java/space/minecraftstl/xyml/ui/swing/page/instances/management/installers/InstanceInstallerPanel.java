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
package space.minecraftstl.xyml.ui.swing.page.instances.management.installers;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.download.LibraryAnalyzer;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.download.UnsupportedInstallationException;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.task.presentation.TaskExecutorPresentationModel;
import space.minecraftstl.xyml.ui.swing.AnimatedTabbedPane;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingTransparency;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.GameLoaderKind;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.LoaderSelectionListener;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.LoaderSelectionWizardPanel;
import space.minecraftstl.xyml.ui.swing.shell.ShellFileDropHandler;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressHostPanel;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;
import space.minecraftstl.xyml.util.io.FileUtils;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.BorderLayout;
import java.awt.Font;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Manages recognized loader replacements, offline installers, and safe third-party library removal for one instance.
///
/// Construction performs no repository access and no remote catalog refresh. [#activate()] explicitly requests the
/// asynchronous service snapshot, then seeds the embedded [LoaderSelectionWizardPanel] with the instance's detected
/// Minecraft version and retained loader kinds. At most one Core task is active; its lifecycle is presented through
/// [TaskProgressHostPanel], and closing this panel cancels that task before rejecting every late callback.
@NotNullByDefault
public final class InstanceInstallerPanel extends JPanel implements AutoCloseable {
    /// Stable existing instance whose Core installer state this panel presents.
    private final GameInstanceID instanceId;

    /// Asynchronous snapshot and stopped-task boundary for the managed instance.
    private final InstanceInstallerManagementService service;

    /// Native local-file and destructive-operation interaction boundary.
    private final InstanceInstallerInteractions interactions;

    /// Shared remote loader selector retaining exact Core remote-version objects.
    private final LoaderSelectionWizardPanel loaderWizard;

    /// Host owning visual presentation of the single active Core task.
    private final TaskProgressHostPanel progressHost;

    /// Stable top-level navigation between installed-state and online-loader workflows.
    private final JTabbedPane tabs = new AnimatedTabbedPane();

    /// Mutable model for recognized loader rows in the installed-state page.
    private final DefaultListModel<InstanceInstallerEntry> installedLoaderModel = new DefaultListModel<>();

    /// List rendering recognized installed loaders without inferring removal eligibility.
    private final JList<InstanceInstallerEntry> installedLoaderList = new JList<>(installedLoaderModel);

    /// Mutable model for third-party libraries that Core allows the page to describe.
    private final DefaultListModel<InstanceOtherLibraryEntry> otherLibraryModel = new DefaultListModel<>();

    /// Single-choice list of third-party libraries, where only clear entries are removable.
    private final JList<InstanceOtherLibraryEntry> otherLibraryList = new JList<>(otherLibraryModel);

    /// Displays the detected base Minecraft version or a localized unavailable state.
    private final JLabel gameVersionValue = new JLabel();

    /// Explicitly starts a fresh asynchronous snapshot request.
    private final JButton refreshButton = new JButton();

    /// Opens a local `.jar` or `.exe` chooser before creating an offline installation task.
    private final JButton offlineInstallButton = new JButton();

    /// Submits exactly the remote versions retained by the embedded wizard's staged selection.
    private final JButton onlineInstallButton = new JButton();

    /// Requests removal only for a selected recognized loader with a clear Core structure state.
    private final JButton removeInstalledLoaderButton = new JButton();

    /// Requests removal only for a selected third-party library with a clear structure state.
    private final JButton removeOtherLibraryButton = new JButton();

    /// Shows idle, loading, terminal-task, and validation feedback.
    private final JLabel statusLabel = new JLabel();

    /// Recomputes removal eligibility after a final third-party-library selection change.
    private final ListSelectionListener otherLibrarySelectionListener = this::otherLibrarySelectionChanged;

    /// Recomputes recognized-loader removal eligibility after a final selection change.
    private final ListSelectionListener installedLoaderSelectionListener = this::installedLoaderSelectionChanged;

    /// Recomputes online-task eligibility after the embedded wizard's local selection changes.
    private final LoaderSelectionListener loaderSelectionListener = ignored -> updateControls();

    /// Rejects actions and late worker publications synchronously once closing begins.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Monotonic snapshot request identity used to discard stale asynchronous read completions.
    private long snapshotRevision;

    /// Whether one explicit asynchronous snapshot read is currently in flight.
    private boolean snapshotLoading;

    /// Latest successfully rendered snapshot, or null before activation succeeds.
    private @Nullable InstanceInstallerSnapshot displayedSnapshot;

    /// Exact Core task executor currently running, or null when no mutation is in flight.
    private volatile @Nullable TaskExecutor activeExecutor;

    /// Presentation model bound to the progress host, or null before the first task.
    private @Nullable TaskExecutorPresentationModel activePresentation;

    /// Exact terminal listener registration owned by the current task, or null while idle.
    private @Nullable Subscription activeCompletionSubscription;

    /// Page-scoped single-file route for local loader installers.
    private final ShellFileDropHandler.RouteRegistration dropRegistration;

    /// Creates a production panel for an existing XYML repository instance without loading any state.
    ///
    /// @param repository repository containing the instance
    /// @param instanceId stable existing instance identifier
    /// @param taskProgressStrings localized task-progress labels
    /// @param animator optional shared progress animator
    /// @param progressAnimationDuration non-negative determinate-progress animation duration
    public InstanceInstallerPanel(
            XYMLGameRepository repository,
            GameInstanceID instanceId,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        this(
                instanceId,
                new RepositoryInstanceInstallerManagementService(Objects.requireNonNull(repository, "repository")),
                LoaderSelectionWizardPanel.createForLauncher(),
                new SwingInstanceInstallerInteractions(),
                taskProgressStrings,
                animator,
                progressAnimationDuration);
    }

    /// Creates a panel with explicit service, selector, interaction, and task-presentation seams for focused tests.
    ///
    /// No constructor argument is invoked for repository or network work. The caller owns the injected service and
    /// loader selector sources; this panel closes only the selector and its own presentation resources.
    ///
    /// @param instanceId stable existing instance identifier
    /// @param service asynchronous snapshot and stopped-task service
    /// @param loaderWizard local selector for remote loader versions
    /// @param interactions native dialog interaction boundary
    /// @param taskProgressStrings localized task-progress labels
    /// @param animator optional shared progress animator
    /// @param progressAnimationDuration non-negative determinate-progress animation duration
    InstanceInstallerPanel(
            GameInstanceID instanceId,
            InstanceInstallerManagementService service,
            LoaderSelectionWizardPanel loaderWizard,
            InstanceInstallerInteractions interactions,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        super(new BorderLayout(0, 10));
        EdtDispatcher.requireEventDispatchThread();
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.service = Objects.requireNonNull(service, "service");
        this.loaderWizard = Objects.requireNonNull(loaderWizard, "loaderWizard");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        progressHost = new TaskProgressHostPanel(
                Objects.requireNonNull(taskProgressStrings, "taskProgressStrings"),
                animator,
                Objects.requireNonNull(progressAnimationDuration, "progressAnimationDuration"));
        if (progressAnimationDuration.isNegative()) {
            throw new IllegalArgumentException("progressAnimationDuration must not be negative");
        }

        configureComponents();
        loaderWizard.addSelectionListener(loaderSelectionListener);
        updateControls();
        dropRegistration = ShellFileDropHandler.register(
                this,
                this::supportsDroppedInstaller,
                this::installDroppedOffline);
    }

    /// Returns the localized outer tab title used by the containing instance-management view.
    ///
    /// @return non-blank installer-management title
    public String title() {
        return i18n("settings.tabs.installers");
    }

    /// Returns the latest successfully rendered installer snapshot, or null before loading succeeds.
    ///
    /// @return immutable current snapshot, or null while inactive, loading, or after failure
    public @Nullable InstanceInstallerSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return displayedSnapshot;
    }

    /// Starts the initial asynchronous instance snapshot read once the surrounding tab becomes visible.
    ///
    /// Repeated activation retains the latest valid snapshot and avoids background work until an explicit refresh or
    /// a completed mutation needs a fresh state.
    public void activate() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || displayedSnapshot != null || snapshotLoading || activeExecutor != null) {
            return;
        }
        requestSnapshot();
    }

    /// Cancels a running Core task and permanently rejects delayed loader and task notifications.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        @Nullable Throwable cancellationFailure = null;
        @Nullable TaskExecutor executor = activeExecutor;
        if (executor != null) {
            try {
                executor.cancel();
            } catch (RuntimeException | Error failure) {
                cancellationFailure = failure;
            }
        }
        try {
            SwingUiDispatcher.INSTANCE.dispatchOrRun(this::closeOnEventDispatchThread);
        } catch (RuntimeException | Error cleanupFailure) {
            if (cancellationFailure != null && cancellationFailure != cleanupFailure) {
                cleanupFailure.addSuppressed(cancellationFailure);
            }
            throw cleanupFailure;
        }
        if (cancellationFailure instanceof Error error) {
            throw error;
        }
        if (cancellationFailure instanceof RuntimeException exception) {
            throw exception;
        }
    }

    /// Creates the installed-state surface, online selector, task host, and explicit commands without I/O.
    private void configureComponents() {
        setName("instanceInstallerPage");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());

        JPanel root = new JPanel(new MigLayout(
                "insets 12 16 12 16, fill, wrap 1",
                "[grow,fill]",
                "[]10[grow,fill]8[]"));
        root.setOpaque(false);
        root.add(createHeadingBand(), "growx");

        tabs.setName("instanceInstallerTabs");
        SwingTransparency.revealBackgroundThroughTabs(tabs);
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addTab(i18n("settings.tabs.installers"), createInstalledPage());
        tabs.addTab(i18n("install.installer.install_online"), createOnlinePage());
        root.add(tabs, "grow, push");

        progressHost.setName("instanceInstallerTaskProgress");
        root.add(progressHost, "growx");
        statusLabel.setName("instanceInstallerStatus");
        root.add(statusLabel, "growx, h 24!");
        add(root, BorderLayout.CENTER);
    }

    /// Creates the compact top-level title and explicit snapshot/offline-install commands.
    ///
    /// @return configured heading band
    private JComponent createHeadingBand() {
        JPanel heading = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]8[]8[]", "[40!]"));
        heading.setOpaque(false);
        JLabel title = new JLabel(title());
        title.setName("instanceInstallerTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22.0F));
        heading.add(title, "growx");

        configureIconButton(
                refreshButton,
                "instanceInstallerRefresh",
                "assets/swing/icons/refresh.svg",
                i18n("button.refresh"),
                this::refreshSnapshot);
        heading.add(refreshButton, "w 40!, h 40!");

        offlineInstallButton.setName("instanceInstallerOfflineInstall");
        offlineInstallButton.setText(i18n("install.installer.install_offline"));
        offlineInstallButton.setIcon(new FlatSVGIcon("assets/swing/icons/file-import.svg", 18, 18));
        offlineInstallButton.setToolTipText(i18n("install.installer.install_offline.tooltip"));
        offlineInstallButton.addActionListener(event -> chooseAndInstallOffline());
        heading.add(offlineInstallButton, "h 40!");
        return heading;
    }

    /// Creates the current loader and third-party library state page.
    ///
    /// @return configured installed-state page
    private JComponent createInstalledPage() {
        JPanel page = new JPanel(new MigLayout(
                "insets 12, fill, wrap 1",
                "[grow,fill]",
                "[]6[]12[]6[grow,fill]12[]6[grow,fill]"));
        page.setOpaque(false);

        JPanel gameVersionRow = new JPanel(new MigLayout("insets 0, fillx", "[][grow,fill]", "[]"));
        gameVersionRow.setOpaque(false);
        JLabel gameVersionLabel = new JLabel(i18n("install.installer.game"));
        gameVersionLabel.setLabelFor(gameVersionValue);
        gameVersionRow.add(gameVersionLabel);
        gameVersionValue.setName("instanceInstallerGameVersion");
        gameVersionRow.add(gameVersionValue, "growx");
        page.add(gameVersionRow, "growx");

        JPanel installedHeadingBand = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", "[]"));
        installedHeadingBand.setOpaque(false);
        JLabel installedHeading = new JLabel(i18n("settings.tabs.installers"));
        installedHeading.setLabelFor(installedLoaderList);
        installedHeadingBand.add(installedHeading, "growx");
        configureIconButton(
                removeInstalledLoaderButton,
                "instanceInstallerRemoveInstalledLoader",
                "assets/swing/icons/delete.svg",
                i18n("button.remove"),
                this::removeSelectedInstalledLoader);
        installedHeadingBand.add(removeInstalledLoaderButton, "w 40!, h 40!");
        page.add(installedHeadingBand, "growx");
        configureInstalledLoaderList();
        page.add(createListScrollPane(installedLoaderList, "instanceInstallerInstalledLoaders"), "grow, h 90:160:");

        JPanel thirdPartyHeading = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", "[]"));
        thirdPartyHeading.setOpaque(false);
        JLabel otherLibrariesLabel = new JLabel(i18n("message.unknown"));
        otherLibrariesLabel.setLabelFor(otherLibraryList);
        thirdPartyHeading.add(otherLibrariesLabel, "growx");
        configureIconButton(
                removeOtherLibraryButton,
                "instanceInstallerRemoveOtherLibrary",
                "assets/swing/icons/delete.svg",
                i18n("button.remove"),
                this::removeSelectedOtherLibrary);
        thirdPartyHeading.add(removeOtherLibraryButton, "w 40!, h 40!");
        page.add(thirdPartyHeading, "growx");
        configureOtherLibraryList();
        page.add(createListScrollPane(otherLibraryList, "instanceInstallerOtherLibraries"), "grow, h 90:160:");
        return page;
    }

    /// Creates the online-loader workflow with an explicit final task-submission command.
    ///
    /// @return configured online installation page
    private JComponent createOnlinePage() {
        JPanel page = new JPanel(new MigLayout(
                "insets 12, fill, wrap 1",
                "[grow,fill]",
                "[grow,fill]8[]"));
        page.setOpaque(false);
        loaderWizard.setName("instanceInstallerLoaderWizard");
        page.add(loaderWizard, "grow, push");

        onlineInstallButton.setName("instanceInstallerOnlineInstall");
        onlineInstallButton.setText(i18n("install.installer.install_online"));
        onlineInstallButton.setIcon(new FlatSVGIcon("assets/swing/icons/nav-downloads.svg", 18, 18));
        onlineInstallButton.setToolTipText(i18n("install.installer.install_online.tooltip"));
        onlineInstallButton.addActionListener(event -> installSelectedRemoteVersions());
        page.add(onlineInstallButton, "alignx right, h 40!");
        return page;
    }

    /// Configures the recognized-loader list's stable single-line renderer.
    private void configureInstalledLoaderList() {
        installedLoaderList.setName("instanceInstallerInstalledLoaderList");
        installedLoaderList.setOpaque(false);
        installedLoaderList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        installedLoaderList.setCellRenderer((list, entry, index, selected, focus) -> {
            if (entry == null) {
                return listRenderer(list, "", selected);
            }
            return listRenderer(
                    list,
                    entry.kind().displayName() + " " + localizedInstallerVersion(entry.version(), entry.status()),
                    selected);
        });
        installedLoaderList.addListSelectionListener(installedLoaderSelectionListener);
    }

    /// Configures third-party library selection and its explicit structural-state renderer.
    private void configureOtherLibraryList() {
        otherLibraryList.setName("instanceInstallerOtherLibraryList");
        otherLibraryList.setOpaque(false);
        otherLibraryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        otherLibraryList.setCellRenderer((list, entry, index, selected, focus) -> {
            if (entry == null) {
                return listRenderer(list, "", selected);
            }
            @Nullable String version = entry.version();
            return listRenderer(list, entry.libraryId() + " " + localizedInstallerVersion(
                    version == null ? i18n("message.unknown") : version,
                    entry.structureState() == InstanceOtherLibraryEntry.StructureState.CLEAR
                            ? LibraryAnalyzer.LibraryMark.LibraryStatus.CLEAR
                            : LibraryAnalyzer.LibraryMark.LibraryStatus.UNSURE), selected);
        });
        otherLibraryList.addListSelectionListener(otherLibrarySelectionListener);
    }

    /// Creates one viewport-backed list container with predictable scrolling behavior.
    ///
    /// @param list configured list content
    /// @param name stable scroll-pane name
    /// @return configured list scroll pane
    private static JScrollPane createListScrollPane(JList<?> list, String name) {
        JScrollPane scrollPane = new JScrollPane(Objects.requireNonNull(list, "list"));
        scrollPane.setName(requireNonBlank(name, "name"));
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        SwingTransparency.revealBackgroundThroughScrollPane(scrollPane);
        return scrollPane;
    }

    /// Produces one compact list renderer following active Swing selection colors.
    ///
    /// @param list source list
    /// @param text non-null visible row text
    /// @param selected whether the row is selected
    /// @return configured renderer component
    private static JLabel listRenderer(JList<?> list, String text, boolean selected) {
        JLabel label = new JLabel(Objects.requireNonNull(text, "text"));
        label.setOpaque(selected);
        label.setBorder(BorderFactory.createEmptyBorder(4, 7, 4, 7));
        label.setHorizontalAlignment(SwingConstants.LEADING);
        if (selected) {
            label.setBackground(list.getSelectionBackground());
            label.setForeground(list.getSelectionForeground());
        } else {
            label.setBackground(list.getBackground());
            label.setForeground(list.getForeground());
        }
        return label;
    }

    /// Formats a loader or library version using the historical clear-versus-external installer translations.
    ///
    /// @param version visible version text
    /// @param status Core structure status for the represented row
    /// @return localized version and structure state without exposing internal enum names
    private static String localizedInstallerVersion(
            String version,
            LibraryAnalyzer.LibraryMark.LibraryStatus status) {
        String visibleVersion = Objects.requireNonNull(version, "version");
        LibraryAnalyzer.LibraryMark.LibraryStatus structureStatus = Objects.requireNonNull(status, "status");
        return structureStatus == LibraryAnalyzer.LibraryMark.LibraryStatus.CLEAR
                ? i18n("install.installer.version", visibleVersion)
                : i18n("install.installer.external_version", visibleVersion);
    }

    /// Starts one explicit asynchronous snapshot refresh after the user requests it.
    private void refreshSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || snapshotLoading || activeExecutor != null) {
            return;
        }
        requestSnapshot();
    }

    /// Requests one service snapshot and retains a revision that rejects stale completions.
    private void requestSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        snapshotLoading = true;
        long requestRevision = ++snapshotRevision;
        statusLabel.setText(i18n("message.doing"));
        updateControls();
        final CompletionStage<InstanceInstallerSnapshot> completion;
        try {
            completion = Objects.requireNonNull(
                    service.loadSnapshot(instanceId),
                    "service.loadSnapshot returned null completion");
        } catch (RuntimeException failure) {
            snapshotLoaded(requestRevision, null, failure);
            return;
        }
        completion.whenComplete((@Nullable InstanceInstallerSnapshot snapshot, @Nullable Throwable failure) ->
                SwingUiDispatcher.INSTANCE.dispatchOrRun(
                        () -> snapshotLoaded(requestRevision, snapshot, failure)));
    }

    /// Applies exactly the latest successful snapshot completion to visible models and retained loader state.
    ///
    /// @param requestRevision snapshot request identity
    /// @param snapshot loaded state, or null after an exceptional completion
    /// @param failure exceptional completion failure, or null on success
    private void snapshotLoaded(
            long requestRevision,
            @Nullable InstanceInstallerSnapshot snapshot,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || requestRevision != snapshotRevision) {
            return;
        }
        snapshotLoading = false;
        if (failure != null || snapshot == null) {
            statusLabel.setText(failureStatus(failure == null
                    ? new IllegalStateException("Installer snapshot completed without a result")
                    : failure));
            updateControls();
            return;
        }
        if (!instanceId.equals(snapshot.instanceId())) {
            statusLabel.setText(failureStatus(
                    new IllegalStateException("Installer snapshot belongs to another instance")));
            updateControls();
            return;
        }
        applySnapshot(snapshot);
        statusLabel.setText("");
        updateControls();
    }

    /// Replaces visible lists and resets the staged online selector against one authoritative instance state.
    ///
    /// @param snapshot immutable current service snapshot
    private void applySnapshot(InstanceInstallerSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        InstanceInstallerSnapshot current = Objects.requireNonNull(snapshot, "snapshot");
        displayedSnapshot = current;
        gameVersionValue.setText(current.gameVersion().orElse(i18n("message.unknown")));

        installedLoaderModel.clear();
        for (InstanceInstallerEntry entry : current.installedLoaders()) {
            installedLoaderModel.addElement(entry);
        }
        otherLibraryModel.clear();
        for (InstanceOtherLibraryEntry entry : current.otherRemovableLibraries()) {
            otherLibraryModel.addElement(entry);
        }

        List<GameLoaderKind> retainedKinds = new ArrayList<>(current.installedLoaders().size());
        for (InstanceInstallerEntry entry : current.installedLoaders()) {
            retainedKinds.add(entry.kind());
        }
        if (current.gameVersion().isPresent()) {
            loaderWizard.selectGameVersion(current.gameVersion().orElseThrow());
        } else {
            loaderWizard.clearGameVersion();
        }
        loaderWizard.setRetainedLoaderKinds(retainedKinds);
    }

    /// Opens the local chooser and starts an offline Core installation only after a concrete file is selected.
    private void chooseAndInstallOffline() {
        EdtDispatcher.requireEventDispatchThread();
        if (!isReadyForMutation()) {
            return;
        }
        @Nullable Path installer = interactions.chooseOfflineInstaller(this);
        if (installer != null) {
            startTask(() -> service.installOffline(instanceId, installer), i18n("install.installer.install_offline"));
        }
    }

    /// Returns whether this ready page accepts one dropped offline installer.
    ///
    /// @param installer normalized dropped path
    /// @return whether the path has a supported installer suffix and no operation is active
    private boolean supportsDroppedInstaller(Path installer) {
        String extension = FileUtils.getExtension(Objects.requireNonNull(installer, "installer"))
                .toLowerCase(Locale.ROOT);
        return isReadyForMutation() && ("jar".equals(extension) || "exe".equals(extension));
    }

    /// Starts the existing offline installer task for one accepted dropped path.
    ///
    /// @param installer normalized supported installer path
    private void installDroppedOffline(Path installer) {
        EdtDispatcher.requireEventDispatchThread();
        if (supportsDroppedInstaller(installer)) {
            startTask(
                    () -> service.installOffline(instanceId, installer),
                    i18n("install.installer.install_offline"));
        }
    }

    /// Starts an online Core task with exactly the original remote-version objects retained by the loader selector.
    private void installSelectedRemoteVersions() {
        EdtDispatcher.requireEventDispatchThread();
        if (!isReadyForMutation()) {
            return;
        }
        List<RemoteVersion> selectedVersions = loaderWizard.selectedRemoteVersions();
        if (selectedVersions.isEmpty()) {
            updateControls();
            return;
        }
        startTask(
                () -> service.installRemoteVersions(instanceId, selectedVersions),
                i18n("install.installer.install_online"));
    }

    /// Confirms and starts removal only for a selected third-party library whose structural state is clear.
    private void removeSelectedOtherLibrary() {
        EdtDispatcher.requireEventDispatchThread();
        if (!isReadyForMutation()) {
            return;
        }
        @Nullable InstanceOtherLibraryEntry selected = otherLibraryList.getSelectedValue();
        if (selected == null || selected.structureState() != InstanceOtherLibraryEntry.StructureState.CLEAR) {
            updateControls();
            return;
        }
        if (interactions.confirmRemoval(this, selected.libraryId())) {
            startTask(() -> service.removeLibrary(instanceId, selected.libraryId()), i18n("button.remove"));
        }
    }

    /// Confirms and starts removal only for a selected recognized loader whose Core structure is clear.
    private void removeSelectedInstalledLoader() {
        EdtDispatcher.requireEventDispatchThread();
        if (!isReadyForMutation()) {
            return;
        }
        @Nullable InstanceInstallerEntry selected = installedLoaderList.getSelectedValue();
        if (selected == null || selected.status() != LibraryAnalyzer.LibraryMark.LibraryStatus.CLEAR) {
            updateControls();
            return;
        }
        String libraryId = selected.kind().versionListId();
        if (interactions.confirmRemoval(this, libraryId)) {
            startTask(() -> service.removeLibrary(instanceId, libraryId), i18n("button.remove"));
        }
    }

    /// Creates, presents, and starts exactly one stopped Core mutation task.
    ///
    /// @param taskSupplier task-construction boundary called only once after panel validation
    /// @param title localized task title
    private void startTask(TaskSupplier taskSupplier, String title) {
        EdtDispatcher.requireEventDispatchThread();
        if (!isReadyForMutation()) {
            return;
        }
        releaseCompletedPresentation();
        final Task<InstanceInstallerSnapshot> task;
        try {
            task = Objects.requireNonNull(taskSupplier.create(), "task supplier returned null");
        } catch (RuntimeException failure) {
            presentFailure(Objects.requireNonNull(title, "title"), failure);
            updateControls();
            return;
        }

        TaskExecutor executor = task.executor();
        TaskExecutorPresentationModel presentation = new TaskExecutorPresentationModel(
                executor,
                Objects.requireNonNull(title, "title"),
                i18n("message.doing"));
        Subscription completionSubscription = executor.subscribeTaskListener(
                new InstallerCompletionListener(executor, task));
        activeExecutor = executor;
        activePresentation = presentation;
        activeCompletionSubscription = completionSubscription;
        statusLabel.setText(i18n("message.doing"));
        updateControls();
        try {
            progressHost.bind(presentation);
            executor.start();
        } catch (RuntimeException | Error startFailure) {
            cleanupFailedTaskStart(presentation, completionSubscription);
            presentFailure(title, startFailure);
            updateControls();
        }
    }

    /// Handles one terminal task executor transition on the event dispatch thread.
    ///
    /// @param executor exact active task executor
    /// @param task exact task yielding a snapshot on success
    /// @param succeeded whether the entire Core mutation succeeded
    private void taskCompleted(
            TaskExecutor executor,
            Task<InstanceInstallerSnapshot> task,
            boolean succeeded) {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed.get() || activeExecutor != executor) {
                return;
            }
            unsubscribe(activeCompletionSubscription);
            activeCompletionSubscription = null;
            activeExecutor = null;
            if (succeeded) {
                @Nullable InstanceInstallerSnapshot result = task.getResult();
                if (result == null || !instanceId.equals(result.instanceId())) {
                    presentFailure(i18n("message.failed"), new IllegalStateException(
                            "Installer task completed without the target instance snapshot"));
                } else {
                    applySnapshot(result);
                    statusLabel.setText(i18n("message.success"));
                }
            } else if (executor.isCancelled()) {
                statusLabel.setText(i18n("message.cancelled"));
            } else {
                @Nullable Throwable failure = executor.getFailure();
                presentFailure(
                        i18n("message.failed"),
                        failure == null ? new IllegalStateException("Installer task failed without a cause") : failure);
            }
            updateControls();
        });
    }

    /// Clears a completed visual task presentation before another task is constructed.
    private void releaseCompletedPresentation() {
        EdtDispatcher.requireEventDispatchThread();
        if (activeExecutor != null) {
            return;
        }
        @Nullable TaskExecutorPresentationModel presentation = activePresentation;
        activePresentation = null;
        progressHost.clear();
        if (presentation != null) {
            presentation.close();
        }
    }

    /// Cleans up a task presentation after executor startup fails before terminal callback ownership begins.
    ///
    /// @param presentation presentation constructed for the failed task start
    /// @param completionSubscription exact terminal-listener registration for that task
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

    /// Recomputes controls after a final third-party-library selection transition.
    ///
    /// @param event Swing list-selection transition
    private void otherLibrarySelectionChanged(ListSelectionEvent event) {
        if (!event.getValueIsAdjusting()) {
            updateControls();
        }
    }

    /// Recomputes controls after a final recognized-loader selection transition.
    ///
    /// @param event Swing list-selection transition
    private void installedLoaderSelectionChanged(ListSelectionEvent event) {
        if (!event.getValueIsAdjusting()) {
            updateControls();
        }
    }

    /// Returns whether the panel has an authoritative snapshot and no active snapshot read or mutation task.
    ///
    /// @return whether a new mutation may begin
    private boolean isReadyForMutation() {
        return !closed.get() && displayedSnapshot != null && !snapshotLoading && activeExecutor == null;
    }

    /// Synchronizes every command availability with snapshot, task, and selected-library state.
    private void updateControls() {
        EdtDispatcher.requireEventDispatchThread();
        boolean ready = isReadyForMutation();
        refreshButton.setEnabled(!closed.get() && !snapshotLoading && activeExecutor == null);
        offlineInstallButton.setEnabled(ready);
        onlineInstallButton.setEnabled(ready
                && displayedSnapshot != null
                && displayedSnapshot.gameVersion().isPresent()
                && !loaderWizard.selectedRemoteVersions().isEmpty());
        @Nullable InstanceOtherLibraryEntry selectedOtherLibrary = otherLibraryList.getSelectedValue();
        @Nullable InstanceInstallerEntry selectedInstalledLoader = installedLoaderList.getSelectedValue();
        removeInstalledLoaderButton.setEnabled(ready
                && selectedInstalledLoader != null
                && selectedInstalledLoader.status() == LibraryAnalyzer.LibraryMark.LibraryStatus.CLEAR);
        removeOtherLibraryButton.setEnabled(ready
                && selectedOtherLibrary != null
                && selectedOtherLibrary.structureState() == InstanceOtherLibraryEntry.StructureState.CLEAR);
        tabs.setEnabled(!closed.get());
    }

    /// Presents a concise visible and native failure without embedding a full exception trace in the panel.
    ///
    /// @param title visible failure title
    /// @param failure terminal exception or error
    private void presentFailure(String title, Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        String nonBlankTitle = Objects.requireNonNull(title, "title");
        String detail = failureDetail(Objects.requireNonNull(failure, "failure"));
        statusLabel.setText(i18n("message.failed") + ": " + detail);
        interactions.showFailure(this, nonBlankTitle, detail);
    }

    /// Formats one nested completion failure without losing its original concise detail.
    ///
    /// @param failure terminal failure
    /// @return non-blank visible error detail
    private static String failureStatus(Throwable failure) {
        return i18n("message.failed") + ": " + failureDetail(failure);
    }

    /// Extracts concise text from a task or completion failure.
    ///
    /// @param failure terminal failure
    /// @return non-blank error detail
    private static String failureDetail(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        if (current instanceof CompletionException && current.getCause() != null) {
            current = Objects.requireNonNull(current.getCause(), "completion cause");
        }
        if (current instanceof UnsupportedInstallationException unsupported
                && unsupported.getReason()
                == UnsupportedInstallationException.CLEANROOM_NOT_COMPATIBLE_WITH_FORGE) {
            return i18n("install.failed.cleanroom_not_compatible_with_forge");
        }
        @Nullable String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    /// Unsubscribes one owned listener registration when it is present.
    ///
    /// @param subscription owned registration, or null while none is retained
    private static void unsubscribe(@Nullable Subscription subscription) {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /// Cancels the active task again defensively, closes owned resources, and disables every component on the EDT.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        dropRegistration.close();
        ++snapshotRevision;
        snapshotLoading = false;
        displayedSnapshot = null;
        @Nullable TaskExecutor executor = activeExecutor;
        if (executor != null) {
            executor.cancel();
        }
        unsubscribe(activeCompletionSubscription);
        activeCompletionSubscription = null;
        activeExecutor = null;
        @Nullable TaskExecutorPresentationModel presentation = activePresentation;
        activePresentation = null;
        loaderWizard.removeSelectionListener(loaderSelectionListener);
        loaderWizard.close();
        progressHost.close();
        if (presentation != null) {
            presentation.close();
        }
        otherLibraryList.removeListSelectionListener(otherLibrarySelectionListener);
        installedLoaderList.removeListSelectionListener(installedLoaderSelectionListener);
        refreshButton.setEnabled(false);
        offlineInstallButton.setEnabled(false);
        onlineInstallButton.setEnabled(false);
        removeInstalledLoaderButton.setEnabled(false);
        removeOtherLibraryButton.setEnabled(false);
        tabs.setEnabled(false);
        removeAll();
    }

    /// Configures one fixed-size familiar-symbol command with accessible text.
    ///
    /// @param button target button
    /// @param name deterministic component name
    /// @param iconResource bundled SVG resource path
    /// @param tooltip visible and assistive description
    /// @param action EDT command implementation
    private static void configureIconButton(
            JButton button,
            String name,
            String iconResource,
            String tooltip,
            Runnable action) {
        JButton target = Objects.requireNonNull(button, "button");
        target.setName(requireNonBlank(name, "name"));
        target.setText(null);
        target.setIcon(new FlatSVGIcon(requireNonBlank(iconResource, "iconResource"), 18, 18));
        target.setToolTipText(requireNonBlank(tooltip, "tooltip"));
        target.getAccessibleContext().setAccessibleName(tooltip);
        target.addActionListener(event -> Objects.requireNonNull(action, "action").run());
    }

    /// Validates a stable non-blank identifier or component name without rewriting it.
    ///
    /// @param value candidate text
    /// @param name diagnostic field name
    /// @return exact validated non-blank text
    private static String requireNonBlank(String value, String name) {
        String candidate = Objects.requireNonNull(value, name);
        if (candidate.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return candidate;
    }

    /// Builds a stopped Core installer task after the panel has accepted a user action.
    @FunctionalInterface
    @NotNullByDefault
    private interface TaskSupplier {
        /// Creates one stopped Core task for the current panel operation.
        ///
        /// @return stopped task returning the authoritative resulting snapshot
        Task<InstanceInstallerSnapshot> create();
    }

    /// Routes terminal notifications from exactly one task executor back to the panel's EDT state machine.
    @NotNullByDefault
    private final class InstallerCompletionListener extends TaskListener {
        /// Exact executor whose terminal notification this listener accepts.
        private final TaskExecutor executor;

        /// Exact Core task yielding the resulting snapshot on a successful operation.
        private final Task<InstanceInstallerSnapshot> task;

        /// Creates a terminal listener tied to one task execution.
        ///
        /// @param executor exact started executor
        /// @param task exact Core task represented by the executor
        private InstallerCompletionListener(TaskExecutor executor, Task<InstanceInstallerSnapshot> task) {
            this.executor = Objects.requireNonNull(executor, "executor");
            this.task = Objects.requireNonNull(task, "task");
        }

        /// Publishes only the matching executor's terminal event.
        ///
        /// @param succeeded whether the whole task chain succeeded
        /// @param sourceExecutor executor publishing this event
        @Override
        public void onStop(boolean succeeded, TaskExecutor sourceExecutor) {
            if (sourceExecutor == executor) {
                taskCompleted(executor, task, succeeded);
            }
        }
    }
}
