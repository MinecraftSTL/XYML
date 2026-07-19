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
package space.minecraftstl.xyml.event;

import space.minecraftstl.xyml.util.ToStringBuilder;
import space.minecraftstl.xyml.util.platform.ManagedProcess;

/**
 * This event gets fired when a JavaProcess exited abnormally and the exit code is not zero.
 *
 * This event is fired on the {@link space.minecraftstl.xyml.event.EventBus#EVENT_BUS}
 *
 * @author huangyuhui
 */
public final class ProcessExitedAbnormallyEvent extends Event {

    private final ManagedProcess process;

    /**
     * Constructor.
     *
     * @param source  {@link space.minecraftstl.xyml.launch.ExitWaiter}
     * @param process The process that exited abnormally.
     */
    public ProcessExitedAbnormallyEvent(Object source, ManagedProcess process) {
        super(source);
        this.process = process;
    }

    public ManagedProcess getProcess() {
        return process;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("source", source)
                .append("process", process)
                .toString();
    }
}
