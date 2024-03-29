/*
 * Copyright (c) 2009, Steve Ratcliffe
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;

/**
 * @author Steve Ratcliffe
 */
public abstract class Element {
	protected ArrayList<Tag> tags; 
	private long id;
	private int version;
	
	public void setId(long id) {
		this.id = id;
	}

	public long getId() {
		return id;
	}

	public int getVersion() {
		return version;
	}

	public void setVersion(int version) {
		this.version = version;
	}

	public static class Tag {
		public final String key, value;

		public Tag(String key, String value) {
			this.key = key;
			this.value = value;
		}

		public String getKey() {
			return key;
		}

		public String getValue() {
			return value;
		}

		@Override
		public String toString() {
			return key + "=" + value;
		}
	}
	
	public void addTag(String key, String value) {
		if (key.equals("created_by"))
			return;
		// Most elements are nodes. Most nodes have no tags. Create the tag table lazily
		if (tags == null)
			tags = new ArrayList<>(4);

		tags.add(new Tag(key, value));
	}

	public boolean hasTags() {
		return tags != null && !tags.isEmpty();
	}

	public Iterator<Tag> tagsIterator() {
		if (tags == null)
			return Collections.emptyIterator();

		return tags.iterator();
	}
	
	public String getTag (String key){
		if (tags == null)
			return null;
		for (Tag tag:tags){
			if (key.equals(tag.key))
				return tag.value;
		}
		return null;
	}
}
