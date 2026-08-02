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

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GraphicsAPI;
import space.minecraftstl.xyml.game.ProcessPriority;
import space.minecraftstl.xyml.game.QuickPlayType;
import space.minecraftstl.xyml.game.Renderer;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameWindowType;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.setting.LauncherVisibility;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingTransparency;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaManagerRuntimeManagementService;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaRuntimeManagementService;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaRuntimeManagementSnapshot;
import space.minecraftstl.xyml.util.i18n.I18n;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceGameSettingsValueCodec.formatWindowDimension;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceGameSettingsValueCodec.parseOptionalInteger;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceGameSettingsValueCodec.parseRequiredDouble;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceGameSettingsValueCodec.parseRequiredInteger;

/// Edits the complete launch-settings surface using radio modes for memory and Java plus per-property overrides.
/// Expensive runtime and renderer choices load only when opened, keeping the instance page responsive.
@NotNullByDefault
public final class InstanceGameSettingsPanel extends JPanel implements AutoCloseable {
    /// Largest manually accepted heap allocation in MiB.
    private static final int MAXIMUM_MEMORY_MIB = 1_048_576;

    /// First game version that exposes explicit graphics-backend selection.
    private static final GameVersionNumber GRAPHICS_BACKEND_VERSION =
            GameVersionNumber.asGameVersion("26.2-snapshot-2");

    /// Presentation contract controlling inheritance and outer page chrome.
    private final GameSettingsEditorPresentation presentation;

    /// Backing store that owns durable values and inheritance markers.
    private final InstanceGameSettingsStore store;

    /// Invalidates already-loaded instance pages after a successful working-directory change.
    private final Runnable workingDirectoryChanged;

    /// Non-blocking adapter over the process-wide local Java runtime registry.
    private final JavaRuntimeManagementService javaRuntimeService;

    /// Listener registration that keeps an opened detected-Java list synchronized with background discovery.
    private final Subscription javaRuntimeSubscription;

    /// Tabbed grouping for the complete settings surface.
    private final JTabbedPane settingsTabs = new JTabbedPane();

    /// Inherited, automatic, and manual memory-allocation choices.
    private final InstanceMemoryModeSelector memoryModeSelector;

    /// Manual maximum heap allocation editor.
    private final JTextField maximumMemoryField = new JTextField();

    /// Manual allocation slider and physical-memory summary.
    private final InstanceMemoryAllocationControls memoryAllocationControls;

    /// Java selection strategy setting.
    private final InstanceJavaModeSelector javaModeSelector;

    /// Requested Java major version setting.
    private final JTextField javaVersionField = new JTextField();

    /// Custom Java executable path setting.
    private final JTextField javaPathField = new JTextField();

    /// Editable custom Java executable path and file-selection command.
    private final InstanceJavaPathControls javaPathControls;

    /// Persisted detected Java runtime reference setting.
    private final JComboBox<DetectedJavaChoice> detectedJavaComboBox = new JComboBox<>();

    /// Game-window mode setting.
    private final InheritedControl<JComboBox<GameWindowType>> windowTypeControl = inheritedControl(
            "instanceGameSettingsWindowType",
            new JComboBox<>(GameWindowType.values()));

    /// Game-window width setting.
    private final InheritedControl<JTextField> windowWidthControl =
            inheritedControl("instanceGameSettingsWindowWidth", new JTextField());

    /// Game-window height setting.
    private final InheritedControl<JTextField> windowHeightControl =
            inheritedControl("instanceGameSettingsWindowHeight", new JTextField());

    /// Launcher visibility behavior setting.
    private final InheritedControl<JComboBox<LauncherVisibility>> launcherVisibilityControl = inheritedControl(
            "instanceGameSettingsLauncherVisibility",
            new JComboBox<>(LauncherVisibility.values()));

    /// Automatic Java-agent permission setting.
    private final InheritedControl<JCheckBox> allowAutoAgentControl =
            inheritedControl("instanceGameSettingsAllowAutoAgent", new JCheckBox());

    /// Automatic game-options adjustment setting.
    private final InheritedControl<JCheckBox> disableAutoGameOptionsControl =
            inheritedControl("instanceGameSettingsDisableAutoGameOptions", new JCheckBox());

    /// Launch log-window visibility setting.
    private final InheritedControl<JCheckBox> showLogsControl =
            inheritedControl("instanceGameSettingsShowLogs", new JCheckBox());

    /// Debug log output setting.
    private final InheritedControl<JCheckBox> debugLogControl =
            inheritedControl("instanceGameSettingsDebugLog", new JCheckBox());

    /// Game completeness validation setting.
    private final InheritedControl<JCheckBox> notCheckGameControl =
            inheritedControl("instanceGameSettingsSkipGameCheck", new JCheckBox());

    /// Quick Play destination type setting.
    private final InheritedControl<JComboBox<QuickPlayType>> quickPlayTypeControl = inheritedControl(
            "instanceGameSettingsQuickPlayMode",
            new JComboBox<>(QuickPlayType.values()));

    /// Quick Play multiplayer server setting.
    private final InheritedControl<JTextField> quickPlayMultiplayerControl =
            inheritedControl("instanceGameSettingsQuickPlayMultiplayer", new JTextField());

    /// Quick Play singleplayer world setting.
    private final InheritedControl<JTextField> quickPlaySingleplayerControl =
            inheritedControl("instanceGameSettingsQuickPlaySingleplayer", new JTextField());

    /// Quick Play Realms target setting.
    private final InheritedControl<JTextField> quickPlayRealmsControl =
            inheritedControl("instanceGameSettingsQuickPlayRealms", new JTextField());

    /// Game working-directory setting.
    private final InheritedControl<JTextField> runningDirectoryControl =
            inheritedControl("instanceGameSettingsRunningDirectory", new JTextField());

    /// Explicit isolation switch and editable directory chooser sharing the running-directory controls.
    private final InstanceIsolationControls isolationControls;

    /// Parent global game-settings preset selector and preview control.
    private final InstanceParentPresetControls parentPresetControls = new InstanceParentPresetControls();

    /// Additional Minecraft arguments setting.
    private final InheritedControl<JTextField> gameArgumentsControl =
            inheritedControl("instanceGameSettingsGameArguments", new JTextField());

    /// Game process environment-variable setting.
    private final InheritedControl<JTextField> environmentVariablesControl =
            inheritedControl("instanceGameSettingsEnvironmentVariables", new JTextField());

    /// Game process priority setting.
    private final InheritedControl<JComboBox<ProcessPriority>> processPriorityControl = inheritedControl(
            "instanceGameSettingsProcessPriority",
            new JComboBox<>(ProcessPriority.values()));

    /// Default JVM argument suppression setting.
    private final InheritedControl<JCheckBox> noJvmOptionsControl =
            inheritedControl("instanceGameSettingsNoJvmOptions", new JCheckBox());

    /// Default JVM optimization argument suppression setting.
    private final InheritedControl<JCheckBox> noOptimizingJvmOptionsControl =
            inheritedControl("instanceGameSettingsNoOptimizingJvmOptions", new JCheckBox());

    /// JVM compatibility validation setting.
    private final InheritedControl<JCheckBox> notCheckJvmControl =
            inheritedControl("instanceGameSettingsSkipJvmCheck", new JCheckBox());

    /// Additional JVM arguments setting.
    private final InheritedControl<JTextArea> jvmOptionsControl =
            inheritedControl("instanceGameSettingsJvmOptions", createTextArea());

    /// Legacy minimum heap allocation setting.
    private final InheritedControl<JTextField> minimumMemoryControl =
            inheritedControl("instanceGameSettingsMinimumMemory", new JTextField());

    /// Legacy permanent-generation allocation setting.
    private final InheritedControl<JTextField> permanentGenerationControl =
            inheritedControl("instanceGameSettingsPermanentGeneration", new JTextField());

    /// Pre-launch command setting.
    private final InheritedControl<JTextField> preLaunchCommandControl =
            inheritedControl("instanceGameSettingsPreLaunchCommand", new JTextField());

    /// Wrapper command setting.
    private final InheritedControl<JTextField> commandWrapperControl =
            inheritedControl("instanceGameSettingsCommandWrapper", new JTextField());

    /// Post-exit command setting.
    private final InheritedControl<JTextField> postExitCommandControl =
            inheritedControl("instanceGameSettingsPostExitCommand", new JTextField());

    /// Graphics API setting.
    private final InheritedControl<JComboBox<GraphicsAPI>> graphicsBackendControl = inheritedControl(
            "instanceGameSettingsGraphicsBackend",
            new JComboBox<>(GraphicsAPI.values()));

    /// OpenGL renderer setting.
    private final InheritedControl<JComboBox<Renderer>> openGlRendererControl = inheritedControl(
            "instanceGameSettingsOpenGlRenderer",
            new JComboBox<>());

    /// Vulkan renderer setting.
    private final InheritedControl<JComboBox<Renderer>> vulkanRendererControl = inheritedControl(
            "instanceGameSettingsVulkanRenderer",
            new JComboBox<>());

    /// Version-gated graphics-backend row.
    private final JPanel graphicsBackendRow = inheritedRowPanel(
            "instanceGameSettingsGraphicsBackendRow",
            i18n("settings.advanced.graphics_backend"),
            graphicsBackendControl);

    /// OpenGL renderer row, visible for every known game version.
    private final JPanel openGlRendererRow = inheritedRowPanel(
            "instanceGameSettingsOpenGlRendererRow",
            i18n("settings.advanced.renderer.opengl"),
            openGlRendererControl);

    /// Version-gated Vulkan renderer row.
    private final JPanel vulkanRendererRow = inheritedRowPanel(
            "instanceGameSettingsVulkanRendererRow",
            i18n("settings.advanced.renderer.vulkan"),
            vulkanRendererControl);

    /// Custom native-library directory activation setting.
    private final InheritedControl<JCheckBox> useCustomNativesControl =
            inheritedControl("instanceGameSettingsUseCustomNatives", new JCheckBox());

    /// Native-library directory setting.
    private final InheritedControl<JTextField> nativesDirectoryControl =
            inheritedControl("instanceGameSettingsNativesDirectory", new JTextField());

    /// Automatic native-library patch suppression setting.
    private final InheritedControl<JCheckBox> notPatchNativesControl =
            inheritedControl("instanceGameSettingsDisableNativePatching", new JCheckBox());

    /// System GLFW setting.
    private final InheritedControl<JCheckBox> nativeGlfwControl =
            inheritedControl("instanceGameSettingsUseNativeGlfw", new JCheckBox());

    /// System OpenAL setting.
    private final InheritedControl<JCheckBox> nativeOpenAlControl =
            inheritedControl("instanceGameSettingsUseNativeOpenAl", new JCheckBox());

    /// Status, persistence, reload, and read-only recovery footer.
    private final InstanceGameSettingsFooterControls footerControls;

    /// Snapshot currently represented by the controls, or `null` during construction only.
    private @Nullable InstanceGameSettingsSnapshot displayedSnapshot;

    /// Prevents programmatic snapshot application from acting like user edits.
    private boolean applyingSnapshot;

    /// Allows an owning asynchronous workflow to freeze the complete editor without closing it.
    private boolean interactionEnabled = true;

    /// Prevents interaction after lifecycle cleanup.
    private boolean closed;

    /// Prevents duplicate local Java refresh requests while discovery initializes.
    private boolean javaRefreshRequested;

    /// Whether the user has requested the potentially expensive detected-Java choice list.
    private boolean detectedJavaChoicesRequested;

    /// Runtime snapshot revision currently being converted to detected-Java identities, or `-1` when idle.
    private long detectedJavaLoadingRevision = -1L;

    /// Runtime snapshot revision currently represented by the combo model, or `-1` before its first lazy load.
    private long detectedJavaLoadedRevision = -1L;

    /// Whether OpenGL renderer choices are being loaded.
    private boolean openGlRendererLoading;

    /// Whether OpenGL renderer choices have been loaded successfully.
    private boolean openGlRendererLoaded;

    /// Whether Vulkan renderer choices are being loaded.
    private boolean vulkanRendererLoading;

    /// Whether Vulkan renderer choices have been loaded successfully.
    private boolean vulkanRendererLoaded;

    /// Creates a production panel backed by one repository instance.
    ///
    /// @param repository repository containing the managed instance
    /// @param instanceId stable non-blank instance identifier
    /// @param executor background executor used for time-consuming game-version detection
    public InstanceGameSettingsPanel(
            XYMLGameRepository repository,
            GameInstanceID instanceId,
            Executor executor) {
        this(repository, instanceId, executor, () -> { });
    }

    /// Creates a production panel and reports successful working-directory changes to its instance workspace.
    ///
    /// @param repository repository containing the managed instance
    /// @param instanceId stable non-blank instance identifier
    /// @param executor background executor used for time-consuming game-version detection
    /// @param workingDirectoryChanged callback invalidating pages backed by the previous working directory
    InstanceGameSettingsPanel(
            XYMLGameRepository repository,
            GameInstanceID instanceId,
            Executor executor,
            Runnable workingDirectoryChanged) {
        this(
                new RepositoryInstanceGameSettingsStore(repository, instanceId),
                new JavaManagerRuntimeManagementService(),
                loadGameVersion(repository, instanceId, executor),
                GameSettingsEditorPresentation.INSTANCE,
                workingDirectoryChanged);
    }

    /// Creates an editor over an explicit store for either instance or embedded global-preset presentation.
    ///
    /// Global-preset callers own persistence and therefore use [#editedSnapshot()] with a store whose snapshot can
    /// be replaced before [#reloadFromStore()] is invoked.
    ///
    /// @param store backing store for complete setting values
    /// @param presentation instance or embedded global-preset presentation
    public InstanceGameSettingsPanel(
            InstanceGameSettingsStore store,
            GameSettingsEditorPresentation presentation) {
        this(
                store,
                new JavaManagerRuntimeManagementService(),
                CompletableFuture.completedFuture(GameVersionNumber.unknown()),
                presentation);
    }

    /// Creates a settings panel with an explicit store for deterministic UI testing.
    ///
    /// @param store backing store for effective values and persistence
    InstanceGameSettingsPanel(InstanceGameSettingsStore store) {
        this(
                store,
                new JavaManagerRuntimeManagementService(),
                CompletableFuture.completedFuture(GameVersionNumber.unknown()),
                GameSettingsEditorPresentation.INSTANCE);
    }

    /// Creates a settings panel with explicit persistence and local-Java services for focused tests.
    ///
    /// @param store backing store for effective values and persistence
    /// @param javaRuntimeService non-blocking local Java discovery service
    InstanceGameSettingsPanel(
            InstanceGameSettingsStore store,
            JavaRuntimeManagementService javaRuntimeService) {
        this(
                store,
                javaRuntimeService,
                CompletableFuture.completedFuture(GameVersionNumber.unknown()),
                GameSettingsEditorPresentation.INSTANCE);
    }

    /// Creates a settings panel with explicit persistence, local-Java, and version services for focused tests.
    ///
    /// @param store backing store for effective values and persistence
    /// @param javaRuntimeService non-blocking local Java discovery service
    /// @param gameVersionStage asynchronous instance game-version result
    InstanceGameSettingsPanel(
            InstanceGameSettingsStore store,
            JavaRuntimeManagementService javaRuntimeService,
            CompletionStage<GameVersionNumber> gameVersionStage) {
        this(store, javaRuntimeService, gameVersionStage, GameSettingsEditorPresentation.INSTANCE);
    }

    /// Creates a settings panel with explicit persistence, local-Java, version, and presentation services.
    ///
    /// @param store backing store for effective values and persistence
    /// @param javaRuntimeService non-blocking local Java discovery service
    /// @param gameVersionStage asynchronous instance game-version result
    /// @param presentation instance or embedded global-preset presentation
    public InstanceGameSettingsPanel(
            InstanceGameSettingsStore store,
            JavaRuntimeManagementService javaRuntimeService,
            CompletionStage<GameVersionNumber> gameVersionStage,
            GameSettingsEditorPresentation presentation) {
        this(store, javaRuntimeService, gameVersionStage, presentation, () -> { });
    }

    /// Creates a settings panel with explicit services and a successful working-directory change callback.
    ///
    /// @param store backing store for effective values and persistence
    /// @param javaRuntimeService non-blocking local Java discovery service
    /// @param gameVersionStage asynchronous instance game-version result
    /// @param presentation instance or embedded global-preset presentation
    /// @param workingDirectoryChanged callback invalidating pages backed by the previous working directory
    InstanceGameSettingsPanel(
            InstanceGameSettingsStore store,
            JavaRuntimeManagementService javaRuntimeService,
            CompletionStage<GameVersionNumber> gameVersionStage,
            GameSettingsEditorPresentation presentation,
            Runnable workingDirectoryChanged) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.store = Objects.requireNonNull(store, "store");
        this.presentation = Objects.requireNonNull(presentation, "presentation");
        memoryModeSelector = new InstanceMemoryModeSelector(
                this.presentation == GameSettingsEditorPresentation.INSTANCE);
        maximumMemoryField.setName("instanceGameSettingsMaximumMemory");
        memoryAllocationControls = new InstanceMemoryAllocationControls(memoryModeSelector, maximumMemoryField);
        javaModeSelector = new InstanceJavaModeSelector(this.presentation == GameSettingsEditorPresentation.INSTANCE);
        javaVersionField.setName("instanceGameSettingsJavaVersion");
        javaPathField.setName("instanceGameSettingsJavaPath");
        detectedJavaComboBox.setName("instanceGameSettingsDetectedJava");
        javaPathControls = new InstanceJavaPathControls(javaPathField);
        isolationControls = new InstanceIsolationControls(
                store.forcedRunningDirectory(),
                runningDirectoryControl.overrideBox(),
                runningDirectoryControl.editor(),
                this::updateEditingAvailability);
        footerControls = new InstanceGameSettingsFooterControls(
                store,
                this::saveEditedSnapshot,
                this::reloadSnapshot);
        this.javaRuntimeService = Objects.requireNonNull(javaRuntimeService, "javaRuntimeService");
        this.workingDirectoryChanged = Objects.requireNonNull(
                workingDirectoryChanged,
                "workingDirectoryChanged");
        configureComponents();
        javaRuntimeSubscription = javaRuntimeService.subscribe(change -> javaRuntimeSnapshotChanged());
        applySnapshot(store.snapshot());
        applyGraphicsCompatibility(GameVersionNumber.unknown());
        Objects.requireNonNull(gameVersionStage, "gameVersionStage").whenComplete((version, failure) ->
                SwingUiDispatcher.INSTANCE.dispatch(() -> completeGameVersion(version, failure)));
    }

    /// Starts time-consuming instance game-version detection away from the EDT.
    ///
    /// @param repository repository containing the managed instance
    /// @param instanceId stable instance identifier
    /// @param executor background executor
    /// @return asynchronous parsed game version
    private static CompletionStage<GameVersionNumber> loadGameVersion(
            XYMLGameRepository repository,
            GameInstanceID instanceId,
            Executor executor) {
        XYMLGameRepository requiredRepository = Objects.requireNonNull(repository, "repository");
        GameInstanceID requiredInstanceId = Objects.requireNonNull(instanceId, "instanceId");
        Executor requiredExecutor = Objects.requireNonNull(executor, "executor");
        return CompletableFuture.supplyAsync(
                () -> GameVersionNumber.asGameVersion(requiredRepository.getGameVersion(requiredInstanceId)),
                requiredExecutor);
    }

    /// Returns the snapshot currently represented by the UI controls.
    ///
    /// @return displayed settings snapshot
    public InstanceGameSettingsSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(displayedSnapshot, "initial game settings snapshot was not applied");
    }

    /// Replaces the rendered controls with the latest snapshot supplied by the backing store.
    ///
    /// Embedded global-preset editors call this after changing which preset their store represents.
    public void reloadFromStore() {
        reloadSnapshot();
    }

    /// Enables or freezes every editor control while preserving the current draft values.
    ///
    /// @param enabled whether users may interact with the editor
    public void setInteractionEnabled(boolean enabled) {
        EdtDispatcher.requireEventDispatchThread();
        interactionEnabled = enabled && !closed;
        updateEditingAvailability();
    }

    /// Releases this panel and prevents further persistence requests.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                closed = true;
                interactionEnabled = false;
                javaRuntimeSubscription.unsubscribe();
                updateEditingAvailability();
            }
        });
    }

    /// Builds the heading, tabs, footer, renderers, and control interactions.
    private void configureComponents() {
        setName(presentation == GameSettingsEditorPresentation.INSTANCE
                ? "instanceGameSettings"
                : "globalGameSettingsPresetEditor");
        setOpaque(false);
        if (presentation == GameSettingsEditorPresentation.INSTANCE) {
            add(createHeader(), BorderLayout.NORTH);
        }
        add(createSettingsTabs(), BorderLayout.CENTER);
        if (presentation == GameSettingsEditorPresentation.INSTANCE) {
            add(footerControls.component(), BorderLayout.SOUTH);
        }

        configureChoiceRenderers();
        configureLazyChoices();
        configureControlInteractions();
        if (presentation == GameSettingsEditorPresentation.GLOBAL_PRESET) {
            for (InheritedControl<? extends JComponent> control : allControls()) {
                control.overrideBox().setSelected(true);
                control.overrideBox().setVisible(false);
            }
        }
    }

    /// Creates the page heading.
    /// @return unframed heading panel
    private static JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(18, 20, 8, 20));
        JLabel heading = new JLabel(i18n("settings.game"));
        heading.setName("instanceGameSettingsTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 22.0F));
        header.add(heading, BorderLayout.WEST);
        return header;
    }

    /// Creates all six settings tabs.
    /// @return configured tabbed surface
    private JTabbedPane createSettingsTabs() {
        settingsTabs.setName(presentation == GameSettingsEditorPresentation.INSTANCE
                ? "instanceGameSettingsTabs"
                : "globalGameSettingsPresetTabs");
        SwingTransparency.revealBackgroundThroughTabs(settingsTabs);
        settingsTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        settingsTabs.addTab(i18n("settings.game"), createScrollableTab(createGameSettingsTab()));
        settingsTabs.addTab(i18n("settings.launcher"), createScrollableTab(createLauncherSettingsTab()));
        settingsTabs.addTab(i18n("settings.advanced.jvm"), createScrollableTab(createJvmSettingsTab()));
        settingsTabs.addTab(
                i18n("settings.advanced.custom_commands"),
                createScrollableTab(createCommandSettingsTab()));
        settingsTabs.addTab(i18n("settings.advanced.graphics"), createScrollableTab(createGraphicsSettingsTab()));
        settingsTabs.addTab(
                i18n("settings.advanced.natives_settings"),
                createScrollableTab(createNativeSettingsTab()));
        return settingsTabs;
    }

    /// Creates the game, Java, window, Quick Play, and general launch settings tab.
    /// @return game settings content
    private JPanel createGameSettingsTab() {
        JPanel content = tabContent("instanceGameSettingsGameTab");

        if (presentation == GameSettingsEditorPresentation.INSTANCE) {
            content.add(parentPresetControls.createRow(), "growx");
            content.add(new JSeparator(), "growx");
            content.add(isolationControls.createIsolationRow(), "growx");
            content.add(new JSeparator(), "growx");
        }

        JPanel memory = sectionPanel("instanceGameSettingsMemory", i18n("settings.memory"));
        memoryModeSelector.addRows(memory, maximumMemoryField);
        memory.add(memoryAllocationControls.component(), "skip 1, span 2, growx");
        content.add(memory, "growx");
        content.add(new JSeparator(), "growx");

        JPanel java = sectionPanel("instanceGameSettingsJava", i18n("settings.game.java_directory"));
        javaModeSelector.addRows(
                java,
                javaVersionField,
                javaPathControls.component(),
                detectedJavaComboBox);
        content.add(java, "growx");
        content.add(new JSeparator(), "growx");

        JPanel window = sectionPanel("instanceGameSettingsWindow", i18n("settings.game.window_type"));
        addControlRow(window, i18n("settings.game.window_type"), windowTypeControl);
        window.add(new InstanceWindowSizeControls(
                windowWidthControl.overrideBox(),
                windowHeightControl.overrideBox(),
                windowWidthControl.editor(),
                windowHeightControl.editor(),
                windowTypeControl.editor()).component(), "span 3, growx");
        addControlRow(window, i18n("settings.game.window_width"), windowWidthControl);
        addControlRow(window, i18n("settings.game.window_height"), windowHeightControl);
        content.add(window, "growx");
        content.add(new JSeparator(), "growx");

        JPanel quickPlay = sectionPanel("instanceGameSettingsQuickPlay", i18n("settings.game.quick_play"));
        addControlRow(quickPlay, i18n("settings.game.quick_play"), quickPlayTypeControl);
        addControlRow(quickPlay, i18n("settings.game.quick_play.multiplayer"), quickPlayMultiplayerControl);
        addControlRow(quickPlay, i18n("settings.game.quick_play.singleplayer"), quickPlaySingleplayerControl);
        addControlRow(quickPlay, i18n("settings.game.quick_play.realms"), quickPlayRealmsControl);
        content.add(quickPlay, "growx");
        content.add(new JSeparator(), "growx");

        JPanel launch = sectionPanel(
                "instanceGameSettingsLaunchOptions",
                i18n("settings.advanced.launch_options"));
        isolationControls.addRunningDirectoryRow(launch, presentation == GameSettingsEditorPresentation.GLOBAL_PRESET);
        addControlRow(launch, i18n("settings.advanced.minecraft_arguments"), gameArgumentsControl);
        addControlRow(launch, i18n("settings.advanced.environment_variables"), environmentVariablesControl);
        addControlRow(launch, i18n("settings.advanced.process_priority"), processPriorityControl);
        content.add(launch, "growx");
        return content;
    }

    /// Creates launcher behavior and diagnostics controls.
    /// @return launcher settings content
    private JPanel createLauncherSettingsTab() {
        JPanel content = tabContent("instanceGameSettingsLauncherTab");
        JPanel section = sectionPanel("instanceGameSettingsLauncher", i18n("settings.launcher"));
        addControlRow(section, i18n("settings.advanced.launcher_visible"), launcherVisibilityControl);
        addBooleanControlRow(section, i18n("settings.launcher.allow_auto_agent"), allowAutoAgentControl);
        addBooleanControlRow(
                section,
                i18n("settings.launcher.disable_auto_game_options"),
                disableAutoGameOptionsControl);
        addBooleanControlRow(section, i18n("settings.show_log"), showLogsControl);
        addBooleanControlRow(section, i18n("settings.enable_debug_log_output"), debugLogControl);
        addBooleanControlRow(
                section,
                i18n("settings.advanced.dont_check_game_completeness"),
                notCheckGameControl);
        content.add(section, "growx");
        return content;
    }

    /// Creates JVM validation, argument, and compatibility controls.
    /// @return JVM settings content
    private JPanel createJvmSettingsTab() {
        JPanel content = tabContent("instanceGameSettingsJvmTab");
        JPanel section = sectionPanel("instanceGameSettingsJvm", i18n("settings.advanced.jvm"));
        addBooleanControlRow(section, i18n("settings.advanced.no_jvm_args"), noJvmOptionsControl);
        addBooleanControlRow(
                section,
                i18n("settings.advanced.no_optimizing_jvm_args"),
                noOptimizingJvmOptionsControl);
        addBooleanControlRow(section, i18n("settings.advanced.dont_check_jvm_validity"), notCheckJvmControl);
        addTextAreaRow(section, i18n("settings.advanced.jvm_args"), jvmOptionsControl);
        addControlRow(section, i18n("settings.memory.lower_bound"), minimumMemoryControl);
        addControlRow(
                section,
                i18n("settings.advanced.java_permanent_generation_space"),
                permanentGenerationControl);
        content.add(section, "growx");
        return content;
    }

    /// Creates custom command-hook controls.
    /// @return command settings content
    private JPanel createCommandSettingsTab() {
        JPanel content = tabContent("instanceGameSettingsCommandsTab");
        JPanel section = sectionPanel(
                "instanceGameSettingsCommands",
                i18n("settings.advanced.custom_commands"));
        addControlRow(section, i18n("settings.advanced.precall_command"), preLaunchCommandControl);
        addControlRow(section, i18n("settings.advanced.wrapper_launcher"), commandWrapperControl);
        addControlRow(section, i18n("settings.advanced.post_exit_command"), postExitCommandControl);
        content.add(section, "growx");
        return content;
    }

    /// Creates graphics API and renderer controls.
    ///
    /// @return graphics settings content
    private JPanel createGraphicsSettingsTab() {
        JPanel content = tabContent("instanceGameSettingsGraphicsTab");
        JPanel section = sectionPanel("instanceGameSettingsGraphics", i18n("settings.advanced.graphics"));
        section.add(graphicsBackendRow, "span 3, growx");
        section.add(openGlRendererRow, "span 3, growx");
        section.add(vulkanRendererRow, "span 3, growx");
        content.add(section, "growx");
        return content;
    }

    /// Creates native-library replacement controls.
    ///
    /// @return native-library settings content
    private JPanel createNativeSettingsTab() {
        JPanel content = tabContent("instanceGameSettingsNativeTab");
        JPanel section = sectionPanel(
                "instanceGameSettingsNativeLibraries",
                i18n("settings.advanced.natives_settings"));
        addBooleanControlRow(
                section,
                i18n("settings.advanced.natives_directory.custom.enabled"),
                useCustomNativesControl);
        addControlRow(section, i18n("settings.advanced.natives_directory"), nativesDirectoryControl);
        addBooleanControlRow(section, i18n("settings.advanced.dont_patch_natives"), notPatchNativesControl);
        addBooleanControlRow(section, i18n("settings.advanced.use_native_glfw"), nativeGlfwControl);
        addBooleanControlRow(section, i18n("settings.advanced.use_native_openal"), nativeOpenAlControl);
        content.add(section, "growx");
        return content;
    }

    /// Configures localized display text for enum and renderer choices.
    private void configureChoiceRenderers() {
        installRenderer(windowTypeControl.editor(), InstanceGameSettingsPanel::windowTypeName);
        installRenderer(
                launcherVisibilityControl.editor(),
                value -> i18n("settings.advanced.launcher_visibility." + enumKey(value)));
        installRenderer(quickPlayTypeControl.editor(), InstanceGameSettingsPanel::quickPlayTypeName);
        installRenderer(
                processPriorityControl.editor(),
                value -> i18n("settings.advanced.process_priority." + enumKey(value)));
        installRenderer(
                graphicsBackendControl.editor(),
                value -> i18n("settings.advanced.graphics_backend." + enumKey(value)));
        installRenderer(openGlRendererControl.editor(), InstanceGameSettingsPanel::rendererName);
        installRenderer(vulkanRendererControl.editor(), InstanceGameSettingsPanel::rendererName);
    }

    /// Configures popup-triggered local Java and renderer choice loading.
    private void configureLazyChoices() {
        detectedJavaComboBox.addPopupMenuListener(new PopupOpeningListener(() -> {
            detectedJavaChoicesRequested = true;
            requestDetectedJavaChoices();
            requestJavaRefreshIfNecessary();
        }));
        openGlRendererControl.editor().addPopupMenuListener(new PopupOpeningListener(
                () -> requestRendererChoices(GraphicsAPI.OPENGL)));
        vulkanRendererControl.editor().addPopupMenuListener(new PopupOpeningListener(
                () -> requestRendererChoices(GraphicsAPI.VULKAN)));
    }

    /// Connects override, dependent-value, save, and reload interactions.
    private void configureControlInteractions() {
        for (InheritedControl<? extends JComponent> control : allControls()) {
            control.overrideBox().addActionListener(event -> {
                updateOverrideTooltip(control);
                updateEditingAvailability();
            });
        }
        memoryModeSelector.addSelectionListener(this::updateEditingAvailability);
        javaModeSelector.addSelectionListener(this::updateEditingAvailability);
        quickPlayTypeControl.editor().addActionListener(event -> updateEditingAvailability());
        noJvmOptionsControl.editor().addActionListener(event -> updateEditingAvailability());
        graphicsBackendControl.editor().addActionListener(event -> updateEditingAvailability());
        useCustomNativesControl.editor().addActionListener(event -> updateEditingAvailability());
        parentPresetControls.addSelectionListener(this::previewParentPreset);
    }

    /// Resolves unsaved local overrides against the newly selected parent preset.
    private void previewParentPreset() {
        EdtDispatcher.requireEventDispatchThread();
        if (applyingSnapshot || closed || presentation != GameSettingsEditorPresentation.INSTANCE) {
            return;
        }
        try {
            applySnapshot(store.preview(editedSnapshot()));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            footerControls.setStatus(i18n(
                    "swing.instance_settings.reload_failed",
                    Objects.requireNonNullElse(
                            exception.getMessage(),
                            i18n("swing.instance_settings.unavailable"))));
        }
    }

    /// Persists the currently edited values or presents one concise validation failure.
    private void saveEditedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        try {
            InstanceGameSettingsSnapshot previous = displayedSnapshot();
            store.save(editedSnapshot());
            InstanceGameSettingsSnapshot saved = store.snapshot();
            applySnapshot(saved);
            footerControls.setStatus(i18n("message.success"));
            if (workingDirectoryChanged(previous, saved)) {
                workingDirectoryChanged.run();
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            footerControls.setStatus(i18n(
                    "swing.instance_settings.save_failed",
                    Objects.requireNonNullElse(
                            exception.getMessage(),
                            i18n("swing.instance_settings.invalid"))));
        }
    }

    /// Returns whether saved settings resolve instance content from a different working-directory configuration.
    ///
    /// @param previous settings rendered before persistence
    /// @param saved durable settings returned after persistence
    /// @return whether directory-dependent instance pages must be rebuilt
    private static boolean workingDirectoryChanged(
            InstanceGameSettingsSnapshot previous,
            InstanceGameSettingsSnapshot saved) {
        InstanceGameSettingsSnapshot.LaunchOptionsSettings before = previous.launchOptions();
        InstanceGameSettingsSnapshot.LaunchOptionsSettings after = saved.launchOptions();
        return before.runningDirectoryOverridden() != after.runningDirectoryOverridden()
                || !before.runningDirectory().equals(after.runningDirectory());
    }

    /// Restores the visible controls from the latest durable values.
    private void reloadSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        try {
            applySnapshot(store.snapshot());
            footerControls.setStatus(i18n("message.success"));
        } catch (IllegalStateException exception) {
            footerControls.setStatus(i18n(
                    "swing.instance_settings.reload_failed",
                    Objects.requireNonNullElse(
                            exception.getMessage(),
                            i18n("swing.instance_settings.unavailable"))));
        }
    }

    /// Reads every editor and validates user-controlled values before storage.
    ///
    /// This is the persistence boundary used by embedded global-preset presentation; it never mutates the backing
    /// store by itself.
    ///
    /// @return complete edited snapshot
    public InstanceGameSettingsSnapshot editedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            throw new IllegalStateException("Game settings editor is closed");
        }
        InstanceGameSettingsSnapshot current = displayedSnapshot();

        boolean memoryModeOverridden = !memoryModeSelector.isInherited();
        boolean automaticMemory = memoryModeOverridden
                ? memoryModeSelector.isAutomatic()
                : current.memory().automatic();
        boolean maximumMemoryOverridden = memoryModeOverridden
                && (presentation == GameSettingsEditorPresentation.GLOBAL_PRESET || !automaticMemory);
        int maximumMemory = maximumMemoryOverridden && !automaticMemory
                ? parseRequiredInteger(
                        maximumMemoryField.getText(),
                        "Maximum memory",
                        1,
                        MAXIMUM_MEMORY_MIB)
                : current.memory().maximumMiB();
        boolean javaTypeOverridden = !javaModeSelector.isInherited();
        JavaVersionType javaType = javaTypeOverridden
                ? javaModeSelector.selectedMode()
                : current.javaRuntime().type();
        String javaVersion = javaTypeOverridden
                ? javaVersionField.getText().trim()
                : current.javaRuntime().customVersion();
        String javaPath = javaTypeOverridden
                ? javaPathField.getText().trim()
                : current.javaRuntime().customPath();
        GameSettings.DetectedJava detectedJava = javaTypeOverridden
                ? selectedOrStoredDetectedJava()
                : current.javaRuntime().detectedJava();
        if (javaTypeOverridden) {
            validateJavaSettings(javaType, javaVersion, javaPath, detectedJava);
        }
        if (javaTypeOverridden && javaType == JavaVersionType.CUSTOM) {
            GameSettingsEditorValidation.validatePath(
                    true,
                    javaPathField.getText(),
                    "custom Java path");
        }

        double windowWidth = editedRequiredDouble(
                windowWidthControl,
                current.window().width(),
                "Window width");
        double windowHeight = editedRequiredDouble(
                windowHeightControl,
                current.window().height(),
                "Window height");
        GameSettingsEditorValidation.validatePath(
                runningDirectoryControl.overrideBox().isSelected(),
                runningDirectoryControl.editor().getText(),
                "game working directory");
        boolean useCustomNatives = editedBoolean(
                useCustomNativesControl,
                current.nativeLibraries().customDirectoryEnabled());
        if (useCustomNatives) {
            GameSettingsEditorValidation.validatePath(
                    nativesDirectoryControl.overrideBox().isSelected(),
                    nativesDirectoryControl.editor().getText(),
                    "native library directory");
        }
        QuickPlayType quickPlayType = editedChoice(
                quickPlayTypeControl,
                current.quickPlay().type(),
                "Quick Play type");
        GameSettingsEditorValidation.validateQuickPlayTargets(
                quickPlayType,
                quickPlayMultiplayerControl.overrideBox().isSelected(),
                quickPlayMultiplayerControl.editor().getText(),
                quickPlaySingleplayerControl.overrideBox().isSelected(),
                quickPlaySingleplayerControl.editor().getText());

        @Nullable Integer minimumMemory = editedOptionalInteger(
                minimumMemoryControl,
                current.jvm().minimumMemoryMiB(),
                "Minimum memory",
                0,
                MAXIMUM_MEMORY_MIB);
        String permanentGeneration = editedText(
                permanentGenerationControl,
                current.jvm().permanentGenerationMiB());
        String normalizedPermanentGeneration = permanentGeneration.trim();
        if (permanentGenerationControl.overrideBox().isSelected()
                && !normalizedPermanentGeneration.isEmpty()
                && !normalizedPermanentGeneration.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Permanent generation size must be a whole number of MiB");
        }

        return new InstanceGameSettingsSnapshot(
                current.writable(),
                parentPresetControls.edited(current.parentPreset()),
                new InstanceGameSettingsSnapshot.MemorySettings(
                        memoryModeOverridden,
                        automaticMemory,
                        maximumMemoryOverridden,
                        maximumMemory),
                new InstanceGameSettingsSnapshot.JavaRuntimeSettings(
                        javaTypeOverridden,
                        javaType,
                        javaTypeOverridden
                                && (presentation == GameSettingsEditorPresentation.GLOBAL_PRESET
                                || javaType == JavaVersionType.VERSION),
                        javaVersion,
                        javaTypeOverridden
                                && (presentation == GameSettingsEditorPresentation.GLOBAL_PRESET
                                || javaType == JavaVersionType.CUSTOM),
                        javaPath,
                        javaTypeOverridden
                                && (presentation == GameSettingsEditorPresentation.GLOBAL_PRESET
                                || javaType == JavaVersionType.DETECTED),
                        detectedJava),
                new InstanceGameSettingsSnapshot.WindowSettings(
                        windowTypeControl.overrideBox().isSelected(),
                        editedChoice(windowTypeControl, current.window().type(), "window type"),
                        windowWidthControl.overrideBox().isSelected(),
                        windowWidth,
                        windowHeightControl.overrideBox().isSelected(),
                        windowHeight),
                editedLauncherSettings(current.launcher()),
                editedQuickPlaySettings(current.quickPlay()),
                editedLaunchOptions(current.launchOptions()),
                new InstanceGameSettingsSnapshot.JvmSettings(
                        noJvmOptionsControl.overrideBox().isSelected(),
                        editedBoolean(noJvmOptionsControl, current.jvm().noOptions()),
                        noOptimizingJvmOptionsControl.overrideBox().isSelected(),
                        editedBoolean(
                                noOptimizingJvmOptionsControl,
                                current.jvm().noOptimizingOptions()),
                        notCheckJvmControl.overrideBox().isSelected(),
                        editedBoolean(notCheckJvmControl, current.jvm().notCheckJvm()),
                        jvmOptionsControl.overrideBox().isSelected(),
                        editedTextArea(jvmOptionsControl, current.jvm().options()),
                        minimumMemoryControl.overrideBox().isSelected(),
                        minimumMemory,
                        permanentGenerationControl.overrideBox().isSelected(),
                        permanentGeneration),
                new InstanceGameSettingsSnapshot.CommandSettings(
                        preLaunchCommandControl.overrideBox().isSelected(),
                        editedRawText(preLaunchCommandControl, current.commands().preLaunch()),
                        commandWrapperControl.overrideBox().isSelected(),
                        editedRawText(commandWrapperControl, current.commands().wrapper()),
                        postExitCommandControl.overrideBox().isSelected(),
                        editedRawText(postExitCommandControl, current.commands().postExit())),
                new InstanceGameSettingsSnapshot.GraphicsSettings(
                        graphicsBackendControl.overrideBox().isSelected(),
                        editedChoice(
                                graphicsBackendControl,
                                current.graphics().backend(),
                                "graphics API"),
                        openGlRendererControl.overrideBox().isSelected(),
                        editedChoice(
                                openGlRendererControl,
                                current.graphics().openGlRenderer(),
                                "OpenGL renderer"),
                        vulkanRendererControl.overrideBox().isSelected(),
                        editedChoice(
                                vulkanRendererControl,
                                current.graphics().vulkanRenderer(),
                                "Vulkan renderer")),
                new InstanceGameSettingsSnapshot.NativeLibrarySettings(
                        useCustomNativesControl.overrideBox().isSelected(),
                        editedBoolean(
                                useCustomNativesControl,
                                current.nativeLibraries().customDirectoryEnabled()),
                        nativesDirectoryControl.overrideBox().isSelected(),
                        editedText(nativesDirectoryControl, current.nativeLibraries().directory()),
                        notPatchNativesControl.overrideBox().isSelected(),
                        editedBoolean(
                                notPatchNativesControl,
                                current.nativeLibraries().patchingDisabled()),
                        nativeGlfwControl.overrideBox().isSelected(),
                        editedBoolean(nativeGlfwControl, current.nativeLibraries().nativeGlfw()),
                        nativeOpenAlControl.overrideBox().isSelected(),
                        editedBoolean(nativeOpenAlControl, current.nativeLibraries().nativeOpenAl())));
    }

    /// Builds launcher behavior values from their independent controls.
    ///
    /// @param current current effective launcher values
    /// @return edited launcher settings
    private InstanceGameSettingsSnapshot.LauncherSettings editedLauncherSettings(
            InstanceGameSettingsSnapshot.LauncherSettings current) {
        return new InstanceGameSettingsSnapshot.LauncherSettings(
                launcherVisibilityControl.overrideBox().isSelected(),
                editedChoice(launcherVisibilityControl, current.visibility(), "launcher visibility"),
                allowAutoAgentControl.overrideBox().isSelected(),
                editedBoolean(allowAutoAgentControl, current.allowAutoAgent()),
                disableAutoGameOptionsControl.overrideBox().isSelected(),
                editedBoolean(disableAutoGameOptionsControl, current.disableAutoGameOptions()),
                showLogsControl.overrideBox().isSelected(),
                editedBoolean(showLogsControl, current.showLogs()),
                debugLogControl.overrideBox().isSelected(),
                editedBoolean(debugLogControl, current.debugLog()),
                notCheckGameControl.overrideBox().isSelected(),
                editedBoolean(notCheckGameControl, current.notCheckGame()));
    }

    /// Builds Quick Play values from their independent controls.
    ///
    /// @param current current effective Quick Play values
    /// @return edited Quick Play settings
    private InstanceGameSettingsSnapshot.QuickPlaySettings editedQuickPlaySettings(
            InstanceGameSettingsSnapshot.QuickPlaySettings current) {
        return new InstanceGameSettingsSnapshot.QuickPlaySettings(
                quickPlayTypeControl.overrideBox().isSelected(),
                editedChoice(quickPlayTypeControl, current.type(), "Quick Play type"),
                quickPlayMultiplayerControl.overrideBox().isSelected(),
                editedText(quickPlayMultiplayerControl, current.multiplayer()),
                quickPlaySingleplayerControl.overrideBox().isSelected(),
                editedText(quickPlaySingleplayerControl, current.singleplayer()),
                quickPlayRealmsControl.overrideBox().isSelected(),
                editedText(quickPlayRealmsControl, current.realms()));
    }

    /// Builds general launch options from their independent controls.
    ///
    /// @param current current effective launch-option values
    /// @return edited launch options
    private InstanceGameSettingsSnapshot.LaunchOptionsSettings editedLaunchOptions(
            InstanceGameSettingsSnapshot.LaunchOptionsSettings current) {
        return new InstanceGameSettingsSnapshot.LaunchOptionsSettings(
                isolationControls.editedOverridden(current.runningDirectoryOverridden()),
                isolationControls.editedDirectory(current.runningDirectory()),
                gameArgumentsControl.overrideBox().isSelected(),
                editedRawText(gameArgumentsControl, current.gameArguments()),
                environmentVariablesControl.overrideBox().isSelected(),
                editedRawText(environmentVariablesControl, current.environmentVariables()),
                processPriorityControl.overrideBox().isSelected(),
                editedChoice(processPriorityControl, current.priority(), "process priority"));
    }

    /// Validates Java payloads only when the corresponding local setting can affect launch behavior.
    private static void validateJavaSettings(
            JavaVersionType type,
            String version,
            String path,
            GameSettings.DetectedJava detectedJava) {
        if (type == JavaVersionType.VERSION) {
            parseRequiredInteger(version, "Java version", 1, Integer.MAX_VALUE);
        }
        if (type == JavaVersionType.CUSTOM && path.isBlank()) {
            throw new IllegalArgumentException("Custom Java path must not be blank");
        }
        if (type == JavaVersionType.DETECTED && detectedJava.isEmpty()) {
            throw new IllegalArgumentException("Select a detected Java runtime");
        }
    }

    /// Returns a boolean editor value only when locally overridden.
    ///
    /// @param control inherited boolean control
    /// @param inherited current effective inherited value
    /// @return local draft or inherited value
    private static boolean editedBoolean(InheritedControl<JCheckBox> control, boolean inherited) {
        return control.overrideBox().isSelected() ? control.editor().isSelected() : inherited;
    }

    /// Returns a single-line text editor value only when locally overridden.
    ///
    /// @param control inherited text control
    /// @param inherited current effective inherited value
    /// @return trimmed local draft or inherited value
    private static String editedText(InheritedControl<JTextField> control, String inherited) {
        return control.overrideBox().isSelected()
                ? control.editor().getText().trim()
                : Objects.requireNonNull(inherited, "inherited");
    }

    /// Returns exact single-line free-form text only when locally overridden.
    ///
    /// @param control inherited free-form text control
    /// @param inherited current effective inherited value
    /// @return exact local draft or inherited value
    private static String editedRawText(InheritedControl<JTextField> control, String inherited) {
        return control.overrideBox().isSelected()
                ? control.editor().getText()
                : Objects.requireNonNull(inherited, "inherited");
    }

    /// Returns a multiline text editor value only when locally overridden.
    ///
    /// @param control inherited text-area control
    /// @param inherited current effective inherited value
    /// @return exact local draft or inherited value
    private static String editedTextArea(InheritedControl<JTextArea> control, String inherited) {
        return control.overrideBox().isSelected()
                ? control.editor().getText()
                : Objects.requireNonNull(inherited, "inherited");
    }

    /// Returns a selected choice only when locally overridden.
    ///
    /// @param control inherited choice control
    /// @param inherited current effective inherited value
    /// @param fieldName field name used if a local selection is absent
    /// @param <T> choice type
    /// @return local selection or inherited value
    private static <T> T editedChoice(
            InheritedControl<JComboBox<T>> control,
            T inherited,
            String fieldName) {
        return control.overrideBox().isSelected()
                ? selectedValue(control.editor(), fieldName)
                : Objects.requireNonNull(inherited, "inherited");
    }

    /// Returns the current effective choice without failing during transient combo-model replacement.
    ///
    /// @param control inherited choice control
    /// @param inherited current effective inherited value
    /// @param <T> choice type
    /// @return local selection when available, otherwise inherited value
    private static <T> T effectiveChoice(
            InheritedControl<JComboBox<T>> control,
            T inherited) {
        return control.overrideBox().isSelected()
                ? selectedOrDefault(control.editor(), inherited)
                : Objects.requireNonNull(inherited, "inherited");
    }

    /// Parses a required integer only when its property is locally overridden.
    ///
    /// @param control inherited integer text control
    /// @param inherited current effective inherited value
    /// @param fieldName user-facing field name
    /// @param minimum inclusive minimum
    /// @param maximum inclusive maximum
    /// @return parsed local value or inherited value
    private static int editedRequiredInteger(
            InheritedControl<JTextField> control,
            int inherited,
            String fieldName,
            int minimum,
            int maximum) {
        return control.overrideBox().isSelected()
                ? parseRequiredInteger(control.editor().getText(), fieldName, minimum, maximum)
                : inherited;
    }

    /// Parses a finite non-negative dimension only when its property is locally overridden.
    ///
    /// @param control inherited dimension text control
    /// @param inherited current effective inherited value
    /// @param fieldName user-facing field name
    /// @return parsed local value or inherited value
    private static double editedRequiredDouble(
            InheritedControl<JTextField> control,
            double inherited,
            String fieldName) {
        return control.overrideBox().isSelected()
                ? parseRequiredDouble(control.editor().getText(), fieldName)
                : inherited;
    }

    /// Parses an optional integer only when its property is locally overridden.
    ///
    /// @param control inherited optional integer text control
    /// @param inherited current effective inherited value
    /// @param fieldName user-facing field name
    /// @param minimum inclusive minimum
    /// @param maximum inclusive maximum
    /// @return parsed local value or inherited value
    private static @Nullable Integer editedOptionalInteger(
            InheritedControl<JTextField> control,
            @Nullable Integer inherited,
            String fieldName,
            int minimum,
            int maximum) {
        return control.overrideBox().isSelected()
                ? parseOptionalInteger(control.editor().getText(), fieldName, minimum, maximum)
                : inherited;
    }

    /// Applies a durable snapshot without saving control events back to the store.
    ///
    /// @param snapshot latest effective values and override states
    private void applySnapshot(InstanceGameSettingsSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(snapshot, "snapshot");
        applyingSnapshot = true;
        try {
            displayedSnapshot = snapshot;
            parentPresetControls.apply(snapshot.parentPreset());
            memoryModeSelector.apply(
                    snapshot.memory().automaticOverridden() || snapshot.memory().maximumOverridden(),
                    snapshot.memory().automatic());
            maximumMemoryField.setText(Integer.toString(snapshot.memory().maximumMiB()));
            memoryAllocationControls.applyInheritedAutomatic(snapshot.memory().automatic());
            javaModeSelector.apply(
                    snapshot.javaRuntime().typeOverridden()
                            || snapshot.javaRuntime().customVersionOverridden()
                            || snapshot.javaRuntime().customPathOverridden()
                            || snapshot.javaRuntime().detectedJavaOverridden(),
                    snapshot.javaRuntime().type());
            javaVersionField.setText(snapshot.javaRuntime().customVersion());
            javaPathField.setText(snapshot.javaRuntime().customPath());
            applyDetectedJavaSnapshotValue(snapshot.javaRuntime().detectedJava());

            applyChoice(windowTypeControl, snapshot.window().typeOverridden(), snapshot.window().type());
            applyText(
                    windowWidthControl,
                    snapshot.window().widthOverridden(),
                    formatWindowDimension(snapshot.window().width()));
            applyText(
                    windowHeightControl,
                    snapshot.window().heightOverridden(),
                    formatWindowDimension(snapshot.window().height()));
            applyLauncherSettings(snapshot.launcher());
            applyQuickPlaySettings(snapshot.quickPlay());
            applyLaunchOptions(snapshot.launchOptions());
            applyJvmSettings(snapshot.jvm());
            applyCommandSettings(snapshot.commands());
            applyGraphicsSettings(snapshot.graphics());
            applyNativeSettings(snapshot.nativeLibraries());
            if (presentation == GameSettingsEditorPresentation.GLOBAL_PRESET) {
                for (InheritedControl<? extends JComponent> control : allControls()) {
                    control.overrideBox().setSelected(true);
                }
            }
            footerControls.setStatus("");
            updateAllOverrideTooltips();
            updateEditingAvailability();
        } finally {
            applyingSnapshot = false;
        }
    }

    /// Applies launcher behavior controls.
    private void applyLauncherSettings(InstanceGameSettingsSnapshot.LauncherSettings values) {
        applyChoice(launcherVisibilityControl, values.visibilityOverridden(), values.visibility());
        applyBoolean(allowAutoAgentControl, values.allowAutoAgentOverridden(), values.allowAutoAgent());
        applyBoolean(
                disableAutoGameOptionsControl,
                values.disableAutoGameOptionsOverridden(),
                values.disableAutoGameOptions());
        applyBoolean(showLogsControl, values.showLogsOverridden(), values.showLogs());
        applyBoolean(debugLogControl, values.debugLogOverridden(), values.debugLog());
        applyBoolean(notCheckGameControl, values.notCheckGameOverridden(), values.notCheckGame());
    }

    /// Applies Quick Play controls.
    private void applyQuickPlaySettings(InstanceGameSettingsSnapshot.QuickPlaySettings values) {
        applyChoice(quickPlayTypeControl, values.typeOverridden(), values.type());
        applyText(quickPlayMultiplayerControl, values.multiplayerOverridden(), values.multiplayer());
        applyText(quickPlaySingleplayerControl, values.singleplayerOverridden(), values.singleplayer());
        applyText(quickPlayRealmsControl, values.realmsOverridden(), values.realms());
    }

    /// Applies general launch-option controls.
    private void applyLaunchOptions(InstanceGameSettingsSnapshot.LaunchOptionsSettings values) {
        isolationControls.apply(values.runningDirectoryOverridden(), values.runningDirectory());
        applyText(gameArgumentsControl, values.gameArgumentsOverridden(), values.gameArguments());
        applyText(environmentVariablesControl, values.environmentOverridden(), values.environmentVariables());
        applyChoice(processPriorityControl, values.priorityOverridden(), values.priority());
    }

    /// Applies JVM controls.
    private void applyJvmSettings(InstanceGameSettingsSnapshot.JvmSettings values) {
        applyBoolean(noJvmOptionsControl, values.noOptionsOverridden(), values.noOptions());
        applyBoolean(
                noOptimizingJvmOptionsControl,
                values.noOptimizingOptionsOverridden(),
                values.noOptimizingOptions());
        applyBoolean(notCheckJvmControl, values.notCheckJvmOverridden(), values.notCheckJvm());
        applyTextArea(jvmOptionsControl, values.optionsOverridden(), values.options());
        applyText(
                minimumMemoryControl,
                values.minimumMemoryOverridden(),
                values.minimumMemoryMiB() == null ? "" : values.minimumMemoryMiB().toString());
        applyText(
                permanentGenerationControl,
                values.permanentGenerationOverridden(),
                values.permanentGenerationMiB());
    }

    /// Applies custom command controls.
    private void applyCommandSettings(InstanceGameSettingsSnapshot.CommandSettings values) {
        applyText(preLaunchCommandControl, values.preLaunchOverridden(), values.preLaunch());
        applyText(commandWrapperControl, values.wrapperOverridden(), values.wrapper());
        applyText(postExitCommandControl, values.postExitOverridden(), values.postExit());
    }

    /// Applies graphics controls while retaining stored renderers before lazy option loading.
    private void applyGraphicsSettings(InstanceGameSettingsSnapshot.GraphicsSettings values) {
        applyChoice(graphicsBackendControl, values.backendOverridden(), values.backend());
        ensureComboValue(openGlRendererControl.editor(), values.openGlRenderer());
        openGlRendererControl.overrideBox().setSelected(values.openGlRendererOverridden());
        ensureComboValue(vulkanRendererControl.editor(), values.vulkanRenderer());
        vulkanRendererControl.overrideBox().setSelected(values.vulkanRendererOverridden());
    }

    /// Applies one asynchronous game-version result unless the panel has already closed.
    ///
    /// @param version parsed game version, or `null` after failure
    /// @param failure detection failure, or `null` on success
    private void completeGameVersion(
            @Nullable GameVersionNumber version,
            @Nullable Throwable failure) {
        if (closed || failure != null || version == null) {
            return;
        }
        applyGraphicsCompatibility(version);
    }

    /// Matches the legacy version gates for graphics-backend and renderer controls.
    ///
    /// @param gameVersion parsed instance game version
    private void applyGraphicsCompatibility(GameVersionNumber gameVersion) {
        EdtDispatcher.requireEventDispatchThread();
        GameVersionNumber version = Objects.requireNonNull(gameVersion, "gameVersion");
        if (presentation == GameSettingsEditorPresentation.GLOBAL_PRESET) {
            graphicsBackendRow.setVisible(true);
            openGlRendererRow.setVisible(true);
            vulkanRendererRow.setVisible(true);
            revalidate();
            repaint();
            return;
        }
        graphicsBackendRow.setVisible(version.compareTo(GRAPHICS_BACKEND_VERSION) >= 0);
        openGlRendererRow.setVisible(GraphicsAPI.OPENGL.isSupported(version));
        vulkanRendererRow.setVisible(GraphicsAPI.VULKAN.isSupported(version));
        revalidate();
        repaint();
    }

    /// Applies native-library controls.
    private void applyNativeSettings(InstanceGameSettingsSnapshot.NativeLibrarySettings values) {
        applyBoolean(
                useCustomNativesControl,
                values.customDirectoryEnabledOverridden(),
                values.customDirectoryEnabled());
        applyText(nativesDirectoryControl, values.directoryOverridden(), values.directory());
        applyBoolean(
                notPatchNativesControl,
                values.patchingDisabledOverridden(),
                values.patchingDisabled());
        applyBoolean(nativeGlfwControl, values.nativeGlfwOverridden(), values.nativeGlfw());
        applyBoolean(nativeOpenAlControl, values.nativeOpenAlOverridden(), values.nativeOpenAl());
    }

    /// Recomputes editor availability from writable, override, and dependent values.
    private void updateEditingAvailability() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable InstanceGameSettingsSnapshot snapshot = displayedSnapshot;
        boolean writable = interactionEnabled && !closed && snapshot != null && snapshot.writable();
        for (InheritedControl<? extends JComponent> control : allControls()) {
            control.overrideBox().setEnabled(
                    writable && presentation == GameSettingsEditorPresentation.INSTANCE);
            control.editor().setEnabled(writable && control.overrideBox().isSelected());
        }
        isolationControls.updateAvailability(writable, presentation == GameSettingsEditorPresentation.INSTANCE);
        parentPresetControls.updateAvailability(
                writable,
                presentation == GameSettingsEditorPresentation.INSTANCE);

        memoryModeSelector.setEnabled(writable);
        boolean localMemorySettings = snapshot != null && !memoryModeSelector.isInherited();
        boolean automaticMemory = localMemorySettings
                ? memoryModeSelector.isAutomatic()
                : snapshot == null || snapshot.memory().automatic();
        maximumMemoryField.setEnabled(writable && localMemorySettings && !automaticMemory);
        javaModeSelector.setEnabled(writable);
        boolean localJavaSettings = snapshot != null && !javaModeSelector.isInherited();
        JavaVersionType javaType = localJavaSettings
                ? javaModeSelector.selectedMode()
                : snapshot == null ? JavaVersionType.AUTO : snapshot.javaRuntime().type();
        javaVersionField.setEnabled(writable && localJavaSettings && javaType == JavaVersionType.VERSION);
        javaPathField.setEnabled(writable && localJavaSettings && javaType == JavaVersionType.CUSTOM);
        javaPathControls.updateAvailability(javaPathField.isEnabled());
        detectedJavaComboBox.setEnabled(writable && localJavaSettings && javaType == JavaVersionType.DETECTED);

        GameWindowType windowType = snapshot == null
                ? GameWindowType.WINDOWED
                : effectiveChoice(windowTypeControl, snapshot.window().type());
        boolean windowed = windowType == GameWindowType.WINDOWED;
        windowWidthControl.editor().setEnabled(windowWidthControl.editor().isEnabled() && windowed);
        windowHeightControl.editor().setEnabled(windowHeightControl.editor().isEnabled() && windowed);

        QuickPlayType quickPlayType = snapshot == null
                ? QuickPlayType.NONE
                : effectiveChoice(quickPlayTypeControl, snapshot.quickPlay().type());
        quickPlayMultiplayerControl.editor().setEnabled(
                quickPlayMultiplayerControl.editor().isEnabled() && quickPlayType == QuickPlayType.MULTIPLAYER);
        quickPlaySingleplayerControl.editor().setEnabled(
                quickPlaySingleplayerControl.editor().isEnabled() && quickPlayType == QuickPlayType.SINGLEPLAYER);
        quickPlayRealmsControl.editor().setEnabled(
                quickPlayRealmsControl.editor().isEnabled() && quickPlayType == QuickPlayType.REALMS);
        boolean noJvmOptions = snapshot != null
                && editedBoolean(noJvmOptionsControl, snapshot.jvm().noOptions());
        noOptimizingJvmOptionsControl.editor().setEnabled(
                noOptimizingJvmOptionsControl.editor().isEnabled() && !noJvmOptions);
        boolean useCustomNatives = snapshot != null
                && editedBoolean(
                        useCustomNativesControl,
                        snapshot.nativeLibraries().customDirectoryEnabled());
        nativesDirectoryControl.editor().setEnabled(
                nativesDirectoryControl.editor().isEnabled() && useCustomNatives);

        footerControls.updateAvailability(writable, interactionEnabled && !closed);
        settingsTabs.setEnabled(interactionEnabled && !closed);
        if (!applyingSnapshot && snapshot != null && !snapshot.writable()) {
            footerControls.setStatus(i18n("settings.game.instance_settings.unsupported"));
        }
    }

    /// Applies one durable detected-Java value without enumerating local runtimes on the EDT.
    ///
    /// @param desired persisted runtime reference
    private void applyDetectedJavaSnapshotValue(GameSettings.DetectedJava desired) {
        if (!detectedJavaChoicesRequested) {
            replaceDetectedJavaChoices(List.of(), desired);
            return;
        }
        JComboBox<DetectedJavaChoice> comboBox = detectedJavaComboBox;
        if (!desired.isEmpty() && !containsDetectedJava(comboBox, desired)) {
            comboBox.insertItemAt(new DetectedJavaChoice(desired, desired.version()), 0);
        }
        selectDetectedJava(desired);
    }

    /// Starts one background local Java rescan only after initialized choices are requested.
    private void requestJavaRefreshIfNecessary() {
        if (javaRuntimeService.snapshot().initialized() && !javaRefreshRequested) {
            javaRefreshRequested = true;
            javaRuntimeService.refreshLocalRuntimes();
        }
    }

    /// Requests background conversion of the newest runtime snapshot after the list is first opened.
    private void requestDetectedJavaChoices() {
        JavaRuntimeManagementSnapshot runtimeSnapshot = javaRuntimeService.snapshot();
        long revision = runtimeSnapshot.revision();
        if (revision == detectedJavaLoadedRevision || revision == detectedJavaLoadingRevision) {
            return;
        }
        detectedJavaLoadingRevision = revision;
        CompletableFuture.supplyAsync(() -> createDetectedJavaChoices(runtimeSnapshot.runtimes()))
                .whenComplete((choices, failure) -> SwingUiDispatcher.INSTANCE.dispatch(
                        () -> completeDetectedJavaChoices(revision, choices, failure)));
    }

    /// Converts local runtimes to stable persisted identities away from the EDT.
    ///
    /// @param runtimes immutable local runtime snapshot
    /// @return immutable display choices
    private static @Unmodifiable List<DetectedJavaChoice> createDetectedJavaChoices(
            @Unmodifiable List<JavaRuntime> runtimes) {
        List<DetectedJavaChoice> choices = new ArrayList<>();
        for (JavaRuntime runtime : runtimes) {
            choices.add(new DetectedJavaChoice(
                    GameSettings.DetectedJava.of(runtime),
                    runtime.getVersion() + " - " + runtime.getBinary()));
        }
        return List.copyOf(choices);
    }

    /// Applies one completed lazy detected-Java conversion when its runtime revision is still current.
    ///
    /// @param revision converted runtime revision
    /// @param choices converted choices, or `null` after failure
    /// @param failure conversion failure, or `null` on success
    private void completeDetectedJavaChoices(
            long revision,
            @Nullable List<DetectedJavaChoice> choices,
            @Nullable Throwable failure) {
        if (detectedJavaLoadingRevision == revision) {
            detectedJavaLoadingRevision = -1L;
        }
        if (closed || failure != null || choices == null || !detectedJavaChoicesRequested) {
            return;
        }
        if (javaRuntimeService.snapshot().revision() != revision) {
            requestDetectedJavaChoices();
            return;
        }
        replaceDetectedJavaChoices(choices, selectedOrStoredDetectedJava());
        detectedJavaLoadedRevision = revision;
        updateEditingAvailability();
    }

    /// Replaces the detected-Java model while retaining a selected unavailable runtime as a placeholder.
    ///
    /// @param choices converted available runtime choices
    /// @param desired runtime identity that must remain selected when possible
    private void replaceDetectedJavaChoices(
            List<DetectedJavaChoice> choices,
            GameSettings.DetectedJava desired) {
        DefaultComboBoxModel<DetectedJavaChoice> model = new DefaultComboBoxModel<>();
        if (!desired.isEmpty() && choices.stream().noneMatch(choice -> choice.value().equals(desired))) {
            model.addElement(new DetectedJavaChoice(desired, desired.version()));
        }
        for (DetectedJavaChoice choice : choices) {
            model.addElement(choice);
        }
        detectedJavaComboBox.setModel(model);
        selectDetectedJava(desired);
    }

    /// Returns whether a combo model already contains one detected-Java identity.
    ///
    /// @param comboBox detected-Java combo
    /// @param desired runtime identity
    /// @return whether the identity is present
    private static boolean containsDetectedJava(
            JComboBox<DetectedJavaChoice> comboBox,
            GameSettings.DetectedJava desired) {
        for (int index = 0; index < comboBox.getItemCount(); index++) {
            if (comboBox.getItemAt(index).value().equals(desired)) {
                return true;
            }
        }
        return false;
    }

    /// Refreshes requested detected-Java choices after discovery publishes a newer local snapshot.
    private void javaRuntimeSnapshotChanged() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed || !detectedJavaChoicesRequested) {
                return;
            }
            requestDetectedJavaChoices();
        });
    }

    /// Returns the unsaved detected-Java selection before falling back to the last applied snapshot.
    ///
    /// @return current editor selection or durable effective reference
    private GameSettings.DetectedJava selectedOrStoredDetectedJava() {
        @Nullable DetectedJavaChoice selected = selectedNullableValue(detectedJavaComboBox);
        if (selected != null) {
            return selected.value();
        }
        @Nullable InstanceGameSettingsSnapshot snapshot = displayedSnapshot;
        return snapshot == null ? GameSettings.DetectedJava.EMPTY : snapshot.javaRuntime().detectedJava();
    }

    /// Selects one detected Java reference when it exists in the current combo model.
    ///
    /// @param desired desired reference
    private void selectDetectedJava(GameSettings.DetectedJava desired) {
        JComboBox<DetectedJavaChoice> comboBox = detectedJavaComboBox;
        for (int index = 0; index < comboBox.getItemCount(); index++) {
            DetectedJavaChoice choice = comboBox.getItemAt(index);
            if (choice.value().equals(desired)) {
                comboBox.setSelectedIndex(index);
                return;
            }
        }
        comboBox.setSelectedIndex(-1);
    }

    /// Loads supported renderer choices in the background on first popup opening.
    ///
    /// @param api graphics API whose renderer choices are requested
    private void requestRendererChoices(GraphicsAPI api) {
        boolean openGl = api == GraphicsAPI.OPENGL;
        if ((openGl && (openGlRendererLoaded || openGlRendererLoading))
                || (!openGl && (vulkanRendererLoaded || vulkanRendererLoading))) {
            return;
        }
        if (openGl) {
            openGlRendererLoading = true;
        } else {
            vulkanRendererLoading = true;
        }

        CompletableFuture.supplyAsync(() -> Renderer.getSupported(api)).whenComplete((choices, failure) ->
                SwingUiDispatcher.INSTANCE.dispatch(() -> completeRendererChoices(api, choices, failure)));
    }

    /// Applies asynchronously loaded renderer choices on the event dispatch thread.
    ///
    /// @param api requested graphics API
    /// @param choices supported choices, or `null` on failure
    /// @param failure loading failure, or `null` on success
    private void completeRendererChoices(
            GraphicsAPI api,
            @Nullable List<Renderer> choices,
            @Nullable Throwable failure) {
        if (api == GraphicsAPI.OPENGL) {
            openGlRendererLoading = false;
        } else {
            vulkanRendererLoading = false;
        }
        if (closed || failure != null || choices == null) {
            return;
        }

        InheritedControl<JComboBox<Renderer>> control = api == GraphicsAPI.OPENGL
                ? openGlRendererControl
                : vulkanRendererControl;
        @Nullable Renderer selected = selectedNullableValue(control.editor());
        Set<Renderer> merged = new LinkedHashSet<>();
        if (selected != null) {
            merged.add(selected);
        }
        merged.addAll(choices);
        DefaultComboBoxModel<Renderer> model = new DefaultComboBoxModel<>();
        for (Renderer renderer : merged) {
            model.addElement(renderer);
        }
        control.editor().setModel(model);
        if (selected != null) {
            control.editor().setSelectedItem(selected);
        }
        if (api == GraphicsAPI.OPENGL) {
            openGlRendererLoaded = true;
        } else {
            vulkanRendererLoaded = true;
        }
        updateEditingAvailability();
    }

    /// Updates all inheritance tooltips after snapshot application.
    private void updateAllOverrideTooltips() {
        for (InheritedControl<? extends JComponent> control : allControls()) {
            updateOverrideTooltip(control);
        }
    }

    /// Updates one inheritance tooltip from its current override state.
    ///
    /// @param control inherited control
    private static void updateOverrideTooltip(InheritedControl<? extends JComponent> control) {
        String tooltip = i18n(control.overrideBox().isSelected()
                ? "settings.game.override_global"
                : "settings.game.inherit_global");
        control.overrideBox().setToolTipText(tooltip);
        control.overrideBox().getAccessibleContext().setAccessibleDescription(tooltip);
    }

    /// Returns every inherited control in stable UI order.
    ///
    /// @return immutable control list
    private @Unmodifiable List<InheritedControl<? extends JComponent>> allControls() {
        return List.of(
                windowTypeControl,
                windowWidthControl,
                windowHeightControl,
                launcherVisibilityControl,
                allowAutoAgentControl,
                disableAutoGameOptionsControl,
                showLogsControl,
                debugLogControl,
                notCheckGameControl,
                quickPlayTypeControl,
                quickPlayMultiplayerControl,
                quickPlaySingleplayerControl,
                quickPlayRealmsControl,
                runningDirectoryControl,
                gameArgumentsControl,
                environmentVariablesControl,
                processPriorityControl,
                noJvmOptionsControl,
                noOptimizingJvmOptionsControl,
                notCheckJvmControl,
                jvmOptionsControl,
                minimumMemoryControl,
                permanentGenerationControl,
                preLaunchCommandControl,
                commandWrapperControl,
                postExitCommandControl,
                graphicsBackendControl,
                openGlRendererControl,
                vulkanRendererControl,
                useCustomNativesControl,
                nativesDirectoryControl,
                notPatchNativesControl,
                nativeGlfwControl,
                nativeOpenAlControl);
    }

    /// Creates one inherited editor and stable component names.
    ///
    /// @param name stable editor component name
    /// @param editor value editor
    /// @param <T> editor component type
    /// @return inherited control pair
    private static <T extends JComponent> InheritedControl<T> inheritedControl(String name, T editor) {
        JCheckBox overrideBox = new JCheckBox();
        overrideBox.setName(Objects.requireNonNull(name, "name") + "Override");
        editor.setName(name);
        return new InheritedControl<>(overrideBox, editor);
    }

    /// Creates a line-wrapped JVM argument editor with stable dimensions.
    ///
    /// @return configured text area
    private static JTextArea createTextArea() {
        JTextArea textArea = new JTextArea(4, 40);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        return textArea;
    }

    /// Creates one transparent tab content panel.
    ///
    /// @param name stable component name
    /// @return configured tab content
    private static JPanel tabContent(String name) {
        JPanel content = new ViewportTrackingPanel(
                new MigLayout("insets 16 20 20 20, fillx, wrap 1", "[grow,fill]", "[]14[]"));
        content.setName(Objects.requireNonNull(name, "name"));
        return content;
    }

    /// Wraps one tab in a vertical-only scroll pane.
    ///
    /// @param content tab content panel
    /// @return configured scroll pane
    private static JScrollPane createScrollableTab(JPanel content) {
        JScrollPane scrollPane = new JScrollPane(Objects.requireNonNull(content, "content"));
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        SwingTransparency.revealBackgroundThroughScrollPane(scrollPane);
        return scrollPane;
    }

    /// Creates one unframed three-column section.
    ///
    /// @param name stable component name
    /// @param title localized section title
    /// @return configured section panel
    private static JPanel sectionPanel(String name, String title) {
        JPanel section = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 3", "[26!,center]8[320:pref,fill]16[grow,fill]", "[]10[]"));
        section.setName(Objects.requireNonNull(name, "name"));
        section.setOpaque(false);
        JLabel heading = new JLabel(Objects.requireNonNull(title, "title"));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15.0F));
        section.add(heading, "span 3, growx");
        return section;
    }

    /// Creates one transparent inherited-control row that can be version-gated as a unit.
    ///
    /// @param name stable row component name
    /// @param labelText localized field label
    /// @param control inherited editor
    /// @return configured row panel
    private static JPanel inheritedRowPanel(
            String name,
            String labelText,
            InheritedControl<? extends JComponent> control) {
        JPanel row = new JPanel(new MigLayout(
                "insets 0, fillx", "[26!,center]8[320:pref,fill]16[grow,fill]", "[]"));
        row.setName(Objects.requireNonNull(name, "name"));
        row.setOpaque(false);
        addControlRow(row, labelText, control);
        return row;
    }

    /// Adds one inherited editor row.
    ///
    /// @param section target section
    /// @param labelText localized field label
    /// @param control inherited editor
    private static void addControlRow(
            JPanel section,
            String labelText,
            InheritedControl<? extends JComponent> control) {
        String validatedLabel = Objects.requireNonNull(labelText, "labelText");
        section.add(control.overrideBox(), "aligny center");
        JLabel label = new JLabel(validatedLabel);
        label.setLabelFor(control.editor());
        label.setName(control.editor().getName() + "Label");
        control.overrideBox().getAccessibleContext().setAccessibleName(validatedLabel);
        section.add(label, "aligny center");
        section.add(control.editor(), "growx");
    }

    /// Adds one inherited boolean editor whose visible text belongs to the value checkbox.
    ///
    /// @param section target section
    /// @param labelText localized field label
    /// @param control inherited boolean editor
    private static void addBooleanControlRow(
            JPanel section,
            String labelText,
            InheritedControl<JCheckBox> control) {
        String validatedLabel = Objects.requireNonNull(labelText, "labelText");
        JCheckBox editor = control.editor();
        editor.setText(validatedLabel);
        editor.getAccessibleContext().setAccessibleName(validatedLabel);
        control.overrideBox().getAccessibleContext().setAccessibleName(validatedLabel);
        section.add(control.overrideBox(), "aligny center");
        section.add(editor, "span 2, growx");
    }

    /// Adds one inherited multiline text editor row.
    ///
    /// @param section target section
    /// @param labelText localized field label
    /// @param control inherited text area
    private static void addTextAreaRow(
            JPanel section,
            String labelText,
            InheritedControl<JTextArea> control) {
        String validatedLabel = Objects.requireNonNull(labelText, "labelText");
        section.add(control.overrideBox(), "aligny top");
        JLabel label = new JLabel(validatedLabel);
        label.setLabelFor(control.editor());
        label.setName(control.editor().getName() + "Label");
        control.overrideBox().getAccessibleContext().setAccessibleName(validatedLabel);
        section.add(label, "aligny top");
        JScrollPane scrollPane = new JScrollPane(control.editor());
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        section.add(scrollPane, "growx, h 88!");
    }

    /// Applies one boolean value and override state.
    private static void applyBoolean(
            InheritedControl<JCheckBox> control,
            boolean overridden,
            boolean value) {
        control.overrideBox().setSelected(overridden);
        control.editor().setSelected(value);
    }

    /// Applies one text value and override state.
    private static void applyText(
            InheritedControl<JTextField> control,
            boolean overridden,
            String value) {
        control.overrideBox().setSelected(overridden);
        control.editor().setText(Objects.requireNonNull(value, "value"));
    }

    /// Applies one multiline text value and override state.
    private static void applyTextArea(
            InheritedControl<JTextArea> control,
            boolean overridden,
            String value) {
        control.overrideBox().setSelected(overridden);
        control.editor().setText(Objects.requireNonNull(value, "value"));
    }

    /// Applies one combo value and override state.
    private static <T> void applyChoice(InheritedControl<JComboBox<T>> control, boolean overridden, T value) {
        ensureComboValue(control.editor(), Objects.requireNonNull(value, "value"));
        control.overrideBox().setSelected(overridden);
    }

    /// Adds a value to a combo model when necessary, then selects it.
    private static <T> void ensureComboValue(JComboBox<T> comboBox, T value) {
        T target = Objects.requireNonNull(value, "value");
        for (int index = 0; index < comboBox.getItemCount(); index++) {
            if (target.equals(comboBox.getItemAt(index))) {
                comboBox.setSelectedIndex(index);
                return;
            }
        }
        comboBox.addItem(target);
        comboBox.setSelectedItem(target);
    }

    /// Returns a selected combo value or raises a field-specific validation failure.
    private static <T> T selectedValue(JComboBox<T> comboBox, String fieldName) {
        @Nullable T value = selectedNullableValue(comboBox);
        if (value == null) {
            throw new IllegalArgumentException("Select " + Objects.requireNonNull(fieldName, "fieldName"));
        }
        return value;
    }

    /// Returns a selected combo value, or `null` when no item is selected.
    private static <T> @Nullable T selectedNullableValue(JComboBox<T> comboBox) {
        int index = comboBox.getSelectedIndex();
        return index >= 0 ? comboBox.getItemAt(index) : null;
    }

    /// Returns a selected combo value or a caller-supplied fallback.
    private static <T> T selectedOrDefault(JComboBox<T> comboBox, T fallback) {
        @Nullable T value = selectedNullableValue(comboBox);
        return value != null ? value : Objects.requireNonNull(fallback, "fallback");
    }

    /// Installs a text-converting combo renderer.
    private static <T> void installRenderer(JComboBox<T> comboBox, Function<T, String> displayName) {
        DefaultListCellRenderer fallback = new DefaultListCellRenderer();
        Function<T, String> converter = Objects.requireNonNull(displayName, "displayName");
        ListCellRenderer<T> renderer = (
                JList<? extends T> list,
                T value,
                int index,
                boolean selected,
                boolean focused) -> {
            Component component = fallback.getListCellRendererComponent(
                    list,
                    value,
                    index,
                    selected,
                    focused);
            if (component instanceof JLabel label && value != null) {
                label.setText(converter.apply(value));
            }
            return component;
        };
        comboBox.setRenderer(renderer);
    }

    /// Returns a localized game-window mode name.
    private static String windowTypeName(GameWindowType value) {
        return i18n("settings.game.window_type." + enumKey(value));
    }

    /// Returns a localized Quick Play mode name.
    private static String quickPlayTypeName(QuickPlayType value) {
        return i18n("settings.game.quick_play." + enumKey(value));
    }

    /// Returns a localized renderer name when available.
    private static String rendererName(Renderer renderer) {
        String key = "settings.advanced.renderer." + renderer.name().toLowerCase(Locale.ROOT);
        return I18n.hasKey(key) ? i18n(key) : renderer.name();
    }

    /// Returns the lowercase localization suffix for one enum value.
    private static String enumKey(Enum<?> value) {
        return Objects.requireNonNull(value, "value").name().toLowerCase(Locale.ROOT);
    }

}
