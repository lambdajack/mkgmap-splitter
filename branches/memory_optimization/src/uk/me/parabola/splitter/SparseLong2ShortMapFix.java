package uk.me.parabola.splitter;

import it.unimi.dsi.bits.Fast;

import java.util.Arrays;

/**
 * Stores long/short pairs, optimized for speed. 
 * Inspired by SparseInt2ShortMapInline, but uses fixed arrays instead of ArrayList.
 * Key values are limited to a maximum of 2^37 - 1.
 * 
 * @author GerdP
 *
 */
public class SparseLong2ShortMapFix implements SparseLong2ShortMapFunction {
	static final int POOL_SIZE = 8191;
	private final long maxNodeId;
	private final long nodeCount;

	/** What to return on unassigned indices */
	short unassigned = -1;
	private long [] countChunkLen;
	final long initsize;
	
	private int poolIndex = 0;
	private int countPools = 0;
	private short[][] pool;

	private long[] maskmap;
	private short[][] chunkmap;

	long size;

	SparseLong2ShortMapFix(long nodeCount, long maxNodeId) {
		this.initsize = maxNodeId/CHUNK_SIZE;
		this.maxNodeId = maxNodeId;
		this.nodeCount = nodeCount;
		clear();
	}

	void arrayPush(short[] array, int index) {
		for (int j = array.length - 1; j > index; j--)
			array[j] = array[j - 1];
	}

	void arrayCopyFill(short[] from, short[] to) {
		int j = 0;
		for (; j < from.length; j++)
			to[j] = from[j];
		for (; j < to.length; j++)
			to[j] = unassigned;
	}

	short[] chunkAdd(short[] array, int index, short val) {
		if (array[array.length - 1] != unassigned) {
			// give it back to my pool ?
			if (array.length == 4 & poolIndex > 0)
				pool[--poolIndex] = array;
			//System.out.println("F:poolIndex:" + poolIndex);
			--countChunkLen[array.length];
			short tmp[] = new short[array.length + 4];
			arrayCopyFill(array, tmp);
			array = tmp;
			++countChunkLen[array.length];
		}
		arrayPush(array, index);
		array[index] = val;
		return array;
	}

	short[] chunkMake() {
		short[] out;
		if (pool[poolIndex] != null){
			out = pool[poolIndex];
			pool[poolIndex] = null;
			if (poolIndex < POOL_SIZE) 
				++poolIndex;
			else {
				//System.out.println("pool is full :-(");
				makeNewPool();
			}
		}
		else
			out = new short[4];

		Arrays.fill(out, unassigned);
		++countChunkLen[out.length];
		return out;
	}

	void chunkSet(short[] array, int index, short val) {
		array[index] = val;
	}

	short chunkGet(short[] array, int index) {
		return array[index];
	}

	/**
	 * Count how many of the lowest X bits in mask are set
	 * 
	 * @return
	 */
	int countUnder(long mask, int lowest) {
		return Fast.count(mask & ((1L << lowest) - 1));
	}

	@Override
	public boolean containsKey(long key) {
		if (key >= MAX_KEY) {
			throw new IllegalArgumentException(
					"Cannot handle such key, is too high. key=" + key);
		}
		int chunkid = (int) (key / CHUNK_SIZE);
		long chunkmask = maskmap[chunkid];

		if (chunkmask == 0)
			return false;
		int chunkoffset = (int) (key % CHUNK_SIZE);

		long elementmask = 1L << chunkoffset;
		return (chunkmask & elementmask) != 0;
	}

	@Override
	public short put(long key, short val) {
		if (val == unassigned) {
			throw new IllegalArgumentException(
					"Cannot store the value that is reserved as being unassigned. val="
							+ val);
		}
		if (key < 0) {
			throw new IllegalArgumentException("Cannot store the negative key,"
					+ key);
		} else if (key >= MAX_KEY) {
			throw new IllegalArgumentException(
					"Cannot handle such key, is too high. key=" + key);
		}
		short chunk[];
		int chunkid = (int) (key / CHUNK_SIZE);
		int chunkoffset = (int) (key % CHUNK_SIZE);

		long chunkmask = maskmap[chunkid];
		if (chunkmask == 0){
			//chunkmask = 0;
			chunk = chunkMake();
		}
		else 
			chunk = chunkmap[chunkid];
		
		long elementmask = 1L << chunkoffset;
		if ((chunkmask & elementmask) != 0) {
			// Already in the array, find the offset and store.
			short out = chunkGet(chunk,
					countUnder(chunkmask, chunkoffset));
			chunkSet(chunk, countUnder(chunkmask, chunkoffset), val);
			// System.out.println("Returning found key "+out+" from put "+ key +
			// " " + val);
			return out;
		} else {
			size++;
			// Not in the array. Time to insert.
			int offset = countUnder(chunkmask, chunkoffset);
			chunk = chunkAdd(chunk, offset, val);
			chunkmask |= elementmask;
			maskmap[chunkid] = chunkmask;
			chunkmap[chunkid] = chunk;
			return unassigned;
		}
	}

	@Override
	public short get(long key) {
		if (key >= MAX_KEY) {
			throw new IllegalArgumentException(
					"Cannot handle such key, is too high. key=" + key);
		}
		
		int chunkid = (int) (key / CHUNK_SIZE);
		long chunkmask = maskmap[chunkid];
		if (chunkmask == 0)
			return unassigned;
		int chunkoffset = (int) key % CHUNK_SIZE;
		long elementmask = 1L << chunkoffset;
		if ((chunkmask & elementmask) == 0) {
			return unassigned;
		} else {
			short [] chunk = chunkmap[chunkid];
			return chunkGet(chunk, countUnder(chunkmask, chunkoffset));
		}
	}

	@Override
	public void clear() {
		chunkmap = new short [(int)(this.maxNodeId /CHUNK_SIZE)+1][];
		countChunkLen = new long[CHUNK_SIZE+1];
		maskmap = new long[(int)(this.maxNodeId /CHUNK_SIZE)+1];
		makeNewPool();
		size = 0;
	}

	@Override
	public short remove(long key) {
		throw new UnsupportedOperationException("TODO: Implement");
	}

	@Override
	public boolean containsKey(Object arg0) {
		throw new UnsupportedOperationException("TODO: Implement");
	}

	@Override
	public Short get(Object arg0) {
		throw new UnsupportedOperationException("TODO: Implement");
	}

	@Override
	public Short put(Long arg0, Short arg1) {
		return put(arg0.intValue(), arg1.shortValue());
	}

	@Override
	public Short remove(Object arg0) {
		throw new UnsupportedOperationException("TODO: Implement");
	}
	
	@Override
	public int size() {
		return (int) size; 
	}
	
	@Override
	public short defaultReturnValue() {
		return unassigned;
	}

	@Override
	public void defaultReturnValue(short arg0) {
		unassigned = arg0;
	}
	
	private void makeNewPool(){
		pool = new short[POOL_SIZE+1][4];
		poolIndex = 0;
		++countPools;
	}

	@Override
	public void stats() {
		long usedChunks = 0;
		long pctusage = 0L;
		for (int i=4; i<=CHUNK_SIZE; i+=4) {
			usedChunks += countChunkLen[i];
			if (countChunkLen[i] > 0) {
				//	System.out.println("type-" + i + " chunks: " + Utils.format(countChunkLen[i]));
			}
		}
		if (maskmap.length > 0) 
			pctusage = Math.round((double)usedChunks*100/maskmap.length) ;

		System.out.println("chunk map details: Used " + Utils.format(usedChunks) + " of " + Utils.format(maskmap.length) + " allocated map entries (~ " + pctusage + "%)");
	}
}	

