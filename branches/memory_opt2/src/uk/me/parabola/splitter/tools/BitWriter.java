/*
 * Copyright (C) 2006 Steve Ratcliffe
 * 
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2 as
 *  published by the Free Software Foundation.
 * 
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 * 
 * Author: Steve Ratcliffe
 * Create date: 14-Dec-2006
 */
package uk.me.parabola.splitter.tools;

import java.util.Arrays;

/**
 * A class to write the bitstream.
 *
 * @author Steve Ratcliffe
 */
public class BitWriter {
	// Choose so that chunks will not fill it.

	// The byte buffer and its current length (allocated length)
	private byte[] buf;  // The buffer
	private int bufsize;  // The allocated size
	private int buflen; // The actual used length
	private int cntExtra;

	// The bit offset into the byte array.
	private int bitoff;
	private static final int BUFSIZE_INC = 50;
	private static final int INITIAL_BUF_SIZE = 20;  
	
	public BitWriter(int sizeInBytes) {
		bufsize = sizeInBytes;
		buf = new byte[bufsize];
	}

	public BitWriter() {
		this(INITIAL_BUF_SIZE);
	}

	public void clear() {
		Arrays.fill(buf, (byte)0);
		bitoff = 0;
		buflen = 0;
		cntExtra = 0;
	}

	/**
	 * Put exactly one bit into the buffer.
	 *
	 * @param b The bottom bit of the integer is set at the current bit position.
	 */
	private void put1(int b) {
		ensureSize(bitoff + 1);

		int off = getByteOffset(bitoff);

		// Get the remaining bits into the byte.
		int rem = bitoff - 8 * off;

		// Or it in, we are assuming that the position is never turned back.
		buf[off] |= (b & 0x1) << rem;

		// Increment position
		bitoff++;

		// If we are in a new byte, increase the byte length.
		if ((bitoff & 0x7) == 1)
			buflen++;

//		debugPrint(b, 1);
	}
	
	public void put1(boolean b) {
		put1(b ? 1 : 0);
	}

	/**
	 * Put a number of bits into the buffer, growing it if necessary.
	 *
	 * @param bval The bits to add, the lowest <b>n</b> bits will be added to
	 * the buffer.
	 * @param nb The number of bits.
	 */
	public void putn(int bval, int nb) {
		assert nb >= 1 && nb <= 32;
		int val = nb < 32 ? bval & ((1<<nb) - 1) : bval;
		int n = nb;

		ensureSize(bitoff + n);

		// Get each affected byte and set bits into it until we are done.
		while (n > 0) {
			int ind = getByteOffset(bitoff);
			int rem = bitoff - 8*ind;

			buf[ind] |= ((val << rem) & 0xff);

			// Shift down in preparation for next byte.
			val >>>= 8-rem;

			// Account for change so far
			int nput = 8 - rem;
			if (nput > n)
				nput = n;
			bitoff += nput;
			n -= nput;
		}

		buflen = (bitoff+7)/8;
	}
	
	/**
	 * Write a signed value. Caller must make sure that absolute value fits into 
	 * the given number of bits
	 */

	public void sputn(final int bval, final int nb) {
		assert nb > 1 && nb <= 32;
		int top = 1 << (nb - 1);
		if (bval == Integer.MIN_VALUE) {
			long dd =4;
		}
		if (bval < 0) {
			assert -bval <  top || top < 0;  
			int v = (top + bval) | top; 
			putn(v, nb);
		} else {
			assert bval < top || top < 0;
			putn(bval, nb);
		}
	}
	
	/**
	 * Write a signed value. If the value doesn't fit into nb bits, write one or more 1 << (nb-1)  
	 * as a flag for extended range.
	 */

	public void sputn2(final int bval, final int nb) {
		assert nb > 1 && nb <= 32;
		int top = 1 << (nb - 1);
		int mask = top - 1;
		int val = Math.abs(bval);
		
		if (bval == Integer.MIN_VALUE) {
			// catch special case : Math.abs(Integer.MIN_VALUE) returns Integer.MIN_VALUE
			cntExtra++;
			putn(top, nb);
			val = Math.abs(val - mask);
		}
		assert val >= 0;
		while (val > mask) {
			cntExtra++;
			putn(top, nb);
			val -= mask;
		}
		if (bval < 0) {
			putn((top - val) | top, nb);
		} else {
			putn(val, nb);
		}
	}
	
	public int getCntExtra() {
		return cntExtra;
	}

	public byte[] getBytes() {
		return buf;
	}

	public int getBitPosition() {
		return bitoff;
	}
	
	/**
	 * Get the number of bytes actually used to hold the bit stream. This therefore can be and usually
	 * is less than the length of the buffer returned by {@link #getBytes()}.
	 * @return Number of bytes required to hold the output.
	 */
	public int getLength() {
		return buflen;
	}

	/**
	 * Get the byte offset for the given bit number.
	 *
	 * @param boff The number of the bit in question.
	 * @return The index into the byte array where the bit resides.
	 */
	private static int getByteOffset(int boff) {
		return boff/8;
	}

	/**
	 * Set everything up so that the given size can be accommodated.
	 * The buffer is re-sized if necessary.
	 *
	 * @param newlen The new length of the bit buffer in bits.
	 */
	private void ensureSize(int newlen) {
		if (newlen/8 >= bufsize)
			reallocBuffer();
	}

	/**
	 * Reallocate the byte buffer.
	 */
	private void reallocBuffer() {
		bufsize += BUFSIZE_INC;
		byte[] newbuf = new byte[bufsize];

		System.arraycopy(this.buf, 0, newbuf, 0, this.buf.length);
		this.buf = newbuf;
	}
}
