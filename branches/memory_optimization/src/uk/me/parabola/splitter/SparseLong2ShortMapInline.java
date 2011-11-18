package uk.me.parabola.splitter;

import it.unimi.dsi.bits.Fast;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Arrays;

/**
 * SparseLong2ShortMapInline implements SparseLong2ShortMapFunction 
 * optimized for low memory requirements and 
 * inserts in sequential order (lowest indexes first).
 *
 * Inspired by SparseInt2ShortMapInline.
 * Key values are limited to a maximum of 2^37 - 1.
 * 
 * A chunk stores up to CHUNK_SIZE values and a bit-mask. The bit-mask is used
 * to separate used and unused entries in the chunk. Thus, the chunk length 
 * depends on the number of used entries, not on the highest used entry.
 * A typical (uncompressed) chunk looks like this:
 * {m1,m2,m3,m4,v1,v1,v1,v1,v1,v1,v2,v2,v2,v2,v1,v1,v1,v1,v1,u,?,?,...}
 * m1-m4: the bit-mask
 * v1,v2: values stored in the chunk
 * u: "unassigned" value
 * ?: anything
 * 
 * After applying Run Length Encryption on this the chunk looks like this:
 * {m1,m2,m3,m4,u,6,v1,4,v2,5,v1,?,?,?}
 * The unassigned value on index 5 signals a compressed chunk.
 * 
 * An (uncompressed)  ONE_VALUE_CHUNK may look like this:
 * {m1,m2,m3,m4,v1,v1,v1,v1,v1,v1,v1,v1,v1,v1,v1,u,?,?,...}
 * This is stored without run length info in the shortest possible trunk:
 * {m1,m2,m3,m4,u,v1}
 * 
 * Fortunately, OSM data is distributed in a way that most(!) chunks contain
 * just one distinct value, so most chunks can be stored in 24 or 32 bytes
 * instead of 152 bytes for the worst case (counting also the padding bytes).
 */
public class SparseLong2ShortMapInline implements SparseLong2ShortMapFunction{
	static final int CHUNK_SIZE = 64; // MUST be <= 64.
	static final long MAX_KEY = (long) (CHUNK_SIZE * (long) Integer.MIN_VALUE * -1L) - 1;  // 2^37 - 1 
	static final int MASK_SIZE = 4;	// number of chunk elements used to store mask
	static final int ONE_VALUE_CHUNK_SIZE = MASK_SIZE+2; 
	static final int SIZE_INCR = 1024;
	static final long INIT_SIZE = 1L<<31; // maximum ID that can be saved without re-sizing 

	static final int SPARSE_CHUNK_VECTOR_SIZE = 16; 

	enum Method {array, sparsearray};
	private final Method method; 

	private ObjectArrayList<short[]> chunkvector;	// directly addresses chunks
	private ObjectArrayList<short[][]> topvector;	// addresses vectors of references of chunks 
	int [] paddedLen = new int[CHUNK_SIZE+MASK_SIZE];

	int size;

	int oldChunkId = -1;
	short [] oldChunk = null; 
	short [] currentChunk = new short[CHUNK_SIZE+MASK_SIZE]; 
	short [] tmpWork = new short[CHUNK_SIZE+MASK_SIZE]; 
	short [] RLEWork = new short[CHUNK_SIZE+MASK_SIZE];

	/** What to return on unassigned indices */
	short unassigned = -1;
	int capacity = 0; // available chunks in vector 

	// for statistics
	long countSparseChunk = 0;
	private long [] countChunkLen; 
	long compressed = 0;
	long countTryRLE = 0;
	long expanded = 0;
	long highestStoredKey = -1;
	long uncompressedLen = 0;
	long compressedLen = 0;

	/**
	 * A map that stores pairs of (OSM) IDs and short values identifying the
	 * areas in which the object (node,way) with the ID occurs.
	 *  
	 * @param optimizeMem: true -> expect rather few IDs (sparse vector), 
	 * false -> expect large number of nodes
	 * 						
	 */
	SparseLong2ShortMapInline(boolean optimizeMem) {
		if (optimizeMem)
			method = Method.sparsearray;
		else 
			method = Method.array;
		// good chunk length is a value that gives (12+ 2 * chunk.length) % 8 == 0
		// that means chunk.length values 6,10,14,..,66 are nice
		for (int i=0; i<paddedLen.length; i++){
			int plen = i;
			while ((plen+2) % 4 != 0)  plen++;
			paddedLen[i] = Math.min(plen, CHUNK_SIZE+MASK_SIZE);
		}
		clear();

	}

	/**
	 * Get the value from a (compressed) chunk
	 * @param array: 
	 * @param index: 
	 * @return
	 */
	private short chunkGet(short[] array, int index) {
		//assert index+MASK_SIZE < array.length;
		// if the first value is unassigned we have a compressed chunk
		if (array[MASK_SIZE] == unassigned){
			if (array.length == ONE_VALUE_CHUNK_SIZE) {
				//this is a one-value-chunk
				return array[MASK_SIZE+1];
			}
			else 
			{
			short len;
			int x = index;
			for (int j=MASK_SIZE+1; j < array.length; j+=2){
				len =  array[j];
				x -= len;
				if (x < 0) return array[j+1];
			}
			return unassigned; // should not happen
		}
		}
		else
			return array[index + MASK_SIZE];
	}


	/**
	 * Count how many of the lowest X bits in mask are set
	 * 
	 * @return
	 */
	private int countUnder(long mask, int lowest) {
		return Fast.count(mask & ((1L << lowest) - 1));
	}

	/**
	 * retrieve (compressed) chunk and expand it into the work buffer
	 * @param array
	 */
	private void fillCurrentChunk(short [] array) {
		long mask = extractMask(array);
		long elementmask = 0;

		++expanded;
		Arrays.fill(currentChunk, unassigned);
		if (array[MASK_SIZE] == unassigned){
			int opos = 0;
			if (array.length == ONE_VALUE_CHUNK_SIZE) {
				// decode one-value-chunk
				short val = array[MASK_SIZE+1];
				elementmask = 1;
				for (opos = 0; opos<CHUNK_SIZE; opos++){
					if ((mask & elementmask) != 0)
						currentChunk[opos] = val;
					elementmask <<= 1;
				}
			}
			else {
				// decode RLE-compressed chunk with multiple values
			short ipos = MASK_SIZE+1;
			short len = array[ipos++];
			short val = array[ipos++];
			while (len > 0){
					while (len > 0 && opos < currentChunk.length){
					if ((mask & 1L << opos) != 0){ 
						currentChunk[opos] = val; 
						--len;
					}
					++opos;
						if (opos == 68)
							opos = opos;
				}
				if (ipos+1 < array.length){
					len = array[ipos++];
					val = array[ipos++];
				}
				else len = -1;
			}
		}
		}
		else {
			// decode uncompressed chunk
			int ipos = MASK_SIZE;
			elementmask = 1;
			for (int opos=0; opos < CHUNK_SIZE; opos++) {
				if ((mask & elementmask) != 0) 
					currentChunk[opos] = array[ipos++];
				elementmask <<= 1;
			}
		}
	}

	/**
	 * Try to use Run Length Encoding to compress the chunk stored in tmpWork. In most
	 * cases this works very well because chunks often have only one 
	 * or two distinct values.
	 * @param maxlen: number of elements in the chunk. 
	 * @return -1 if compression doesn't save space, else the number of elements in the 
	 * compressed chunk stored in buffer RLEWork.
	 */
	private int chunkCompressRLE (int maxlen){
		int opos = MASK_SIZE + 1;
		++countTryRLE;
        for (int i = MASK_SIZE; i < maxlen; i++) {
            short runLength = 1;
            while (i+1 < maxlen && tmpWork[i] == tmpWork[i+1]) {
                runLength++;
                i++;
            }
            if (opos+2 >= tmpWork.length) 
            	return -1;
            RLEWork[opos++] = runLength;
            RLEWork[opos++] = tmpWork[i]; 
        }
        if (RLEWork[opos-1] == unassigned)
        	opos -= 2;
		if (opos > ONE_VALUE_CHUNK_SIZE+1){
			// cosmetic: fill unused bytes with 0
			//while(opos < maxlen && (opos+2) % 4 != 0) 
				//RLEWork[opos++] = 0;
			if (opos < maxlen)
				opos = paddedLen[opos];
		}
		else {
			// special case: the chunk contains only one distinct value
			// we can store this in a length-6 chunk because we don't need
			// the length counter
			RLEWork[MASK_SIZE+1] = RLEWork[MASK_SIZE+2];
			opos = ONE_VALUE_CHUNK_SIZE;
		}

        if (opos < maxlen){
			RLEWork[MASK_SIZE] = unassigned; // signal a compressed record
    		++compressed;
        	return opos;
        }
        else 
        	return -1;
	}
	/**
	 * Try to compress the chunk in currentChunk and store it in the chunk vector.
	 */
	private void saveCurrentChunk(){
		long mask = 0;
		int RLELen = -1;
		int opos = MASK_SIZE;
		long elementMask = 1L;
		short [] chunkToSave;
		for (int j=0; j < CHUNK_SIZE; j++){
			if (currentChunk[j] != unassigned) {
				mask |= elementMask;
				tmpWork[opos++] = currentChunk[j];
			}
			elementMask <<= 1;
		}
		// good chunk length is a value that gives (12+2*opos) % 8 == 0
		// that means opos values 6,10,14,..,66, are nice
		//while(opos < tmpWork.length && (opos+2) % 4 != 0) 
			//tmpWork[opos++] = unassigned;
		int saveOpos = opos;
		if (opos < tmpWork.length){
			tmpWork[opos] = unassigned;
			opos = paddedLen[opos];
		}
		uncompressedLen += opos;
		if (opos > ONE_VALUE_CHUNK_SIZE)
			RLELen =  chunkCompressRLE(saveOpos);
		if (RLELen > 0){
			chunkToSave = RLEWork;
			opos = RLELen;
		}
		else
			chunkToSave = tmpWork;
		compressedLen += opos;
		oldChunk = new short[opos];
		System.arraycopy(chunkToSave, MASK_SIZE, oldChunk, MASK_SIZE, opos-MASK_SIZE);
		storeMask(mask, oldChunk);
		putChunk(oldChunkId,oldChunk);
		++countChunkLen[oldChunk.length];
	}

	@Override
	public boolean containsKey(long key) {
		if (key >= MAX_KEY) {
			throw new IllegalArgumentException(
					"Cannot handle such key, is too high. key=" + key);
		}
		int chunkid = (int) (key / CHUNK_SIZE);
		short [] chunk = getChunk(chunkid);
		if (chunk == null) 
			return false;
		long chunkmask = extractMask(chunk);
		int chunkoffset = (int) (key % CHUNK_SIZE);

		long elementmask = 1L << chunkoffset;
		return (chunkmask & elementmask) != 0;
	}


	@Override
	public short put (long key, short val) {
		return this._put(key, val, false);
	}

	@Override
	public short putIfAbsent (long key, short val) {
		return this._put(key, val, true);
	}

	public short _put(long key, short val, boolean putIfAbsent) {
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

		if (key > highestStoredKey)
			highestStoredKey = key;

		int chunkid = (int) (key / CHUNK_SIZE);
		int chunkoffset = (int) (key % CHUNK_SIZE);
		short out;
		if (oldChunkId == chunkid){
			out = currentChunk[chunkoffset];
			if (!putIfAbsent || out == unassigned) 
				currentChunk[chunkoffset] = val;
			return out;
		}
		if (oldChunkId >= 0)
			saveCurrentChunk();

		short [] chunk = getChunk(chunkid);
		if (chunk == null){
			size++;
			Arrays.fill(currentChunk, unassigned);
		}
		else {
			// this is the worst case: we have to modify
			// a chunk that was already saved
			fillCurrentChunk(chunk);
			--countChunkLen[chunk.length];
		}
		out = currentChunk[chunkoffset];
		oldChunkId = chunkid;
		if (!putIfAbsent || out == unassigned) 
			//return out;
			currentChunk[chunkoffset] = val;
		return out;
	}


	@Override
	public short get(long key) {
		if (key >= MAX_KEY) {
			throw new IllegalArgumentException(
					"Cannot handle such key, is too high. key=" + key);
		}

		int chunkid = (int) (key / CHUNK_SIZE);
		if (oldChunkId != chunkid && oldChunkId >= 0){
			saveCurrentChunk();
		}
		int chunkoffset = (int) (key % CHUNK_SIZE);

		if (oldChunkId == chunkid)
			 return currentChunk[chunkoffset];
		oldChunkId = -1;
		short [] chunk = getChunk(chunkid);
		if (chunk == null) 
			return unassigned;
		long chunkmask = extractMask(chunk);
		long elementmask = 1L << chunkoffset;
		short out;
		if ((chunkmask & elementmask) == 0) {
			out = unassigned;
		} else {
			out = chunkGet(chunk, countUnder(chunkmask, chunkoffset));
		}
		return out;
	}

	@Override
	public void clear() {
		capacity = (int) (INIT_SIZE / CHUNK_SIZE); // OSM data already contains IDs > 1.500.000.000 in year 2011 

		if (method == Method.array) {
			System.out.println("Allocating chunk vector (ObjectArrayList) to hold node IDs up to " + Utils.format(INIT_SIZE));
			chunkvector = new ObjectArrayList<short[]>();
			chunkvector.size(1+capacity);
		}
		else { 
			System.out.println("Allocating sparse chunk vector (ObjectArrayList) to hold node IDs up to " + Utils.format(INIT_SIZE));
			topvector = new ObjectArrayList<short[][]>();
			topvector.size((capacity/SPARSE_CHUNK_VECTOR_SIZE) + 1);
		}
		countChunkLen = new long[CHUNK_SIZE + MASK_SIZE + 1 ]; // used for statistics
		size = 0;
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

	private void resizeTo(int chunkid) {
		if (chunkid <= capacity)
			return;
		capacity = chunkid + chunkid/8 + SIZE_INCR;

		if (capacity < 0 || capacity > 2000000000)
			capacity = 2000000000;
		if (method == Method.array){
			System.out.println("Resizing chunk vector to hold IDs up to " + Utils.format((long) (1+capacity) * CHUNK_SIZE));
			chunkvector.size(capacity + 1);
		}
		else {
			System.out.println("Resizing top chunk vector to hold IDs up to " + Utils.format((long) (1+capacity) * CHUNK_SIZE));
			topvector.size((capacity/SPARSE_CHUNK_VECTOR_SIZE) + 1);
		}
	}

	private short[] getChunk (int chunkid){
		if (chunkid > capacity)
			return null;
		if (method == Method.array)
			return chunkvector.get(chunkid);
		else {
			int topId = (chunkid / SPARSE_CHUNK_VECTOR_SIZE);
			int offset = (chunkid % SPARSE_CHUNK_VECTOR_SIZE);
			short[][] v = topvector.get(topId);
			if (v == null) 
				return null;	// chunk is not allocated
			else {
				return v[offset];
			}				
		}
	}

	private void putChunk (int chunkid, short[] chunk) {
		if (chunkid > capacity)
			resizeTo(chunkid);
		if (method == Method.array)
			chunkvector.set(chunkid, chunk);
		else {
			int topId = (chunkid / SPARSE_CHUNK_VECTOR_SIZE);
			int offset = (chunkid % SPARSE_CHUNK_VECTOR_SIZE);
			short[][] v = topvector.get(topId);
			if (v == null){  
				v = new short[SPARSE_CHUNK_VECTOR_SIZE][];
				topvector.set(topId, v);
				++countSparseChunk;
			}
			v[offset] = chunk;
		}
	}

	/**
	 *  Store the mask value (a long) in the 1st MASK_SIZE chunk elements.
	 * @param mask
	 * @param chunk
	 */
	private void storeMask (long mask, short[] chunk) {
		// store chunkmask in chunk
		long tmp = mask;
		for (int i = 0; i < MASK_SIZE; i++){ 
			chunk[i] = (short) (tmp & 0xffffL);
			tmp >>= 16;
		}
	}
	/** 
	 * Extract the (long) mask value from the 1st MASK_SIZE chunk elements.
	 * @param chunk
	 * @return the mask
	 */
	private long extractMask(short [] chunk){
		long mask = 0;
		for (int i = 0; i < MASK_SIZE; i++) {
			mask |= (chunk[3-i] & 0xffff);
			if (i <= 2)
				mask <<= 16;
		}
		return mask;
	}

	@Override
	public void stats(int msgLevel) {
		long usedChunks = 0;
		long pctusage = 0;
		int i;
		for (i=6; i <=CHUNK_SIZE + MASK_SIZE; i+=4) {
			usedChunks += countChunkLen[i];
			if (msgLevel > 0) { 
				System.out.println("Length-" + i + " chunks: " + Utils.format(countChunkLen[i]) + " (Bytes: " + Utils.format(countChunkLen[i] * (12+i*2)) + ")");
			}
		}
		i = CHUNK_SIZE+MASK_SIZE;
		usedChunks += countChunkLen[i];
		if (msgLevel > 0) { 
			System.out.println("Length-" + i + " chunks: " + Utils.format(countChunkLen[i]) + " (Bytes: " + Utils.format(countChunkLen[i] * (16+i*2)) + ")");
		}
		if (msgLevel > 0 & uncompressedLen > 0){
			System.out.print("RLE compresion info: compressed / uncompressed size / ratio: " + 
					Utils.format(compressedLen) + " / "+ 
					Utils.format(uncompressedLen) + " / "+
					Utils.format(Math.round(100-(float) (compressedLen*100/uncompressedLen))) + "%");
			if (expanded > 0 )
				System.out.print(", times fully expanded: " + Utils.format(expanded));
			System.out.println();
		}
		if (method == Method.array){  
			pctusage = Math.min(100, 1 + Math.round((float)usedChunks*100/(INIT_SIZE/CHUNK_SIZE)));

			System.out.println("Chunk vector details: used " + Utils.format(usedChunks) + " of " + Utils.format(INIT_SIZE/CHUNK_SIZE) + " allocated entries (< " + pctusage + "%)");
		}
		else{ 
			pctusage = Math.min(100, 1 + Math.round((float)usedChunks*100/(countSparseChunk*SPARSE_CHUNK_VECTOR_SIZE))) ;
			System.out.println("Sparse chunk vector details: used " + Utils.format(usedChunks) + " of " + Utils.format(countSparseChunk*SPARSE_CHUNK_VECTOR_SIZE) + " allocated entries (< " + pctusage + "%)");
		}

		if (msgLevel > 1){
			for (i = 0;i < capacity; i++) {
				short [] c = getChunk (i);
				if (c != null){
					System.out.print("c(" +  i + ")={");
					for (int j=MASK_SIZE; j < c.length; j++) 
						System.out.print(" " + c[j]);
					System.out.println("}");
				}
			}
		}
	}
}


