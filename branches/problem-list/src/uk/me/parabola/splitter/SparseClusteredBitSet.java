/*
 * Copyright (C) 2012.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 or
 * version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */ 
package uk.me.parabola.splitter;

import java.util.BitSet;
import java.util.HashMap;

/** A simple partly BitSet implementation optimized for memory 
 * when used to store very large values with a high likelihood 
 * that the stored values build groups like e.g. the OSM node IDs. 
 * 
 * author GerdP */ 
public class SparseClusteredBitSet{
	static final int CHUNK_MASK = 2048-1; // seems to be good value for OSM node IDs 						
	static final long TOP_ID_MASK = ~CHUNK_MASK;  
	private static final int TOP_ID_SHIFT = Long.numberOfTrailingZeros(TOP_ID_MASK);  

	private HashMap<Long,BitSet> topMap = new HashMap<Long, BitSet>();
	private int setBits;
  
  public void set(long key){
      long topId = key >> TOP_ID_SHIFT;
      int bitPos =(int)(key & CHUNK_MASK);
        
      BitSet chunk = topMap.get(topId);
      if (chunk == null){
    	  chunk = new BitSet();
    	  topMap.put(topId, chunk);
      } else {
    	  if (chunk.get(bitPos))
    		  return;
      }
      chunk.set(bitPos);
      ++setBits;
  }

  public void clear(long key){
	  long topId = key >> TOP_ID_SHIFT;
      BitSet chunk = topMap.get(topId);
      if (chunk == null)
          return;
      int bitPos =(int)(key & CHUNK_MASK);
      
	  if (!chunk.get(bitPos))
		  return;
	  chunk.clear(bitPos);
      if (chunk.isEmpty())
    	  topMap.remove(topId);
      --setBits;
  }
  
  public boolean get(long key){
	  long topId = key >> TOP_ID_SHIFT;
      BitSet chunk = topMap.get(topId);
      if (chunk == null)
          return false;
      int bitPos =(int)(key & CHUNK_MASK);
      return chunk.get(bitPos); 
  }

  public void clear(){
	  topMap.clear();
	  setBits = 0;
  }
  
  public int cardinality(){
	  return setBits;
  }
}

                                                                           