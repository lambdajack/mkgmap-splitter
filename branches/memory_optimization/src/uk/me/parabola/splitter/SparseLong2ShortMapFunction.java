package uk.me.parabola.splitter;

import it.unimi.dsi.fastutil.longs.Long2ShortFunction;

/**
 * Stores long/short pairs. 
 * Inspired by SparseInt2ShortMapInline.
 * Key values are limited to a maximum of 2^37 - 1.
 * 
 * @author GerdP
 *
 */
interface SparseLong2ShortMapFunction extends Long2ShortFunction {
	static final int CHUNK_SIZE = 64; // MUST be <= 64.
	static final long MAX_KEY = (long) (CHUNK_SIZE * (long) Integer.MIN_VALUE * -1L) - 1;  // 2^37 - 1 
	
	public void stats(int msgLevel);
}
