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

import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

import uk.me.parabola.splitter.Area;
import uk.me.parabola.splitter.Element;
import uk.me.parabola.splitter.Node;
import uk.me.parabola.splitter.OsmBounds;
import uk.me.parabola.splitter.Relation;
import uk.me.parabola.splitter.Utils;
import uk.me.parabola.splitter.Way;

/**
 * Combines a writer with the bounding box.
 * @author Gerd Petermann
 *
 */
public class BoundedOsmWriter implements OSMWriter {
	protected final Area bounds;
	protected final Area extendedBounds;
	protected final File outputDir;
	protected final int mapId;
	protected final Rectangle bbox;
	private OSMWriter writer;
	
	public BoundedOsmWriter(Area bounds, File outputDir, int mapId, int extra, String outputType) {
		this.bounds = bounds;
		extendedBounds = new Area(bounds.getMinLat() - extra,
				bounds.getMinLong() - extra,
				bounds.getMaxLat() + extra,
				bounds.getMaxLong() + extra);
		this.bbox = Utils.area2Rectangle(bounds, 1);
		this.mapId = mapId;
		this.outputDir = outputDir;
		writer = createWriter(outputType);
	}

	private OSMWriter createWriter(String outputType) {
		String filenameBase = String.format(Locale.ROOT, "%08d", mapId);
		String baseName = new File(outputDir, filenameBase).getPath();
		if ("pbf".equals(outputType)) {
			return new BinaryMapWriter(baseName);
		} else if ("o5m".equals(outputType)) {
			return new O5mMapWriter(baseName);
		} else if ("simulate".equals(outputType)) {
			return new PseudoOSMWriter(baseName);
		} else {
			return new OSMXMLWriter(baseName);
		}
	}

	public Area getBounds() {
		return bounds;
	}

	public Area getExtendedBounds() {
		return extendedBounds;
	}

	public int getMapId() {
		return mapId;
	}

	public Rectangle getBBox() {
		return bbox;
	}

	@Override
	public void initForWrite() {
		try {
			writer.initForWrite();
			writer.write(new OsmBounds(bounds));
		} catch (IOException e) {
			System.out.println("Could not open or write file header. Reason: " + e.getMessage());
			throw new RuntimeException(e);
		}
	}

	@Override
	public void finishWrite() {
		writer.finishWrite();
	}

	@Override
	public void write(Node node) throws IOException {
		writer.write(node);
	}

	@Override
	public void write(Way way) throws IOException {
		writer.write(way);
	}

	@Override
	public void write(Relation rel) throws IOException {
		writer.write(rel);
	}

	@Override
	public void write(OsmBounds osmBounds) throws IOException {
		writer.write(osmBounds);
	}

	@Override
	public File getOutputFile() {
		return writer.getOutputFile();
	}

	@Override
	public void setVersionMethod(int versionMethod) {
		writer.setVersionMethod(versionMethod);
	}

	@Override
	public int getWriteVersion(Element el) {
		return writer.getWriteVersion(el);
	}

}
