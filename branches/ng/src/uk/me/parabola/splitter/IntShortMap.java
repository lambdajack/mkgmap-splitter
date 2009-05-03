/*
 * Copyright (C) 2009 Steve Ratcliffe
 * 
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 3
 *  as published by the Free Software Foundation.
 * 
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 * 
 * Author: Steve Ratcliffe
 * Create date: 08-Dec-2008
 */
package uk.me.parabola.splitter;

import java.util.Iterator;

/**
 * Map using primative values to map an int to a short.
 *
 * It doesn't fully behave the same way that a map would and doesn't implement Map.
 *
 * @author Steve Ratcliffe
 */
public class IntShortMap {
	private static final int INIT_SIZE = 1 << 16;

	private int size;
	private int[] keys;
	private short[] values;

	private int capacity;

	private int targetSize;
	private final float loadFactor;
	//private int hit;
	//private int miss;

	private static final int OFF = 7;

	public IntShortMap() {
		this(INIT_SIZE, 0.9f);
	}

	public IntShortMap(int initCap, float load) {
		keys = new int[initCap];
		values = new short[initCap];
		capacity = initCap;

		loadFactor = load;
		targetSize = (int) (initCap * load);
		assert targetSize > 0;
	}


	public int size() {
		return size;
	}

	public int get(int key) {
		Integer ind = keyPos(key);
		if (ind == null)
			return 0;

		return values[ind];
	}

	public int put(int key, short value) {
		ensureSpace();

		int ind = keyPos(key);
		keys[ind] = key;

		int old = values[ind];
		if (old == 0)
			size++;
		values[ind] = value;

		return old;
	}

	public Iterator<Entry> entryIterator() {
		return new Iterator<Entry>() {
			private final Entry entry = new Entry();
			private int itercount;

			public boolean hasNext() {
				while (itercount < capacity)
					if (values[itercount++] != 0)
						return true;
				return false;
			}

			public Entry next() {
				entry.setKey(keys[itercount-1]);
				entry.setValue(values[itercount-1]);
				return entry;
			}

			public void remove() {
				throw new UnsupportedOperationException();
			}
		};

	}

	private void ensureSpace() {
		while (size + 1 >= targetSize) {
			int ncap = capacity*2;
			targetSize = (int) (ncap * loadFactor);

			int[] okey = keys;
			short[] oval = values;

			size = 0;
			keys = new int[ncap];
			values = new short[ncap];
			capacity = ncap;
			//hit= miss = 0;
			for (int i = 0; i < okey.length; i++) {
				int k = okey[i];
				if (k != 0)
					put(k, oval[i]);
			}
		}
		assert size < capacity;
	}

	private int keyPos(int key) {
		int k = key & (capacity - 1);

		int h1 = keys[k];
		if (h1 != 0 && h1 != key) {
			for (int k2 = k+OFF; ; k2+= OFF) {
				//miss++;
				if (k2 >= capacity)
					//noinspection AssignmentToForLoopParameter
					k2 -= capacity;

				int fk = keys[k2];
				if (fk == 0 || fk == key) {
					//hit++;
					//if ((size % 100000) == 0)
					//	System.out.printf("hit/miss %f at size %d, %d\n",  100.0*hit/(miss - hit), size, targetSize);

					return k2;
				}
			}
		}
		return k;
	}

	/**
     * An primative short version of the Map.Entry class.
	 *
	 * @author Steve Ratcliffe
	 */
	public static class Entry {
		private int key;
		private short value;

		public int getKey() {
			return key;
		}

		void setKey(int key) {
			this.key = key;
		}

		public int getValue() {
			return value;
		}

		void setValue(short value) {
			this.value = value;
		}
	}
}