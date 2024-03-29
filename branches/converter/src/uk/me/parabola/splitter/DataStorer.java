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
package uk.me.parabola.splitter;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import uk.me.parabola.splitter.tools.Long2IntClosedMapFunction;
import uk.me.parabola.splitter.tools.OSMId2ObjectMap;
import uk.me.parabola.splitter.tools.SparseLong2IntMap;
import uk.me.parabola.splitter.writer.BoundedOsmWriter;

/**
 * Stores data that is needed in different passes of the program.
 * 
 * @author GerdP
 *
 */
public class DataStorer {
	public static final int NODE_TYPE = 0;
	public static final int WAY_TYPE = 1;
	public static final int REL_TYPE = 2;

	private final int numOfAreas;

	private final Long2IntClosedMapFunction[] maps = new Long2IntClosedMapFunction[3];

	private final AreaDictionary areaDictionary;
	private final AreaIndex areaIndex;
	private SparseLong2IntMap usedWays = null;
	private final OSMId2ObjectMap<Integer> usedRels = new OSMId2ObjectMap<>();
	private boolean idsAreNotSorted;
	private BoundedOsmWriter[] writers;
	/**
	 * map with relations that should be complete and are written to only one
	 * tile
	 */
	private final Long2ObjectOpenHashMap<Integer> oneDistinctAreaOnlyRels = new Long2ObjectOpenHashMap<>();
	private final OSMId2ObjectMap<Integer> oneTileOnlyRels = new OSMId2ObjectMap<>();

	/**
	 * Create a dictionary for a given number of writers
	 * 
	 * @param overlapAmount
	 * @param numOfWriters
	 *            the number of writers that are used
	 */
	DataStorer(List<Area> areas, int overlapAmount) {
		this.numOfAreas = areas.size();
		this.areaDictionary = new AreaDictionary(areas, overlapAmount);
		this.areaIndex = new AreaGrid(areaDictionary);
		return;
	}

	public int getNumOfAreas() {
		return numOfAreas;
	}

	public AreaDictionary getAreaDictionary() {
		return areaDictionary;
	}

	public Area getArea(int idx) {
		return areaDictionary.getArea(idx);
	}

	public Area getExtendedArea(int idx) {
		return areaDictionary.getExtendedArea(idx);
	}

	public void setWriters(BoundedOsmWriter[] writers) {
		this.writers = writers;
	}

	public void setWriterMap(int type, Long2IntClosedMapFunction nodeWriterMap) {
		maps[type] = nodeWriterMap;
	}

	public Long2IntClosedMapFunction getWriterMap(int type) {
		return maps[type];
	}

	public AreaIndex getGrid() {
		return areaIndex;
	}

	public SparseLong2IntMap getUsedWays() {
		return usedWays;
	}

	public OSMId2ObjectMap<Integer> getUsedRels() {
		return usedRels;
	}

	public void setUsedWays(SparseLong2IntMap ways) {
		usedWays = ways;
	}

	public boolean isIdsAreNotSorted() {
		return idsAreNotSorted;
	}

	public void setIdsAreNotSorted(boolean idsAreNotSorted) {
		this.idsAreNotSorted = idsAreNotSorted;
	}

	public void restartWriterMaps() {
		for (Long2IntClosedMapFunction map : maps) {
			if (map != null) {
				try {
					map.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}

	}

	public void switchToSeqAccess(File fileOutputDir) throws IOException {
		boolean msgWritten = false;
		long start = System.currentTimeMillis();
		for (Long2IntClosedMapFunction map : maps) {
			if (map != null) {
				if (!msgWritten) {
					System.out.println("Writing results of MultiTileAnalyser to temp files ...");
					msgWritten = true;
				}
				map.switchToSeqAccess(fileOutputDir);
			}
		}
		System.out.println("Writing temp files took " + (System.currentTimeMillis() - start) + " ms");
	}

	public void finish() {
		for (Long2IntClosedMapFunction map : maps) {
			if (map != null)
				map.finish();
		}
	}

	public void stats(final String prefix) {
		for (Long2IntClosedMapFunction map : maps) {
			if (map != null)
				map.stats(prefix);
		}
	}

	public BoundedOsmWriter[] getWriters() {
		return writers;
	}

	public void storeRelationAreas(long id, AreaSet areaSet) {
		oneDistinctAreaOnlyRels.put(id, areaDictionary.translate(areaSet));
	}

	public Integer getOneTileOnlyRels(long id) {
		return oneTileOnlyRels.get(id);
	}

	/**
	 * If the ids in oneTileOnlyRels were produced with a different set
	 * of areas we have to translate the values
	 * 
	 * @param distinctAreas
	 *            list of distinct (non-overlapping) areas
	 * @param distinctDataStorer
	 */
	public void translateDistinctToRealAreas(DataStorer distinctDataStorer) {
		List<Area> distinctAreas = distinctDataStorer.getAreaDictionary().getAreas();
		Map<Area, Integer> map = new HashMap<>();
		for (Area distinctArea : distinctAreas) {
			if (distinctArea.getMapId() < 0 && !distinctArea.isPseudoArea()) {
				AreaSet w = new AreaSet();
				for (int i = 0; i < getNumOfAreas(); i++) {
					if (this.areaDictionary.getArea(i).contains(distinctArea)) {
						w.set(i);
					}
				}
				map.put(distinctArea, this.areaDictionary.translate(w));
			}
		}

		for (Entry<Long, Integer> e : distinctDataStorer.oneDistinctAreaOnlyRels.entrySet()) {
			AreaSet singleArea =  distinctDataStorer.getAreaDictionary().getSet(e.getValue());
			assert singleArea.cardinality() == 1;
			int pos = singleArea.iterator().next();
			if (!distinctAreas.get(pos).isPseudoArea()) {
				Integer areaIdx = map.get(distinctAreas.get(pos));
				oneTileOnlyRels.put(e.getKey(), areaIdx != null ? areaIdx : e.getValue());
			} else {
				oneTileOnlyRels.put(e.getKey(), AbstractMapProcessor.UNASSIGNED);
			}

		}
	}
}
