/*
 * Copyright (C) 2008 Steve Ratcliffe
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 or
 * version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
/* Create date: 11-Jul-2009 */
package uk.me.parabola.splitter;

import static org.junit.Assert.assertEquals;
import org.junit.Test;


public class IntIntMapTest {
	private static final int MAXKEY = 100000;

	/**
	 * Test adding a large number of entries to the map.  Check that they can all
	 * be retrieved.
	 */
	@Test
	public void testPut() {
		SplitIntMap map = populateMap();

		int[] results = new int[MAXKEY];

		for (int i = 1; i < MAXKEY; i++) {
			int orig = results[i];
			int res = map.get(i);

			assertEquals("No duplicate", 0, orig);
			assertEquals("Check value", valueFunc(i), res);

			results[i] = res;
		}

		// Check all values were present
		for (int i = 1; i < MAXKEY; i++)
			assertEquals("Correct value", valueFunc(i), results[i]);
	}

	@Test
	public void testEntryIterator() throws Exception {
	}

	private SplitIntMap populateMap() {
		SplitIntMap map = new SplitIntMap();
		for (int i = 1; i < MAXKEY; i++)
			map.put(i, valueFunc(i));
		return map;
	}

	private int valueFunc(int i) {
		return i * 3 - 1100;
	}
}
