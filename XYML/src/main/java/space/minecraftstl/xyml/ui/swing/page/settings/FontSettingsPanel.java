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

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.UIManager;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.Component;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Renders launcher, game-log, and text-antialiasing font preferences inside the appearance page.
///
/// Installed font families are enumerated only after either family selector first opens, and the enumeration always
/// runs through the injected background executor. The launcher family is applied immediately, while antialiasing is
/// tracked by a restart row because AWT initializes its rendering hints at process startup.
@NotNullByDefault
final class FontSettingsPanel extends JPanel implements AutoCloseable {
    /// Combo-box sentinel representing the active toolkit default family.
    private static final String DEFAULT_FAMILY = "";

    /// Persisted font state owned by this panel.
    private final FontSettingsStore store;

    /// Live launcher-font application boundary.
    private final FontSettingsRuntime runtime;

    /// Potentially blocking local font-family source.
    private final FontFamilyCatalog familyCatalog;

    /// Background executor used only for local font enumeration.
    private final Executor fontLoadExecutor;

    /// Launcher UI family selector populated lazily.
    private final JComboBox<String> launcherFontBox = new JComboBox<>();

    /// Resets the launcher UI family to the look-and-feel default.
    private final JButton resetLauncherFontButton = new JButton(i18n("button.reset"));

    /// Immediate launcher-family preview using the product name.
    private final JLabel launcherFontPreview = new JLabel(Metadata.FULL_NAME);

    /// Game-log family selector populated from the same lazy catalogue.
    private final JComboBox<String> logFontBox = new JComboBox<>();

    /// Resets the game-log family to the platform monospaced family.
    private final JButton resetLogFontButton = new JButton(i18n("button.reset"));

    /// Positive game-log font-size editor.
    private final JSpinner logFontSizeSpinner = new JSpinner(
            new SpinnerNumberModel(12.0, Double.MIN_VALUE, null, 1.0));

    /// Immediate game-log family and size preview.
    private final JLabel logFontPreview = new JLabel(i18n("settings.launcher.log.font_preview"));

    /// Restart-sensitive text antialiasing selector.
    private final JComboBox<FontAntialiasingMode> antialiasingBox =
            new JComboBox<>(FontAntialiasingMode.values());

    /// Restart status and action dedicated to text antialiasing.
    private final SettingsRestartPanel antialiasingRestartPanel;

    /// Store subscription released with this panel.
    private final Subscription storeSubscription;

    /// Immutable local family catalogue after its first successful background load.
    private @Unmodifiable List<String> loadedFamilies = List.of();

    /// Latest snapshot represented by the controls, or `null` before initial application.
    private @Nullable FontSettingsSnapshot displayedSnapshot;

    /// Family most recently delivered to the live runtime.
    private @Nullable String appliedLauncherFontFamily;

    /// Whether the live runtime has received its initial family.
    private boolean launcherFontRuntimeInitialized;

    /// Prevents snapshot/model updates from being persisted as user actions.
    private boolean applyingSnapshot;

    /// Prevents duplicate local font enumeration requests.
    private boolean fontLoadStarted;

    /// Whether another settings restart action temporarily blocks these controls.
    private boolean restartInProgress;

    /// Whether resources and late asynchronous completions have been detached.
    private boolean closed;

    /// Creates a font settings section on the Swing event dispatch thread.
    ///
    /// @param store persisted font state owner
    /// @param runtime live launcher-font application boundary
    /// @param familyCatalog local installed-font source
    /// @param fontLoadExecutor executor for potentially blocking font enumeration
    /// @param restartStrings localized restart text
    /// @param restartCommand launcher restart lifecycle command
    /// @param restartActivity listener receiving restart-in-progress transitions
    FontSettingsPanel(
            FontSettingsStore store,
            FontSettingsRuntime runtime,
            FontFamilyCatalog familyCatalog,
            Executor fontLoadExecutor,
            SettingsRestartStrings restartStrings,
            SettingsRestartCommand restartCommand,
            Consumer<Boolean> restartActivity) {
        super(new MigLayout("insets 0 24 24 24, fillx, wrap 1", "[grow,fill]", "[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.store = Objects.requireNonNull(store, "store");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.familyCatalog = Objects.requireNonNull(familyCatalog, "familyCatalog");
        this.fontLoadExecutor = Objects.requireNonNull(fontLoadExecutor, "fontLoadExecutor");
        Consumer<Boolean> validatedRestartActivity = Objects.requireNonNull(restartActivity, "restartActivity");
        antialiasingRestartPanel = new SettingsRestartPanel(
                Objects.requireNonNull(restartStrings, "restartStrings"),
                Objects.requireNonNull(restartCommand, "restartCommand"),
                active -> {
                    setRestartInProgress(active);
                    validatedRestartActivity.accept(active);
                });

        configureComponents();
        storeSubscription = store.subscribe(this::storeChanged);
        applySnapshot(store.snapshot());
    }

    /// Returns the snapshot currently represented by the font controls.
    ///
    /// @return displayed font settings
    FontSettingsSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(displayedSnapshot, "initial font settings snapshot was not applied");
    }

    /// Returns the launcher family selector for focused panel tests.
    ///
    /// @return launcher font selector
    JComboBox<String> launcherFontControl() {
        EdtDispatcher.requireEventDispatchThread();
        return launcherFontBox;
    }

    /// Returns the log family selector for focused panel tests.
    ///
    /// @return game-log font selector
    JComboBox<String> logFontControl() {
        EdtDispatcher.requireEventDispatchThread();
        return logFontBox;
    }

    /// Returns the log size editor for focused panel tests.
    ///
    /// @return game-log font-size editor
    JSpinner logFontSizeControl() {
        EdtDispatcher.requireEventDispatchThread();
        return logFontSizeSpinner;
    }

    /// Returns the antialiasing selector for focused panel tests.
    ///
    /// @return text antialiasing selector
    JComboBox<FontAntialiasingMode> antialiasingControl() {
        EdtDispatcher.requireEventDispatchThread();
        return antialiasingBox;
    }

    /// Updates availability while any settings restart command is active.
    ///
    /// @param inProgress whether a restart is being prepared
    void setRestartInProgress(boolean inProgress) {
        EdtDispatcher.requireEventDispatchThread();
        restartInProgress = inProgress;
        @Nullable FontSettingsSnapshot snapshot = displayedSnapshot;
        setControlsEnabled(snapshot);
    }

    /// Releases the store, listener, restart control, and late font-loading completions.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                closed = true;
                storeSubscription.unsubscribe();
                store.close();
                antialiasingRestartPanel.close();
                setControlsEnabled(displayedSnapshot);
            }
        });
    }

    /// Builds the transparent font section and configures every interaction.
    private void configureComponents() {
        setOpaque(false);
        configureFamilyControl(launcherFontBox, "fontSettingsLauncherFamily");
        configureFamilyControl(logFontBox, "fontSettingsLogFamily");
        configureAntialiasingControl();

        resetLauncherFontButton.setName("fontSettingsLauncherReset");
        resetLauncherFontButton.addActionListener(event -> launcherFontBox.setSelectedItem(DEFAULT_FAMILY));
        resetLogFontButton.setName("fontSettingsLogReset");
        resetLogFontButton.addActionListener(event -> logFontBox.setSelectedItem(DEFAULT_FAMILY));

        logFontSizeSpinner.setName("fontSettingsLogSize");
        logFontSizeSpinner.setEditor(new JSpinner.NumberEditor(logFontSizeSpinner, "0.##"));
        logFontSizeSpinner.addChangeListener(event -> logFontSizeChanged());
        launcherFontPreview.setName("fontSettingsLauncherPreview");
        logFontPreview.setName("fontSettingsLogPreview");
        SwingThemeManager.preserveExplicitFontFamily(logFontPreview);

        JLabel heading = new JLabel(i18n("settings.launcher.fonts"));
        heading.setName("fontSettingsTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18.0F));
        add(heading, "gapbottom 8");
        add(createFamilyRow(i18n("settings.launcher.font"), launcherFontBox, resetLauncherFontButton),
                "gapbottom 2");
        add(launcherFontPreview, "gapleft 150, gapbottom 8");
        add(createFamilyRow(i18n("settings.launcher.log.font"), logFontBox, resetLogFontButton),
                "gapbottom 6");
        add(createFieldRow(i18n("settings.launcher.log.font_size"), logFontSizeSpinner), "gapbottom 2");
        add(logFontPreview, "gapleft 150, gapbottom 12");
        add(new JSeparator(), "growx, gapbottom 12");
        add(createFieldRow(i18n("settings.launcher.font.anti_aliasing"), antialiasingBox), "gapbottom 6");
        antialiasingRestartPanel.setName("fontSettingsAntialiasingRestart");
        add(antialiasingRestartPanel, "growx");
    }

    /// Configures one lazily populated font-family selector.
    ///
    /// @param box selector to configure
    /// @param name stable component name
    private void configureFamilyControl(JComboBox<String> box, String name) {
        box.setName(Objects.requireNonNull(name, "name"));
        box.setModel(new DefaultComboBoxModel<>(new String[]{DEFAULT_FAMILY}));
        box.setRenderer(new FontFamilyRenderer());
        box.addPopupMenuListener(createFontPopupListener());
        box.addActionListener(event -> familySelectionChanged(box));
    }

    /// Configures localized antialiasing display and persistence.
    private void configureAntialiasingControl() {
        antialiasingBox.setName("fontSettingsAntialiasing");
        antialiasingBox.setRenderer(new AntialiasingModeRenderer());
        antialiasingBox.addActionListener(event -> {
            @Nullable FontAntialiasingMode selected =
                    (FontAntialiasingMode) antialiasingBox.getSelectedItem();
            if (!applyingSnapshot && !closed && selected != null) {
                store.setAntialiasingMode(selected);
                antialiasingRestartPanel.updateFontAntialiasing(selected);
            }
        });
    }

    /// Creates one popup listener that starts shared font loading on first expansion.
    ///
    /// @return lazy-load popup listener
    private PopupMenuListener createFontPopupListener() {
        return new PopupMenuListener() {
            /// Starts font loading immediately before choices become visible.
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
                requestFontFamilies();
            }

            /// Performs no work when the popup closes.
            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
            }

            /// Performs no work when popup display is canceled.
            @Override
            public void popupMenuCanceled(PopupMenuEvent event) {
            }
        };
    }

    /// Persists one family selection and applies launcher chrome changes immediately when appropriate.
    ///
    /// @param source selector whose value changed
    private void familySelectionChanged(JComboBox<String> source) {
        if (applyingSnapshot || closed) {
            return;
        }
        @Nullable String family = selectedFamily(source);
        if (source == launcherFontBox) {
            store.setLauncherFontFamily(family);
            applyLauncherFontFamily(family);
        } else if (source == logFontBox) {
            store.setLogFontFamily(family);
        }
    }

    /// Persists a valid numeric log size supplied by the spinner model.
    private void logFontSizeChanged() {
        if (applyingSnapshot || closed) {
            return;
        }
        Object value = logFontSizeSpinner.getValue();
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Log font size spinner returned a non-numeric value");
        }
        double size = number.doubleValue();
        if (!Double.isFinite(size) || size <= 0) {
            @Nullable FontSettingsSnapshot snapshot = displayedSnapshot;
            if (snapshot != null) {
                applyingSnapshot = true;
                try {
                    logFontSizeSpinner.setValue(snapshot.logFontSize());
                } finally {
                    applyingSnapshot = false;
                }
            }
            return;
        }
        store.setLogFontSize(size);
    }

    /// Starts one background local-font enumeration after either selector first opens.
    private void requestFontFamilies() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || fontLoadStarted || !loadedFamilies.isEmpty()) {
            return;
        }
        fontLoadStarted = true;
        try {
            CompletableFuture.supplyAsync(
                            () -> List.copyOf(familyCatalog.loadFamilies()),
                            fontLoadExecutor)
                    .whenComplete((@Nullable List<String> families, @Nullable Throwable failure) ->
                            EdtDispatcher.execute(() -> completeFontFamilyLoad(families, failure)));
        } catch (RuntimeException failure) {
            fontLoadStarted = false;
            LOG.warning("Failed to schedule local font-family enumeration", failure);
        }
    }

    /// Installs a successfully loaded family catalogue or permits retry after failure.
    ///
    /// @param families loaded family names, or `null` after failure
    /// @param failure asynchronous failure, or `null` after success
    private void completeFontFamilyLoad(
            @Nullable List<String> families,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        if (failure != null || families == null) {
            fontLoadStarted = false;
            if (failure != null) {
                LOG.warning("Failed to enumerate local font families", failure);
            }
            return;
        }

        TreeSet<String> normalizedFamilies = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String family : families) {
            String normalized = Objects.requireNonNull(family, "font family").trim();
            if (!normalized.isEmpty()) {
                normalizedFamilies.add(normalized);
            }
        }
        loadedFamilies = List.copyOf(normalizedFamilies);
        rebuildFamilyModels();
    }

    /// Rebuilds both selector models while preserving their current values and suppressing persistence callbacks.
    private void rebuildFamilyModels() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable String launcherSelection = selectedFamily(launcherFontBox);
        @Nullable String logSelection = selectedFamily(logFontBox);
        boolean previousApplyingSnapshot = applyingSnapshot;
        applyingSnapshot = true;
        try {
            replaceFamilyModel(launcherFontBox, launcherSelection);
            replaceFamilyModel(logFontBox, logSelection);
        } finally {
            applyingSnapshot = previousApplyingSnapshot;
        }
    }

    /// Replaces one selector model with default, current, and loaded local families.
    ///
    /// @param box selector whose model is replaced
    /// @param selectedFamily currently selected family, or `null` for default
    private void replaceFamilyModel(JComboBox<String> box, @Nullable String selectedFamily) {
        ArrayList<String> choices = new ArrayList<>(loadedFamilies.size() + 2);
        choices.add(DEFAULT_FAMILY);
        if (selectedFamily != null && !containsIgnoreCase(loadedFamilies, selectedFamily)) {
            choices.add(selectedFamily);
        }
        choices.addAll(loadedFamilies);
        box.setModel(new DefaultComboBoxModel<>(choices.toArray(String[]::new)));
        box.setSelectedItem(selectedFamily == null ? DEFAULT_FAMILY : selectedFamily);
    }

    /// Applies one latest store snapshot on the event dispatch thread.
    ///
    /// @param snapshot latest font settings
    private void applySnapshot(FontSettingsSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        FontSettingsSnapshot validatedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        applyingSnapshot = true;
        try {
            displayedSnapshot = validatedSnapshot;
            ensureFamilyChoice(launcherFontBox, validatedSnapshot.launcherFontFamily());
            ensureFamilyChoice(logFontBox, validatedSnapshot.logFontFamily());
            launcherFontBox.setSelectedItem(familyChoice(validatedSnapshot.launcherFontFamily()));
            logFontBox.setSelectedItem(familyChoice(validatedSnapshot.logFontFamily()));
            logFontSizeSpinner.setValue(validatedSnapshot.logFontSize());
            antialiasingBox.setSelectedItem(validatedSnapshot.antialiasingMode());
            antialiasingRestartPanel.updateFontAntialiasing(validatedSnapshot.antialiasingMode());
            setControlsEnabled(validatedSnapshot);
        } finally {
            applyingSnapshot = false;
        }
        applyLauncherFontFamily(validatedSnapshot.launcherFontFamily());
        updateFontPreviews(validatedSnapshot);
    }

    /// Applies current family and size values to both inline previews.
    ///
    /// @param snapshot current normalized font settings
    private void updateFontPreviews(FontSettingsSnapshot snapshot) {
        @Nullable Font labelFont = UIManager.getFont("Label.font");
        Font launcherBaseline = labelFont == null ? new Font(Font.DIALOG, Font.PLAIN, 12) : labelFont;
        @Nullable String launcherFamily = snapshot.launcherFontFamily();
        launcherFontPreview.setFont(new Font(
                launcherFamily == null ? launcherBaseline.getFamily() : launcherFamily,
                launcherBaseline.getStyle(),
                1).deriveFont(launcherBaseline.getSize2D()));

        @Nullable String logFamily = snapshot.logFontFamily();
        float logSize = (float) snapshot.logFontSize();
        if (!Float.isFinite(logSize) || logSize <= 0) {
            logSize = 12.0F;
        }
        logFontPreview.setFont(new Font(
                logFamily == null ? Font.MONOSPACED : logFamily,
                Font.PLAIN,
                1).deriveFont(logSize));
    }

    /// Ensures a persisted family unavailable from the local catalogue remains selectable and resettable.
    ///
    /// @param box selector receiving the persisted value
    /// @param family persisted family, or `null`
    private static void ensureFamilyChoice(JComboBox<String> box, @Nullable String family) {
        if (family == null) {
            return;
        }
        for (int index = 0; index < box.getItemCount(); index++) {
            if (family.equalsIgnoreCase(box.getItemAt(index))) {
                return;
            }
        }
        box.insertItemAt(family, Math.min(1, box.getItemCount()));
    }

    /// Applies one launcher family only when it differs from the last live value.
    ///
    /// @param family selected family, or `null` for default
    private void applyLauncherFontFamily(@Nullable String family) {
        if (!launcherFontRuntimeInitialized || !Objects.equals(appliedLauncherFontFamily, family)) {
            runtime.applyLauncherFontFamily(family);
            appliedLauncherFontFamily = family;
            launcherFontRuntimeInitialized = true;
        }
    }

    /// Enables controls according to both settings owners and restart lifecycle state.
    ///
    /// @param snapshot current snapshot, or `null` before initial setup
    private void setControlsEnabled(@Nullable FontSettingsSnapshot snapshot) {
        boolean launcherWritable = snapshot != null
                && snapshot.launcherSettingsWritable()
                && !closed
                && !restartInProgress;
        boolean userWritable = snapshot != null
                && snapshot.userSettingsWritable()
                && !closed
                && !restartInProgress;
        launcherFontBox.setEnabled(launcherWritable);
        resetLauncherFontButton.setEnabled(launcherWritable);
        logFontBox.setEnabled(launcherWritable);
        resetLogFontButton.setEnabled(launcherWritable);
        logFontSizeSpinner.setEnabled(launcherWritable);
        antialiasingBox.setEnabled(userWritable);
        antialiasingRestartPanel.setAvailable(userWritable);
    }

    /// Coalesces store callbacks to the latest snapshot on the event dispatch thread.
    ///
    /// @param change font store transition
    private void storeChanged(ValueChange<FontSettingsSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                applySnapshot(store.snapshot());
            }
        });
    }

    /// Returns the nullable family represented by one selector.
    ///
    /// @param box family selector
    /// @return selected family, or `null` for default
    private static @Nullable String selectedFamily(JComboBox<String> box) {
        @Nullable Object selected = box.getSelectedItem();
        if (!(selected instanceof String family)) {
            return null;
        }
        String normalized = family.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /// Maps one nullable family to its combo-box representation.
    ///
    /// @param family family, or `null`
    /// @return family or the default sentinel
    private static String familyChoice(@Nullable String family) {
        return family == null ? DEFAULT_FAMILY : family;
    }

    /// Tests one case-insensitive family membership without allocating a normalized copy.
    ///
    /// @param families candidate family list
    /// @param family sought family
    /// @return whether the family is present
    private static boolean containsIgnoreCase(List<String> families, String family) {
        for (String candidate : families) {
            if (candidate.equalsIgnoreCase(family)) {
                return true;
            }
        }
        return false;
    }

    /// Creates one standard two-column field row.
    ///
    /// @param labelText localized label
    /// @param control field control
    /// @return transparent field row
    private static JPanel createFieldRow(String labelText, Component control) {
        JPanel row = new JPanel(new MigLayout("insets 2 0, fillx", "[150!,fill][grow,fill]", "[]"));
        row.setOpaque(false);
        row.add(new JLabel(Objects.requireNonNull(labelText, "labelText")), "aligny center");
        row.add(Objects.requireNonNull(control, "control"), "growx");
        return row;
    }

    /// Creates one family row with a compact reset action.
    ///
    /// @param labelText localized label
    /// @param box family selector
    /// @param resetButton matching reset action
    /// @return transparent family row
    private static JPanel createFamilyRow(
            String labelText,
            JComboBox<String> box,
            JButton resetButton) {
        JPanel row = new JPanel(new MigLayout(
                "insets 2 0, fillx",
                "[150!,fill][grow,fill]8[]",
                "[]"));
        row.setOpaque(false);
        row.add(new JLabel(Objects.requireNonNull(labelText, "labelText")), "aligny center");
        row.add(Objects.requireNonNull(box, "box"), "growx");
        row.add(Objects.requireNonNull(resetButton, "resetButton"));
        return row;
    }

    /// Renders each installed family using itself while retaining selection colors.
    @NotNullByDefault
    private static final class FontFamilyRenderer extends DefaultListCellRenderer {
        /// Configures one default or installed-family row.
        ///
        /// @param list owning list
        /// @param value family value
        /// @param index row index
        /// @param selected whether the row is selected
        /// @param focused whether the row owns focus
        /// @return configured renderer label
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                @Nullable Object value,
                int index,
                boolean selected,
                boolean focused) {
            String family = value instanceof String text ? text : DEFAULT_FAMILY;
            Component component = super.getListCellRendererComponent(
                    list,
                    family.isEmpty() ? i18n("message.default") : family,
                    index,
                    selected,
                    focused);
            if (!family.isEmpty()) {
                Font baseline = component.getFont();
                component.setFont(new Font(family, baseline.getStyle(), baseline.getSize()));
            }
            return component;
        }
    }

    /// Renders antialiasing modes using existing localized settings keys.
    @NotNullByDefault
    private static final class AntialiasingModeRenderer extends DefaultListCellRenderer {
        /// Configures one localized mode row.
        ///
        /// @param list owning list
        /// @param value mode value
        /// @param index row index
        /// @param selected whether the row is selected
        /// @param focused whether the row owns focus
        /// @return configured renderer label
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                @Nullable Object value,
                int index,
                boolean selected,
                boolean focused) {
            String text = value instanceof FontAntialiasingMode mode
                    ? switch (mode) {
                case AUTO -> i18n("settings.launcher.font.anti_aliasing.auto");
                case LCD -> i18n("settings.launcher.font.anti_aliasing.lcd");
                case GRAY -> i18n("settings.launcher.font.anti_aliasing.gray");
            }
                    : "";
            return super.getListCellRendererComponent(list, text, index, selected, focused);
        }
    }
}
