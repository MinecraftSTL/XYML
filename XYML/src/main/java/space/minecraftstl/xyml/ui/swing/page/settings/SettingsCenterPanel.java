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
package space.minecraftstl.xyml.ui.swing.page.settings;

import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.setting.DownloadSource;
import space.minecraftstl.xyml.setting.EnumCommonDirectory;
import space.minecraftstl.xyml.setting.ProxyType;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingTransparency;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.log.LauncherLogPanel;
import space.minecraftstl.xyml.ui.swing.page.nbt.NBTSettingsPanel;
import space.minecraftstl.xyml.ui.swing.update.UpdateCheckRequest;
import space.minecraftstl.xyml.ui.swing.update.UpdateCheckResult;
import space.minecraftstl.xyml.upgrade.UpdateChannel;
import space.minecraftstl.xyml.util.i18n.SupportedLocale;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Renders an embeddable Swing settings center backed by [SettingsCenterStore].
///
/// This panel owns its embedded appearance, fonts, preset, directory, Java management, NBT tool, launcher-log
/// controls, and asynchronous maintenance actions. It closes those resources with the general and network settings
/// store, making the settings center safe to cache as one shell page.
@NotNullByDefault
public final class SettingsCenterPanel extends JPanel implements AutoCloseable {
    /// Add-on catalogue IDs exposed by the launcher setting.
    private static final @Unmodifiable List<String> ADDON_SOURCES = List.of("modrinth", "curseforge");

    /// Broadly distributed release channels exposed by the manual update selector.
    private static final @Unmodifiable List<UpdateChannel> UPDATE_CHANNELS =
            List.of(UpdateChannel.STABLE, UpdateChannel.BETA);

    /// Store supplying and persisting the non-appearance settings.
    private final SettingsCenterStore store;

    /// Asynchronous update and cache actions owned by this settings center.
    private final SettingsMaintenanceActions maintenanceActions;

    /// Appearance content embedded and closed with this settings center.
    private final AppearanceSettingsPanel appearancePanel;

    /// Optional production font settings section embedded below appearance controls.
    private final @Nullable FontSettingsPanel fontSettingsPanel;

    /// Local Java runtime page embedded and closed with this settings center.
    private final JavaManagementPanel javaManagementPanel;

    /// Global game-launch settings preset page embedded and closed with this settings center.
    private final GameSettingsPresetsPanel gameSettingsPresetsPanel;

    /// Managed game-directory page embedded and closed with this settings center.
    private final GameDirectoryManagementPanel gameDirectoryManagementPanel;

    /// Launcher-log actions embedded in the general settings page and closed with this center.
    private final LauncherLogPanel launcherLogPanel;

    /// NBT file tool exposed only as a settings page and closed with this center.
    private final NBTSettingsPanel nbtSettingsPanel;

    /// Stable top-level navigation among functional settings pages.
    private final JTabbedPane tabs = new JTabbedPane();

    /// Locale selector with user-facing localized display names.
    private final JComboBox<SupportedLocale> languageBox;

    /// Preview-update eligibility preference.
    private final JCheckBox previewUpdatesBox = new JCheckBox(i18n("update.preview"));

    /// Release channel used by explicit update checks without changing the build's own channel.
    private final JComboBox<UpdateChannel> updateChannelBox = new JComboBox<>(new DefaultComboBoxModel<>(
            UPDATE_CHANNELS.toArray(UpdateChannel[]::new)));

    /// Starts a manual update check for the selected release channel.
    private final JButton checkUpdatesButton = new JButton(i18n("update.tooltip"));

    /// Reports manual update-check progress and the latest terminal result.
    private final JLabel updateStatusLabel = new JLabel();

    /// Automatic update-dialog suppression preference.
    private final JCheckBox disableUpdatePromptBox = new JCheckBox(i18n("update.disable_auto_show_update_dialog"));

    /// April Fools behavior suppression preference.
    private final JCheckBox disableAprilFoolsBox = new JCheckBox(i18n("settings.launcher.disable_april_fools"));

    /// Shared restart status and action for the language and April Fools settings.
    private final SettingsRestartPanel restartPanel;

    /// Common-directory mode selector.
    private final JComboBox<EnumCommonDirectory> commonDirectoryTypeBox = new JComboBox<>(EnumCommonDirectory.values());

    /// Editable custom common-directory value.
    private final JTextField commonDirectoryField = new JTextField();

    /// Opens a directory chooser for the custom common directory.
    private final JButton chooseDirectoryButton = new JButton(i18n("launcher.cache_directory.choose"));

    /// Opens the currently resolved common directory in the native file manager.
    private final JButton revealDirectoryButton = new JButton(i18n("button.reveal_dir"));

    /// Removes entries from the cache child of the currently resolved common directory.
    private final JButton clearCacheButton = new JButton(i18n("launcher.cache_directory.clean"));

    /// Reports cache-cleaning progress and the latest terminal result.
    private final JLabel cacheStatusLabel = new JLabel();

    /// Automatic download-concurrency preference.
    private final JCheckBox automaticThreadsBox = new JCheckBox(i18n("settings.launcher.download.threads.auto"));

    /// Editable manual download-concurrency value.
    private final JTextField downloadThreadsField = new JTextField();

    /// Version-list source selector.
    private final JComboBox<DownloadSource> versionListSourceBox = new JComboBox<>(DownloadSource.values());

    /// File-download source selector.
    private final JComboBox<DownloadSource> fileDownloadSourceBox = new JComboBox<>(DownloadSource.values());

    /// Default add-on catalogue source selector.
    private final JComboBox<String> addonSourceBox = new JComboBox<>(
            new DefaultComboBoxModel<>(ADDON_SOURCES.toArray(String[]::new)));

    /// Selected proxy handling strategy.
    private final JComboBox<ProxyType> proxyTypeBox = new JComboBox<>(ProxyType.values());

    /// Custom proxy host input.
    private final JTextField proxyHostField = new JTextField();

    /// Custom proxy TCP port input.
    private final JTextField proxyPortField = new JTextField();

    /// Proxy authentication enablement preference.
    private final JCheckBox proxyAuthenticationBox = new JCheckBox(i18n("settings.launcher.proxy.authentication"));

    /// Proxy authentication username input.
    private final JTextField proxyUsernameField = new JTextField();

    /// Proxy authentication password input.
    private final JPasswordField proxyPasswordField = new JPasswordField();

    /// Shows a concise validation result below editable network values.
    private final JLabel networkValidationLabel = new JLabel();

    /// Commits validated free-form download and proxy values.
    private final JButton confirmNetworkButton = new JButton(i18n("button.ok"));

    /// Store subscription released when this panel is discarded.
    private final Subscription storeSubscription;

    /// Snapshot currently represented by every settings control, or null before initial setup.
    private @Nullable SettingsCenterSnapshot displayedSnapshot;

    /// Prevents programmatic component changes from writing a snapshot back to the store.
    private boolean applyingSnapshot;

    /// Whether restart preparation temporarily blocks settings navigation and edits.
    private boolean restartInProgress;

    /// Whether one manual update request currently owns its controls.
    private boolean updateCheckInProgress;

    /// Whether one cache-cleaning request currently owns its control.
    private boolean cacheClearInProgress;

    /// Whether this panel has released its owned store resources.
    private boolean closed;

    /// Creates a settings center backed by the process-wide launcher settings.
    ///
    /// @param appearancePanel appearance page to embed and own
    /// @param fontRuntime live launcher-font application boundary
    /// @return configured settings center
    public static SettingsCenterPanel createForCurrentSettings(
            AppearanceSettingsPanel appearancePanel,
            FontSettingsRuntime fontRuntime) {
        EdtDispatcher.requireEventDispatchThread();
        LauncherSettingsCenterStore settingsStore = LauncherSettingsCenterStore.createForCurrentSettings();
        final LauncherFontSettingsStore fontStore;
        try {
            fontStore = LauncherFontSettingsStore.createForCurrentSettings();
        } catch (RuntimeException | Error failure) {
            settingsStore.close();
            throw failure;
        }
        try {
            return new SettingsCenterPanel(
                    settingsStore,
                    appearancePanel,
                    LauncherSettingsRestartCommand.create(),
                    null,
                    fontStore,
                    Objects.requireNonNull(fontRuntime, "fontRuntime"));
        } catch (RuntimeException | Error failure) {
            fontStore.close();
            settingsStore.close();
            throw failure;
        }
    }

    /// Creates an embeddable settings center on the event dispatch thread.
    ///
    /// @param store toolkit-neutral general and network settings store
    /// @param appearancePanel appearance page to embed and own
    public SettingsCenterPanel(SettingsCenterStore store, AppearanceSettingsPanel appearancePanel) {
        this(store, appearancePanel, LauncherSettingsRestartCommand.create());
    }

    /// Creates an embeddable settings center with an injectable restart lifecycle command.
    ///
    /// @param store toolkit-neutral general and network settings store
    /// @param appearancePanel appearance page to embed and own
    /// @param restartCommand launcher restart lifecycle command
    SettingsCenterPanel(
            SettingsCenterStore store,
            AppearanceSettingsPanel appearancePanel,
            SettingsRestartCommand restartCommand) {
        this(store, appearancePanel, restartCommand, null);
    }

    /// Creates an embeddable settings center with injectable restart and maintenance actions.
    ///
    /// @param store toolkit-neutral general and network settings store
    /// @param appearancePanel appearance page to embed and own
    /// @param restartCommand launcher restart lifecycle command
    /// @param maintenanceActions asynchronous maintenance actions, or `null` to create production actions
    SettingsCenterPanel(
            SettingsCenterStore store,
            AppearanceSettingsPanel appearancePanel,
            SettingsRestartCommand restartCommand,
            @Nullable SettingsMaintenanceActions maintenanceActions) {
        this(store, appearancePanel, restartCommand, maintenanceActions, null, null);
    }

    /// Creates an embeddable settings center with optional production font settings.
    ///
    /// @param store toolkit-neutral general and network settings store
    /// @param appearancePanel appearance page to embed and own
    /// @param restartCommand launcher restart lifecycle command
    /// @param maintenanceActions asynchronous maintenance actions, or `null` to create production actions
    /// @param fontStore font settings store, or `null` to omit the production-only font section
    /// @param fontRuntime live font runtime when `fontStore` is present, otherwise `null`
    private SettingsCenterPanel(
            SettingsCenterStore store,
            AppearanceSettingsPanel appearancePanel,
            SettingsRestartCommand restartCommand,
            @Nullable SettingsMaintenanceActions maintenanceActions,
            @Nullable FontSettingsStore fontStore,
            @Nullable FontSettingsRuntime fontRuntime) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.store = Objects.requireNonNull(store, "store");
        this.appearancePanel = Objects.requireNonNull(appearancePanel, "appearancePanel");
        restartPanel = new SettingsRestartPanel(
                localizedRestartStrings(),
                restartCommand,
                this::restartActivityChanged);
        appearancePanel.attachCornerRadiusRestartPanel(
                localizedDelayedEffectRestartStrings(),
                restartCommand,
                this::restartActivityChanged);
        fontSettingsPanel = fontStore == null
                ? null
                : new FontSettingsPanel(
                        fontStore,
                        Objects.requireNonNull(fontRuntime, "fontRuntime"),
                        FontFamilyCatalog.system(),
                        Schedulers.io(),
                        localizedDelayedEffectRestartStrings(),
                        restartCommand,
                        this::restartActivityChanged);
        languageBox = new JComboBox<>(new DefaultComboBoxModel<>(
                SupportedLocale.getSupportedLocales().toArray(SupportedLocale[]::new)));
        javaManagementPanel = new JavaManagementPanel(new JavaManagerRuntimeManagementService());
        gameSettingsPresetsPanel = GameSettingsPresetsPanel.createForCurrentSettings();
        gameDirectoryManagementPanel = GameDirectoryManagementPanel.createForCurrentDirectories();
        launcherLogPanel = LauncherLogPanel.createForCurrentLauncher();
        nbtSettingsPanel = NBTSettingsPanel.createForCurrentLauncher();
        this.maintenanceActions = maintenanceActions == null
                ? LauncherSettingsMaintenanceActions.create(this)
                : maintenanceActions;

        configureComponents();
        storeSubscription = store.subscribe(this::storeChanged);
        applySnapshot(store.snapshot());
    }

    /// Returns the settings snapshot currently represented by controls.
    ///
    /// @return displayed immutable settings state
    public SettingsCenterSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(displayedSnapshot, "initial settings snapshot was not applied");
    }

    /// Returns the stable tab navigation component for shell-level focused tests.
    ///
    /// @return settings page tabs
    public JTabbedPane tabs() {
        EdtDispatcher.requireEventDispatchThread();
        return tabs;
    }

    /// Selects the dedicated game-directory management tab.
    public void showGameDirectories() {
        EdtDispatcher.requireEventDispatchThread();
        tabs.setSelectedComponent(gameDirectoryManagementPanel);
    }

    /// Selects game-directory management and opens its add editor.
    public void beginAddingGameDirectory() {
        EdtDispatcher.requireEventDispatchThread();
        showGameDirectories();
        gameDirectoryManagementPanel.beginAddingDirectory();
    }

    /// Releases the owned store subscription, store, and every embedded settings resource.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                closed = true;
                storeSubscription.unsubscribe();
                store.close();
                appearancePanel.close();
                if (fontSettingsPanel != null) {
                    fontSettingsPanel.close();
                }
                javaManagementPanel.close();
                gameSettingsPresetsPanel.close();
                gameDirectoryManagementPanel.close();
                launcherLogPanel.close();
                nbtSettingsPanel.close();
                maintenanceActions.close();
                restartPanel.close();
                setInteractiveControlsEnabled(false);
            }
        });
    }

    /// Creates every functional settings tab and configures component interactions.
    private void configureComponents() {
        setOpaque(false);
        configureGeneralControls();
        configureDownloadAndProxyControls();
        SwingTransparency.revealBackgroundThroughTabs(tabs);
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

        tabs.addTab(i18n("settings.launcher.general"), createScrollPane(createGeneralPage()));
        tabs.addTab(i18n("settings.launcher.download"), createScrollPane(createDownloadAndProxyPage()));
        tabs.addTab(i18n("settings.launcher.appearance"), createScrollPane(createAppearancePage()));
        tabs.addTab(i18n("settings.type.global.preset.manage_all"), gameSettingsPresetsPanel);
        tabs.addTab(i18n("game_directory.title"), gameDirectoryManagementPanel);
        tabs.addTab(i18n("java.management"), javaManagementPanel);
        tabs.addTab(nbtSettingsPanel.tabTitle(), nbtSettingsPanel);
        tabs.addTab(i18n("help"), createScrollPane(createHelpPage()));
        tabs.addTab(i18n("contact"), createScrollPane(createFeedbackPage()));
        tabs.addTab(i18n("about"), createScrollPane(createAboutPage()));
        add(tabs, BorderLayout.CENTER);
    }

    /// Combines appearance and font controls into one continuous transparent page.
    ///
    /// @return appearance page content
    private JPanel createAppearancePage() {
        JPanel page = new JPanel(new MigLayout("insets 0, fillx, wrap 1", "[grow,fill]", "[]"));
        page.setOpaque(false);
        page.add(appearancePanel, "growx");
        if (fontSettingsPanel != null) {
            page.add(new JSeparator(), "growx, gapx 24, gapbottom 12");
            page.add(fontSettingsPanel, "growx");
        }
        return page;
    }

    /// Configures immediate-persistence controls for launcher-wide general preferences.
    private void configureGeneralControls() {
        languageBox.setRenderer(localeRenderer());
        languageBox.addActionListener(event -> {
            @Nullable SupportedLocale selected = (SupportedLocale) languageBox.getSelectedItem();
            if (!applyingSnapshot && selected != null) {
                store.setLanguage(selected);
                restartPanel.updateSettings(selected, disableAprilFoolsBox.isSelected());
            }
        });
        updateChannelBox.setName("settingsUpdateChannel");
        updateChannelBox.setRenderer(updateChannelRenderer());
        updateChannelBox.setSelectedItem(UpdateChannel.getChannel());
        checkUpdatesButton.setName("settingsUpdateCheck");
        checkUpdatesButton.addActionListener(event -> checkForUpdates());
        updateStatusLabel.setName("settingsUpdateStatus");
        previewUpdatesBox.addActionListener(event -> {
            if (!applyingSnapshot) {
                store.setAcceptPreviewUpdates(previewUpdatesBox.isSelected());
            }
        });
        disableUpdatePromptBox.addActionListener(event -> {
            if (!applyingSnapshot) {
                store.setAutomaticUpdatePromptDisabled(disableUpdatePromptBox.isSelected());
            }
        });
        disableAprilFoolsBox.addActionListener(event -> {
            if (!applyingSnapshot) {
                store.setAprilFoolsDisabled(disableAprilFoolsBox.isSelected());
                @Nullable SupportedLocale selected = (SupportedLocale) languageBox.getSelectedItem();
                if (selected != null) {
                    restartPanel.updateSettings(selected, disableAprilFoolsBox.isSelected());
                }
            }
        });
    }

    /// Configures immediate selectors and explicit free-form download and proxy persistence actions.
    private void configureDownloadAndProxyControls() {
        commonDirectoryTypeBox.setRenderer(commonDirectoryTypeRenderer());
        commonDirectoryTypeBox.addActionListener(event -> {
            @Nullable EnumCommonDirectory selected = (EnumCommonDirectory) commonDirectoryTypeBox.getSelectedItem();
            if (!applyingSnapshot && selected != null) {
                store.setCommonDirectoryType(selected);
                updateDownloadControlAvailability();
            }
        });
        chooseDirectoryButton.addActionListener(event -> chooseCommonDirectory());
        revealDirectoryButton.addActionListener(event -> revealResolvedCommonDirectory());
        clearCacheButton.setName("settingsClearCache");
        clearCacheButton.addActionListener(event -> clearCache());
        cacheStatusLabel.setName("settingsCacheStatus");

        automaticThreadsBox.addActionListener(event -> {
            if (!applyingSnapshot) {
                store.setAutomaticDownloadThreads(automaticThreadsBox.isSelected());
                updateDownloadControlAvailability();
            }
        });
        versionListSourceBox.setRenderer(downloadSourceRenderer());
        versionListSourceBox.addActionListener(event -> {
            @Nullable DownloadSource selected = (DownloadSource) versionListSourceBox.getSelectedItem();
            if (!applyingSnapshot && selected != null) {
                store.setVersionListSource(selected);
            }
        });
        fileDownloadSourceBox.setRenderer(downloadSourceRenderer());
        fileDownloadSourceBox.addActionListener(event -> {
            @Nullable DownloadSource selected = (DownloadSource) fileDownloadSourceBox.getSelectedItem();
            if (!applyingSnapshot && selected != null) {
                store.setFileDownloadSource(selected);
            }
        });
        addonSourceBox.addActionListener(event -> {
            @Nullable String selected = (String) addonSourceBox.getSelectedItem();
            if (!applyingSnapshot && selected != null) {
                store.setDefaultAddonSource(selected);
            }
        });

        proxyTypeBox.setRenderer(proxyTypeRenderer());
        proxyTypeBox.addActionListener(event -> {
            @Nullable ProxyType selected = (ProxyType) proxyTypeBox.getSelectedItem();
            if (!applyingSnapshot && selected != null) {
                store.setProxyType(selected);
                updateProxyControlAvailability();
            }
        });
        proxyAuthenticationBox.addActionListener(event -> {
            if (!applyingSnapshot) {
                store.setProxyAuthenticationEnabled(proxyAuthenticationBox.isSelected());
                updateProxyControlAvailability();
            }
        });
    }

    /// Creates the general preferences page.
    ///
    /// @return fully configured general-preferences content
    private JPanel createGeneralPage() {
        JPanel page = createPage();
        page.add(createHeading(i18n("settings.launcher.general")), "growx");
        page.add(createFieldRow(i18n("settings.launcher.language"), languageBox), "growx");
        page.add(disableAprilFoolsBox, "growx");
        page.add(restartPanel, "growx");
        page.add(new JSeparator(), "growx");
        page.add(createHeading(i18n("update")), "growx");
        page.add(createFieldRow(i18n("update"), updateChannelBox), "growx");
        page.add(createUpdateActionsRow(), "growx");
        page.add(previewUpdatesBox, "growx");
        page.add(disableUpdatePromptBox, "growx");
        page.add(new JSeparator(), "growx");
        page.add(createHeading(i18n("settings.launcher.debug")), "growx");
        page.add(launcherLogPanel, "growx");
        return page;
    }

    /// Creates the download and proxy preferences page.
    ///
    /// @return fully configured network-preferences content
    private JPanel createDownloadAndProxyPage() {
        JPanel page = createPage();
        page.add(createHeading(i18n("settings.launcher.download")), "growx");
        page.add(createFieldRow(i18n("launcher.cache_directory"), commonDirectoryTypeBox), "growx");
        page.add(createDirectoryRow(), "growx");
        page.add(createCacheActionsRow(), "growx");
        page.add(new JSeparator(), "growx");
        page.add(automaticThreadsBox, "growx");
        page.add(createFieldRow(i18n("settings.launcher.download.threads"), downloadThreadsField), "growx");
        page.add(createFieldRow(i18n("settings.launcher.version_list_source"), versionListSourceBox), "growx");
        page.add(createFieldRow(i18n("settings.launcher.download_source"), fileDownloadSourceBox), "growx");
        page.add(createFieldRow(i18n("settings.launcher.default_addon_source"), addonSourceBox), "growx");
        page.add(new JSeparator(), "growx");
        page.add(createHeading(i18n("settings.launcher.proxy")), "growx");
        page.add(createFieldRow(i18n("settings.launcher.proxy"), proxyTypeBox), "growx");
        page.add(createFieldRow(i18n("settings.launcher.proxy.host"), proxyHostField), "growx");
        page.add(createFieldRow(i18n("settings.launcher.proxy.port"), proxyPortField), "growx");
        page.add(proxyAuthenticationBox, "growx");
        page.add(createFieldRow(i18n("settings.launcher.proxy.username"), proxyUsernameField), "growx");
        page.add(createFieldRow(i18n("settings.launcher.proxy.password"), proxyPasswordField), "growx");
        page.add(createNetworkActionsRow(), "growx");
        page.add(networkValidationLabel, "growx");
        return page;
    }

    /// Creates a custom-directory input row with native selection and reveal actions.
    ///
    /// @return configured common-directory row
    private JPanel createDirectoryRow() {
        JPanel row = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]8[]8[]", "[]"));
        row.setOpaque(false);
        row.add(commonDirectoryField, "growx");
        row.add(chooseDirectoryButton);
        row.add(revealDirectoryButton);
        return row;
    }

    /// Creates a cache-cleaning action row that remains usable without widening the directory input row.
    ///
    /// @return configured cache-action row
    private JPanel createCacheActionsRow() {
        JPanel row = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", "[]"));
        row.setOpaque(false);
        row.add(cacheStatusLabel, "growx");
        row.add(clearCacheButton);
        return row;
    }

    /// Creates the manual update action and its non-modal status presentation.
    ///
    /// @return configured update-action row
    private JPanel createUpdateActionsRow() {
        JPanel row = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", "[]"));
        row.setOpaque(false);
        row.add(updateStatusLabel, "growx");
        row.add(checkUpdatesButton);
        return row;
    }

    /// Creates the explicit persistence action for free-form download and proxy values.
    ///
    /// @return configured action row
    private JPanel createNetworkActionsRow() {
        JPanel row = new JPanel(new MigLayout("insets 4 0 0 0", "[grow]", "[]"));
        row.setOpaque(false);
        confirmNetworkButton.addActionListener(event -> persistNetworkInputs());
        row.add(confirmNetworkButton, "alignx right");
        return row;
    }

    /// Creates the functional help page with documentation and changelog navigation.
    ///
    /// @return help content
    private JPanel createHelpPage() {
        JPanel page = createPage();
        page.add(createHeading(i18n("help")), "growx");
        page.add(createExternalLinkButton(i18n("help.doc"), Metadata.DOCS_URL), "alignx left");
        page.add(createExternalLinkButton(i18n("update.changelog"), Metadata.CHANGELOG_URL), "alignx left");
        return page;
    }

    /// Creates the functional feedback page with upstream contact and community navigation.
    ///
    /// @return feedback content
    private JPanel createFeedbackPage() {
        JPanel page = createPage();
        page.add(createHeading(i18n("contact")), "growx");
        page.add(createExternalLinkButton(i18n("contact"), Metadata.CONTACT_URL), "alignx left");
        page.add(createExternalLinkButton(i18n("contact.chat"), Metadata.GROUPS_URL), "alignx left");
        return page;
    }

    /// Creates the about page with identity, acknowledgements, dependency notices, and legal links.
    ///
    /// @return about content
    private JPanel createAboutPage() {
        return new AboutPanel(this::openExternalLink);
    }

    /// Creates a button that opens one trusted metadata URL through the native browser.
    ///
    /// @param text localized action text
    /// @param url metadata URL to open
    /// @return browser-opening button
    private JButton createExternalLinkButton(String text, String url) {
        URI destination = URI.create(Objects.requireNonNull(url, "url"));
        JButton button = new JButton(Objects.requireNonNull(text, "text"));
        button.addActionListener(event -> openExternalLink(destination));
        return button;
    }

    /// Creates a scroll container that preserves page width while allowing long settings pages to remain usable.
    ///
    /// @param content page content
    /// @return configured vertical scrolling container
    private static JScrollPane createScrollPane(Component content) {
        JScrollPane scrollPane = new JScrollPane(Objects.requireNonNull(content, "content"));
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        SwingTransparency.revealBackgroundThroughScrollPane(scrollPane);
        return scrollPane;
    }

    /// Creates a consistently spaced unframed page column.
    ///
    /// @return reusable page layout
    private static JPanel createPage() {
        JPanel page = new JPanel(new MigLayout("insets 20, fillx, wrap 1", "[grow,fill]", "[]12[]12[]"));
        page.setOpaque(false);
        return page;
    }

    /// Creates a compact heading suitable for a settings-page section.
    ///
    /// @param text heading text
    /// @return configured heading label
    private static JLabel createHeading(String text) {
        JLabel heading = new JLabel(Objects.requireNonNull(text, "text"));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 20.0F));
        return heading;
    }

    /// Creates a standard two-column label and component row.
    ///
    /// @param labelText localized field label
    /// @param control editable or selectable field control
    /// @return configured field row
    private static JPanel createFieldRow(String labelText, Component control) {
        JPanel row = new JPanel(new MigLayout("insets 2 0, fillx", "[150!,fill][grow,fill]", "[]"));
        row.setOpaque(false);
        row.add(new JLabel(Objects.requireNonNull(labelText, "labelText")), "aligny center");
        row.add(Objects.requireNonNull(control, "control"), "growx");
        return row;
    }

    /// Creates a localized renderer for supported locales.
    ///
    /// @return locale combo-box renderer
    private static ListCellRenderer<SupportedLocale> localeRenderer() {
        return (list, value, index, selected, focus) -> comboRenderer(
                list,
                value == null ? "" : value.getDisplayName(SupportedLocale.DEFAULT),
                selected);
    }

    /// Creates a localized renderer for launcher update channels.
    ///
    /// @return update-channel combo-box renderer
    private static ListCellRenderer<UpdateChannel> updateChannelRenderer() {
        return (list, value, index, selected, focus) -> comboRenderer(list, updateChannelText(value), selected);
    }

    /// Creates a localized renderer for common-directory modes.
    ///
    /// @return common-directory combo-box renderer
    private static ListCellRenderer<EnumCommonDirectory> commonDirectoryTypeRenderer() {
        return (list, value, index, selected, focus) -> comboRenderer(list, commonDirectoryTypeText(value), selected);
    }

    /// Creates a localized renderer for download source preferences.
    ///
    /// @return download-source combo-box renderer
    private static ListCellRenderer<DownloadSource> downloadSourceRenderer() {
        return (list, value, index, selected, focus) -> comboRenderer(list, downloadSourceText(value), selected);
    }

    /// Creates a localized renderer for proxy strategies.
    ///
    /// @return proxy-type combo-box renderer
    private static ListCellRenderer<ProxyType> proxyTypeRenderer() {
        return (list, value, index, selected, focus) -> comboRenderer(list, proxyTypeText(value), selected);
    }

    /// Builds a list-cell renderer label following the active Swing list colors.
    ///
    /// @param list source list
    /// @param text rendered item text
    /// @param selected whether the item is selected
    /// @return configured renderer component
    private static JLabel comboRenderer(JList<?> list, String text, boolean selected) {
        JLabel label = new JLabel(Objects.requireNonNull(text, "text"));
        label.setOpaque(true);
        if (selected) {
            label.setBackground(list.getSelectionBackground());
            label.setForeground(list.getSelectionForeground());
        } else {
            label.setBackground(list.getBackground());
            label.setForeground(list.getForeground());
        }
        label.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        return label;
    }

    /// Converts a nullable common-directory selection to its localized display name.
    ///
    /// @param directoryType represented directory selection, or null while a renderer initializes
    /// @return localized directory mode text
    private static String commonDirectoryTypeText(@Nullable EnumCommonDirectory directoryType) {
        if (directoryType == EnumCommonDirectory.CUSTOM) {
            return i18n("settings.custom");
        }
        return i18n("launcher.cache_directory.default");
    }

    /// Converts a nullable update-channel selection to its localized display name.
    ///
    /// @param channel represented update channel, or null while a renderer initializes
    /// @return localized channel text
    private static String updateChannelText(@Nullable UpdateChannel channel) {
        if (channel == null) {
            return "";
        }
        return switch (channel) {
            case STABLE -> i18n("update.channel.stable");
            case BETA -> i18n("update.channel.beta");
            case ALPHA -> i18n("update.channel.alpha");
            case DEV -> i18n("update.channel.dev");
        };
    }

    /// Converts a nullable download source to its localized display name.
    ///
    /// @param source represented source, or null while a renderer initializes
    /// @return localized download-source text
    private static String downloadSourceText(@Nullable DownloadSource source) {
        if (source == DownloadSource.OFFICIAL) {
            return i18n("download.provider.official");
        }
        if (source == DownloadSource.MIRROR) {
            return i18n("download.provider.mirror");
        }
        return i18n("settings.launcher.download_source.auto");
    }

    /// Converts a nullable proxy strategy to its localized display name.
    ///
    /// @param proxyType represented proxy type, or null while a renderer initializes
    /// @return localized proxy-type text
    private static String proxyTypeText(@Nullable ProxyType proxyType) {
        if (proxyType == ProxyType.DIRECT) {
            return i18n("settings.launcher.proxy.none");
        }
        if (proxyType == ProxyType.HTTP) {
            return i18n("settings.launcher.proxy.http");
        }
        if (proxyType == ProxyType.SOCKS) {
            return i18n("settings.launcher.proxy.socks");
        }
        return i18n("settings.launcher.proxy.default");
    }

    /// Starts a manual launcher update check for the selected channel and preview preference.
    private void checkForUpdates() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || restartInProgress || updateCheckInProgress) {
            return;
        }
        @Nullable UpdateChannel channel = (UpdateChannel) updateChannelBox.getSelectedItem();
        if (channel == null) {
            updateStatusLabel.setText(i18n("update.failed"));
            return;
        }

        updateCheckInProgress = true;
        updateStatusLabel.setText(i18n("update.checking"));
        updateMaintenanceControlAvailability();
        try {
            maintenanceActions.checkForUpdates(new UpdateCheckRequest(channel, previewUpdatesBox.isSelected()))
                    .whenComplete((
                            @Nullable UpdateCheckResult result,
                            @Nullable Throwable failure) -> EdtDispatcher.execute(
                                    () -> completeUpdateCheck(result, failure)));
        } catch (RuntimeException failure) {
            completeUpdateCheck(null, failure);
        }
    }

    /// Restores manual update controls and presents one terminal status on the EDT.
    ///
    /// @param result successful check result, or null on failure
    /// @param failure check or prompt failure, or null on success
    private void completeUpdateCheck(
            @Nullable UpdateCheckResult result,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        updateCheckInProgress = false;
        if (failure != null || result == null) {
            updateStatusLabel.setText(i18n("update.failed"));
            if (failure != null) {
                LOG.warning("Failed to check for launcher updates from settings", failure);
            }
        } else if (result.updateAvailable()) {
            updateStatusLabel.setText(i18n("update.newest_version", result.remoteVersion().version()));
        } else {
            updateStatusLabel.setText(i18n("update.latest"));
        }
        updateMaintenanceControlAvailability();
    }

    /// Starts cache cleanup for the effective common directory without blocking the EDT.
    private void clearCache() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable SettingsCenterSnapshot snapshot = displayedSnapshot;
        if (closed || restartInProgress || cacheClearInProgress || snapshot == null) {
            return;
        }
        String resolvedDirectory = snapshot.resolvedCommonDirectory();
        if (resolvedDirectory.isBlank()) {
            cacheStatusLabel.setText(i18n("message.failed"));
            return;
        }

        Path commonDirectory;
        try {
            commonDirectory = Path.of(resolvedDirectory);
        } catch (RuntimeException failure) {
            cacheStatusLabel.setText(i18n("message.failed"));
            LOG.warning("Invalid launcher cache directory: " + resolvedDirectory, failure);
            return;
        }

        cacheClearInProgress = true;
        cacheStatusLabel.setText(i18n("launcher.cache_directory.clean"));
        updateDownloadControlAvailability();
        try {
            maintenanceActions.clearCache(commonDirectory).whenComplete((
                    @Nullable Boolean cleaned,
                    @Nullable Throwable failure) -> EdtDispatcher.execute(
                            () -> completeCacheClear(commonDirectory, cleaned, failure)));
        } catch (RuntimeException failure) {
            completeCacheClear(commonDirectory, null, failure);
        }
    }

    /// Restores the cache action and reports whether the exact cache child was cleaned.
    ///
    /// @param commonDirectory common directory used by the completed request
    /// @param cleaned whether cleanup succeeded, or null on exceptional completion
    /// @param failure cleanup failure, or null for a normal boolean outcome
    private void completeCacheClear(
            Path commonDirectory,
            @Nullable Boolean cleaned,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        cacheClearInProgress = false;
        boolean succeeded = failure == null && Boolean.TRUE.equals(cleaned);
        cacheStatusLabel.setText(i18n(succeeded ? "message.success" : "message.failed"));
        if (failure != null) {
            LOG.warning("Failed to clear launcher cache directory " + commonDirectory.resolve("cache"), failure);
        } else if (!succeeded) {
            LOG.warning("Failed to clear launcher cache directory " + commonDirectory.resolve("cache"));
        }
        updateDownloadControlAvailability();
    }

    /// Opens a native directory chooser and persists the chosen custom directory.
    private void chooseCommonDirectory() {
        EdtDispatcher.requireEventDispatchThread();
        JFileChooser chooser = new EditablePathChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        String configuredDirectory = commonDirectoryField.getText().trim();
        if (!configuredDirectory.isEmpty()) {
            File configuredFile = new File(configuredDirectory);
            if (configuredFile.isDirectory()) {
                chooser.setCurrentDirectory(configuredFile);
            }
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            @Nullable File chosenDirectory = chooser.getSelectedFile();
            if (chosenDirectory != null) {
                commonDirectoryField.setText(chosenDirectory.getAbsolutePath());
                commonDirectoryTypeBox.setSelectedItem(EnumCommonDirectory.CUSTOM);
                persistNetworkInputs();
            }
        }
    }

    /// Opens the currently resolved common directory in the platform file manager when supported.
    private void revealResolvedCommonDirectory() {
        @Nullable SettingsCenterSnapshot snapshot = displayedSnapshot;
        if (snapshot == null || snapshot.resolvedCommonDirectory().isBlank()) {
            showError(i18n("message.failed"));
            return;
        }
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("Desktop integration is unavailable");
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.OPEN)) {
                throw new IOException("Directory opening is unavailable");
            }
            desktop.open(new File(snapshot.resolvedCommonDirectory()));
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            showError(snapshot.resolvedCommonDirectory());
        }
    }

    /// Validates and persists text-field settings that must be committed as a coherent group.
    private void persistNetworkInputs() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || applyingSnapshot) {
            return;
        }
        @Nullable Integer threads = parsePositiveInteger(downloadThreadsField.getText());
        @Nullable ProxyType proxyType = (ProxyType) proxyTypeBox.getSelectedItem();
        @Nullable Integer proxyPort = parseProxyPort(proxyPortField.getText());
        if (threads == null || (proxyType != null && proxyType.usesCustomAddress() && proxyPort == null)) {
            networkValidationLabel.setText(i18n("input.number"));
            return;
        }

        networkValidationLabel.setText("");
        store.setCommonDirectory(commonDirectoryField.getText().trim());
        store.setDownloadThreads(threads);
        if (proxyPort != null) {
            store.setProxyPort(proxyPort);
        }
        store.setProxyHost(proxyHostField.getText().trim());
        store.setProxyUsername(proxyUsernameField.getText());
        char[] password = proxyPasswordField.getPassword();
        try {
            store.setProxyPassword(new String(password));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /// Parses a positive download-concurrency value.
    ///
    /// @param raw text-field value
    /// @return positive integer, or null when the input is invalid
    private static @Nullable Integer parsePositiveInteger(String raw) {
        try {
            int parsed = Integer.parseInt(Objects.requireNonNull(raw, "raw").trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /// Parses a legal TCP proxy port, accepting an empty value only when a custom proxy is not in use.
    ///
    /// @param raw text-field value
    /// @return port from 0 through 65535, or null when no valid port is present
    private static @Nullable Integer parseProxyPort(String raw) {
        String normalized = Objects.requireNonNull(raw, "raw").trim();
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(normalized);
            return parsed >= 0 && parsed <= 0xFFFF ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /// Opens a trusted launcher metadata URL in the desktop browser.
    ///
    /// @param destination destination URI
    private void openExternalLink(URI destination) {
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("Desktop integration is unavailable");
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                throw new IOException("Browser opening is unavailable");
            }
            desktop.browse(Objects.requireNonNull(destination, "destination"));
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            showError(destination.toString());
        }
    }

    /// Displays a concise native error message for an action whose platform integration is unavailable.
    ///
    /// @param detail actionable directory or URL text
    private void showError(String detail) {
        JOptionPane.showMessageDialog(
                this,
                Objects.requireNonNull(detail, "detail"),
                i18n("message.error"),
                JOptionPane.ERROR_MESSAGE);
    }

    /// Coalesces store changes to the latest snapshot on the event dispatch thread.
    ///
    /// @param change store transition that invalidated displayed controls
    private void storeChanged(ValueChange<SettingsCenterSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                applySnapshot(store.snapshot());
            }
        });
    }

    /// Applies a snapshot without treating component updates as user persistence requests.
    ///
    /// @param snapshot latest settings state
    private void applySnapshot(SettingsCenterSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(snapshot, "snapshot");
        applyingSnapshot = true;
        try {
            displayedSnapshot = snapshot;
            languageBox.setSelectedItem(snapshot.language());
            previewUpdatesBox.setSelected(snapshot.acceptPreviewUpdates());
            disableUpdatePromptBox.setSelected(snapshot.disableAutomaticUpdatePrompt());
            disableAprilFoolsBox.setSelected(snapshot.disableAprilFools());
            restartPanel.updateSettings(snapshot.language(), snapshot.disableAprilFools());

            commonDirectoryTypeBox.setSelectedItem(snapshot.commonDirectoryType());
            commonDirectoryField.setText(snapshot.commonDirectory());
            automaticThreadsBox.setSelected(snapshot.autoDownloadThreads());
            downloadThreadsField.setText(Integer.toString(snapshot.downloadThreads()));
            versionListSourceBox.setSelectedItem(snapshot.versionListSource());
            fileDownloadSourceBox.setSelectedItem(snapshot.fileDownloadSource());
            addonSourceBox.setSelectedItem(snapshot.defaultAddonSource());

            proxyTypeBox.setSelectedItem(snapshot.proxyType());
            proxyHostField.setText(snapshot.proxyHost());
            proxyPortField.setText(Integer.toString(snapshot.proxyPort()));
            proxyAuthenticationBox.setSelected(snapshot.proxyAuthenticationEnabled());
            proxyUsernameField.setText(snapshot.proxyUsername());
            proxyPasswordField.setText(snapshot.proxyPassword());
            networkValidationLabel.setText("");

            setInteractiveControlsEnabled(snapshot.writable());
            updateDownloadControlAvailability();
            updateProxyControlAvailability();
        } finally {
            applyingSnapshot = false;
        }
    }

    /// Enables or disables all settings controls based on persistent-settings availability.
    ///
    /// @param enabled whether settings changes can be persisted
    private void setInteractiveControlsEnabled(boolean enabled) {
        boolean interactive = enabled && !closed && !restartInProgress;
        tabs.setEnabled(!closed && !restartInProgress);
        languageBox.setEnabled(interactive);
        previewUpdatesBox.setEnabled(interactive);
        disableUpdatePromptBox.setEnabled(interactive);
        disableAprilFoolsBox.setEnabled(interactive);
        restartPanel.setAvailable(interactive);
        appearancePanel.setRestartInProgress(restartInProgress);
        if (fontSettingsPanel != null) {
            fontSettingsPanel.setRestartInProgress(restartInProgress);
        }
        commonDirectoryTypeBox.setEnabled(interactive);
        chooseDirectoryButton.setEnabled(interactive);
        automaticThreadsBox.setEnabled(interactive);
        versionListSourceBox.setEnabled(interactive);
        fileDownloadSourceBox.setEnabled(interactive);
        addonSourceBox.setEnabled(interactive);
        proxyTypeBox.setEnabled(interactive);
        proxyAuthenticationBox.setEnabled(interactive);
        confirmNetworkButton.setEnabled(interactive);
        networkValidationLabel.setEnabled(interactive);
        updateStatusLabel.setEnabled(!closed);
        cacheStatusLabel.setEnabled(!closed);
        updateMaintenanceControlAvailability();
        updateDownloadControlAvailability();
        updateProxyControlAvailability();
    }

    /// Blocks or restores settings edits while a restart waits for persistence and process startup.
    ///
    /// @param inProgress whether the restart command is active
    private void restartActivityChanged(boolean inProgress) {
        EdtDispatcher.requireEventDispatchThread();
        restartInProgress = inProgress;
        @Nullable SettingsCenterSnapshot snapshot = displayedSnapshot;
        setInteractiveControlsEnabled(snapshot != null && snapshot.writable());
    }

    /// Updates directory, cache, and download controls from persistent state and active-operation ownership.
    private void updateDownloadControlAvailability() {
        @Nullable SettingsCenterSnapshot snapshot = displayedSnapshot;
        boolean writable = snapshot != null && snapshot.writable() && !closed && !restartInProgress;
        boolean resolvedDirectoryAvailable = snapshot != null && !snapshot.resolvedCommonDirectory().isBlank();
        @Nullable EnumCommonDirectory directoryType = (EnumCommonDirectory) commonDirectoryTypeBox.getSelectedItem();
        boolean customDirectory = directoryType == EnumCommonDirectory.CUSTOM;
        commonDirectoryField.setEnabled(writable && customDirectory);
        chooseDirectoryButton.setEnabled(writable && customDirectory);
        revealDirectoryButton.setEnabled(resolvedDirectoryAvailable && !closed && !restartInProgress);
        clearCacheButton.setEnabled(
                resolvedDirectoryAvailable && !closed && !restartInProgress && !cacheClearInProgress);
        downloadThreadsField.setEnabled(writable && !automaticThreadsBox.isSelected());
    }

    /// Updates controls used by manual update checks independently of persistent-settings writability.
    private void updateMaintenanceControlAvailability() {
        boolean available = !closed && !restartInProgress && !updateCheckInProgress;
        updateChannelBox.setEnabled(available);
        checkUpdatesButton.setEnabled(available && updateChannelBox.getSelectedItem() != null);
    }

    /// Updates controls whose availability depends on the selected proxy strategy and authentication preference.
    private void updateProxyControlAvailability() {
        @Nullable SettingsCenterSnapshot snapshot = displayedSnapshot;
        boolean writable = snapshot != null && snapshot.writable() && !closed && !restartInProgress;
        @Nullable ProxyType proxyType = (ProxyType) proxyTypeBox.getSelectedItem();
        boolean customProxy = proxyType != null && proxyType.usesCustomAddress();
        boolean authenticatedProxy = customProxy && proxyAuthenticationBox.isSelected();
        proxyHostField.setEnabled(writable && customProxy);
        proxyPortField.setEnabled(writable && customProxy);
        proxyAuthenticationBox.setEnabled(writable && customProxy);
        proxyUsernameField.setEnabled(writable && authenticatedProxy);
        proxyPasswordField.setEnabled(writable && authenticatedProxy);
    }

    /// Creates localized restart copy for the two settings that are process-initialized.
    ///
    /// @return localized shared restart presentation
    private static SettingsRestartStrings localizedRestartStrings() {
        return localizedRestartStrings("settings.restart.prompt");
    }

    /// Creates localized restart copy for settings whose status text is shared by all locales.
    ///
    /// @return localized delayed-effect restart presentation
    private static SettingsRestartStrings localizedDelayedEffectRestartStrings() {
        return localizedRestartStrings("settings.take_effect_after_restart");
    }

    /// Creates localized restart copy from one prompt key and the shared restart action keys.
    ///
    /// @param promptKey localized text shown before a restart-sensitive change
    /// @return localized restart presentation
    private static SettingsRestartStrings localizedRestartStrings(String promptKey) {
        return new SettingsRestartStrings(
                i18n(Objects.requireNonNull(promptKey, "promptKey")),
                i18n("settings.restart.required"),
                i18n("settings.restart.action"),
                i18n("settings.restart.in_progress"),
                i18n("settings.restart.failed"));
    }
}
