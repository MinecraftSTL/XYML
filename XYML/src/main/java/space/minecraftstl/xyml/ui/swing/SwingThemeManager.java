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

import javax.swing.LookAndFeel;
import javax.swing.UIManager;
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

    /// Installs FlatLaf and the current design tokens before the application creates its first window.
    ///
    /// This method may be called from any thread and returns only after initialization on the EDT has completed.
    public void initialize() {
        EdtDispatcher.executeAndWait(() -> applyResolvedTheme(mode.resolve(systemThemeDetector), true));
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

    /// Rechecks the operating-system appearance and updates open windows when system mode is active.
    ///
    /// Platform integrations should invoke this method after receiving a native appearance-change event. This method may be called from
    /// any thread and returns only after any required EDT update has completed.
    public void refreshSystemTheme() {
        EdtDispatcher.executeAndWait(() -> {
            if (mode != ThemeMode.SYSTEM) {
                return;
            }

            ThemeVariant resolvedVariant = mode.resolve(systemThemeDetector);
            if (!initialized || resolvedVariant != effectiveVariant) {
                applyResolvedTheme(resolvedVariant, true);
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

        mode = newMode;
        designTokens = newDesignTokens;

        if (!initialized || tokensChanged || variantChanged) {
            applyResolvedTheme(resolvedVariant, !initialized || variantChanged);
        }
    }

    /// Installs the required FlatLaf palette, applies tokens, and refreshes all displayable Swing trees.
    ///
    /// @param resolvedVariant the concrete palette to apply
    /// @param installLookAndFeel whether the current FlatLaf instance must be replaced
    private void applyResolvedTheme(ThemeVariant resolvedVariant, boolean installLookAndFeel) {
        EdtDispatcher.requireEventDispatchThread();

        if (installLookAndFeel) {
            LookAndFeel lookAndFeel = switch (resolvedVariant) {
                case LIGHT -> new FlatLightLaf();
                case DARK -> new FlatDarkLaf();
            };
            if (!FlatLaf.setup(lookAndFeel)) {
                throw new IllegalStateException("Failed to install the FlatLaf " + resolvedVariant + " palette");
            }
        }

        designTokens.applyTo(UIManager.getDefaults());
        effectiveVariant = resolvedVariant;
        initialized = true;
        FlatLaf.updateUI();
    }
}
