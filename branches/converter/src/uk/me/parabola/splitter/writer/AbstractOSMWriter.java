/*
 * Copyright (c) 2021
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

import uk.me.parabola.splitter.Element;

public abstract class AbstractOSMWriter implements OSMWriter {
	public static final int REMOVE_VERSION = 1;
	public static final int FAKE_VERSION = 2;
	public static final int KEEP_VERSION = 3;
	protected int versionMethod;
	protected File outputFile;
	
	protected AbstractOSMWriter(File oFile) {
		this.outputFile = oFile;
	}

	@Override
	public final int getWriteVersion (Element el){
		if (versionMethod == REMOVE_VERSION)
			return 0;
		if (versionMethod == FAKE_VERSION)
			return 1;
		// XXX maybe return 1 if no version was read ?
		return el.getVersion();
	}

	@Override
	public final void setVersionMethod(int versionMethod) {
		this.versionMethod = versionMethod;
	}
	
	@Override
	public final File getOutputFile() {
		return outputFile;
	}

}
