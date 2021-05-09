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
 
package uk.me.parabola.splitter.writer;

import java.io.File;

import uk.me.parabola.splitter.Node;
import uk.me.parabola.splitter.OsmBounds;
import uk.me.parabola.splitter.Relation;
import uk.me.parabola.splitter.Way;

/**
 * A do-nothing writer (used with --output=simulate) 
 * @author Gerd Petermann
 *
 */
public class PseudoOSMWriter extends AbstractOSMWriter{
	
	public PseudoOSMWriter(String baseName) {
		super(new File(baseName+".pseudo"));
	}

	@Override
	public void write(Relation rel) {}
	
	@Override
	public void write(Way way) {}
	
	@Override
	public void write(Node node) {}
	
	@Override
	public void finishWrite() {}

	@Override
	public void write(OsmBounds bounds) {}

	@Override
	public void initForWrite() {
		// nothing to do
	}
}
