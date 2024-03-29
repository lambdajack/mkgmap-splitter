/*
 * Copyright (C) 2014, Gerd Petermann
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
package uk.me.parabola.splitter.solver;

import java.awt.Rectangle;
import java.util.BitSet;

import uk.me.parabola.splitter.Area;
import uk.me.parabola.splitter.Utils;

/**
 * Contains info that is needed by the {@link Tile} class. For a given
 * DensityMap we calculate some extra info to allow faster access to row sums
 * and column sums.
 * 
 * @author GerdP
 * 
 */
public class EnhancedDensityMap {
	private final DensityMap densityMap;
	private int[][] xyMap;
	private int[][] yxMap;
	private BitSet xyOutsidePolygon = new BitSet();
	private double[] aspectRatioFactor;
	private int minAspectRatioFactorPos;
	private int maxNodesInDensityMapGridElement = Integer.MIN_VALUE;
	private int maxNodesInDensityMapGridElementInPoly = Integer.MIN_VALUE;
	private java.awt.geom.Area polygonArea;

	public EnhancedDensityMap(DensityMap densities, java.awt.geom.Area polygonArea) {
		this.densityMap = densities;
		this.polygonArea = polygonArea;
		prepare();
	}

	/**
	 * If a polygon is given, filter the density data Compute once complex
	 * trigonometric results for needed for proper aspect ratio calculations.
	 * 
	 */
	private void prepare() {
		// performance: calculate only once the needed complex math results
		aspectRatioFactor = new double[densityMap.getHeight() + 1];
		int minLat = densityMap.getBounds().getMinLat();
		int maxLat = densityMap.getBounds().getMaxLat();
		int lat = 0;
		double maxAspectRatioFactor = Double.MIN_VALUE;
		int minPos = Integer.MAX_VALUE;
		for (int i = 0; i < aspectRatioFactor.length; i++) {
			lat = minLat + i * (1 << densityMap.getShift());
			assert lat <= maxLat;
			aspectRatioFactor[i] = Math.cos(Math.toRadians(Utils.toDegrees(lat)));
			if (maxAspectRatioFactor < aspectRatioFactor[i]) {
				maxAspectRatioFactor = aspectRatioFactor[i];
				minPos = i;
			}
		}
		minAspectRatioFactorPos = minPos;
		assert lat == maxLat;

		// filter the density map and populate xyMap
		int width = densityMap.getWidth();
		int height = densityMap.getHeight();
		xyMap = new int[width][];
		int shift = densityMap.getShift();
		for (int x = 0; x < width; x++) {
			int polyXPos = densityMap.getBounds().getMinLong() + (x << shift);
			int[] xCol = null;
			xCol = new int[height];
			boolean colNeeded = false;
			for (int y = 0; y < height; y++) {
				int count = densityMap.getNodeCount(x, y);
				if (polygonArea != null) {
					int polyYPos = densityMap.getBounds().getMinLat() + (y << shift);
					if (polygonArea.intersects(polyXPos, polyYPos, 1 << shift, 1 << shift)) {
						maxNodesInDensityMapGridElementInPoly = Math.max(count, maxNodesInDensityMapGridElementInPoly);
					} else {
						xyOutsidePolygon.set(x * height + y);
					}
				}
				if (count > 0) {
					maxNodesInDensityMapGridElement = Math.max(count, maxNodesInDensityMapGridElement);
					xCol[y] = count;
					colNeeded = true;
				}
			}
			if (colNeeded)
				xyMap[x] = xCol;
		}
		
		// create and populate yxMap, this helps to speed up row access
		yxMap = new int[height][];
		for (int y = 0; y < height; y++) {
			boolean rowNeeded = false;
			int[] yRow = new int[width];
			for (int x = 0; x < width; x++) {
				int count = 0;
				if (xyMap[x] != null)
					count = xyMap[x][y];
				if (count > 0) {
					rowNeeded = true;
					yRow[x] = count;
				}
			}
			if (rowNeeded)
				yxMap[y] = yRow;
		}
	}

	public boolean isGridElemInPolygon(int x, int y) {
		if (polygonArea == null || xyOutsidePolygon.isEmpty())
			return true;
		return !xyOutsidePolygon.get(x * densityMap.getHeight() + y);
	}

	// calculate aspect ratio of a tile which is a view on the densityMap
	public double getAspectRatio(Rectangle r) {
		double ratio;
		double maxWidth;
		if (r.y < minAspectRatioFactorPos && r.y + r.height > minAspectRatioFactorPos) {
			maxWidth = r.width; // tile crosses equator
		} else {
			double width1 = r.width * aspectRatioFactor[r.y];
			double width2 = r.width * aspectRatioFactor[r.y + r.height];
			maxWidth = Math.max(width1, width2);
		}
		ratio = maxWidth / r.height;
		return ratio;
	}

	public Area getBounds() {
		return densityMap.getBounds();
	}

	public DensityMap getDensityMap() {
		return densityMap;
	}

	public long getNodeCount() {
		return densityMap.getNodeCount();
	}

	public int[] getMapRow(int mapRow) {
		return yxMap[mapRow];
	}

	public int[] getMapCol(int mapCol) {
		return xyMap[mapCol];
	}

	public double[] getAspectRatioFactor() {
		return aspectRatioFactor;
	}

	public int getMinAspectRatioFactorPos() {
		return minAspectRatioFactorPos;
	}

	public int getMaxNodesInDensityMapGridElement() {
		return maxNodesInDensityMapGridElement;
	}

	public int getMaxNodesInDensityMapGridElementInPoly() {
		return maxNodesInDensityMapGridElementInPoly;
	}

	public java.awt.geom.Area getPolygonArea() {
		return polygonArea;
	}

	public boolean allInsidePolygon() {
		return polygonArea == null || xyOutsidePolygon.isEmpty();
	}
	
}
