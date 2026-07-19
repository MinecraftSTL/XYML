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
package space.minecraftstl.xyml.ui.download;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.stage.FileChooser;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.game.ManuallyCreatedModpackException;
import space.minecraftstl.xyml.game.ModpackHelper;
import space.minecraftstl.xyml.modpack.Modpack;
import space.minecraftstl.xyml.setting.GameDirectoryManager;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.Controllers;
import space.minecraftstl.xyml.ui.FXUtils;
import space.minecraftstl.xyml.ui.WebPage;
import space.minecraftstl.xyml.ui.construct.MessageDialogPane;
import space.minecraftstl.xyml.ui.construct.RequiredValidator;
import space.minecraftstl.xyml.ui.construct.Validator;
import space.minecraftstl.xyml.ui.wizard.WizardController;
import space.minecraftstl.xyml.util.SettingsMap;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.io.CompressingUtils;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.nio.charset.Charset;
import java.nio.file.Path;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

public final class LocalModpackPage extends ModpackPage {

    private final BooleanProperty installAsVersion = new SimpleBooleanProperty(true);
    private Modpack manifest = null;
    private Charset charset;

    public LocalModpackPage(WizardController controller) {
        super(controller);

        XYMLGameRepository repository = controller.getSettings().get(ModpackPage.REPOSITORY);

        String name = controller.getSettings().get(MODPACK_NAME);
        if (name != null) {
            txtModpackName.setText(name);
            txtModpackName.setDisable(true);
        } else {
            FXUtils.onChangeAndOperate(installAsVersion, installAsVersion -> {
                if (installAsVersion) {
                    txtModpackName.getValidators().setAll(
                            new RequiredValidator(),
                            new Validator(i18n("install.new_game.already_exists"), str -> !repository.versionIdConflicts(str)),
                            new Validator(i18n("install.new_game.malformed"), XYMLGameRepository::isValidVersionId));
                } else {
                    txtModpackName.getValidators().setAll(
                            new RequiredValidator(),
                            new Validator(i18n("install.new_game.already_exists"), str -> !ModpackHelper.isExternalGameNameConflicts(str)
                                    && GameDirectoryManager.getGameDirectories().stream()
                                            .noneMatch(existingProfile ->
                                                    str.equals(GameDirectoryManager.getGameDirectoryCustomName(existingProfile)))),
                            new Validator(i18n("install.new_game.malformed"), XYMLGameRepository::isValidVersionId));
                }
            });
        }

        btnDescription.setVisible(false);

        Path selectedFile;
        Path filePath = controller.getSettings().get(MODPACK_FILE);
        if (filePath != null) {
            selectedFile = filePath;
        } else {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(i18n("modpack.choose"));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(i18n("modpack"), "*.zip"));
            selectedFile = FileUtils.toPath(chooser.showOpenDialog(Controllers.getStage()));
            if (selectedFile == null) {
                controller.onEnd();
                return;
            }

            controller.getSettings().put(MODPACK_FILE, selectedFile);
        }

        showSpinner();
        Task.supplyAsync(() -> CompressingUtils.findSuitableEncoding(selectedFile))
                .thenApplyAsync(encoding -> {
                    charset = encoding;
                    manifest = ModpackHelper.readModpackManifest(selectedFile, encoding);
                    return manifest;
                })
                .whenComplete(Schedulers.javafx(), (manifest, exception) -> {
                    if (exception instanceof ManuallyCreatedModpackException) {
                        hideSpinner();
                        nameProperty.set(FileUtils.getName(selectedFile));
                        installAsVersion.set(false);

                        if (name == null) {
                            // trim: https://github.com/HMCL-dev/HMCL/issues/962
                            txtModpackName.setText(FileUtils.getNameWithoutExtension(selectedFile));
                        }

                        Controllers.confirm(i18n("modpack.type.manual.warning"), i18n("install.modpack"), MessageDialogPane.MessageType.WARNING,
                                () -> {},
                                controller::onEnd);

                        controller.getSettings().put(MODPACK_MANUALLY_CREATED, true);
                    } else if (exception != null) {
                        LOG.warning("Failed to read modpack manifest", exception);
                        Controllers.dialog(i18n("modpack.task.install.error"), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                        Platform.runLater(controller::onEnd);
                    } else {
                        hideSpinner();
                        controller.getSettings().put(MODPACK_MANIFEST, manifest);
                        nameProperty.set(manifest.getName());
                        versionProperty.set(manifest.getVersion());
                        authorProperty.set(manifest.getAuthor());

                        if (name == null) {
                            // trim: https://github.com/HMCL-dev/HMCL/issues/962
                            txtModpackName.setText(manifest.getName().trim());
                        }

                        btnDescription.setVisible(StringUtils.isNotBlank(manifest.getDescription()));
                    }
                }).start();
    }

    @Override
    public void cleanup(SettingsMap settings) {
        settings.remove(MODPACK_FILE);
    }

    protected void onInstall() {
        String name = txtModpackName.getText();

        // Check for non-ASCII characters.
        if (!StringUtils.isASCII(name)) {
            Controllers.dialog(new MessageDialogPane.Builder(
                    i18n("install.name.invalid"),
                    i18n("message.warning"),
                    MessageDialogPane.MessageType.QUESTION)
                    .yesOrNo(() -> {
                        controller.getSettings().put(MODPACK_NAME, name);
                        controller.getSettings().put(MODPACK_CHARSET, charset);
                        controller.onFinish();
                    }, () -> {
                        // The user selects Cancel and does nothing.
                    })
                    .build());
        } else {
            controller.getSettings().put(MODPACK_NAME, name);
            controller.getSettings().put(MODPACK_CHARSET, charset);
            controller.onFinish();
        }
    }

    protected void onDescribe() {
        if (manifest != null)
            Controllers.navigate(new WebPage(i18n("modpack.description"), manifest.getDescription()));
    }

    public static final SettingsMap.Key<Path> MODPACK_FILE = new SettingsMap.Key<>("MODPACK_FILE");
    public static final SettingsMap.Key<String> MODPACK_NAME = new SettingsMap.Key<>("MODPACK_NAME");
    public static final SettingsMap.Key<Modpack> MODPACK_MANIFEST = new SettingsMap.Key<>("MODPACK_MANIFEST");
    public static final SettingsMap.Key<Charset> MODPACK_CHARSET = new SettingsMap.Key<>("MODPACK_CHARSET");
    public static final SettingsMap.Key<Boolean> MODPACK_MANUALLY_CREATED = new SettingsMap.Key<>("MODPACK_MANUALLY_CREATED");
    public static final SettingsMap.Key<String> MODPACK_ICON_URL = new SettingsMap.Key<>("MODPACK_ICON_URL");
}
