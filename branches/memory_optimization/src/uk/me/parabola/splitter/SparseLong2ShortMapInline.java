package uk.me.parabola.splitter;

import it.unimi.dsi.bits.Fast;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * SparseLong2ShortMapFunction optimized for low memory requirements.
 * 
 * @author GerdP
 *
 */
public class SparseLong2ShortMapInline implements SparseLong2ShortMapFunction{
	static final int MASK_SIZE = 4;	// number of chunk elements used to store mask
	static final int POOL_SIZE = 8191;
	
	/** What to return on unassigned indices */
	short unassigned = -1;
	private long [] countChunkLen;
	final int initsize;
	
	// we use a simple pool to be able to reuse allocated small chunks
	private short[][] pool;
	private int poolIndex = 0;
	
	private Int2ObjectOpenHashMap<short[]> chunkmap;

	int size;

	SparseLong2ShortMapInline(long nodeCount, long MaxNodeId) {
		// estimate the needed elements
		this.initsize = (int) (nodeCount/(CHUNK_SIZE/8));
		clear();
		
	}

	void arrayPush(short[] array, int index) {
		for (int j = array.length - 1; j > index + MASK_SIZE; j--)
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
			if (array.length == 8 & poolIndex > 0)
				pool[--poolIndex] = array; // move chunk back into the pool
			--countChunkLen[array.length];
			short tmp[] = new short[array.length + 4];
			arrayCopyFill(array, tmp);
			array = tmp;
			++countChunkLen[array.length];
		}
		arrayPush(array, index);
		array[index + MASK_SIZE] = val;
		return array;
	}

	short[] chunkMake() {
		short[] out;

		if (pool[poolIndex] != null){
			out = pool[poolIndex];
			storeMask(0L, out);
			pool[poolIndex] = null;
			if (poolIndex < POOL_SIZE) 
				++poolIndex;
			else {
				// chunks from current pool are now all in use by the chunkmap. 
				makeNewPool();
			}
		}
		else
			out = new short[8];
		/* fill last for bytes , the first 4 bytes are used for chunkmask */
		for (int j = out.length-1; j >= MASK_SIZE; j--)
			out[j] = unassigned; 
		++countChunkLen[out.length];
		return out;
	}

	void chunkSet(short[] array, int index, short val) {
		array[index + MASK_SIZE] = val;
	}

	short chunkGet(short[] array, int index) {
		assert index+MASK_SIZE < array.length;
		return array[index + MASK_SIZE];
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
		short [] chunk = chunkmap.get(chunkid);
		if (chunk == null) 
			return false;
		long chunkmask = extractMask(chunk);
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

		int chunkid = (int) (key / CHUNK_SIZE);
		int chunkoffset = (int) (key % CHUNK_SIZE);
		short [] chunk = chunkmap.get(chunkid);
		if (chunk == null)
			chunk = chunkMake();
		
		long chunkmask = extractMask(chunk);
		
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
			storeMask(chunkmask, chunk);
			chunkmap.put(chunkid, chunk);
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
		short [] chunk = chunkmap.get(chunkid);
		if (chunk == null) 
			return unassigned;
		long chunkmask = extractMask(chunk);
		int chunkoffset = (int) key % CHUNK_SIZE;
		long elementmask = 1L << chunkoffset;
		if ((chunkmask & elementmask) == 0) {
			return unassigned;
		} else {
			return chunkGet(chunk, countUnder(chunkmask, chunkoffset));
		}
	}

	@Override
	public void clear() {
		chunkmap = new Int2ObjectOpenHashMap<short[]>(initsize);
		countChunkLen = new long[CHUNK_SIZE + MASK_SIZE + 1 ];
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
		return size;
	}

	@Override
	public short defaultReturnValue() {
		return unassigned;
	}

	@Override
	public void defaultReturnValue(short arg0) {
		unassigned = arg0;
	}
	
	private void storeMask (long mask, short[] chunk) {
			// store chunkmask in chunk
			long tmp = mask;
			if (tmp == 32768)
				tmp = 32768;
			for (int i = 0; i < MASK_SIZE; i++){ 
				chunk[i] = (short) (tmp & 0xffffL);
				tmp = (tmp >> 16);
	}
			}
	private long extractMask(short [] chunk){
		long mask = 0;
		for (int i = 0; i < MASK_SIZE; i++) {
			mask |= (chunk[3-i] & 0xffff);
			if (i <= 2)
				mask = (mask << 16);
		}
		return mask;
	}

	private void makeNewPool(){
		pool = new short[POOL_SIZE+1][8];
		poolIndex = 0;
	}
	
	@Override
	public void stats() {
		long usedChunks = 0;
		long pctusage = 0;
		for (int i=4; i <=CHUNK_SIZE + MASK_SIZE; i+=4) {
			usedChunks += countChunkLen[i];
			if (countChunkLen[i] > 0) {
				//	System.out.println("type-" + i + " chunks: " + Utils.format(countChunkLen[i]));
			}
		}
		if (initsize > 0) 
			pctusage = Math.round((double)usedChunks*100/initsize) ;
		System.out.println("chunk map details: Used " + Utils.format(chunkmap.size()) + " (~ " + pctusage + "%) of the estimated " + initsize + " map entries." );
	}
}	

