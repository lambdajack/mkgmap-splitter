/*
 * Copyright (c) 2011.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */

package uk.me.parabola.splitter;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.BitSet;
import java.util.HashMap;

/**
 * A map that stores all areas in which a node or way ID occurs.
 * 
 */
public class Node2AreaMap {
	private int size;
	private final short unassigned = Short.MIN_VALUE;
	private final int numAreas;
	private static int DICT_START = -1 * (Short.MIN_VALUE + 1);
	

	private SparseLong2ShortMapFunction map;
	private final ObjectArrayList<BitSet> dictionary; 
	private final HashMap<BitSet, Integer> index;
	private BitSet btst;

	public Node2AreaMap(int numAreas, boolean optimizeMem) {
		System.out.println("Making Node2AreaMap");		
		this.numAreas = numAreas;
		map = new SparseLong2ShortMapInline (optimizeMem);
		map.defaultReturnValue(unassigned);
		dictionary = new ObjectArrayList<BitSet>();
		index = new HashMap<BitSet, Integer>();
		btst = new BitSet();

		// init the dictionary
		for (int i=0;i <= numAreas; i++){
			BitSet b = new BitSet();
			b.set (i);
			dictionary.add(b);
			int combiIndex = i - DICT_START; 
			index.put( b, combiIndex);
		}
	}

	public void addTo(long key, BitSet out) {
		int idx = map.get (key);
		if (idx == unassigned)
			return;
		idx += DICT_START;
		if (idx > numAreas)
		out.or(dictionary.get(idx));
		else
			out.set(idx);
		//System.out.println(key+":"+out.toString());
	}


	
	public void put(long key, short val) {
		//System.out.println("p"+key+","+val);

		// if the value is new, we store it directly as 
		// it is the index to our dictionary
		
		int idx = map.putIfAbsent (key, (short) (val-DICT_START));
		if (idx == unassigned) {
			size++;
			if (size %1000000 == 0) {
				stats(0);
			}
			//map.put (key, val);
		}
		else {
			// unlikely: node or way belongs to multiple areas
			idx += DICT_START;
			btst.clear();
			if (idx > numAreas)
			btst.or(dictionary.get(idx));
			else 
				btst.set(idx);
			btst.set(val);

			Integer combiIndex = index.get(btst);
			if (combiIndex == null){
				// very unlikely:
				// this is a new combination of areas, create new entry 
				// in the dictionary
				BitSet bnew = new BitSet();
				bnew.or(btst); 

				combiIndex = dictionary.size() - DICT_START;
				if (combiIndex > Short.MAX_VALUE){
					throw new RuntimeException("Dictionary is full. Either decrease --max-areas value or increase --max-nodes");
				}
				dictionary.add(bnew);
				index.put(bnew, combiIndex);
				//System.out.println("new combination " + combiIndex + " " + bnew.toString());
			}
			map.put (key, (short) (combiIndex & 0xffff));
		}

	}

	public boolean contains (long key) {
		return map.containsKey(key);
	}


	public int size() {
		return size;
	}

	public void stats(int msgLevel) {
		System.out.println("MAP occupancy: " + Utils.format(size) + ", number of area dictionary entries: " + dictionary.size() + " of " + ((1<<16) - 1));
		map.stats(msgLevel);
	}
}
