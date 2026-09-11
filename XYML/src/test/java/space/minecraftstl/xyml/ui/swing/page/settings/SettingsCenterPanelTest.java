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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.setting.BackgroundType;
import space.minecraftstl.xyml.setting.DownloadSource;
import space.minecraftstl.xyml.setting.EnumCommonDirectory;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;
import space.minecraftstl.xyml.setting.GameSettingsPresets;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.ProxyType;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.setting.UserSettings;
import space.minecraftstl.xyml.theme.BuiltinBackground;
import space.minecraftstl.xyml.theme.NetworkBackgroundImageCachePolicy;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.i18n.SupportedLocale;

import javax.swing.JCheckBox;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Verifies the MCP enablement gate through the real settings-center Swing controls.
@Isolated
@NotNullByDefault
public final class SettingsCenterPanelTest {
    /// Stable appearance strings used by the embedded appearance panel.
    private static final AppearanceSettingsStrings APPEARANCE_STRINGS = new AppearanceSettingsStrings(
            "Appearance",
            "Theme mode",
            "Theme",
            "System",
            "Light",
            "Dark",
            "Corner radius",
            "Animations",
            "Animation speed",
            AppearanceBackgroundStrings.englishFallback());

    /// Maintenance actions are intentionally unavailable in these focused MCP tests.
    private static final SettingsMaintenanceActions NOOP_MAINTENANCE = new SettingsMaintenanceActions() {
        /// Rejects an update request that is outside this focused test surface.
        @Override
        public CompletionStage<space.minecraftstl.xyml.ui.swing.update.UpdateCheckResult> checkForUpdates(
                space.minecraftstl.xyml.ui.swing.update.UpdateCheckRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("not used"));
        }

        /// Rejects a cache request that is outside this focused test surface.
        @Override
        public CompletionStage<Boolean> clearCache(java.nio.file.Path commonDirectory) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("not used"));
        }

        /// Releases no resources because this fake owns none.
        @Override
        public void close() {
        }
    };

    /// Enabling after an explicit confirmation persists only after the decision callback returns.
    @Test
    public void confirmationPrecedesPersistenceAndSelection() throws Exception {
        try (SettingsFixture ignored = SettingsFixture.install()) {
            FakeSettingsStore store = new FakeSettingsStore(snapshot(false, true));
            AtomicInteger decisions = new AtomicInteger();
            SettingsCenterPanel panel = createPanel(store, () -> {
                assertFalse(store.mcpEnabled(), "the store must remain disabled while the warning is pending");
                decisions.incrementAndGet();
                return new McpEnablementResult(true, false);
            });
            try {
                onEventDispatchThread(() -> {
                    JCheckBox enabled = findComponent(panel, "settingsMcpEnabled", JCheckBox.class);
                    enabled.doClick();
                    assertAll(
                            () -> assertEquals(1, decisions.get()),
                            () -> assertEquals(1, store.mcpEnablementWrites()),
                            () -> assertTrue(store.mcpEnabled()),
                            () -> assertTrue(enabled.isSelected()));
                });
            } finally {
                onEventDispatchThread(panel::close);
            }
        }
    }

    /// Cancelling the injected decision leaves both the persisted value and the visible switch disabled.
    @Test
    public void cancellationKeepsMcpDisabled() throws Exception {
        try (SettingsFixture ignored = SettingsFixture.install()) {
            FakeSettingsStore store = new FakeSettingsStore(snapshot(false, true));
            SettingsCenterPanel panel = createPanel(store, () -> new McpEnablementResult(false, false));
            try {
                onEventDispatchThread(() -> {
                    JCheckBox enabled = findComponent(panel, "settingsMcpEnabled", JCheckBox.class);
                    enabled.doClick();
                    assertAll(
                            () -> assertEquals(0, store.mcpEnablementWrites()),
                            () -> assertFalse(store.mcpEnabled()),
                            () -> assertFalse(enabled.isSelected()));
                });
            } finally {
                onEventDispatchThread(panel::close);
            }
        }
    }

    /// Keeps the permanent warning opt-out when the same decision cancels MCP enablement.
    @Test
    public void permanentSkipPersistsWhenEnablementIsCancelled() throws Exception {
        try (SettingsFixture ignored = SettingsFixture.install()) {
            FakeSettingsStore store = new FakeSettingsStore(snapshot(false, true));
            SettingsCenterPanel panel = createPanel(store, () -> new McpEnablementResult(false, true));
            try {
                onEventDispatchThread(() -> {
                    JCheckBox enabled = findComponent(panel, "settingsMcpEnabled", JCheckBox.class);
                    JCheckBox warning = findComponent(panel, "settingsMcpEnablementWarning", JCheckBox.class);
                    enabled.doClick();
                    assertAll(
                            () -> assertFalse(store.mcpEnabled()),
                            () -> assertFalse(store.showMcpEnablementWarning()),
                            () -> assertFalse(enabled.isSelected()),
                            () -> assertFalse(warning.isSelected()));
                });
            } finally {
                onEventDispatchThread(panel::close);
            }
        }
    }

    /// Applies the permanent warning opt-out before committing a confirmed MCP enablement.
    @Test
    public void permanentSkipPersistsWhenEnablementIsConfirmed() throws Exception {
        try (SettingsFixture ignored = SettingsFixture.install()) {
            FakeSettingsStore store = new FakeSettingsStore(snapshot(false, true));
            SettingsCenterPanel panel = createPanel(store, () -> new McpEnablementResult(true, true));
            try {
                onEventDispatchThread(() -> {
                    JCheckBox enabled = findComponent(panel, "settingsMcpEnabled", JCheckBox.class);
                    JCheckBox warning = findComponent(panel, "settingsMcpEnablementWarning", JCheckBox.class);
                    enabled.doClick();
                    assertAll(
                            () -> assertTrue(store.mcpEnabled()),
                            () -> assertFalse(store.showMcpEnablementWarning()),
                            () -> assertTrue(enabled.isSelected()),
                            () -> assertFalse(warning.isSelected()));
                });
            } finally {
                onEventDispatchThread(panel::close);
            }
        }
    }

    /// Disabling the warning preference bypasses the decision boundary and enables MCP immediately.
    @Test
    public void disabledWarningBypassesDecision() throws Exception {
        try (SettingsFixture ignored = SettingsFixture.install()) {
            FakeSettingsStore store = new FakeSettingsStore(snapshot(false, false));
            AtomicInteger decisions = new AtomicInteger();
            SettingsCenterPanel panel = createPanel(store, () -> {
                decisions.incrementAndGet();
                return new McpEnablementResult(false, false);
            });
            try {
                onEventDispatchThread(() -> {
                    JCheckBox enabled = findComponent(panel, "settingsMcpEnabled", JCheckBox.class);
                    enabled.doClick();
                    assertAll(
                            () -> assertEquals(0, decisions.get()),
                            () -> assertEquals(1, store.mcpEnablementWrites()),
                            () -> assertTrue(store.mcpEnabled()),
                            () -> assertTrue(enabled.isSelected()));
                });
            } finally {
                onEventDispatchThread(panel::close);
            }
        }
    }

    /// A broken warning surface follows advanced/headless semantics without changing the warning preference.
    @Test
    public void decisionFailureEnablesWithoutChangingWarningPreference() throws Exception {
        try (SettingsFixture ignored = SettingsFixture.install()) {
            FakeSettingsStore store = new FakeSettingsStore(snapshot(false, true));
            SettingsCenterPanel panel = createPanel(store, () -> {
                throw new IllegalStateException("synthetic dialog failure");
            });
            try {
                onEventDispatchThread(() -> {
                    JCheckBox enabled = findComponent(panel, "settingsMcpEnabled", JCheckBox.class);
                    enabled.doClick();
                    assertAll(
                            () -> assertEquals(1, store.mcpEnablementWrites()),
                            () -> assertTrue(store.mcpEnabled()),
                            () -> assertTrue(store.showMcpEnablementWarning()),
                            () -> assertTrue(enabled.isSelected()));
                });
            } finally {
                onEventDispatchThread(panel::close);
            }
        }
    }

    /// Re-enabling the warning checkbox restores the confirmation gate after a permanent skip.
    @Test
    public void warningCanBeReenabledAfterBeingDisabled() throws Exception {
        try (SettingsFixture ignored = SettingsFixture.install()) {
            FakeSettingsStore store = new FakeSettingsStore(snapshot(false, false));
            AtomicInteger decisions = new AtomicInteger();
            SettingsCenterPanel panel = createPanel(store, () -> {
                decisions.incrementAndGet();
                return new McpEnablementResult(false, false);
            });
            try {
                onEventDispatchThread(() -> {
                    JCheckBox warning = findComponent(panel, "settingsMcpEnablementWarning", JCheckBox.class);
                    JCheckBox enabled = findComponent(panel, "settingsMcpEnabled", JCheckBox.class);
                    assertFalse(warning.isSelected());
                    enabled.doClick();
                    assertTrue(store.mcpEnabled());
                    enabled.doClick();
                    assertFalse(store.mcpEnabled());
                    warning.doClick();
                    assertTrue(store.showMcpEnablementWarning());
                    enabled.doClick();
                    assertAll(
                            () -> assertEquals(1, decisions.get()),
                            () -> assertFalse(store.mcpEnabled()),
                            () -> assertFalse(enabled.isSelected()));
                });
            } finally {
                onEventDispatchThread(panel::close);
            }
        }
    }

    /// Uses the production decision in a truly headless test JVM without changing the warning preference.
    @Test
    public void headlessEnvironmentEnablesWithoutChangingWarningPreference() throws Exception {
        assumeTrue(GraphicsEnvironment.isHeadless(), "run with java.awt.headless=true to exercise this branch");
        try (SettingsFixture ignored = SettingsFixture.install()) {
            FakeSettingsStore store = new FakeSettingsStore(snapshot(false, true));
            SettingsCenterPanel panel = createPanelWithProductionDecision(store);
            try {
                onEventDispatchThread(() -> {
                    JCheckBox enabled = findComponent(panel, "settingsMcpEnabled", JCheckBox.class);
                    enabled.doClick();
                    assertAll(
                            () -> assertTrue(store.mcpEnabled()),
                            () -> assertTrue(store.showMcpEnablementWarning()),
                            () -> assertTrue(enabled.isSelected()));
                });
            } finally {
                onEventDispatchThread(panel::close);
            }
        }
    }

    /// Keeps the bearer token masked by default, persists exact text, and restores masking after visibility changes.
    @Test
    public void bearerTokenControlMasksAndPersistsExactText() throws Exception {
        try (SettingsFixture ignored = SettingsFixture.install()) {
            FakeSettingsStore store = new FakeSettingsStore(snapshot(false, true));
            SettingsCenterPanel panel = createPanel(store, () -> new McpEnablementResult(false, false));
            try {
                onEventDispatchThread(() -> {
                    JPasswordField token = findComponent(panel, "settingsMcpBearerToken", JPasswordField.class);
                    JCheckBox visibility = findComponent(
                            panel, "settingsMcpBearerTokenVisibility", JCheckBox.class);
                    char maskedEcho = token.getEchoChar();
                    assertNotEquals((char) 0, maskedEcho);
                    assertEquals(0, token.getPassword().length);

                    token.setText("  exact token text  ");
                    token.postActionEvent();
                    assertEquals("  exact token text  ", store.mcpBearerToken());

                    visibility.doClick();
                    assertEquals((char) 0, token.getEchoChar());
                    visibility.doClick();
                    assertEquals(maskedEcho, token.getEchoChar());
                    visibility.doClick();
                    panel.close();
                    assertFalse(visibility.isSelected());
                    assertEquals(maskedEcho, token.getEchoChar());
                });
            } finally {
                onEventDispatchThread(panel::close);
            }
        }
    }

    /// Constrains the native warning copy to a wrapping, label-like text component.
    @Test
    public void riskMessageUsesBoundedWrappingText() {
        onEventDispatchThread(() -> {
            JTextArea message = SettingsCenterPanel.createMcpEnablementWarningMessage(
                    "A deliberately long warning that must wrap instead of widening the native dialog forever.");
            assertAll(
                    () -> assertFalse(message.isEditable()),
                    () -> assertFalse(message.isFocusable()),
                    () -> assertTrue(message.getLineWrap()),
                    () -> assertTrue(message.getWrapStyleWord()),
                    () -> assertEquals(4, message.getRows()),
                    () -> assertEquals(48, message.getColumns()));
        });
    }

    /// Creates a real settings-center panel with only the MCP decision boundary replaced.
    private static SettingsCenterPanel createPanel(FakeSettingsStore store, McpEnablementDecision decision) {
        return onEventDispatchThread(() -> {
            AppearanceSettingsPanel appearance = new AppearanceSettingsPanel(
                    new FakeAppearanceSettingsModel(),
                    APPEARANCE_STRINGS);
            return new SettingsCenterPanel(
                    store,
                    appearance,
                    owner -> CompletableFuture.completedFuture(null),
                    NOOP_MAINTENANCE,
                    decision);
        });
    }

    /// Creates a settings-center panel using the production MCP warning decision.
    ///
    /// @param store recording settings store
    /// @return panel using the real headless or native-dialog branch
    private static SettingsCenterPanel createPanelWithProductionDecision(FakeSettingsStore store) {
        return onEventDispatchThread(() -> {
            AppearanceSettingsPanel appearance = new AppearanceSettingsPanel(
                    new FakeAppearanceSettingsModel(),
                    APPEARANCE_STRINGS);
            return new SettingsCenterPanel(
                    store,
                    appearance,
                    owner -> CompletableFuture.completedFuture(null),
                    NOOP_MAINTENANCE);
        });
    }

    /// Creates the smallest valid settings snapshot accepted by the settings-center controls.
    private static SettingsCenterSnapshot snapshot(boolean mcpEnabled, boolean showWarning) {
        return new SettingsCenterSnapshot(
                SupportedLocale.DEFAULT,
                false,
                false,
                false,
                EnumCommonDirectory.DEFAULT,
                "",
                "",
                true,
                1,
                DownloadSource.DEFAULT,
                DownloadSource.DEFAULT,
                "modrinth",
                ProxyType.SYSTEM,
                "",
                0,
                false,
                "",
                "",
                mcpEnabled,
                "",
                LauncherSettings.DEFAULT_MCP_PORT,
                false,
                false,
                showWarning,
                true);
    }

    /// Installs process-global settings required by production child panels and restores them after one test.
    @NotNullByDefault
    private static final class SettingsFixture implements AutoCloseable {
        /// Reflected launcher-settings field.
        private final Field launcherSettingsField;

        /// Reflected detached-preset field.
        private final Field gameSettingsPresetsField;

        /// Reflected user-settings field.
        private final Field userSettingsField;

        /// Values that were present before this fixture was installed.
        private final @Nullable Object previousLauncherSettings;
        private final @Nullable Object previousGameSettingsPresets;
        private final @Nullable Object previousUserSettings;

        /// Creates a fixture from already-resolved reflected fields and previous values.
        private SettingsFixture(
                Field launcherSettingsField,
                Field gameSettingsPresetsField,
                Field userSettingsField,
                @Nullable Object previousLauncherSettings,
                @Nullable Object previousGameSettingsPresets,
                @Nullable Object previousUserSettings) {
            this.launcherSettingsField = launcherSettingsField;
            this.gameSettingsPresetsField = gameSettingsPresetsField;
            this.userSettingsField = userSettingsField;
            this.previousLauncherSettings = previousLauncherSettings;
            this.previousGameSettingsPresets = previousGameSettingsPresets;
            this.previousUserSettings = previousUserSettings;
        }

        /// Installs a launcher settings, one preset, and one user-settings instance.
        private static SettingsFixture install() throws ReflectiveOperationException {
            Field launcherSettings = accessibleField("launcherSettings");
            Field gameSettingsPresets = accessibleField("gameSettingsPresets");
            Field userSettings = accessibleField("userSettingsInstance");
            @Nullable Object previousLauncherSettings = launcherSettings.get(null);
            @Nullable Object previousGameSettingsPresets = gameSettingsPresets.get(null);
            @Nullable Object previousUserSettings = userSettings.get(null);

            LauncherSettings launcher = new LauncherSettings();
            GameSettingsPresetID presetId = GameSettingsPresetID.generate();
            GameSettingsPresets presets = new GameSettingsPresets();
            presets.getPresets().add(new GameSettings.Preset(presetId));
            launcher.defaultGameSettingsPresetProperty().set(presetId);
            launcherSettings.set(null, launcher);
            gameSettingsPresets.set(null, presets);
            userSettings.set(null, new UserSettings());
            return new SettingsFixture(
                    launcherSettings,
                    gameSettingsPresets,
                    userSettings,
                    previousLauncherSettings,
                    previousGameSettingsPresets,
                    previousUserSettings);
        }

        /// Resolves and makes one SettingsManager field writable for this isolated fixture.
        private static Field accessibleField(String name) throws ReflectiveOperationException {
            Field field = SettingsManager.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        /// Restores every process-global value captured during installation.
        @Override
        public void close() throws IllegalAccessException {
            launcherSettingsField.set(null, previousLauncherSettings);
            gameSettingsPresetsField.set(null, previousGameSettingsPresets);
            userSettingsField.set(null, previousUserSettings);
        }
    }

    /// Minimal appearance model that never publishes changes during MCP tests.
    @NotNullByDefault
    private static final class FakeAppearanceSettingsModel implements AppearanceSettingsModel {
        /// Immutable appearance fixture.
        private final AppearanceSettingsSnapshot snapshot = new AppearanceSettingsSnapshot(
                ThemeBrightnessPreference.SYSTEM,
                6,
                0,
                20,
                1,
                true,
                new BackgroundAppearanceSettings(
                        BackgroundType.DEFAULT,
                        BuiltinBackground.FALLBACK.id(),
                        "",
                        "",
                        null,
                        1.0,
                        NetworkBackgroundImageCachePolicy.ENABLED,
                        false,
                        false,
                        false),
                true);

        /// Returns the immutable appearance fixture.
        @Override
        public AppearanceSettingsSnapshot snapshot() {
            return snapshot;
        }

        /// Registers a no-op listener because these tests never mutate appearance state.
        @Override
        public Subscription subscribe(ValueChangeListener<AppearanceSettingsSnapshot> listener) {
            return Subscription.create(() -> { });
        }

        /// Ignores unrelated appearance writes.
        @Override
        public void setThemeBrightnessPreference(ThemeBrightnessPreference preference) {
        }

        /// Ignores unrelated appearance writes.
        @Override
        public void setCornerRadius(int cornerRadius) {
        }

        /// Ignores unrelated appearance writes.
        @Override
        public void setAnimationsEnabled(boolean enabled) {
        }

        /// Ignores unrelated appearance writes.
        @Override
        public void setAnimationSpeedPercentage(int percentage) {
        }

        /// Ignores unrelated appearance writes.
        @Override
        public void setThemeColorAppearance(ThemeColorAppearanceSettings themeColor) {
        }

        /// Ignores unrelated appearance writes.
        @Override
        public void setBackgroundAppearance(BackgroundAppearanceSettings background) {
        }
    }

    /// In-memory store that records only MCP writes while satisfying the full settings-center contract.
    @NotNullByDefault
    private static final class FakeSettingsStore implements SettingsCenterStore {
        /// Initial immutable snapshot used during panel construction.
        private final SettingsCenterSnapshot initialSnapshot;

        /// Current MCP enablement value.
        private boolean mcpEnabled;

        /// Current MCP warning-preference value.
        private boolean showMcpEnablementWarning;

        /// Exact bearer token most recently accepted by this fake.
        private String mcpBearerToken;

        /// Number of MCP enablement writes accepted by this fake.
        private int mcpEnablementWrites;

        /// Creates a recording store from one immutable snapshot.
        private FakeSettingsStore(SettingsCenterSnapshot initialSnapshot) {
            this.initialSnapshot = Objects.requireNonNull(initialSnapshot, "initialSnapshot");
            mcpEnabled = initialSnapshot.mcpEnabled();
            showMcpEnablementWarning = initialSnapshot.showMcpEnablementWarning();
            mcpBearerToken = initialSnapshot.mcpBearerToken();
        }

        /// Returns the initial snapshot; focused actions are inspected through explicit recording accessors.
        @Override
        public SettingsCenterSnapshot snapshot() {
            return initialSnapshot;
        }

        /// Registers a no-op store listener because no background snapshot changes are needed here.
        @Override
        public Subscription subscribe(ValueChangeListener<SettingsCenterSnapshot> listener) {
            return Subscription.create(() -> { });
        }

        /// Records an MCP enablement write.
        @Override
        public void setMcpEnabled(boolean enabled) {
            mcpEnabled = enabled;
            mcpEnablementWrites++;
        }

        /// Records an exact bearer-token write.
        @Override
        public void setMcpBearerToken(String token) {
            mcpBearerToken = Objects.requireNonNull(token, "token");
        }

        /// Records the warning preference written by the checkbox.
        @Override
        public void setShowMcpEnablementWarning(boolean show) {
            showMcpEnablementWarning = show;
        }

        /// Returns the recorded MCP state.
        private boolean mcpEnabled() {
            return mcpEnabled;
        }

        /// Returns the recorded warning preference.
        private boolean showMcpEnablementWarning() {
            return showMcpEnablementWarning;
        }

        /// Returns the exact bearer token most recently written by the panel.
        private String mcpBearerToken() {
            return mcpBearerToken;
        }

        /// Returns the number of enablement writes.
        private int mcpEnablementWrites() {
            return mcpEnablementWrites;
        }

        /// Releases no resources because this fake owns no listeners.
        @Override
        public void close() {
        }

        /// Ignores a launcher-language write.
        @Override
        public void setLanguage(SupportedLocale language) {
        }

        /// Ignores a preview-update write.
        @Override
        public void setAcceptPreviewUpdates(boolean accepted) {
        }

        /// Ignores an update-prompt write.
        @Override
        public void setAutomaticUpdatePromptDisabled(boolean disabled) {
        }

        /// Ignores an April-Fools write.
        @Override
        public void setAprilFoolsDisabled(boolean disabled) {
        }

        /// Ignores a directory-mode write.
        @Override
        public void setCommonDirectoryType(EnumCommonDirectory directoryType) {
        }

        /// Ignores a directory write.
        @Override
        public void setCommonDirectory(String directory) {
        }

        /// Ignores an automatic-thread write.
        @Override
        public void setAutomaticDownloadThreads(boolean automatic) {
        }

        /// Ignores a download-thread write.
        @Override
        public void setDownloadThreads(int threads) {
        }

        /// Ignores a version-source write.
        @Override
        public void setVersionListSource(DownloadSource source) {
        }

        /// Ignores a file-source write.
        @Override
        public void setFileDownloadSource(DownloadSource source) {
        }

        /// Ignores an add-on-source write.
        @Override
        public void setDefaultAddonSource(String sourceId) {
        }

        /// Ignores a proxy-type write.
        @Override
        public void setProxyType(ProxyType proxyType) {
        }

        /// Ignores a proxy-host write.
        @Override
        public void setProxyHost(String host) {
        }

        /// Ignores a proxy-port write.
        @Override
        public void setProxyPort(int port) {
        }

        /// Ignores a proxy-authentication write.
        @Override
        public void setProxyAuthenticationEnabled(boolean enabled) {
        }

        /// Ignores a proxy-username write.
        @Override
        public void setProxyUsername(String username) {
        }

        /// Ignores a proxy-password write.
        @Override
        public void setProxyPassword(String password) {
        }

        /// Ignores an MCP-port write.
        @Override
        public void setMcpPort(int port) {
        }

        /// Ignores an MCP instance-confirmation write.
        @Override
        public void setMcpConfirmInstanceDeletion(boolean required) {
        }

        /// Ignores an MCP mod-confirmation write.
        @Override
        public void setMcpConfirmModDeletion(boolean required) {
        }
    }

    /// Finds one typed named Swing component recursively.
    private static <T extends Component> T findComponent(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (Objects.equals(name, child.getName()) && type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container container) {
                @Nullable T nested = findOptionalComponent(container, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        throw new AssertionError("Missing component: " + name);
    }

    /// Finds one typed named component or returns null while recursing.
    private static <T extends Component> @Nullable T findOptionalComponent(
            Container root,
            String name,
            Class<T> type) {
        for (Component child : root.getComponents()) {
            if (Objects.equals(name, child.getName()) && type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container container) {
                @Nullable T nested = findOptionalComponent(container, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// Runs a value-producing operation synchronously on the Swing EDT.
    private static <T extends Object> T onEventDispatchThread(Supplier<T> operation) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(operation.get()));
        return Objects.requireNonNull(result.get(), "EDT operation did not return a result");
    }

    /// Runs an operation synchronously on the Swing EDT.
    private static void onEventDispatchThread(Runnable operation) {
        EdtDispatcher.executeAndWait(operation);
    }
}
