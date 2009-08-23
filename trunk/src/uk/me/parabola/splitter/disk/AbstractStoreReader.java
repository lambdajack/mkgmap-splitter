/**
 * Copyright 2009 Chris Miller
 */
package uk.me.parabola.splitter.disk;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Abstract base class for reading in binary cache files
 */
public abstract class AbstractStoreReader {
	protected final LengthPrefixInputStream in;
	protected final KeyLookupReader keys;
	protected int id;
	protected Map<String, String> tags = new HashMap<String, String>(20);

	public AbstractStoreReader(InputStream in, KeyLookupReader keys) throws IOException {
		this.in = new LengthPrefixInputStream(new BufferedInputStream(in, 16384));
		this.keys = keys;
	}

	public int getId() {
		return id;
	}

	public Map<String, String> getTags() {
		return tags;
	}

	public boolean next() throws IOException {
		try {
			if (!in.next()) {
				return false;
			}
			id = in.readInt();
			readHeader();
			readTags();
			return true;
		} catch (EOFException e) {
			return false;
		}
	}

	protected LengthPrefixInputStream getIn() {
		return in;
	}

	protected abstract void readHeader() throws IOException;

	private void readTags() throws IOException {
		short keyId;
		tags.clear();
		while ((keyId = in.readShort()) != 0) {
			tags.put(keys.get(keyId), in.readUTF());
		}
	}

	public void close() throws IOException {
		in.close();
	}
}
