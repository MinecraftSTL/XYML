// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.event;

import org.jetbrains.annotations.NotNull;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// @author huangyuhui
public final class EventBus extends ClassValue<EventManager<?>> {

    public static final EventBus EVENT_BUS = new EventBus();

    private EventBus() {
    }

    @Override
    protected EventManager<?> computeValue(@NotNull Class<?> type) {
        return new EventManager<>();
    }

    @SuppressWarnings("unchecked")
    public <T extends Event> EventManager<T> channel(Class<T> clazz) {
        return (EventManager<T>) get(clazz);
    }

    @SuppressWarnings("unchecked")
    public Event.Result fireEvent(Event obj) {
        LOG.info(obj + " gets fired");

        return ((EventManager<Event>) get(obj.getClass())).fireEvent(obj);
    }
}
