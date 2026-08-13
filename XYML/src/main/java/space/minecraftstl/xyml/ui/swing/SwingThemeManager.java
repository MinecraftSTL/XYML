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
import com.formdev.flatlaf.icons.FlatCheckBoxIcon;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.theme.ResolvedTheme;
import space.minecraftstl.xyml.theme.ThemeBrightness;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.theme.ThemeColor;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.UIResource;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Window;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/// Initializes FlatLaf and applies live theme or design-token changes to all Swing windows.
@NotNullByDefault
public final class SwingThemeManager {
    /// Client property marking components whose domain-specific font family must not follow launcher chrome.
    private static final String FIXED_FONT_FAMILY_PROPERTY = "xyml.fixedFontFamily";

    /// Serializes complete native-window appearance replacements.
    private final Object windowAppearanceLock = new Object();

    /// Publishes renderer-ready background and native-transparency requests.
    private final ValueChangeSupport<SwingWindowAppearanceRequest> windowAppearanceChanges =
            new ValueChangeSupport<>(this);

    /// Supplies the current operating-system appearance for system-dependent preferences.
    private final SystemThemeDetector systemThemeDetector;

    /// Stores the current four-state brightness preference.
    private volatile ThemeBrightnessPreference brightnessPreference;

    /// Stores the design tokens most recently requested by the application.
    private volatile SwingDesignTokens designTokens;

    /// Stores the palette currently installed by this manager, or `null` before initialization.
    private volatile @Nullable ThemeVariant effectiveVariant;

    /// Stores concrete theme-domain values requested through the resolved-theme API.
    private volatile @Nullable ResolvedTheme resolvedTheme;

    /// Stores the accent currently installed into FlatLaf, or `null` for FlatLaf's platform default.
    private volatile @Nullable ThemeColor effectiveAccentColor;

    /// Requested launcher UI family, or `null` for the active look-and-feel default.
    private volatile @Nullable String defaultFontFamily;

    /// Unmodified default font captured from the most recently installed look and feel.
    private @Nullable Font lookAndFeelDefaultFont;

    /// Optional application callback that re-resolves a concrete theme after the system appearance changes.
    private volatile @Nullable Runnable systemThemeRefreshHandler;

    /// Latest renderer-ready window request, available before the first frame is created.
    private volatile SwingWindowAppearanceRequest windowAppearance;

    /// Tracks whether this manager has installed FlatLaf at least once.
    private volatile boolean initialized;

    /// Creates a theme manager with explicit initial state and system detection.
    ///
    /// @param initialBrightnessPreference initial theme, system, light, or dark preference
    /// @param initialDesignTokens the initial visual measurements
    /// @param systemThemeDetector the non-blocking operating-system appearance detector
    public SwingThemeManager(
            ThemeBrightnessPreference initialBrightnessPreference,
            SwingDesignTokens initialDesignTokens,
            SystemThemeDetector systemThemeDetector) {
        brightnessPreference = Objects.requireNonNull(
                initialBrightnessPreference,
                "initialBrightnessPreference");
        designTokens = Objects.requireNonNull(initialDesignTokens);
        this.systemThemeDetector = Objects.requireNonNull(systemThemeDetector);
        ThemeVariant initialVariant = bootstrapVariant(brightnessPreference);
        windowAppearance = SwingWindowAppearanceRequest.initial(
                initialVariant == ThemeVariant.DARK
                        ? ThemeBrightness.DARK
                        : ThemeBrightness.LIGHT);
    }

    /// Installs FlatLaf and the current design tokens before the application creates its first window.
    ///
    /// This method may be called from any thread and returns only after initialization on the EDT has completed.
    public void initialize() {
        EdtDispatcher.executeAndWait(() -> {
            @Nullable ResolvedTheme currentTheme = resolvedTheme;
            ThemeVariant variant = currentTheme != null
                    ? themeVariant(currentTheme.brightness())
                    : bootstrapVariant(brightnessPreference);
            applyResolvedTheme(
                    variant,
                    currentTheme != null ? currentTheme.primaryColorSeed() : null,
                    true);
        });
    }

    /// Applies a new brightness preference and token set to existing and future Swing components.
    ///
    /// This method may be called from any thread and returns only after any required EDT update has completed.
    ///
    /// @param newBrightnessPreference the new theme, system, light, or dark preference
    /// @param newDesignTokens the new visual measurements
    public void update(
            ThemeBrightnessPreference newBrightnessPreference,
            SwingDesignTokens newDesignTokens) {
        Objects.requireNonNull(newBrightnessPreference, "newBrightnessPreference");
        Objects.requireNonNull(newDesignTokens);

        EdtDispatcher.executeAndWait(() -> updateOnEventDispatchThread(
                newBrightnessPreference,
                newDesignTokens));
    }

    /// Applies concrete theme values while retaining the preference that produced them.
    ///
    /// Theme and system brightness preferences both need re-resolution when the operating-system appearance
    /// changes because manifest conditions can depend on that context. Explicit light and dark preferences do not.
    ///
    /// @param newTheme concrete selected-theme values after user overrides
    /// @param newDesignTokens new visual measurements
    /// @param newBrightnessPreference preference used to resolve the concrete theme
    public void update(
            ResolvedTheme newTheme,
            SwingDesignTokens newDesignTokens,
            ThemeBrightnessPreference newBrightnessPreference) {
        Objects.requireNonNull(newTheme, "newTheme");
        Objects.requireNonNull(newDesignTokens, "newDesignTokens");
        Objects.requireNonNull(newBrightnessPreference, "newBrightnessPreference");
        EdtDispatcher.executeAndWait(() -> updateResolvedThemeOnEventDispatchThread(
                newTheme,
                newDesignTokens,
                newBrightnessPreference));
    }

    /// Applies a launcher-wide font family immediately and preserves it across later palette changes.
    ///
    /// This method may be called before initialization. In that case the request is retained and applied as part of
    /// the first FlatLaf installation, avoiding a visible system-font frame during startup.
    ///
    /// @param family selected local family, or `null` for the active look-and-feel default
    public void updateDefaultFontFamily(@Nullable String family) {
        @Nullable String normalizedFamily = normalizeFontFamily(family);
        EdtDispatcher.executeAndWait(() -> {
            if (Objects.equals(defaultFontFamily, normalizedFamily)) {
                return;
            }
            @Nullable Font previousDefaultFont = UIManager.getFont("defaultFont");
            defaultFontFamily = normalizedFamily;
            if (initialized) {
                @Nullable Font replacement = applyDefaultFont();
                FlatLaf.updateUI();
                updateOpenWindowFonts(previousDefaultFont, replacement);
            }
        });
    }

    /// Marks a component subtree whose explicit font family must survive launcher-font changes.
    ///
    /// Domain-specific text surfaces such as game logs should use this marker while still inheriting colors and other
    /// look-and-feel values.
    ///
    /// @param component subtree root with an independently configured family
    public static void preserveExplicitFontFamily(JComponent component) {
        Objects.requireNonNull(component, "component").putClientProperty(FIXED_FONT_FAMILY_PROPERTY, true);
    }

    /// Installs the callback used to rebuild a resolved theme after a system-appearance change.
    ///
    /// @param handler non-blocking refresh request, or `null` to remove the callback
    public void setSystemThemeRefreshHandler(@Nullable Runnable handler) {
        systemThemeRefreshHandler = handler;
    }

    /// Replaces the renderer-ready background and transparency request.
    ///
    /// Publication is synchronous on the calling thread; native-window listeners must dispatch to the EDT.
    ///
    /// @param request complete renderer request
    public void updateWindowAppearance(SwingWindowAppearanceRequest request) {
        SwingWindowAppearanceRequest replacement = Objects.requireNonNull(request, "request");
        SwingWindowAppearanceRequest previous;
        synchronized (windowAppearanceLock) {
            previous = windowAppearance;
            windowAppearance = replacement;
        }
        windowAppearanceChanges.fireChange(previous, replacement);
    }

    /// Returns the latest complete native-window appearance request.
    ///
    /// @return current renderer request
    public SwingWindowAppearanceRequest windowAppearance() {
        return windowAppearance;
    }

    /// Registers for future background or native-transparency replacements.
    ///
    /// @param listener request transition listener
    /// @return independently cancellable listener registration
    public Subscription subscribeWindowAppearance(
            ValueChangeListener<SwingWindowAppearanceRequest> listener) {
        return windowAppearanceChanges.subscribe(Objects.requireNonNull(listener, "listener"));
    }

    /// Rechecks the operating-system appearance when theme or system brightness depends on it.
    ///
    /// Platform integrations should invoke this method after receiving a native appearance-change event. This method may be called from
    /// any thread and returns only after any required EDT update has completed.
    public void refreshSystemTheme() {
        ThemeBrightnessPreference currentPreference = brightnessPreference;
        if (!followsSystemAppearance(currentPreference)) {
            return;
        }
        if (resolvedTheme != null) {
            @Nullable Runnable handler = systemThemeRefreshHandler;
            if (handler != null) {
                handler.run();
            }
            return;
        }
        ThemeVariant resolvedVariant = bootstrapVariant(currentPreference);
        EdtDispatcher.executeAndWait(() -> {
            if (!followsSystemAppearance(brightnessPreference)) {
                return;
            }

            if (!initialized || resolvedVariant != effectiveVariant) {
                applyResolvedTheme(resolvedVariant, null, true);
            }
        });
    }

    /// Returns the current four-state brightness preference.
    ///
    /// @return selected theme, system, light, or dark preference
    public ThemeBrightnessPreference brightnessPreference() {
        return brightnessPreference;
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
    /// @return resolved theme, or `null` before theme-domain resolution completes
    public @Nullable ResolvedTheme resolvedTheme() {
        return resolvedTheme;
    }

    /// Returns the explicit accent currently installed into FlatLaf.
    ///
    /// @return resolved theme color, or `null` while using FlatLaf's platform default
    public @Nullable ThemeColor effectiveAccentColor() {
        return effectiveAccentColor;
    }

    /// Returns the requested launcher UI font family.
    ///
    /// @return selected family, or `null` for the look-and-feel default
    public @Nullable String defaultFontFamily() {
        return defaultFontFamily;
    }

    /// Returns whether FlatLaf has been installed by this manager.
    ///
    /// @return `true` after successful initialization
    public boolean isInitialized() {
        return initialized;
    }

    /// Updates state and refreshes Swing only when the rendered appearance changes.
    ///
    /// @param newBrightnessPreference the new brightness preference
    /// @param newDesignTokens the new design tokens
    private void updateOnEventDispatchThread(
            ThemeBrightnessPreference newBrightnessPreference,
            SwingDesignTokens newDesignTokens) {
        EdtDispatcher.requireEventDispatchThread();

        ThemeVariant resolvedVariant = bootstrapVariant(newBrightnessPreference);
        boolean tokensChanged = !newDesignTokens.equals(designTokens);
        boolean variantChanged = resolvedVariant != effectiveVariant;
        boolean accentChanged = effectiveAccentColor != null;

        brightnessPreference = newBrightnessPreference;
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
            ThemeBrightnessPreference newBrightnessPreference) {
        EdtDispatcher.requireEventDispatchThread();

        ThemeVariant resolvedVariant = themeVariant(newTheme.brightness());
        boolean tokensChanged = !newDesignTokens.equals(designTokens);
        boolean variantChanged = resolvedVariant != effectiveVariant;
        boolean accentChanged = !Objects.equals(newTheme.primaryColorSeed(), effectiveAccentColor);

        brightnessPreference = newBrightnessPreference;
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
        @Nullable Font previousDefaultFont = UIManager.getFont("defaultFont");

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
            lookAndFeelDefaultFont = UIManager.getLookAndFeelDefaults().getFont("defaultFont");
        }

        designTokens.applyTo(UIManager.getDefaults());
        @Nullable Font replacementFont = applyDefaultFont();
        // FlatLaf caches the checkbox icon after first resolution, including the arc read at construction time.
        @Nullable Icon currentCheckBoxIcon = UIManager.getIcon("CheckBox.icon");
        if (currentCheckBoxIcon != null && currentCheckBoxIcon.getClass() == FlatCheckBoxIcon.class) {
            UIManager.put("CheckBox.icon", new FlatCheckBoxIcon());
        }
        effectiveVariant = resolvedVariant;
        effectiveAccentColor = accentColor;
        initialized = true;
        FlatLaf.updateUI();
        updateOpenWindowFonts(previousDefaultFont, replacementFont);
        updateOpenWindowCornerPreferences(designTokens.cornerRadius());
    }

    /// Synchronizes native frame and dialog corners after a live launcher-radius change.
    ///
    /// Lightweight popup windows retain their component-defined shapes and are intentionally excluded.
    ///
    /// @param cornerRadius current launcher component radius
    private static void updateOpenWindowCornerPreferences(int cornerRadius) {
        for (Window window : Window.getWindows()) {
            if (window.isDisplayable() && (window instanceof Frame || window instanceof Dialog)) {
                WindowsNativeUtils.applyWindowCornerPreference(window, cornerRadius);
            }
        }
    }

    /// Installs the requested family while preserving the look-and-feel font style and logical size.
    ///
    /// @return installed default font, or `null` when the look and feel exposes no font baseline
    private @Nullable Font applyDefaultFont() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable Font baseline = lookAndFeelDefaultFont;
        if (baseline == null) {
            baseline = UIManager.getFont("Label.font");
        }
        if (baseline == null) {
            return null;
        }
        @Nullable String family = defaultFontFamily;
        Font replacement = family == null
                ? baseline
                : new Font(family, baseline.getStyle(), baseline.getSize()).deriveFont(baseline.getSize2D());
        UIManager.put("defaultFont", new FontUIResource(replacement));
        return replacement;
    }

    /// Updates explicit derived fonts in every displayable window after a launcher-family replacement.
    ///
    /// @param previousDefaultFont prior launcher default, or `null`
    /// @param replacementFont replacement launcher default, or `null`
    private static void updateOpenWindowFonts(
            @Nullable Font previousDefaultFont,
            @Nullable Font replacementFont) {
        if (previousDefaultFont == null || replacementFont == null) {
            return;
        }
        for (Window window : Window.getWindows()) {
            if (window.isDisplayable()) {
                replaceFontFamily(window, previousDefaultFont, replacementFont);
            }
        }
    }

    /// Replaces one launcher-derived family throughout a component subtree while preserving style and size.
    ///
    /// @param component current subtree root
    /// @param previousDefaultFont previous launcher default
    /// @param replacementFont replacement launcher default
    static void replaceFontFamily(
            Component component,
            Font previousDefaultFont,
            Font replacementFont) {
        Component target = Objects.requireNonNull(component, "component");
        Font previous = Objects.requireNonNull(previousDefaultFont, "previousDefaultFont");
        Font replacement = Objects.requireNonNull(replacementFont, "replacementFont");
        if (target instanceof JComponent swingComponent
                && Boolean.TRUE.equals(swingComponent.getClientProperty(FIXED_FONT_FAMILY_PROPERTY))) {
            return;
        }

        @Nullable Font current = target.getFont();
        if (current != null && current.getFamily().equalsIgnoreCase(previous.getFamily())) {
            Font replaced = new Font(replacement.getFamily(), current.getStyle(), 1).deriveFont(current.getSize2D());
            target.setFont(current instanceof UIResource ? new FontUIResource(replaced) : replaced);
        }
        if (target instanceof Container container) {
            for (Component child : container.getComponents()) {
                replaceFontFamily(child, previous, replacement);
            }
        }
    }

    /// Normalizes blank family names to the look-and-feel default representation.
    ///
    /// @param family requested family, or `null`
    /// @return trimmed family, or `null`
    private static @Nullable String normalizeFontFamily(@Nullable String family) {
        if (family == null) {
            return null;
        }
        String normalized = family.trim();
        return normalized.isEmpty() ? null : normalized;
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

    /// Resolves an initial concrete palette before a selected theme has been resolved.
    ///
    /// Theme inheritance uses the operating-system brightness only as a startup palette until the theme resolver
    /// supplies the selected theme's concrete brightness.
    ///
    /// @param preference current brightness preference
    /// @return concrete startup palette
    private ThemeVariant bootstrapVariant(ThemeBrightnessPreference preference) {
        return switch (Objects.requireNonNull(preference, "preference")) {
            case THEME, SYSTEM -> systemThemeDetector.isDarkTheme()
                    ? ThemeVariant.DARK
                    : ThemeVariant.LIGHT;
            case LIGHT -> ThemeVariant.LIGHT;
            case DARK -> ThemeVariant.DARK;
        };
    }

    /// Returns whether operating-system appearance changes require theme re-resolution.
    ///
    /// @param preference current brightness preference
    /// @return whether system appearance affects the rendered theme
    private static boolean followsSystemAppearance(ThemeBrightnessPreference preference) {
        return preference == ThemeBrightnessPreference.THEME
                || preference == ThemeBrightnessPreference.SYSTEM;
    }
}
