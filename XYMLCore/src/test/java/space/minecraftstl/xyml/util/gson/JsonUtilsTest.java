// SPDX-FileCopyrightText: Copyright (C) 2024 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util.gson;

import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static space.minecraftstl.xyml.util.gson.JsonUtils.listTypeOf;
import static space.minecraftstl.xyml.util.gson.JsonUtils.mapTypeOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Glavo
 */
public class JsonUtilsTest {

    @Test
    public void testGetTypeToken() {
        assertEquals(new TypeToken<List<Object>>(){}, listTypeOf(Object.class));
        assertEquals(new TypeToken<List<String>>(){}, listTypeOf(String.class));
        assertEquals(new TypeToken<List<Map<String, Integer>>>(){}, listTypeOf(mapTypeOf(String.class, Integer.class)));
        assertEquals(new TypeToken<List<Map<String, List<Integer>>>>(){}, listTypeOf(mapTypeOf(String.class, listTypeOf(Integer.class))));
    }
}
