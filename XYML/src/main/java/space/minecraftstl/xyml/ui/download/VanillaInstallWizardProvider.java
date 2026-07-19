/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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

import javafx.scene.Node;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.GameBuilder;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.DownloadProviders;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.wizard.WizardController;
import space.minecraftstl.xyml.ui.wizard.WizardProvider;
import space.minecraftstl.xyml.util.SettingsMap;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

public final class VanillaInstallWizardProvider implements WizardProvider {
    private final XYMLGameRepository repository;
    private final DefaultDependencyManager dependencyManager;
    private final DownloadProvider downloadProvider;

    public VanillaInstallWizardProvider(XYMLGameRepository repository) {
        this.repository = repository;
        this.downloadProvider = DownloadProviders.getDownloadProvider();
        this.dependencyManager = repository.getDependency(downloadProvider);
    }

    @Override
    public void start(SettingsMap settings) {
        settings.put(ModpackPage.GAME_DIRECTORY, repository.getGameDirectory());
        settings.put(ModpackPage.REPOSITORY, repository);
    }

    private Task<Void> finishVersionDownloadingAsync(SettingsMap settings) {
        GameBuilder builder = dependencyManager.gameBuilder();

        String name = (String) settings.get("name");
        builder.name(name);
        builder.gameVersion(((RemoteVersion) settings.get("game")).getGameVersion());

        settings.asStringMap().forEach((key, value) -> {
            if (!"game".equals(key) && value instanceof RemoteVersion remoteVersion)
                builder.version(remoteVersion);
        });

        repository.applyDefaultIsolationSettingForNewInstance(name, settings.isInstallingModdedVersion());
        return builder.buildAsync().whenComplete(any -> {
            repository.refreshVersions();
        })
                .thenRunAsync(Schedulers.javafx(), () -> repository.setSelectedInstance(name));
    }

    @Override
    public Object finish(SettingsMap settings) {
        settings.put("title", i18n("install.new_game.installation"));
        settings.put("success_message", i18n("install.success"));
        settings.put(FailureCallback.KEY, (settings1, exception, next) -> UpdateInstallerWizardProvider.alertFailureMessage(exception, next));

        return finishVersionDownloadingAsync(settings);
    }

    @Override
    public Node createPage(WizardController controller, int step, SettingsMap settings) {
        switch (step) {
            case 0:
                return new VersionsPage(controller, i18n("install.installer.choose", i18n("install.installer.game")), "", downloadProvider, "game",
                        () -> controller.onNext(new InstallersPage(controller, repository, ((RemoteVersion) controller.getSettings().get("game")).getGameVersion(), downloadProvider)));
            default:
                throw new IllegalStateException("error step " + step + ", settings: " + settings + ", pages: " + controller.getPages());
        }
    }

    @Override
    public boolean cancel() {
        return true;
    }
}
