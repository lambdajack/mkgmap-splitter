/*
 * Copyright (c) 2010, Scott Crosby
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

package uk.me.parabola.splitter.parser;

import crosby.binary.BinaryParser;
import crosby.binary.Osmformat;
import crosby.binary.file.FileBlockPosition;

import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import uk.me.parabola.splitter.Area;
import uk.me.parabola.splitter.MapProcessor;
import uk.me.parabola.splitter.Node;
import uk.me.parabola.splitter.OsmBounds;
import uk.me.parabola.splitter.Relation;
import uk.me.parabola.splitter.UnknownFeatureException;
import uk.me.parabola.splitter.Utils;
import uk.me.parabola.splitter.Way;

import java.util.List;

public class BinaryMapParser extends BinaryParser {
	private static final short TYPE_DENSE = 0x1;
	private static final short TYPE_NODES = 0x2;
	private static final short TYPE_WAYS = 0x4;
	private static final short TYPE_RELS = 0x8;
	private final ShortArrayList blockTypes = new ShortArrayList();
	private final ShortArrayList knownBlockTypes;

	// for status messages
	private final ElementCounter elemCounter = new ElementCounter();

	private short blockType = -1;
	private int blockCount = -1;
	private boolean skipTags;
	private boolean skipNodes;
	private boolean skipWays;
	private boolean skipRels;
	private short wantedTypeMask = 0;
	private int msgLevel;

	public BinaryMapParser(MapProcessor processor, ShortArrayList knownBlockTypes, int msgLevel) {
		this.processor = processor;
		this.knownBlockTypes = knownBlockTypes;
		this.skipTags = processor.skipTags();
		this.skipNodes = processor.skipNodes();
		this.skipWays = processor.skipWays();
		this.skipRels = processor.skipRels();
		this.msgLevel = msgLevel;

		if (!skipNodes) {
			wantedTypeMask |= TYPE_DENSE;
			wantedTypeMask |= TYPE_NODES;
		}
		if (!skipWays)
			wantedTypeMask |= TYPE_WAYS;
		if (!skipRels)
			wantedTypeMask |= TYPE_RELS;
	}

	MapProcessor processor;

	public ShortArrayList getBlockList() {
		return blockTypes;
	}

	@Override
	public boolean skipBlock(FileBlockPosition block) {
		blockCount++;
		if (knownBlockTypes != null) {
			blockType = knownBlockTypes.getShort(blockCount);
			if (blockType != 0 && (blockType & wantedTypeMask) == 0)
				return true;
		} else if (blockType != -1) {
			blockTypes.add(blockType);
		}
		blockType = 0;
		if (block.getType().equals("OSMData"))
			return false;
		if (block.getType().equals("OSMHeader"))
			return false;
		System.out.println("Skipped block of type: " + block.getType());
		return true;
	}

	@Override
	public void complete() {
		blockTypes.add(blockType);
		// End of map is sent when all input files are processed.
		// So do nothing else.
	}

	@Override
	protected void parseDense(Osmformat.DenseNodes nodes) {
		blockType |= TYPE_DENSE;
		if (skipNodes)
			return;
		long lastId = 0, lastLat = 0, lastLon = 0;
		int j = 0;
		int maxi = nodes.getIdCount();
		for (int i = 0; i < maxi; i++) {
			long lat = nodes.getLat(i) + lastLat;
			lastLat = lat;
			long lon = nodes.getLon(i) + lastLon;
			lastLon = lon;
			long id = nodes.getId(i) + lastId;
			lastId = id;
			double latf = parseLat(lat), lonf = parseLon(lon);

			Node tmp = new Node();
			tmp.set(id, latf, lonf);
			if (nodes.hasDenseinfo())
				tmp.setVersion(nodes.getDenseinfo().getVersion(i));

			if (!skipTags && nodes.getKeysValsCount() > 0) {
				while (nodes.getKeysVals(j) != 0) {
					int keyid = nodes.getKeysVals(j++);
					int valid = nodes.getKeysVals(j++);
					tmp.addTag(getStringById(keyid), getStringById(valid));
				}
				j++; // Skip over the '0' delimiter.

			}
			processor.processNode(tmp);
			elemCounter.countNode(tmp.getId());
		}
	}

	@Override
	protected void parseNodes(List<Osmformat.Node> nodes) {
		if (nodes.isEmpty())
			return;
		blockType |= TYPE_NODES;
		if (skipNodes)
			return;
		for (Osmformat.Node i : nodes) {
			Node tmp = new Node();
			for (int j = 0; j < i.getKeysCount(); j++)
				tmp.addTag(getStringById(i.getKeys(j)), getStringById(i.getVals(j)));
			long id = i.getId();
			double latf = parseLat(i.getLat()), lonf = parseLon(i.getLon());

			tmp.set(id, latf, lonf);
			if (i.hasInfo())
				tmp.setVersion(i.getInfo().getVersion());

			processor.processNode(tmp);
			elemCounter.countNode(tmp.getId());
		}
	}

	@Override
	protected void parseWays(List<Osmformat.Way> ways) {
		long numways = ways.size();
		if (numways == 0)
			return;
		blockType |= TYPE_WAYS;
		if (skipWays)
			return;
		for (Osmformat.Way i : ways) {
			Way tmp = new Way();
			if (!skipTags) {
				for (int j = 0; j < i.getKeysCount(); j++)
					tmp.addTag(getStringById(i.getKeys(j)), getStringById(i.getVals(j)));
			}
			long lastId = 0;
			for (long j : i.getRefsList()) {
				tmp.addRef(j + lastId);
				lastId = j + lastId;
			}

			long id = i.getId();
			tmp.setId(id);
			if (i.hasInfo())
				tmp.setVersion(i.getInfo().getVersion());

			processor.processWay(tmp);
			elemCounter.countWay(i.getId());
		}
	}

	@Override
	protected void parseRelations(List<Osmformat.Relation> rels) {
		if (rels.isEmpty())
			return;
		blockType |= TYPE_RELS;
		if (skipRels)
			return;
		for (Osmformat.Relation i : rels) {
			Relation tmp = new Relation();
			if (!skipTags) {
				for (int j = 0; j < i.getKeysCount(); j++)
					tmp.addTag(getStringById(i.getKeys(j)), getStringById(i.getVals(j)));
			}
			long id = i.getId();
			tmp.setId(id);
			tmp.setVersion(i.getInfo().getVersion());

			long lastMemId = 0;
			for (int j = 0; j < i.getMemidsCount(); j++) {
				long mid = lastMemId + i.getMemids(j);
				lastMemId = mid;
				String role = getStringById(i.getRolesSid(j));
				String etype = null;

				if (i.getTypes(j) == Osmformat.Relation.MemberType.NODE)
					etype = "node";
				else if (i.getTypes(j) == Osmformat.Relation.MemberType.WAY)
					etype = "way";
				else if (i.getTypes(j) == Osmformat.Relation.MemberType.RELATION)
					etype = "relation";
				else
					assert false; // TODO; Illegal file?

				tmp.addMember(etype, mid, role);
			}
			processor.processRelation(tmp);
			elemCounter.countRelation(tmp.getId());
		}
	}

	@Override
	public void parse(Osmformat.HeaderBlock block) {

		for (String s : block.getRequiredFeaturesList()) {
			if (s.equals("OsmSchema-V0.6"))
				continue; // OK.
			if (s.equals("DenseNodes"))
				continue; // OK.
			throw new UnknownFeatureException(s);
		}

		if (block.hasBbox()) {
			final double multiplier = .000000001;
			double rightf = block.getBbox().getRight() * multiplier;
			double leftf = block.getBbox().getLeft() * multiplier;
			double topf = block.getBbox().getTop() * multiplier;
			double bottomf = block.getBbox().getBottom() * multiplier;

			if (msgLevel > 0)
				System.out.println("Bounding box " + leftf + " " + bottomf + " " + rightf + " " + topf);

			OsmBounds osmBounds = new OsmBounds(bottomf, leftf, topf, rightf);
			Area area = osmBounds.toArea();
			if (!area.verify())
				throw new IllegalArgumentException("invalid bbox area in pbf file: " + area);
			processor.boundTag(osmBounds);
		}
	}
}
