/*
 * Copyright (C) 2012, Gerd Petermann
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

package uk.me.parabola.splitter.parser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import uk.me.parabola.splitter.Area;
import uk.me.parabola.splitter.Element;
import uk.me.parabola.splitter.MapProcessor;
import uk.me.parabola.splitter.Node;
import uk.me.parabola.splitter.OsmBounds;
import uk.me.parabola.splitter.Relation;
import uk.me.parabola.splitter.Way;

/**
 * Parser for the o5m format described here: http://wiki.openstreetmap.org/wiki/O5m
 * The routines to are based on the osmconvert.c source from Markus Weber who allows 
 * to copy them for any o5m IO, thanks a lot for that. 
 * @author GerdP  
 *
 */
public class O5mMapParser {
	// O5M data set constants
	private static final int NODE_DATASET = 0x10;
	private static final int WAY_DATASET = 0x11;
	private static final int REL_DATASET = 0x12;
	private static final int BBOX_DATASET = 0xdb;
	private static final int TIMESTAMP_DATASET = 0xdc;
	private static final int HEADER_DATASET = 0xe0;
	private static final int EOD_FLAG = 0xfe;
	private static final int RESET_FLAG = 0xff;
	
	// o5m constants
	private static final int STRING_TABLE_SIZE = 15000;
	private static final int MAX_STRING_PAIR_SIZE = 250 + 2;
	private static final String[] REL_REF_TYPES = {"node", "way", "relation", "?"};
	private static final double FACTOR = 1d / 1000000000; // used with 100*<Val>*FACTOR 
	
	// for status messages
	private final ElementCounter elemCounter = new ElementCounter();
	// flags set by the processor to signal what information is not needed
	private final boolean skipTags;
	private final boolean skipNodes;
	private final boolean skipWays;
	private final boolean skipRels;

	private final FileChannel fileChannel;
	// Buffer size, must be a power of 2
	private static final int BUF_SIZE = 0x1000;
	
	private final ByteBuffer fileBuffer = ByteBuffer.allocate(BUF_SIZE);
	private long filePos;
	private long bufStart;
	private int bufSize = -1; 

	private long nextFilePos;  

	
	private final MapProcessor processor;
	
	// buffer for byte -> String conversions
	private final byte[] cnvBuffer; 
	
	// the o5m string table
	private String[][] stringTable;
	private final String[] stringPair;
	private int currStringTablePos;
	// a counter that must be maintained by all routines that read data

	// performance: save byte position of first occurrence of a data set type (node, way, relation)
	// to allow skipping large parts of the stream
	private long[] firstPosInFile;
	private long[] skipArray;
	
	// for delta calculations
	private long lastNodeId;
	private long lastWayId;
	private long lastRelId;
	private long[] lastRef;
	private long lastTs;
	private long lastChangeSet;
	private int lastLon;
	private int lastLat;
	
	/**
	 * A parser for the o5m format.
	 * @param processor A mapProcessor instance
	 * @param fc the file channel for the input file 
	 * @param skipArray An Array of longs that is used to hold information of file position of the first occurrence of 
	 * each known 05m data type (esp. nodes, ways, and relations). 
	 */
	public O5mMapParser(MapProcessor processor, FileChannel fc, long[] skipArray) {
		this.fileChannel = fc;
		this.processor = processor;
		this.skipArray = skipArray;
		this.skipTags = processor.skipTags();
		this.skipNodes = processor.skipNodes();
		this.skipWays = processor.skipWays();
		this.skipRels = processor.skipRels();
		this.cnvBuffer = new byte[4000]; // OSM data should not contain string pairs with length > 512
		this.stringPair = new String[2];
		this.lastRef = new long[3];
		if (skipArray == null) {
			firstPosInFile = new long[256];
			Arrays.fill(firstPosInFile, -1);
		}
		reset();
	}

	
	/**
	 * parse the input stream.
	 * @throws IOException 
	 */
	public void parse() throws IOException {
		int start = get() & 0xff;
		if (start != RESET_FLAG) 
			throw new IOException("wrong header byte " + start);
		if (skipArray != null && skipNodes) {
			if (skipWays)
				filePos = skipArray[REL_DATASET]; // jump to first relation
			else 
				filePos = skipArray[WAY_DATASET]; // jump to first way
		}
		if (filePos >= 0)
			readFile();
	}
	
	/**
	 * Read the file following the initial byte.
	 * @throws IOException
	 */
	private void readFile() throws IOException {
		boolean done = false;
		while (!done) {
			long size = 0;
			int fileType = get() & 0xff;
			if (fileType >= 0 && fileType < 0xf0) {
				if (skipArray == null && firstPosInFile[fileType] == -1) {
					// save first occurrence of a data set type
					firstPosInFile[fileType] = Math.max(0, filePos- 1);
				}
				size = readUnsignedNum64();
				nextFilePos = filePos + size;
				
				
				boolean doSkip = ((fileType == NODE_DATASET && skipNodes) 
						|| (fileType == WAY_DATASET && skipWays)
						|| (fileType == REL_DATASET && skipRels));
				switch(fileType) {
				case NODE_DATASET: 
				case WAY_DATASET: 
				case REL_DATASET: 
				case BBOX_DATASET:
				case TIMESTAMP_DATASET:
				case HEADER_DATASET:
					if (doSkip) { 
						filePos = nextFilePos;
						continue;
					}
					break;					
				default:	
				}
			}
			if (fileType == NODE_DATASET) readNode();
			else if (fileType == WAY_DATASET) readWay();
			else if (fileType == REL_DATASET) readRel();
			else if (fileType == BBOX_DATASET) readBBox();
			else if (fileType == TIMESTAMP_DATASET) readFileTimestamp();
			else if (fileType == HEADER_DATASET) readHeader();
			else if (fileType == EOD_FLAG) done = true;
			else if (fileType == RESET_FLAG) reset();
			else {
				if (fileType < 0xf0)
					filePos = nextFilePos; // skip unknown data set
			}
		}
	}
	
	/**
	 * read (and ignore) the file timestamp data set.
	 * @throws IOException 
	 */
	private void readFileTimestamp() throws IOException {
		/*long fileTimeStamp = */readSignedNum64();
	}
	
	/**
	 * read the bounding box data set.
	 * @throws IOException
	 */
	private void readBBox() throws IOException {
		double leftf = 100L * readSignedNum32() * FACTOR;
		double bottomf = 100L * readSignedNum32() * FACTOR;
		double rightf = 100L * readSignedNum32() * FACTOR;
		double topf = 100L * readSignedNum32() * FACTOR;
		assert filePos == nextFilePos;
		System.out.println("Bounding box " + leftf + " " + bottomf + " " + rightf + " " + topf);

		OsmBounds osmBounds = new OsmBounds(bottomf, leftf, topf, rightf);
		Area area = osmBounds.toArea();
		if (!area.verify())
			throw new IllegalArgumentException("invalid bbox area in o5m file: " + area);

		processor.boundTag(osmBounds);
	}

	/**
	 * read a node data set.
	 * @throws IOException
	 */
	private void readNode() throws IOException{
		Node node = new Node();
		
		lastNodeId += readSignedNum64();
		if (filePos == nextFilePos)
			return; // only nodeId: this is a delete action, we ignore it 
		int version = readVersionTsAuthor();
		node.setVersion(version);
		if (filePos == nextFilePos)
			return; // only nodeId+version: this is a delete action, we ignore it 
		int lon = readSignedNum32() + lastLon; lastLon = lon;
		int lat = readSignedNum32() + lastLat; lastLat = lat;
			
		double flon = 100L * lon * FACTOR;
		double flat = 100L * lat * FACTOR;
		assert flat >= -90.0 && flat <= 90.0;
		assert flon >= -180.0 && flon <= 180.0;

		node.set(lastNodeId, flat, flon);
		readTags(node);
		elemCounter.countNode(lastNodeId);
		processor.processNode(node);
	}
	
	/**
	 * read a way data set.
	 * @throws IOException
	 */
	private void readWay() throws IOException{
		lastWayId += readSignedNum64();
		if (filePos == nextFilePos)
			return; // only wayId: this is a delete action, we ignore it 

		int version = readVersionTsAuthor();
		if (filePos == nextFilePos)
			return; // only wayId + version: this is a delete action, we ignore it 
		Way way = new Way();
		way.setId(lastWayId);
		way.setVersion(version);
		long refSize = readUnsignedNum32();
		long stop = filePos + refSize;
		
		while (filePos < stop) {
			lastRef[0] += readSignedNum64();
			way.addRef(lastRef[0]);
		}
		
		readTags(way);
		elemCounter.countWay(lastWayId);
		processor.processWay(way);
		
	}
	
	/**
	 * read a relation data set.
	 * @throws IOException
	 */
	private void readRel() throws IOException{
		lastRelId += readSignedNum64(); 
		if (filePos == nextFilePos)
			return; // only relId: this is a delete action, we ignore it 
		int version = readVersionTsAuthor();
		if (filePos == nextFilePos)
			return; // only relId + version: this is a delete action, we ignore it 
		
		Relation rel = new Relation();
		rel.setId(lastRelId);
		rel.setVersion(version);
		long refSize = readUnsignedNum32();
		long stop = filePos + refSize;
		while (filePos < stop) {
			long deltaRef = readSignedNum64();
			int refType = readRelRef();
			lastRef[refType] += deltaRef;
			rel.addMember(stringPair[0], lastRef[refType], stringPair[1]);
		}
		
		// tags
		readTags(rel);
		elemCounter.countRelation(lastRelId);
		processor.processRelation(rel);
	}
	
	private void readTags(Element elem) throws IOException{
		// we cannot skip the tags if we read relations (roles) 
		if (skipTags && skipRels) { 
			filePos = nextFilePos;
			return;
		}
		while (filePos < nextFilePos) {
			readStringPair();
			if (!skipTags) {
				elem.addTag(stringPair[0], stringPair[1]);
			}
		}
		assert filePos == nextFilePos;
		
	}
	/**
	 * Store a new string pair (length check must be performed by caller).
	 */
	private void storeStringPair() {
		stringTable[0][currStringTablePos] = stringPair[0];
		stringTable[1][currStringTablePos] = stringPair[1];
		++currStringTablePos;
		if (currStringTablePos >= STRING_TABLE_SIZE)
			currStringTablePos = 0;
	}

	/**
	 * set stringPair to the values referenced by given string reference
	 * No checking is performed.
	 * @param ref valid values are 1 .. STRING_TABLE_SIZE
	 * @throws IOException 
	 */
	private void setStringRefPair(int ref) throws IOException{
		int pos = currStringTablePos - ref;
		if (pos < 0) 
			pos += STRING_TABLE_SIZE;
		if (pos < 0 || pos >= STRING_TABLE_SIZE)
			throw new IOException("invalid string table reference: " + ref); 
		stringPair[0] = stringTable[0][pos];
		stringPair[1] = stringTable[1][pos];
	}

	/**
	 * Read version, time stamp and change set and author.  
	 * @return the version
	 * @throws IOException
	 */
	private int readVersionTsAuthor() throws IOException {
		int version = readUnsignedNum32(); 
		if (version != 0) {
			// version info
			long ts = readSignedNum64() + lastTs; lastTs = ts;
			if (ts != 0) {
				long changeSet = readSignedNum32() + lastChangeSet; lastChangeSet = changeSet;
				readAuthor();
			}
		}
		return version;
	}
	/**
	 * Read author . 
	 * @throws IOException
	 */
	private void readAuthor() throws IOException{
		int stringRef = readUnsignedNum32();
		if (stringRef == 0) {
			long toReadStart = filePos;
			long uidNum = readUnsignedNum64();
			if (uidNum == 0)
				stringPair[0] = "";
			else {
				stringPair[0] = Long.toString(uidNum);
				get(); // skip terminating zero from uid
			}
			stringPair[1] = readString();
			if (filePos - toReadStart <= MAX_STRING_PAIR_SIZE)
				storeStringPair();
		} else { 
			setStringRefPair(stringRef);
		}
		
		//System.out.println(pair[0]+ "/" + pair[1]);
	}
	
	/**
	 * read object type ("0".."2") concatenated with role (single string).
	 * @return 0..3 for type (3 means unknown)
	 */
	private int readRelRef() throws IOException {
		int refType = -1;
		long toReadStart = filePos;
		int stringRef = readUnsignedNum32();
		if (stringRef == 0) {
			refType = get() - '0';

			if (refType < 0 || refType > 2)
				refType = 3;
			stringPair[0] = REL_REF_TYPES[refType];
			stringPair[1] = readString();
			if (filePos - toReadStart <= MAX_STRING_PAIR_SIZE)
				storeStringPair();
		} else {
			setStringRefPair(stringRef);
			char c = stringPair[0].charAt(0);
			switch (c) {
			case 'n': refType = 0; break;
			case 'w': refType = 1; break;
			case 'r': refType = 2; break;
			default: refType = 3;
			}
		}
		return refType;
	}
	
	/**
	 * read a string pair (see o5m definition).
	 * @throws IOException
	 */
	private void readStringPair() throws IOException{
		int stringRef = readUnsignedNum32();
		if (stringRef == 0) {
			long toReadStart = filePos;
			int cnt = 0;
			while (cnt < 2) {
				stringPair[cnt++] = readString();
			}
			if (filePos - toReadStart <= MAX_STRING_PAIR_SIZE)
				storeStringPair();
		} else { 
			setStringRefPair(stringRef);
		}
	}
	
	/**
	 * Read a zero-terminated string (see o5m definition).
	 * @throws IOException
	 */
	String readString() throws IOException {
		int length = 0; 
		while (true) {
			final int b = get();
			if (b == 0)
				return new String(cnvBuffer, 0, length, StandardCharsets.UTF_8);
			cnvBuffer[length++] = (byte) b;
		}
		
	}
	/** reset the delta values and string table. */
	private void reset() {
		lastNodeId = 0;
		lastWayId = 0;
		lastRelId = 0;
		lastRef[0] = 0;
		lastRef[1] = 0;
		lastRef[2] = 0;
		lastTs = 0;
		lastChangeSet = 0;
		lastLon = 0;
		lastLat = 0;
		stringTable = new String[2][STRING_TABLE_SIZE];
		currStringTablePos = 0;
	}

	/**
	 * read and verify o5m header (known values are o5m2 and o5c2).
	 * @throws IOException
	 */
	private void readHeader() throws IOException {
		byte[] header = new byte[4];
		for (int i = 0; i < header.length; i++) {
			header[i] = get();
		}
		if (header[0] != 'o' || header[1] != '5' || (header[2] != 'c' && header[2] != 'm') || header[3] != '2') {
			throw new IOException("unsupported header");
		}
	}

	/**
	 * read a varying length signed number (see o5m definition).
	 * @return the number
	 * @throws IOException
	 */
	private int readSignedNum32() throws IOException {
		int result;
		int b = get();
		result = b;
		if ((b & 0x80) == 0) { // just one byte
			if ((b & 0x01) == 1)
				return -1 - (result >> 1);
			return result >> 1;
		}
		int sign = b & 0x01;
		result = (result & 0x7e) >> 1;
		int fac = 0x40;
		while (((b = get()) & 0x80) != 0) { // more bytes will follow
			result += fac * (b & 0x7f);
			fac <<= 7;
		}
		result += fac * b;
		if (sign == 1) // negative
			return -1 - result;
		return result;

	}

	/**
	 * read a varying length signed number (see o5m definition).
	 * @return the number
	 * @throws IOException
	 */
	private long readSignedNum64() throws IOException {
		long result;
		int b = get();
		result = b;
		if ((b & 0x80) == 0) { // just one byte
			if ((b & 0x01) == 1)
				return -1 - (result >> 1);
			return result >> 1;
		}
		int sign = b & 0x01;
		result = (result & 0x7e) >> 1;
		long fac = 0x40;
		while (((b = get()) & 0x80) != 0) { // more bytes will follow
			result += fac * (b & 0x7f);
			fac <<= 7;
		}
		result += fac * b;
		if (sign == 1) // negative
			return -1 - result;
		return result;

	}
	
	/**
	 * read a varying length unsigned number (see o5m definition).
	 * @return a long
	 * @throws IOException
	 */
	private long readUnsignedNum64() throws IOException {
		int b = get();
		long result = b;
		if ((b & 0x80) == 0) { // just one byte
			return result;
		}
		result &= 0x7f;
		long fac = 0x80;
		while (((b = get()) & 0x80) != 0) { // more bytes will follow
			result += fac * (b & 0x7f);
			fac <<= 7;
		}
		result += fac * b;
		return result;
	}

	/**
	 * read a varying length unsigned number (see o5m definition)
	 * is similar to the 64 bit version.
	 * @return an int 
	 * @throws IOException
	 */
	private int readUnsignedNum32() throws IOException {
		int b = get();
		int result = b;
		if ((b & 0x80) == 0) { // just one byte
			return result;
		}
		result &= 0x7f;
		long fac = 0x80;
		while (((b = get()) & 0x80) != 0) { // more bytes will follow
			result += fac * (b & 0x7f);
			fac <<= 7;
		}
		result += fac * b;
		return result;
	}

	public long[] getNextSkipArray() {
		return firstPosInFile; 
	}
	
	/**
	 * Read in a single byte from the current position.
	 *
	 * @return The byte that was read.
	 * @throws IOException if buffer contains no data
	 */
	private byte get() throws IOException {
		fillBuffer();

		int pos = (int) (filePos - bufStart);
		if (pos < 0 || pos >= bufSize) {
			throw new IOException("no data in file buffer, pos="+pos);
		}
		filePos++;
		return fileBuffer.get(pos);
		
		
	}
	
	/**
	 * Check to see if the buffer contains the byte at the current position.
	 * If not then it is re-read so that it does.
	 * @throws IOException in case of I/O error
	 */
	private void fillBuffer() throws IOException {
		// If we are no longer inside the buffer, then re-read it.
		if (filePos >= bufStart + bufSize) {

			// Get channel position on a block boundary.
			bufStart = filePos & ~(BUF_SIZE - 1);
			fileChannel.position(bufStart);
			// Fill buffer
			fileBuffer.clear();
			bufSize = fileChannel.read(fileBuffer);
		}
	}
}
