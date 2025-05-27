/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilsTests {

	@Test
	void testHasText() {
		assertFalse(Utils.hasText(null));
		assertFalse(Utils.hasText(""));
		assertFalse(Utils.hasText(" "));
		assertTrue(Utils.hasText("test"));
	}

	@Test
	void testCollectionIsEmpty() {
		assertTrue(Utils.isEmpty((Collection<?>) null));
		assertTrue(Utils.isEmpty(Collections.emptyList()));
		assertFalse(Utils.isEmpty(Collections.singletonList("test")));
	}

	@Test
	void testMapIsEmpty() {
		assertTrue(Utils.isEmpty((Map<?, ?>) null));
		assertTrue(Utils.isEmpty(Collections.emptyMap()));
		
		Map<String, String> singleEntryMap = new HashMap<>();
		singleEntryMap.put("key", "value");
		assertFalse(Utils.isEmpty(singleEntryMap));
	}

}
