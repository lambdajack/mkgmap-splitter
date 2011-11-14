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

/// a map that stores all areas in which a node occurs.
public class Node2AreaMap {
	private int size;
	private final short unassigned = -1;

	private SparseLong2ShortMapFunction map;
	private final ObjectArrayList<BitSet> dictionary; 
	private final HashMap<BitSet, Short> index;
	private BitSet btst;

	public Node2AreaMap(int numareas, boolean optimizeMem) {
		System.out.println("Making Node2AreaMap");		
		map = new SparseLong2ShortMapInline (optimizeMem);
		map.defaultReturnValue(unassigned);
		dictionary = new ObjectArrayList<BitSet>();
		index = new HashMap<BitSet, Short>();
		btst = new BitSet();

		// init the dictionary
		for (short i=0;i <= numareas; i++){
			BitSet b = new BitSet();
			b.set (i);
			dictionary.add(b);
			index.put( b, i);
		}
	}

	public void addTo(long key, BitSet out) {
		int idx = map.get (key);
		if (idx == unassigned)
			return;
		out.or(dictionary.get(idx));
		//System.out.println(key+":"+out.toString());
	}


	public void put(long key, short val) {
		//System.out.println("p"+key+","+val);

		int idx = map.get (key);
		if (idx == unassigned) {
			size++;
			if (size %1000000 == 0) {
				stats(0);
			}
			// node is new -> val is equal index
			map.put (key,val); 
		}
		else {
			// unlikely: node or way belongs to multiple areas
			btst.clear();
			btst.or(dictionary.get(idx));
			btst.set(val);

			Short combiIndex = index.get(btst);
			if (combiIndex == null){
				// very unlikely:
				// this is a new combination of areas, create new entry 
				// in the dictionary
				BitSet bnew = new BitSet();
				bnew.or(btst); 

				combiIndex = (short) dictionary.size();

				dictionary.add(bnew);
				index.put(bnew, combiIndex);
				//System.out.println("new combination " + combiIndex + " " + bnew.toString());
			}
			map.put (key, (short) combiIndex);
		}

	}

	public boolean contains (long key) {
		return map.containsKey(key);
	}


	public int size() {
		return size;
	}

	public void stats(int msgLevel) {
		System.out.println("MAP occupancy: " + Utils.format(size) + ", number of area dictionary entries: " + dictionary.size());
		map.stats(msgLevel);
	}
}
