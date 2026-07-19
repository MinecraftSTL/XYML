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
package space.minecraftstl.xyml.ui;

import space.minecraftstl.xyml.JavaFXLauncher;
import space.minecraftstl.xyml.game.ClassicVersion;
import space.minecraftstl.xyml.game.LaunchOptions;
import space.minecraftstl.xyml.java.JavaInfo;
import space.minecraftstl.xyml.game.Log;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.util.platform.ManagedProcess;
import space.minecraftstl.xyml.util.platform.Platform;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

public class GameCrashWindowTest {

    @Test
    @Disabled
    public void test() throws Exception {
        JavaFXLauncher.start();

        ManagedProcess process = new ManagedProcess(null, Arrays.asList("commands", "2"));

        String logs = Files.readString(new File("../XYMLCore/src/test/resources/logs/too_old_java.txt").toPath());

        CountDownLatch latch = new CountDownLatch(1);
        FXUtils.runInFX(() -> {
            Path workingPath = Path.of(System.getProperty("user.dir"));

            GameCrashWindow window = new GameCrashWindow(process, ProcessListener.ExitType.APPLICATION_ERROR, null,
                    new ClassicVersion(),
                    new LaunchOptions.Builder()
                            .setJava(new JavaRuntime(workingPath, new JavaInfo(Platform.SYSTEM_PLATFORM, "16", null), false, false))
                            .setGameDir(workingPath)
                            .create(),
                    Arrays.stream(logs.split("\\n"))
                            .map(Log::new)
                            .collect(Collectors.toList()));

            window.showAndWait();

            latch.countDown();
        });
        latch.await();
    }
}
