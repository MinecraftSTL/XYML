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
package space.minecraftstl.xyml.ui.swing;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.theme.ResolvedTheme;
import space.minecraftstl.xyml.theme.ThemeBrightness;
import space.minecraftstl.xyml.theme.ThemeColor;

import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/// Initializes FlatLaf and applies live theme or design-token changes to all Swing windows.
@NotNullByDefault
public final class SwingThemeManager {
    /// Supplies the current operating-system appearance for system mode.
    private final SystemThemeDetector systemThemeDetector;

    /// Stores the persisted theme preference.
    private volatile ThemeMode mode;

    /// Stores the design tokens most recently requested by the application.
    private volatile SwingDesignTokens designTokens;

    /// Stores the palette currently installed by this manager, or `null` before initialization.
    private volatile @Nullable ThemeVariant effectiveVariant;

    /// Stores concrete theme-domain values requested through the resolved-theme API.
    private volatile @Nullable ResolvedTheme resolvedTheme;

    /// Stores the accent currently installed into FlatLaf, or `null` for FlatLaf's platform default.
    private volatile @Nullable ThemeColor effectiveAccentColor;

    /// Optional application callback that re-resolves a concrete theme after the system appearance changes.
    private volatile @Nullable Runnable systemThemeRefreshHandler;

    /// Tracks whether this manager has installed FlatLaf at least once.
    private volatile boolean initialized;

    /// Creates a theme manager with explicit initial state and system detection.
    ///
    /// @param initialMode the persisted light, dark, or system preference
    /// @param initialDesignTokens the initial visual measurements
    /// @param systemThemeDetector the non-blocking operating-system appearance detector
    public SwingThemeManager(
            ThemeMode initialMode,
            SwingDesignTokens initialDesignTokens,
            SystemThemeDetector systemThemeDetector) {
        mode = Objects.requireNonNull(initialMode);
        designTokens = Objects.requireNonNull(initialDesignTokens);
        this.systemThemeDetector = Objects.requireNonNull(systemThemeDetector);
    }

    /// Creates a manager with already resolved theme-domain values.
    ///
    /// @param initialTheme concrete selected-theme values
    /// @param initialDesignTokens initial visual measurements
    /// @param systemThemeDetector detector retained for later legacy system-mode updates
    public SwingThemeManager(
            ResolvedTheme initialTheme,
            SwingDesignTokens initialDesignTokens,
            SystemThemeDetector systemThemeDetector) {
        this(themeMode(initialTheme.brightness()), initialDesignTokens, systemThemeDetector);
        resolvedTheme = Objects.requireNonNull(initialTheme, "initialTheme");
    }

    /// Installs FlatLaf and the current design tokens before the application creates its first window.
    ///
    /// This method may be called from any thread and returns only after initialization on the EDT has completed.
    public void initialize() {
        EdtDispatcher.executeAndWait(() -> {
            @Nullable ResolvedTheme currentTheme = resolvedTheme;
            ThemeVariant variant = currentTheme != null
                    ? themeVariant(currentTheme.brightness())
                    : mode.resolve(systemThemeDetector);
            applyResolvedTheme(
                    variant,
                    currentTheme != null ? currentTheme.primaryColorSeed() : null,
                    true);
        });
    }

    /// Applies a new persisted mode and token set to existing and future Swing components.
    ///
    /// This method may be called from any thread and returns only after any required EDT update has completed.
    ///
    /// @param newMode the new light, dark, or system preference
    /// @param newDesignTokens the new visual measurements
    public void update(ThemeMode newMode, SwingDesignTokens newDesignTokens) {
        Objects.requireNonNull(newMode);
        Objects.requireNonNull(newDesignTokens);

        EdtDispatcher.executeAndWait(() -> updateOnEventDispatchThread(newMode, newDesignTokens));
    }

    /// Applies concrete theme-domain values to existing and future Swing components.
    ///
    /// Brightness selects FlatLightLaf or FlatDarkLaf, while the resolved primary color is installed through
    /// FlatLaf's documented global `@accentColor` extra default before the look and feel is loaded.
    ///
    /// @param newTheme concrete selected-theme values after user overrides
    /// @param newDesignTokens new visual measurements
    public void update(ResolvedTheme newTheme, SwingDesignTokens newDesignTokens) {
        update(newTheme, newDesignTokens, false);
    }

    /// Applies concrete theme values while retaining whether system-appearance polling is required.
    ///
    /// Theme and system brightness preferences both need re-resolution when the operating-system appearance
    /// changes because manifest conditions can depend on that context. Explicit light and dark preferences do not.
    ///
    /// @param newTheme concrete selected-theme values after user overrides
    /// @param newDesignTokens new visual measurements
    /// @param followsSystemAppearance whether foreground system-theme changes require a fresh resolution
    public void update(
            ResolvedTheme newTheme,
            SwingDesignTokens newDesignTokens,
            boolean followsSystemAppearance) {
        Objects.requireNonNull(newTheme, "newTheme");
        Objects.requireNonNull(newDesignTokens, "newDesignTokens");
        EdtDispatcher.executeAndWait(() -> updateResolvedThemeOnEventDispatchThread(
                newTheme,
                newDesignTokens,
                followsSystemAppearance));
    }

    /// Installs the callback used to rebuild a resolved theme after a system-appearance change.
    ///
    /// @param handler non-blocking refresh request, or `null` to remove the callback
    public void setSystemThemeRefreshHandler(@Nullable Runnable handler) {
        systemThemeRefreshHandler = handler;
    }

    /// Rechecks the operating-system appearance and updates open windows when system mode is active.
    ///
    /// Platform integrations should invoke this method after receiving a native appearance-change event. This method may be called from
    /// any thread and returns only after any required EDT update has completed.
    public void refreshSystemTheme() {
        ThemeMode currentMode = mode;
        if (currentMode != ThemeMode.SYSTEM) {
            return;
        }
        if (resolvedTheme != null) {
            @Nullable Runnable handler = systemThemeRefreshHandler;
            if (handler != null) {
                handler.run();
            }
            return;
        }
        ThemeVariant resolvedVariant = currentMode.resolve(systemThemeDetector);
        EdtDispatcher.executeAndWait(() -> {
            if (mode != ThemeMode.SYSTEM) {
                return;
            }

            if (!initialized || resolvedVariant != effectiveVariant) {
                applyResolvedTheme(resolvedVariant, null, true);
            }
        });
    }

    /// Returns the persisted theme preference.
    ///
    /// @return the selected theme mode
    public ThemeMode mode() {
        return mode;
    }

    /// Returns the design tokens currently associated with this manager.
    ///
    /// @return the current design tokens
    public SwingDesignTokens designTokens() {
        return designTokens;
    }

    /// Returns the concrete palette most recently resolved by this manager.
    ///
    /// @return the resolved light or dark palette, or `null` before initialization
    public @Nullable ThemeVariant effectiveVariant() {
        return effectiveVariant;
    }

    /// Returns the concrete theme-domain values most recently requested through the resolved-theme API.
    ///
    /// @return resolved theme, or `null` while using the compatibility [ThemeMode] API
    public @Nullable ResolvedTheme resolvedTheme() {
        return resolvedTheme;
    }

    /// Returns the explicit accent currently installed into FlatLaf.
    ///
    /// @return resolved theme color, or `null` while using FlatLaf's platform default
    public @Nullable ThemeColor effectiveAccentColor() {
        return effectiveAccentColor;
    }

    /// Returns whether FlatLaf has been installed by this manager.
    ///
    /// @return `true` after successful initialization
    public boolean isInitialized() {
        return initialized;
    }

    /// Updates state and refreshes Swing only when the rendered appearance changes.
    ///
    /// @param newMode the new persisted theme mode
    /// @param newDesignTokens the new design tokens
    private void updateOnEventDispatchThread(ThemeMode newMode, SwingDesignTokens newDesignTokens) {
        EdtDispatcher.requireEventDispatchThread();

        ThemeVariant resolvedVariant = newMode.resolve(systemThemeDetector);
        boolean tokensChanged = !newDesignTokens.equals(designTokens);
        boolean variantChanged = resolvedVariant != effectiveVariant;
        boolean accentChanged = effectiveAccentColor != null;

        mode = newMode;
        designTokens = newDesignTokens;
        resolvedTheme = null;

        if (!initialized || tokensChanged || variantChanged || accentChanged) {
            applyResolvedTheme(resolvedVariant, null, !initialized || variantChanged || accentChanged);
        }
    }

    /// Updates state from one fully resolved theme and refreshes only when rendered values changed.
    ///
    /// @param newTheme concrete selected-theme values
    /// @param newDesignTokens new design tokens
    private void updateResolvedThemeOnEventDispatchThread(
            ResolvedTheme newTheme,
            SwingDesignTokens newDesignTokens,
            boolean followsSystemAppearance) {
        EdtDispatcher.requireEventDispatchThread();

        ThemeVariant resolvedVariant = themeVariant(newTheme.brightness());
        boolean tokensChanged = !newDesignTokens.equals(designTokens);
        boolean variantChanged = resolvedVariant != effectiveVariant;
        boolean accentChanged = !Objects.equals(newTheme.primaryColorSeed(), effectiveAccentColor);

        mode = followsSystemAppearance ? ThemeMode.SYSTEM : themeMode(newTheme.brightness());
        designTokens = newDesignTokens;
        resolvedTheme = newTheme;

        if (!initialized || tokensChanged || variantChanged || accentChanged) {
            applyResolvedTheme(
                    resolvedVariant,
                    newTheme.primaryColorSeed(),
                    !initialized || variantChanged || accentChanged);
        }
    }

    /// Installs the required FlatLaf palette, applies tokens, and refreshes all displayable Swing trees.
    ///
    /// @param resolvedVariant the concrete palette to apply
    /// @param accentColor explicit accent, or `null` to use FlatLaf's platform default
    /// @param installLookAndFeel whether the current FlatLaf instance must be replaced
    private void applyResolvedTheme(
            ThemeVariant resolvedVariant,
            @Nullable ThemeColor accentColor,
            boolean installLookAndFeel) {
        EdtDispatcher.requireEventDispatchThread();

        if (installLookAndFeel) {
            @Nullable Map<String, String> previousExtraDefaults = FlatLaf.getGlobalExtraDefaults();
            applyAccentExtraDefault(accentColor, previousExtraDefaults);
            LookAndFeel lookAndFeel = switch (resolvedVariant) {
                case LIGHT -> new FlatLightLaf();
                case DARK -> new FlatDarkLaf();
            };
            if (!FlatLaf.setup(lookAndFeel)) {
                FlatLaf.setGlobalExtraDefaults(
                        previousExtraDefaults != null ? previousExtraDefaults : Map.of());
                throw new IllegalStateException("Failed to install the FlatLaf " + resolvedVariant + " palette");
            }
        }

        designTokens.applyTo(UIManager.getDefaults());
        effectiveVariant = resolvedVariant;
        effectiveAccentColor = accentColor;
        initialized = true;
        FlatLaf.updateUI();
    }

    /// Replaces only FlatLaf's global accent variable while preserving unrelated caller defaults.
    ///
    /// @param accentColor explicit accent, or `null` to restore platform-default accent resolution
    /// @param previousExtraDefaults defaults installed before this update, or `null`
    private static void applyAccentExtraDefault(
            @Nullable ThemeColor accentColor,
            @Nullable Map<String, String> previousExtraDefaults) {
        Map<String, String> extraDefaults = previousExtraDefaults != null
                ? new HashMap<>(previousExtraDefaults)
                : new HashMap<>();
        if (accentColor == null) {
            extraDefaults.remove("@accentColor");
        } else {
            extraDefaults.put("@accentColor", accentColor.color());
        }
        FlatLaf.setGlobalExtraDefaults(Map.copyOf(extraDefaults));
    }

    /// Maps toolkit-neutral brightness to a concrete Swing palette.
    ///
    /// @param brightness resolved brightness
    /// @return concrete Swing palette
    private static ThemeVariant themeVariant(ThemeBrightness brightness) {
        return brightness == ThemeBrightness.DARK ? ThemeVariant.DARK : ThemeVariant.LIGHT;
    }

    /// Maps toolkit-neutral brightness to the compatible explicit [ThemeMode] representation.
    ///
    /// @param brightness resolved brightness
    /// @return explicit light or dark mode
    private static ThemeMode themeMode(ThemeBrightness brightness) {
        return Objects.requireNonNull(brightness, "brightness") == ThemeBrightness.DARK
                ? ThemeMode.DARK
                : ThemeMode.LIGHT;
    }
}
