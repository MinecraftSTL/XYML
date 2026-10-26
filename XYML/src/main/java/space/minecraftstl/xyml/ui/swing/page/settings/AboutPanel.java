// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.DataSizeUnit;
import space.minecraftstl.xyml.util.platform.Architecture;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.SystemInfo;
import space.minecraftstl.xyml.util.platform.hardware.CentralProcessor;
import space.minecraftstl.xyml.util.platform.hardware.GraphicsCard;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Renders the grouped About page inside the Swing settings center.
///
/// The acknowledgement and dependency sections are read from the launcher-owned JSON resources so credits, order,
/// images, links, and localized text stay aligned with packaged acknowledgement assets.
@NotNullByDefault
final class AboutPanel extends JPanel implements AutoCloseable {
    /// Environment-only component host updated on the Swing EDT.
    private final JPanel environmentPanel = new JPanel(new MigLayout("insets 0, fillx, wrap 1", "[grow,fill]", "[]2[]"));

    /// Page-owned completion whose result is ignored after close.
    private final CompletableFuture<@Unmodifiable List<AboutEntry>> environmentLoad;

    /// Thread-safe closure gate shared by the worker completion and the settings owner.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Immutable environment rows last published on the EDT.
    private @Unmodifiable List<AboutEntry> environmentRows = List.of();

    /// Classpath resource containing ordered acknowledgements.
    static final String THANKS_RESOURCE = "/assets/about/thanks.json";

    /// Classpath resource containing ordered third-party dependency notices.
    static final String DEPENDENCIES_RESOURCE = "/assets/about/deps.json";

    /// Exact icon size used by the two-line list.
    private static final int ENTRY_ICON_SIZE = 32;

    /// Small command icon size for rows that open an external page.
    private static final int LINK_ICON_SIZE = 16;

    /// Link opening callback owned by the settings center.
    private final Consumer<URI> linkCommand;

    /// Ordered acknowledgement entries loaded from the bundled JSON resource.
    private final @Unmodifiable List<AboutEntry> acknowledgements;

    /// Ordered dependency entries loaded from the bundled JSON resource.
    private final @Unmodifiable List<AboutEntry> dependencies;

    /// Creates the complete About page with all acknowledgement sections.
    ///
    /// @param linkCommand command used to open trusted external links
    AboutPanel(Consumer<URI> linkCommand) {
        this(linkCommand, Schedulers.io(), AboutPanel::readEnvironment);
    }

    /// Creates one About page with explicitly owned asynchronous environment work.
    ///
    /// @param linkCommand trusted external link action
    /// @param executor caller-owned background executor that the page must not shut down
    /// @param environmentSource background row supplier whose returned list is defensively copied
    AboutPanel(
            Consumer<URI> linkCommand,
            Executor executor,
            Supplier<List<AboutEntry>> environmentSource) {
        super(new MigLayout("insets 20, fillx, wrap 1", "[grow,fill]", "[]12[]18[]12[]18[]12[]18[]12[]18[]12[]"));
        this.linkCommand = Objects.requireNonNull(linkCommand, "linkCommand");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(environmentSource, "environmentSource");
        acknowledgements = loadListResource(THANKS_RESOURCE);
        dependencies = loadListResource(DEPENDENCIES_RESOURCE);
        setOpaque(false);
        environmentPanel.setOpaque(false);
        environmentPanel.add(new JLabel(i18n("about.environment.loading")), "growx");
        configureComponents();
        CompletableFuture<@Unmodifiable List<AboutEntry>> load;
        try {
            load = CompletableFuture.supplyAsync(() -> List.copyOf(environmentSource.get()), executor);
        } catch (RuntimeException failure) {
            load = CompletableFuture.failedFuture(failure);
        }
        environmentLoad = load;
        load.whenComplete((@Nullable @Unmodifiable List<AboutEntry> rows, @Nullable Throwable failure) ->
                EdtDispatcher.execute(() -> showEnvironment(rows, failure)));
    }

    /// Returns the ordered acknowledgement data for focused tests.
    ///
    /// @return immutable acknowledgement rows
    @Unmodifiable List<AboutEntry> acknowledgements() {
        return acknowledgements;
    }

    /// Returns the ordered dependency data for focused tests.
    ///
    /// @return immutable dependency rows
    @Unmodifiable List<AboutEntry> dependencies() {
        return dependencies;
    }

    /// Loads and localizes one About-page JSON list from the classpath.
    ///
    /// @param resourcePath absolute classpath resource path
    /// @return immutable ordered entries, or an empty list when the optional resource cannot be loaded
    static @Unmodifiable List<AboutEntry> loadListResource(String resourcePath) {
        String validatedPath = Objects.requireNonNull(resourcePath, "resourcePath");
        try (InputStream input = AboutPanel.class.getResourceAsStream(validatedPath)) {
            if (input == null) {
                return List.of();
            }
            JsonArray array = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonArray();
            List<AboutEntry> entries = new ArrayList<>(array.size());
            for (JsonElement element : array) {
                entries.add(parseEntry(element.getAsJsonObject()));
            }
            return List.copyOf(entries);
        } catch (IOException | IllegalStateException | JsonParseException exception) {
            return List.of();
        }
    }

    /// Returns whether one absolute classpath resource can be resolved.
    ///
    /// @param resourcePath absolute classpath resource path
    /// @return whether the resource exists
    static boolean hasClasspathResource(String resourcePath) {
        try (InputStream input = AboutPanel.class.getResourceAsStream(
                Objects.requireNonNull(resourcePath, "resourcePath"))) {
            return input != null;
        } catch (IOException exception) {
            return false;
        }
    }

    /// Builds every acknowledgement group in resource order.
    private void configureComponents() {
        addSection(i18n("about"), productEntries());
        add(createHeading(i18n("about.environment")), "growx");
        add(environmentPanel, "growx");
        addSection(i18n("about.thanks_to"), acknowledgements);
        addSection(i18n("about.dependency"), dependencies);
        addSection(i18n("about.legal"), legalEntries());
    }

    /// Returns the immutable environment snapshot without exposing the mutable component host.
    ///
    /// @return environment rows published for this page
    @Unmodifiable List<AboutEntry> environmentSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return environmentRows;
    }

    /// Publishes one environment result only while the owning page is open.
    ///
    /// @param rows immutable successful result, or null on failure
    /// @param failure background or scheduling failure, or null on success
    private void showEnvironment(@Nullable @Unmodifiable List<AboutEntry> rows, @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get())
            return;
        environmentPanel.removeAll();
        if (failure != null || rows == null) {
            if (failure != null)
                LOG.warning("Failed to read launcher environment information", failure);
            environmentPanel.add(new JLabel(i18n("about.environment.failed")), "growx");
        } else {
            environmentRows = rows;
            for (AboutEntry entry : rows)
                environmentPanel.add(createEntryRow(entry), "growx");
        }
        environmentPanel.revalidate();
        environmentPanel.repaint();
    }

    /// Collects local system and runtime information without creating a JavaFX context or network request.
    ///
    /// @return immutable rows, omitting unavailable hardware instead of fabricating values
    private static @Unmodifiable List<AboutEntry> readEnvironment() {
        List<AboutEntry> rows = new ArrayList<>();
        String system = OperatingSystem.OS_RELEASE_PRETTY_NAME == null
                ? "%s %s (%s)".formatted(OperatingSystem.SYSTEM_NAME,
                        OperatingSystem.SYSTEM_VERSION.getVersion(), Architecture.SYSTEM_ARCH.getDisplayName())
                : "%s (%s %s, %s)".formatted(OperatingSystem.OS_RELEASE_PRETTY_NAME, OperatingSystem.SYSTEM_NAME,
                        OperatingSystem.SYSTEM_VERSION.getVersion(), Architecture.SYSTEM_ARCH.getDisplayName());
        rows.add(new AboutEntry(null, null, i18n("about.environment.system"), system, null));
        long memory = SystemInfo.getTotalMemorySize();
        if (memory > 0) {
            rows.add(new AboutEntry(null, null, i18n("about.environment.memory"),
                    String.format(Locale.ROOT, "%.1f GiB", DataSizeUnit.GIGABYTES.convertFromBytes(memory)), null));
        }
        rows.addAll(hardwareRows(SystemInfo.getCentralProcessor(), SystemInfo.getGraphicsCards()));
        rows.add(new AboutEntry(null, null, "Java", "%s, %s by %s".formatted(
                Runtime.version(), System.getProperty("java.vm.name"), System.getProperty("java.vendor")), null));
        return List.copyOf(rows);
    }

    /// Formats detected hardware without probing the system or creating UI components.
    ///
    /// @param cpu detected processor, or null when unavailable
    /// @param cards detected adapters, or null when unavailable; unknown classifications remain visible
    /// @return immutable rows with known virtual adapters omitted and visible adapters numbered consecutively
    static @Unmodifiable List<AboutEntry> hardwareRows(
            @Nullable CentralProcessor cpu, @Nullable List<GraphicsCard> cards) {
        List<AboutEntry> rows = new ArrayList<>();
        if (cpu != null) {
            @Nullable CentralProcessor.Cores cores = cpu.getCores();
            int packages = cores != null && cores.packages() > 1 ? cores.packages() : 1;
            int logical = cores == null ? 0 : cores.logical() / packages;
            int physical = cores == null ? 0 : cores.physical() / packages;
            String name;
            if (logical == 0)
                name = i18n("about.environment.cpu.name", cpu.getName());
            else if (logical != physical)
                name = i18n("about.environment.cpu.name.cores.hyper_threading", cpu.getName(), physical, logical);
            else
                name = i18n("about.environment.cpu.name.cores", cpu.getName(), physical);
            for (int index = 1; index <= packages; index++) {
                String title = packages == 1 ? i18n("about.environment.cpu") : i18n("about.environment.cpu.n", index);
                rows.add(new AboutEntry(null, null, title, name, null));
            }
        }
        if (cards != null) {
            @Unmodifiable List<GraphicsCard> visible = cards.stream()
                    .filter(card -> !card.isVirtualDisplayAdapter()).toList();
            for (int index = 0; index < visible.size(); index++) {
                String title = visible.size() == 1 ? i18n("about.environment.gpu")
                        : i18n("about.environment.gpu.n", index + 1);
                rows.add(new AboutEntry(null, null, title, visible.get(index).getName(), null));
            }
        }
        return List.copyOf(rows);
    }

    /// Cancels pending page-owned completion without shutting down its caller-owned executor.
    @Override
    public void close() {
        if (closed.compareAndSet(false, true))
            environmentLoad.cancel(false);
    }

    /// Creates the product and author identity rows at the start of the About page.
    ///
    /// @return immutable product identity rows
    private static @Unmodifiable List<AboutEntry> productEntries() {
        return List.of(
                new AboutEntry(
                        "/assets/img/icon.png",
                        null,
                        Metadata.FULL_NAME,
                        Metadata.VERSION,
                        URI.create("https://github.com/MinecraftSTL/XYML")),
                new AboutEntry(
                        "/assets/img/minecraftstl.png",
                        null,
                        "MinecraftSTL",
                        "bilibili @MinecraftSTL",
                        URI.create("https://space.bilibili.com/2059457567")),
                new AboutEntry(
                        "/assets/img/yellow_fish.png",
                        null,
                        "huanghongxun",
                        i18n("about.author.statement"),
                        URI.create("https://space.bilibili.com/1445341")));
    }

    /// Creates the legal acknowledgement rows at the end of the About page.
    ///
    /// @return immutable legal rows
    private static @Unmodifiable List<AboutEntry> legalEntries() {
        return List.of(
                new AboutEntry(
                        null,
                        null,
                        i18n("about.copyright"),
                        i18n("about.copyright.statement"),
                        null),
                new AboutEntry(
                        null,
                        null,
                        i18n("about.claim"),
                        i18n("about.claim.statement"),
                        URI.create(Metadata.EULA_URL)),
                new AboutEntry(
                        null,
                        null,
                        i18n("about.open_source"),
                        i18n("about.open_source.statement"),
                        URI.create("https://github.com/MinecraftSTL/XYML")));
    }

    /// Parses one resource object into a localized two-line entry.
    ///
    /// @param object resource JSON object
    /// @return parsed entry
    private static AboutEntry parseEntry(JsonObject object) {
        JsonObject validatedObject = Objects.requireNonNull(object, "object");
        AboutImage image = readImage(validatedObject);
        return new AboutEntry(
                image.lightResource(),
                image.darkResource(),
                readText(validatedObject, "title", "titleLocalized"),
                readText(validatedObject, "subtitle", "subtitleLocalized"),
                readUri(validatedObject));
    }

    /// Reads a localizable text property from one resource object.
    ///
    /// @param object resource object
    /// @param literalProperty literal text property name
    /// @param localizedProperty i18n key property name
    /// @return resolved localized text, or an empty string when absent
    private static String readText(JsonObject object, String literalProperty, String localizedProperty) {
        JsonObject validatedObject = Objects.requireNonNull(object, "object");
        JsonElement literal = validatedObject.get(Objects.requireNonNull(literalProperty, "literalProperty"));
        if (literal != null && literal.isJsonPrimitive()) {
            return literal.getAsString();
        }
        JsonElement localized = validatedObject.get(Objects.requireNonNull(localizedProperty, "localizedProperty"));
        return localized != null && localized.isJsonPrimitive()
                ? i18n(localized.getAsString())
                : "";
    }

    /// Reads an optional external link from one resource object.
    ///
    /// @param object resource object
    /// @return parsed URI, or `null` for intentionally non-clickable rows
    private static @Nullable URI readUri(JsonObject object) {
        JsonElement link = Objects.requireNonNull(object, "object").get("externalLink");
        return link != null && link.isJsonPrimitive() ? URI.create(link.getAsString()) : null;
    }

    /// Reads a fixed or theme-aware image declaration from one resource object.
    ///
    /// @param object resource object
    /// @return parsed image resources
    private static AboutImage readImage(JsonObject object) {
        JsonElement image = Objects.requireNonNull(object, "object").get("image");
        if (image == null) {
            return new AboutImage(null, null);
        }
        if (image.isJsonPrimitive()) {
            return new AboutImage(image.getAsString(), null);
        }
        JsonObject themed = image.getAsJsonObject();
        return new AboutImage(readOptionalString(themed, "light"), readOptionalString(themed, "dark"));
    }

    /// Reads one optional string property.
    ///
    /// @param object owning object
    /// @param property property name
    /// @return property value, or `null` when absent
    private static @Nullable String readOptionalString(JsonObject object, String property) {
        JsonElement value = Objects.requireNonNull(object, "object").get(Objects.requireNonNull(property, "property"));
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /// Adds a complete titled list section to this page.
    ///
    /// @param title visible section title
    /// @param entries ordered rows
    private void addSection(String title, @Unmodifiable List<AboutEntry> entries) {
        add(createHeading(title), "growx");
        JPanel list = new JPanel(new MigLayout("insets 0, fillx, wrap 1", "[grow,fill]", "[]2[]"));
        list.setOpaque(false);
        for (AboutEntry entry : entries) {
            list.add(createEntryRow(entry), "growx");
        }
        add(list, "growx");
    }

    /// Creates one compact two-line list row with optional icon and external-link command.
    ///
    /// @param entry row data
    /// @return configured row component
    private JComponent createEntryRow(AboutEntry entry) {
        AboutEntry validatedEntry = Objects.requireNonNull(entry, "entry");
        boolean hasImage = validatedEntry.lightImageResource() != null || validatedEntry.darkImageResource() != null;
        JPanel row = new JPanel(new MigLayout(
                "insets 8 0, fillx",
                hasImage ? "[40!,center][grow,fill][]" : "[grow,fill][]",
                "[]2[]"));
        row.setOpaque(false);

        int textColumn = hasImage ? 1 : 0;
        int linkColumn = hasImage ? 2 : 1;
        if (hasImage) {
            JLabel iconLabel = new JLabel(createEntryIcon(validatedEntry), SwingConstants.CENTER);
            iconLabel.setName("aboutEntryIcon");
            row.add(iconLabel, "cell 0 0 1 2, align center center");
        }

        JLabel title = new JLabel(validatedEntry.title());
        title.setName("aboutEntryTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        row.add(title, "cell " + textColumn + " 0, growx");

        JTextArea subtitle = new JTextArea(validatedEntry.subtitle());
        subtitle.setName("aboutEntrySubtitle");
        subtitle.setOpaque(false);
        subtitle.setEditable(false);
        subtitle.setFocusable(false);
        subtitle.setLineWrap(true);
        subtitle.setWrapStyleWord(true);
        subtitle.setFont(UIManager.getFont("Label.font"));
        subtitle.setForeground(UIManager.getColor("Label.foreground"));
        row.add(subtitle, "cell " + textColumn + " 1, growx");

        @Nullable URI externalLink = validatedEntry.externalLink();
        if (externalLink != null) {
            row.add(
                    createLinkButton(validatedEntry.title(), externalLink),
                    "cell " + linkColumn + " 0 1 2, align center center");
        }
        return row;
    }

    /// Creates a compact heading suitable for grouped about-page sections.
    ///
    /// @param text visible heading text
    /// @return configured heading label
    private static JLabel createHeading(String text) {
        JLabel heading = new JLabel(Objects.requireNonNull(text, "text"));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 20.0F));
        return heading;
    }

    /// Creates one icon-only external-link command.
    ///
    /// @param title row title used for accessibility
    /// @param destination trusted destination URI
    /// @return configured link command
    private JButton createLinkButton(String title, URI destination) {
        URI validatedDestination = Objects.requireNonNull(destination, "destination");
        JButton button = new JButton(new FlatSVGIcon("assets/swing/icons/open-in-new.svg", LINK_ICON_SIZE, LINK_ICON_SIZE));
        String accessibleName = i18n("button.view") + " " + Objects.requireNonNull(title, "title");
        button.setToolTipText(accessibleName);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.getAccessibleContext().setAccessibleName(accessibleName);
        button.addActionListener(event -> linkCommand.accept(validatedDestination));
        return button;
    }

    /// Creates a fixed-size icon, preserving theme-specific image alternatives when present.
    ///
    /// @param entry source row data
    /// @return row icon, using an empty placeholder when no image is configured
    private static Icon createEntryIcon(AboutEntry entry) {
        AboutEntry validatedEntry = Objects.requireNonNull(entry, "entry");
        @Nullable ImageIcon lightIcon = loadImageIcon(validatedEntry.lightImageResource());
        @Nullable ImageIcon darkIcon = loadImageIcon(validatedEntry.darkImageResource());
        if (lightIcon != null && darkIcon != null) {
            return new ThemeAwareRasterIcon(lightIcon, darkIcon);
        }
        if (lightIcon != null) {
            return lightIcon;
        }
        if (darkIcon != null) {
            return darkIcon;
        }
        return new ImageIcon(new BufferedImage(ENTRY_ICON_SIZE, ENTRY_ICON_SIZE, BufferedImage.TYPE_INT_ARGB));
    }

    /// Loads and scales one bundled raster resource.
    ///
    /// @param resourcePath absolute classpath resource path, or null for rows without images
    /// @return scaled icon, or `null` when no image is configured or available
    private static @Nullable ImageIcon loadImageIcon(@Nullable String resourcePath) {
        if (resourcePath == null) {
            return null;
        }
        try (InputStream input = AboutPanel.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                return null;
            }
            @Nullable BufferedImage image = ImageIO.read(input);
            return image == null ? null : new ImageIcon(scale(image));
        } catch (IOException exception) {
            return null;
        }
    }

    /// Scales one source image to the row icon size.
    ///
    /// @param source source image
    /// @return scaled ARGB image
    private static BufferedImage scale(BufferedImage source) {
        BufferedImage target = new BufferedImage(ENTRY_ICON_SIZE, ENTRY_ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(Objects.requireNonNull(source, "source"), 0, 0, ENTRY_ICON_SIZE, ENTRY_ICON_SIZE, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /// Immutable classpath image declaration.
    @NotNullByDefault
    static final class AboutImage {
        /// Image used in light or unspecified themes.
        private final @Nullable String lightResource;

        /// Image used in dark themes when configured.
        private final @Nullable String darkResource;

        /// Creates one image declaration.
        ///
        /// @param lightResource light-theme resource, or null when absent
        /// @param darkResource dark-theme resource, or null when absent
        AboutImage(@Nullable String lightResource, @Nullable String darkResource) {
            this.lightResource = lightResource;
            this.darkResource = darkResource;
        }

        /// Returns the light-theme resource.
        ///
        /// @return light resource, or null
        @Nullable String lightResource() {
            return lightResource;
        }

        /// Returns the dark-theme resource.
        ///
        /// @return dark resource, or null
        @Nullable String darkResource() {
            return darkResource;
        }
    }

    /// Immutable localized about-list row.
    @NotNullByDefault
    static final class AboutEntry {
        /// Light-theme image resource or fixed row image.
        private final @Nullable String lightImageResource;

        /// Dark-theme image resource when present.
        private final @Nullable String darkImageResource;

        /// Visible title text.
        private final String title;

        /// Visible subtitle text.
        private final String subtitle;

        /// Trusted external link, or null for non-clickable acknowledgement rows.
        private final @Nullable URI externalLink;

        /// Creates one localized list row.
        ///
        /// @param lightImageResource light or fixed image resource, or null
        /// @param darkImageResource dark image resource, or null
        /// @param title visible title
        /// @param subtitle visible subtitle
        /// @param externalLink external destination, or null
        AboutEntry(
                @Nullable String lightImageResource,
                @Nullable String darkImageResource,
                String title,
                String subtitle,
                @Nullable URI externalLink) {
            this.lightImageResource = lightImageResource;
            this.darkImageResource = darkImageResource;
            this.title = Objects.requireNonNull(title, "title");
            this.subtitle = Objects.requireNonNull(subtitle, "subtitle");
            this.externalLink = externalLink;
        }

        /// Returns the light-theme image resource or fixed row image.
        ///
        /// @return image resource, or null
        @Nullable String lightImageResource() {
            return lightImageResource;
        }

        /// Returns the dark-theme image resource.
        ///
        /// @return image resource, or null
        @Nullable String darkImageResource() {
            return darkImageResource;
        }

        /// Returns the visible title.
        ///
        /// @return title text
        String title() {
            return title;
        }

        /// Returns the visible subtitle.
        ///
        /// @return subtitle text
        String subtitle() {
            return subtitle;
        }

        /// Returns the external destination.
        ///
        /// @return destination URI, or null for non-clickable rows
        @Nullable URI externalLink() {
            return externalLink;
        }
    }

    /// Raster icon that switches between bundled light and dark variants at paint time.
    @NotNullByDefault
    private static final class ThemeAwareRasterIcon implements Icon {
        /// Light-theme icon.
        private final ImageIcon lightIcon;

        /// Dark-theme icon.
        private final ImageIcon darkIcon;

        /// Creates a theme-aware icon pair.
        ///
        /// @param lightIcon icon for light backgrounds
        /// @param darkIcon icon for dark backgrounds
        private ThemeAwareRasterIcon(ImageIcon lightIcon, ImageIcon darkIcon) {
            this.lightIcon = Objects.requireNonNull(lightIcon, "lightIcon");
            this.darkIcon = Objects.requireNonNull(darkIcon, "darkIcon");
        }

        /// Paints the icon variant that matches the owning component background.
        ///
        /// @param component owning component, or null
        /// @param graphics target graphics
        /// @param x icon x-coordinate
        /// @param y icon y-coordinate
        @Override
        public void paintIcon(@Nullable Component component, java.awt.Graphics graphics, int x, int y) {
            ImageIcon icon = isDarkBackground(component) ? darkIcon : lightIcon;
            icon.paintIcon(component, graphics, x, y);
        }

        /// Returns the icon width.
        ///
        /// @return fixed icon width
        @Override
        public int getIconWidth() {
            return lightIcon.getIconWidth();
        }

        /// Returns the icon height.
        ///
        /// @return fixed icon height
        @Override
        public int getIconHeight() {
            return lightIcon.getIconHeight();
        }

        /// Determines whether the owner is currently painted over a dark background.
        ///
        /// @param component owning component, or null
        /// @return whether to use the dark icon variant
        private static boolean isDarkBackground(@Nullable Component component) {
            Color background = backgroundColor(component);
            double luminance = 0.2126 * background.getRed()
                    + 0.7152 * background.getGreen()
                    + 0.0722 * background.getBlue();
            return luminance < 128.0;
        }

        /// Finds the nearest available background color.
        ///
        /// @param component starting component, or null
        /// @return resolved background color
        private static Color backgroundColor(@Nullable Component component) {
            @Nullable Component current = component;
            while (current != null) {
                @Nullable Color background = current.getBackground();
                if (background != null) {
                    return background;
                }
                current = current.getParent();
            }
            @Nullable Color fallback = UIManager.getColor("Panel.background");
            return fallback == null ? Color.WHITE : fallback;
        }
    }
}
