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

// a map that stores all areas in which a node or way occurs 
public class Node2AreaMap {
	private int size;
	private final short unassigned;

	private SparseInt2ShortMapInline map;   
	private final ObjectArrayList<BitSet> dictionary; 
	private final HashMap<BitSet, Short> index;

	
	public Node2AreaMap(short unassigned, int numareas) {
		System.out.println("Making Node2AreaMap");
		map = new SparseInt2ShortMapInline ();
		this.unassigned = unassigned;
		map.defaultReturnValue(unassigned);
		dictionary = new ObjectArrayList<BitSet>();
		index = new HashMap<BitSet, Short>();
		
		// init the dictionary
		for (short i=0;i <= numareas; i++){
			BitSet b = new BitSet();
			b.set (i);
			dictionary.add(b);
			index.put( b, i);
		}
	}
	
	public void addTo(int key, BitSet out) {
		int idx = map.get (key);
		if (idx == unassigned)
			return;
		out.or(dictionary.get(idx));
		//System.out.println(key+":"+out.toString());
}

	
	public void put(int key, short val) {
		//System.out.println("p"+key+","+val);

     
		int idx = map.get (key);
		BitSet bnew;
		if (idx == unassigned) {
			size++;
			if (size %1000000 == 0) {
				stats();
			}
			// node is new -> val is equal index
			map.put (key,val); 
		}
		else {
			// unlikely: node (or way) belongs to multiple areas
			bnew = (BitSet) dictionary.get(idx).clone();
			bnew.set(val);
			
			Short combiIndex = index.get(bnew);
			if (combiIndex == null){
				// this is a new conbination of areas, create new entry 
				// in the dictionary
				combiIndex = (short) dictionary.size();
				dictionary.add(bnew);
				index.put(bnew, combiIndex);
				//System.out.println("new combination " + combiIndex + " " + bnew.toString());
			}
			map.put (key, (short) combiIndex);
		}
		
	}
	
	public boolean contains (int key) {
		return map.containsKey(key);
	}
	
	
	public int size() {
		return size;
	}
	
	public void stats() {
		System.out.println("MAP occupancy: " + Utils.format(size) + ", number of area dictionary entries: " + dictionary.size());
		//map.stats();
	}
}
