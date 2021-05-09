/*
 * Copyright (c) 2009, Chris Miller
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

package uk.me.parabola.splitter.writer;

import java.io.File;
import java.io.IOException;

import uk.me.parabola.splitter.Element;
import uk.me.parabola.splitter.Node;
import uk.me.parabola.splitter.OsmBounds;
import uk.me.parabola.splitter.Relation;
import uk.me.parabola.splitter.Way;

public interface OSMWriter {

	/**
	 * open output file, allocate buffers etc.
	 */
	void initForWrite();

	/**
	 * close output file, free resources
	 */
	void finishWrite();

	void write(Node node) throws IOException;

	void write(Way way) throws IOException;

	void write(Relation rel) throws IOException;

	default void write (Element element) throws IOException {
		if (element instanceof Node) {
			write((Node) element);
		} else if (element instanceof Way) {
			write((Way) element);
		} else if (element instanceof Relation) {
			write((Relation) element);
		}
	}

	void write(OsmBounds bounds) throws IOException;

	File getOutputFile();

	void setVersionMethod(int versionMethod);

	int getWriteVersion(Element el);
}