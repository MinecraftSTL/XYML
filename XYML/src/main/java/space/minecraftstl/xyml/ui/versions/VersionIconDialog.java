/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.ui.versions;

import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.stage.FileChooser;
import space.minecraftstl.xyml.event.Event;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.VersionIconType;
import space.minecraftstl.xyml.ui.Controllers;
import space.minecraftstl.xyml.ui.FXUtils;
import space.minecraftstl.xyml.ui.SVG;
import space.minecraftstl.xyml.ui.construct.DialogPane;
import space.minecraftstl.xyml.ui.construct.RipplerContainer;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.io.IOException;
import java.nio.file.Path;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

public class VersionIconDialog extends DialogPane {
    private final XYMLGameRepository repository;
    private final String versionId;
    private final Runnable onFinish;
    private final GameSettings.Instance setting;

    public VersionIconDialog(XYMLGameRepository repository, String versionId, Runnable onFinish) {
        this.repository = repository;
        this.versionId = versionId;
        this.onFinish = onFinish;
        this.setting = repository.getInstanceGameSettingsOrCreate(versionId);

        setTitle(i18n("settings.icon"));
        FlowPane pane = new FlowPane();
        setBody(pane);

        pane.getChildren().setAll(
                createCustomIcon(),
                createIcon(VersionIconType.GRASS),
                createIcon(VersionIconType.CHEST),
                createIcon(VersionIconType.CHICKEN),
                createIcon(VersionIconType.COMMAND),
                createIcon(VersionIconType.APRIL_FOOLS),
                createIcon(VersionIconType.OPTIFINE),
                createIcon(VersionIconType.CRAFT_TABLE),
                createIcon(VersionIconType.FABRIC),
                createIcon(VersionIconType.LEGACY_FABRIC),
                createIcon(VersionIconType.FORGE),
                createIcon(VersionIconType.CLEANROOM),
                createIcon(VersionIconType.NEO_FORGE),
                createIcon(VersionIconType.FURNACE),
                createIcon(VersionIconType.QUILT)
        );
    }

    private void exploreIcon() {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(FXUtils.getImageExtensionFilter());
        Path selectedFile = FileUtils.toPath(chooser.showOpenDialog(Controllers.getStage()));
        if (selectedFile != null) {
            try {
                repository.setVersionIconFile(versionId, selectedFile);

                if (setting != null) {
                    setting.iconProperty().setValue(VersionIconType.DEFAULT);
                }

                onAccept();
            } catch (IOException | IllegalArgumentException e) {
                LOG.error("Failed to set icon file: " + selectedFile, e);
            }
        }
    }

    private Node createCustomIcon() {
        Node shape = SVG.ADD_CIRCLE.createIcon(32);
        shape.setMouseTransparent(true);
        RipplerContainer container = new RipplerContainer(shape);
        FXUtils.setLimitWidth(container, 36);
        FXUtils.setLimitHeight(container, 36);
        FXUtils.onClicked(container, this::exploreIcon);
        return container;
    }

    private Node createIcon(VersionIconType type) {
        ImageView imageView = new ImageView(type.getIcon());
        imageView.setMouseTransparent(true);
        RipplerContainer container = new RipplerContainer(imageView);
        FXUtils.setLimitWidth(container, 36);
        FXUtils.setLimitHeight(container, 36);
        FXUtils.onClicked(container, () -> {
            if (setting != null) {
                setting.iconProperty().setValue(type);
                onAccept();
            }
        });
        return container;
    }

    @Override
    protected void onAccept() {
        repository.onVersionIconChanged.fireEvent(new Event(this));
        onFinish.run();
        super.onAccept();
    }
}
