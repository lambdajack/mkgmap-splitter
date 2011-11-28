package uk.me.parabola.splitter;

import it.unimi.dsi.bits.Fast;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Arrays;
import java.util.HashMap;

/**
 * SparseLong2ShortMapInline implements SparseLong2ShortMapFunction 
 * optimized for low memory requirements and inserts in sequential order.
 *
 * Inspired by SparseInt2ShortMapInline.
 * 
 * Two variants are implemented:  
 * three-tier uses a HashMap to address large vectors which address chunks
 * four-tier uses a HashMap to not-so-large vectors which address small 
 * vectors which address chunks. This consumes less memory when e.g. only 
 * data for a country like Germany is processed. 
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
	static final int CHUNK_SIZE = 64; 
	static final long TOP_ID_BITS       = 0xffffffffff000000L;  // the part of the key that is saved in the HashMap
	static final long CHUNK_OFFSET_BITS = CHUNK_SIZE-1;  		// the part of the key that contains the offset in the chunk
	static final long OLD_CHUNK_ID_BITS = ~CHUNK_OFFSET_BITS;	// if this part of the key changes, a different chunk is used
	static final long CHUNK_ID_BITS     = ~TOP_ID_BITS; 
	
	static final long INVALID_CHUNK_ID = 1L; // must not be divisible by CHUNK_SIZE 
	static final int LARGE_VECTOR_SIZE = (int)(CHUNK_ID_BITS/ CHUNK_SIZE + 1); // number of entries addressed by one topMap entry (3-tier)
	static final int SPARSE_CHUNK_VECTOR_SIZE = 16; 
	static final int SPARSE_VECTOR_SIZE = LARGE_VECTOR_SIZE / SPARSE_CHUNK_VECTOR_SIZE; // number of entries addressed by one sparseTopMap entry (4-tier)

	static final int MASK_SIZE = 4;	// number of chunk elements needed to store the chunk mask
	static final int ONE_VALUE_CHUNK_SIZE = MASK_SIZE+2; 


	enum Method {three_tier, four_tier};
	private final Method method; 

	private HashMap<Long, ObjectArrayList<short[]> > topMap;
	private HashMap<Long, ObjectArrayList<short[][]> > sparseTopMap;
	private int [] paddedLen = new int[CHUNK_SIZE+MASK_SIZE];

	private int size;

	private long oldChunkId = INVALID_CHUNK_ID; 
	private short [] oldChunk = null; 
	private short [] currentChunk = new short[CHUNK_SIZE+MASK_SIZE]; 
	private short [] tmpWork = new short[CHUNK_SIZE+MASK_SIZE]; 
	private short [] RLEWork = new short[CHUNK_SIZE+MASK_SIZE];

	/** What to return on unassigned indices */
	private short unassigned = -1;

	// for statistics
	private long countSparseChunk = 0;
	private long [] countChunkLen; 
	private long compressed = 0;
	private long countTryRLE = 0;
	private long expanded = 0;
	private long uncompressedLen = 0;
	private long compressedLen = 0;

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
			method = Method.four_tier;
		else 
			method = Method.three_tier;
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
		short [] chunk = getChunk(key);
		if (chunk == null) 
			return false;
		long chunkmask = extractMask(chunk);
		int chunkoffset = (int) (key & CHUNK_OFFSET_BITS);

		long elementmask = 1L << chunkoffset;
		return (chunkmask & elementmask) != 0;
	}


	@Override
	public short put(long key, short val) {
		long chunkId = key & OLD_CHUNK_ID_BITS;
		if (val == unassigned) {
			throw new IllegalArgumentException("Cannot store the value that is reserved as being unassigned. val=" + val);
		}
		int chunkoffset = (int) (key & CHUNK_OFFSET_BITS);
		short out;
		if (oldChunkId == chunkId){
			out = currentChunk[chunkoffset];
				currentChunk[chunkoffset] = val;
			return out;
		}
		if (oldChunkId != INVALID_CHUNK_ID)
			saveCurrentChunk();

		short [] chunk = getChunk(key);
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
		oldChunkId = chunkId;
			currentChunk[chunkoffset] = val;
		return out;
	}


	@Override
	public short get(long key) {
		long chunkId = key & OLD_CHUNK_ID_BITS;
		if (oldChunkId != chunkId && oldChunkId != INVALID_CHUNK_ID){
			saveCurrentChunk();
		}
		int chunkoffset = (int) (key & CHUNK_OFFSET_BITS);

		if (oldChunkId == chunkId)
			 return currentChunk[chunkoffset];
		oldChunkId = INVALID_CHUNK_ID;
		short [] chunk = getChunk(key);
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
		if (method == Method.three_tier){
			System.out.println("Allocating three-tier structure to save area info (HashMap->vector->chunkvector)");
			topMap = new HashMap<Long, ObjectArrayList<short[]> >();
		}
		else{
			System.out.println("Allocating four-tier structure to save area info (HashMap->vector->vector->chunkvector)");
			sparseTopMap = new HashMap<Long, ObjectArrayList<short[][]> >();
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

	private short[] getChunk (long key){
		long topID = key & TOP_ID_BITS;
		int chunkid = (int) (key & CHUNK_ID_BITS) / CHUNK_SIZE;
		if (method == Method.three_tier) {
			ObjectArrayList<short[]>  t = topMap.get(topID);
			if (t == null){
				return null;
		}
			return t.get(chunkid);
		}
		else {
			ObjectArrayList<short[][]>  t = sparseTopMap.get(topID);
			if (t == null){
				return null;
		}
			
			int midId = (chunkid / SPARSE_CHUNK_VECTOR_SIZE);
			int offset = (chunkid % SPARSE_CHUNK_VECTOR_SIZE);
			short[][] v = t.get(midId);
			if (v == null){
				return null;
	}
			return v[offset]; 			
		}
	}

	private void putChunk (long key, short[] chunk) {
		long topID = key & TOP_ID_BITS;
		int chunkid = (int) (key & CHUNK_ID_BITS) / CHUNK_SIZE;
		if ( method == Method.three_tier) {
			ObjectArrayList<short[]>  largeVector = topMap.get(topID);
			if (largeVector == null){
				largeVector = new ObjectArrayList<short[]>();
				largeVector.size(LARGE_VECTOR_SIZE);
				topMap.put(topID, largeVector);
		}
			largeVector.set(chunkid, chunk);
	}
		else {
			ObjectArrayList<short[][]>  midSizeVector = sparseTopMap.get(topID);
			if (midSizeVector == null){
				midSizeVector = new ObjectArrayList<short[][]>();
				midSizeVector.size(SPARSE_VECTOR_SIZE);
				sparseTopMap.put(topID, midSizeVector);
			}
			
			
			int midId = (chunkid / SPARSE_CHUNK_VECTOR_SIZE);
			int offset = (chunkid % SPARSE_CHUNK_VECTOR_SIZE);
			short[][] v = midSizeVector.get(midId);
			if (v == null){  
				v = new short[SPARSE_CHUNK_VECTOR_SIZE][];
				midSizeVector.set(midId, v);
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
		if (method == Method.three_tier){
			pctusage = Math.min(100, 1 + Math.round((float)usedChunks*100/(topMap.size()*LARGE_VECTOR_SIZE))) ;
			System.out.println("HashMap details: HashMap entries: " + topMap.size() + ", used " + Utils.format(usedChunks) + " of " + Utils.format(topMap.size()*(CHUNK_ID_BITS/ CHUNK_SIZE +1)) + " allocated entries (< " + pctusage + "%)");
		}
		else {
			pctusage = Math.min(100, 1 + Math.round((float)usedChunks*100/(countSparseChunk*SPARSE_CHUNK_VECTOR_SIZE))) ;
			System.out.println("HashMap details: HashMap entries: " + sparseTopMap.size() + ", used " + Utils.format(usedChunks) + " of " + Utils.format(countSparseChunk*SPARSE_CHUNK_VECTOR_SIZE) + " allocated entries (< " + pctusage + "%)"); 			
		}
		/*
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
		 */
	}
}


