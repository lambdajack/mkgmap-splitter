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

import java.util.Iterator;


public class SplitIntMapTest {
	private static final int MAXKEY = 300000;

	/**
	 * Test that values can be added to the map and then retrieved by key.
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

	/**
	 * Check that the size function works.
	 */
	@Test
	public void testSize() {
		SplitIntMap map = populateMap();

		assertEquals("Size", MAXKEY-1, map.size());
	}

	/**
	 * Test that all the values in the map are returned by the iterator.
	 */
	@Test
	public void testFastIterator() {
		SplitIntMap map = populateMap();

		Iterator<IntIntMap.Entry> it = map.fastIterator();

		int[] results = new int[MAXKEY];
		while (it.hasNext()) {
			IntIntMap.Entry entry = it.next();
			int key = entry.getKey();
			int value = entry.getValue();

			assertEquals(0, results[key]);
			assertEquals(valueFunc(key), value);

			results[key] = value;
		}

		for (int i = 1; i < MAXKEY; i++) {
			assertEquals("check all results", valueFunc(i), results[i]);
		}
	}

	/**
	 * Test that all values in the map are returned by the deleting iterator.
	 */
	@Test
	public void testFastDeletingIterator() {
		SplitIntMap map = populateMap();

		Iterator<IntIntMap.Entry> it = map.fastIterator();

		int[] results = new int[MAXKEY];
		while (it.hasNext()) {
			IntIntMap.Entry entry = it.next();
			int key = entry.getKey();
			int value = entry.getValue();

			assertEquals(0, results[key]);
			assertEquals(valueFunc(key), value);

			results[key] = value;
		}

		for (int i = 1; i < MAXKEY; i++) {
			assertEquals("check all results", valueFunc(i), results[i]);
		}
	}

	private SplitIntMap populateMap() {
		SplitIntMap map = new SplitIntMap();
		for (int i = 1; i < MAXKEY; i++)
			map.put(i, valueFunc(i));
		return map;
	}

	private int valueFunc(int i) {
		return i * 2 + 3;
	}
}
