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

package uk.me.parabola.splitter.solver;

import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import uk.me.parabola.splitter.Area;
import uk.me.parabola.splitter.RoundingUtils;
import uk.me.parabola.splitter.SplitFailedException;
import uk.me.parabola.splitter.Utils;

/**
 * Splits a density map into multiple areas, none of which exceed the desired
 * threshold.
 *
 * @author Chris Miller, Gerd Petermann
 */
public class SplittableDensityArea {
	private static final int MAX_LAT_DEGREES = 85;
	private static final int MAX_LON_DEGREES = 90;
	public static final int MAX_SINGLE_POLYGON_VERTICES = 40;
	private static final int MAX_LOOPS = 100; // number of loops to find better solution for one rectangular area
	static final int AXIS_HOR = 0;
	static final int AXIS_VERT = 1;
	public static final double NICE_MAX_ASPECT_RATIO = 4;
	private static final double VERY_NICE_FILL_RATIO = 0.94;
	private static final long LARGE_MAX_NODES = 10_000_000;
	private static final double MAX_OUTSIDE_RATIO = 0.5; 
	private static final int MIN_TILE_AREA_BAD_CACHE = 100;
	private static final int MAX_DEPTH_STATS = 10;
	private boolean enableExtraOpt = true; // option ? 
	
	private final int startSearchLimit;
	

	private final DensityMap allDensities;
	private EnhancedDensityMap extraDensityInfo;

	private boolean beQuiet;
	private static final boolean DEBUG = false;
	private long maxNodes;
	private int stopNumber;
	private final int shift;
	
	private final boolean trimShape;
	private boolean trimTiles;
	private boolean allowEmptyPart;
	private int currMapId;
	private boolean hasEmptyPart;
	private int solverId;

	public SplittableDensityArea(DensityMap densities, int startSearchLimit, boolean trim) {
		this.shift = densities.getShift();
		this.startSearchLimit = startSearchLimit;
		this.trimShape = trim;
		allDensities = densities;
	}

	public DensityMap getAllDensities() {
		return allDensities;
	}

	public void setMapId(int mapId) {
		currMapId = mapId;
	}

	public void setMaxNodes(long maxNodes) {
		this.maxNodes = maxNodes;
	}

	public boolean hasData() {
		return allDensities != null && allDensities.getNodeCount() > 0;
	}

	/**
	 * @return the area that this splittable area represents
	 */
	public Area getBounds() {
		return allDensities.getBounds();
	}

	/**
	 * Calculate a solution (list of areas that either matches the given criteria or is empty) 
	 * 
	 * @return solution (can be empty if none was found with the given criteria)
	 */
	private Solution split() {
		Solution fullSolution = new Solution(maxNodes);
		if (allDensities == null || allDensities.getNodeCount() == 0)
			return fullSolution;
		prepare(null);
		Tile startTile = new Tile(extraDensityInfo);
		List<Tile> startTiles = new ArrayList<>();
		if (trimShape || allDensities.getBounds().getWidth() >= 0x1000000) {
			// if trim is wanted or tile spans over planet
			// we try first to find large empty areas (sea)
			startTiles.addAll(checkForEmptyClusters(0, startTile, true));
		} else {
			startTiles.add(startTile);
		}

		int countNoSol;
		while (true) {
			countNoSol = 0;
			for (Tile tile : startTiles) {
				hasEmptyPart = false; // possibly overwritten in solveRectangularArea
				if (!beQuiet)
					System.out.println("Solving partition " + tile.toString());
				Solution solution = solveRectangularArea(tile);
				if (solution != null && !solution.isEmpty())
					fullSolution.merge(solution);
				else {
					countNoSol++;
					if (!beQuiet)
						System.out.println("Warning: No solution found for partition " + tile.toString());
				}
			}
			if (countNoSol == 0)
				break;
			if (allowEmptyPart || !hasEmptyPart)
				break;
			allowEmptyPart = true;
			fullSolution = new Solution(maxNodes);
		}
		if (countNoSol > 0 && stopNumber == 0)
			throw new SplitFailedException("Failed to find a correct split");
		if (!beQuiet) {
			printFinalSplitMsg(fullSolution);
		}
		return fullSolution;
	}

	/**
	 * Split with a given polygon and max nodes threshold. If the polygon is not
	 * singular, it is divided into singular areas.
	 * 
	 * @param polygonArea
	 * @return list of areas
	 */
	private List<Area> split(java.awt.geom.Area polygonArea) {
		if (polygonArea == null)
			return getAreas(split(), null);
		if (polygonArea.isSingular()) {
			java.awt.geom.Area rasteredArea = allDensities.rasterPolygon(polygonArea);
			List<List<Point>> shapes = Utils.areaToShapes(rasteredArea);
			List<Area> areas = new ArrayList<>();
			for (List<Point> shape : shapes) {
				java.awt.geom.Area rasteredPart = Utils.shapeToArea(shape);
				if (rasteredPart.isEmpty()) {
					System.err.println("Bounding polygon doesn't intersect with the bounding box of the input file(s)");
					return Collections.emptyList();
				}
				if (rasteredPart.isSingular()) {
					prepare(polygonArea);
					Tile tile = new Tile(extraDensityInfo, rasteredPart.getBounds());
					Solution solution = findSolutionWithSinglePolygon(0, tile, rasteredPart);
					if (solution == null && rasteredPart.isRectangular()) 
						solution = split();
					if (solution != null) { 
						areas.addAll(getAreas(solution, polygonArea));
					}
				}
			}
			return areas;
		}
		if (polygonArea.intersects(Utils.area2Rectangle(allDensities.getBounds(), 0)))
			return splitPolygon(polygonArea);
		System.err.println("Bounding polygon doesn't intersect with the bounding box of the input file(s)");
		return Collections.emptyList();
	}

	/**
	 * Split a list of named polygons. Overlapping areas of the polygons are
	 * extracted and each one is split for itself. A polygon may not be singular.
	 * 
	 * @param namedPolygons list of polygons, if empty the tile bounds are used
	 * @return list of areas
	 */
	public List<Area> split(List<PolygonDesc> namedPolygons) {
		if (namedPolygons.isEmpty()) {
			return getAreas(split(), null);
		}
		List<Area> result = new ArrayList<>();
		class ShareInfo {
			java.awt.geom.Area area;
			final IntArrayList sharedBy = new IntArrayList();
		}
		List<ShareInfo> sharedParts = new ArrayList<>();
		for (int i = 0; i < namedPolygons.size(); i++) {
			boolean wasDistinct = true;
			PolygonDesc namedPart = namedPolygons.get(i);
			java.awt.geom.Area distinctPart = new java.awt.geom.Area(namedPart.getArea());
			for (int j = 0; j < namedPolygons.size(); j++) {
				if (j == i)
					continue;
				java.awt.geom.Area test = new java.awt.geom.Area(namedPart.getArea());
				test.intersect(namedPolygons.get(j).getArea());
				if (!test.isEmpty()) {
					wasDistinct = false;
					distinctPart.subtract(namedPolygons.get(j).getArea());
					if (j > i) {
						ShareInfo si = new ShareInfo();
						si.area = test;
						si.sharedBy.add(i);
						si.sharedBy.add(j);
						sharedParts.add(si);
					}
				}
			}
			if (!distinctPart.isEmpty() && distinctPart.intersects(Utils.area2Rectangle(allDensities.getBounds(), 0))) {
//				KmlWriter.writeKml("e:/ld_sp/distinct_"+namedPart.getName(), "distinct", distinctPart);
				if (!wasDistinct)
					System.out.println("splitting distinct part of " + namedPart.getName());
				else
					System.out.println("splitting " + namedPart.getName());
				result.addAll(split(distinctPart));
			}
		}

		for (int i = 0; i < sharedParts.size(); i++) {
			ShareInfo si = sharedParts.get(i);
			int last = namedPolygons.size(); // list is extended in the loop
			for (int j = 0; j < last; j++) {
				if (si.sharedBy.contains(j))
					continue;
				java.awt.geom.Area test = new java.awt.geom.Area(si.area);
				test.intersect(namedPolygons.get(j).getArea());
				if (!test.isEmpty()) {
					si.area.subtract(test);
					if (j > si.sharedBy.getInt(si.sharedBy.size() - 1)) {
						ShareInfo si2 = new ShareInfo();
						si2.area = test;
						si2.sharedBy.addAll(si.sharedBy);
						si2.sharedBy.add(j);
						sharedParts.add(si2);
					}
				}
				if (si.area.isEmpty())
					break;
			}
			if (!si.area.isEmpty() && si.area.intersects(Utils.area2Rectangle(allDensities.getBounds(), 0))) {
				String desc = "";
				for (int pos : si.sharedBy)
					desc += namedPolygons.get(pos).getName() + " and ";
				desc = desc.substring(0, desc.lastIndexOf(" and"));
				System.out.println("splitting area shared by exactly " + si.sharedBy.size() + " polygons: " + desc);
//				KmlWriter.writeKml("e:/ld_sp/shared_"+desc.replace(" " , "_"), desc, si.area);
				result.addAll(split(si.area));
			}
		}
		return result;
	}

	/**
	 * Split into a given number of tiles.
	 * 
	 * @param wantedTiles
	 * @return list of areas
	 */
	public List<Area> split(int wantedTiles) {
		this.stopNumber = wantedTiles;
		long currMaxNodes = (long) (this.allDensities.getNodeCount() / (wantedTiles * 0.95));
		class Pair {
			long myMaxNodes;
			int numTiles;

			Pair(long maxNodes, int numTiles) {
				this.myMaxNodes = maxNodes;
				this.numTiles = numTiles;
			}
		}
		Pair bestBelow = null;
		Pair bestAbove = null;
		beQuiet = true;
		while (true) {
			this.setMaxNodes(currMaxNodes);
			System.out.println("Trying a max-nodes value of " + currMaxNodes + " to split "
					+ allDensities.getNodeCount() + " nodes into " + wantedTiles + " areas");
			Solution sol = split();
			
			if (sol.isEmpty() || sol.size() == wantedTiles) {
				beQuiet = false;
				printFinalSplitMsg(sol);
				return getAreas(sol, null);
			}
			
			Pair pair = new Pair(currMaxNodes, sol.size());
			if (sol.size() > wantedTiles) {
				if (bestAbove == null || bestAbove.numTiles > pair.numTiles
						|| (bestAbove.numTiles == pair.numTiles && pair.myMaxNodes < bestAbove.myMaxNodes))
					bestAbove = pair;
			} else {
				if (bestBelow == null || bestBelow.numTiles < pair.numTiles
						|| (bestBelow.numTiles == pair.numTiles && pair.myMaxNodes > bestBelow.myMaxNodes))
					bestBelow = pair;
			}
			long testMaxNodes;
			if (bestBelow == null || bestAbove == null)
				testMaxNodes = Math.min(Math.round((double) currMaxNodes * sol.size() / wantedTiles),
						this.allDensities.getNodeCount() - 1);
			else
				testMaxNodes = (bestBelow.myMaxNodes + bestAbove.myMaxNodes) / 2;
			if (testMaxNodes == currMaxNodes) {
				System.err.println("Cannot find a good split with exactly " + wantedTiles + " areas");
				printFinalSplitMsg(sol);
				return getAreas(sol, null);
			}
			currMaxNodes = testMaxNodes;
		}
	}

	/**
	 * Filter the density data, calculate once complex trigonometric results
	 * 
	 * @param polygonArea
	 */
	private void prepare(java.awt.geom.Area polygonArea) {
		extraDensityInfo = new EnhancedDensityMap(allDensities, polygonArea);
		if (!beQuiet) {
			System.out.println("Highest node count in a single grid element is "
					+ Utils.format(extraDensityInfo.getMaxNodesInDensityMapGridElement()));
			if (polygonArea != null) {
				System.out.println("Highest node count in a single grid element within the bounding polygon is "
						+ Utils.format(extraDensityInfo.getMaxNodesInDensityMapGridElementInPoly()));
			}
		}
		if (polygonArea != null)
			trimTiles = true;

	}

	/**
	 * Try to find empty areas. This will fail if the empty area is enclosed by a
	 * non-empty area.
	 * 
	 * @param depth      recursion depth
	 * @param tile       the tile that might contain an empty area
	 * @param splitHoriz true: search horizontal, else vertical
	 * @return a list containing one or more tiles, cut from the original tile, or
	 *         just the original tile
	 */
	private ArrayList<Tile> checkForEmptyClusters(int depth, final Tile tile, boolean splitHoriz) {
		java.awt.geom.Area area = new java.awt.geom.Area(tile);
		int firstEmpty = -1;
		int countEmpty = 0;
		long countLastPart = 0;
		long countRemaining = tile.getCount();
		int maxEmpty = Utils.toMapUnit(30) / (1 << shift);
		int minEmpty = Utils.toMapUnit(10) / (1 << shift);
		if (splitHoriz) {
			for (int i = 0; i < tile.width; i++) {
				long count = tile.getColSum(i);
				if (count == 0) {
					if (firstEmpty < 0)
						firstEmpty = i;
					countEmpty++;
				} else {
					if (countEmpty > maxEmpty
							|| (countEmpty > minEmpty && countLastPart > maxNodes / 3 && countRemaining > maxNodes / 3)) {
						java.awt.geom.Area empty = new java.awt.geom.Area(
								new Rectangle(firstEmpty, tile.y, countEmpty, tile.height));
						area.subtract(empty);
						countLastPart = 0;
					}
					countRemaining -= count;
					firstEmpty = -1;
					countEmpty = 0;
					countLastPart += count;
				}
			}
		} else {
			for (int i = 0; i < tile.height; i++) {
				long count = tile.getRowSum(i);
				if (count == 0) {
					if (firstEmpty < 0)
						firstEmpty = i;
					countEmpty++;
				} else {
					if (countEmpty > maxEmpty
							|| (countEmpty > minEmpty && countLastPart > maxNodes / 3 && countRemaining > maxNodes / 3)) {
						java.awt.geom.Area empty = new java.awt.geom.Area(
								new Rectangle(tile.x, firstEmpty, tile.width, countEmpty));
						area.subtract(empty);
						countLastPart = 0;
					}
					countRemaining -= count;
					firstEmpty = -1;
					countEmpty = 0;
					countLastPart += count;
				}
			}
		}
		ArrayList<Tile> clusters = new ArrayList<>();
		if (depth == 0 && area.isSingular()) {
			// try also the other split axis
			clusters.addAll(checkForEmptyClusters(depth + 1, tile.trim(), !splitHoriz));
		} else {
			if (area.isSingular()) {
				clusters.add(tile.trim());
			} else {
				List<List<Point>> shapes = Utils.areaToShapes(area);
				for (List<Point> shape : shapes) {
					java.awt.geom.Area part = Utils.shapeToArea(shape);
					Tile t = new Tile(extraDensityInfo, part.getBounds());
					if (t.getCount() > 0)
						clusters.addAll(checkForEmptyClusters(depth + 1, t.trim(), !splitHoriz));
				}
			}
		}
		return clusters;
	}

	/**
	 * Split, handling a polygon that may contain multiple distinct areas.
	 * 
	 * @param polygonArea
	 * @return a list of areas that cover the polygon
	 */
	private List<Area> splitPolygon(final java.awt.geom.Area polygonArea) {
		List<Area> result = new ArrayList<>();
		List<List<Point>> shapes = Utils.areaToShapes(polygonArea);
		for (int i = 0; i < shapes.size(); i++) {
			List<Point> shape = shapes.get(i);
			if (!Utils.clockwise(shape))
				continue;
			java.awt.geom.Area shapeArea = Utils.shapeToArea(shape);
			Rectangle rShape = shapeArea.getBounds();
			if (shape.size() > MAX_SINGLE_POLYGON_VERTICES) {
				shapeArea = new java.awt.geom.Area(rShape);
				System.out.println("Warning: shape is too complex, using rectangle " + rShape + " instead");
			}
			Area shapeBounds = new Area(rShape.y, rShape.x, (int) rShape.getMaxY(), (int) rShape.getMaxX());
			int resolution = 24 - allDensities.getShift();
			shapeBounds = RoundingUtils.round(shapeBounds, resolution);
			SplittableDensityArea splittableArea = new SplittableDensityArea(allDensities.subset(shapeBounds),
					startSearchLimit, trimShape);
			splittableArea.setMaxNodes(maxNodes);
			if (!splittableArea.hasData()) {
				System.out.println(
						"Warning: a part of the bounding polygon would be empty and is ignored:" + shapeBounds);
				// result.add(shapeBounds);
				continue;
			}
			List<Area> partResult = splittableArea.split(shapeArea);
			if (partResult != null)
				result.addAll(partResult);
		}
		return result;
	}

	/**
	 * Split the given tile using the given (singular) polygon area. The routine
	 * splits the polygon into parts and calls itself recursively for each part that
	 * is not rectangular.
	 * 
	 * @param depth               recursion depth
	 * @param tile                the tile to split
	 * @param rasteredPolygonArea an area describing a rectilinear shape
	 * @return a solution (maybe empty), or null if rasteredPolygon is not rectangular
	 */
	private Solution findSolutionWithSinglePolygon(int depth, final Tile tile, java.awt.geom.Area rasteredPolygonArea) {
		if (!rasteredPolygonArea.isSingular()) {
			return null;
		}
		if (rasteredPolygonArea.isRectangular()) {
			Tile part = new Tile(extraDensityInfo, rasteredPolygonArea.getBounds());
			return solveRectangularArea(part);
		}
		List<List<Point>> shapes = Utils.areaToShapes(rasteredPolygonArea);
		List<Point> shape = shapes.get(0);

		if (shape.size() > MAX_SINGLE_POLYGON_VERTICES) {
			Tile part = new Tile(extraDensityInfo, rasteredPolygonArea.getBounds());
			System.out.println("Warning: rastered shape is too complex, using rectangle " + part + " instead");
			return solveRectangularArea(part);
		}

		Rectangle pBounds = rasteredPolygonArea.getBounds();
		int lastPoint = shape.size() - 1;
		if (shape.get(0).equals(shape.get(lastPoint)))
			--lastPoint;
		for (int i = 0; i <= lastPoint; i++) {
			Point point = shape.get(i);
			if (i > 0 && point.equals(shape.get(0)))
				continue;
			int cutX = point.x;
			int cutY = point.y;
			Solution part0Sol = null, part1Sol = null;
			for (int axis = 0; axis < 2; axis++) {
				Rectangle r1, r2;
				if (axis == AXIS_HOR) {
					r1 = new Rectangle(pBounds.x, pBounds.y, cutX - pBounds.x, pBounds.height);
					r2 = new Rectangle(cutX, pBounds.y, (int) (pBounds.getMaxX() - cutX), pBounds.height);
				} else {
					r1 = new Rectangle(pBounds.x, pBounds.y, pBounds.width, cutY - pBounds.y);
					r2 = new Rectangle(pBounds.x, cutY, pBounds.width, (int) (pBounds.getMaxY() - cutY));
				}

				if (r1.width * r1.height > r2.width * r2.height) {
					Rectangle help = r1;
					r1 = r2;
					r2 = help;
				}
				if (!r1.isEmpty() && !r2.isEmpty()) {
					java.awt.geom.Area area = new java.awt.geom.Area(r1);
					area.intersect(rasteredPolygonArea);

					part0Sol = findSolutionWithSinglePolygon(depth + 1, tile, area);
					if (part0Sol != null && !part0Sol.isEmpty()) {
						area = new java.awt.geom.Area(r2);
						area.intersect(rasteredPolygonArea);
						part1Sol = findSolutionWithSinglePolygon(depth + 1, tile, area);
						if (part1Sol != null && !part1Sol.isEmpty())
							break;
					}
				}
			}
			if (part0Sol != null && part1Sol != null) {
				part0Sol.merge(part1Sol);
				return part0Sol;
			}
		}
		return new Solution(maxNodes);
	}

	private Solution solveRectangularArea(Tile startTile) {
		int bestPossible = stopNumber > 0 ? stopNumber : startTile.getMinParts(maxNodes);
		System.out.println("Splitting tile " + startTile + ", goal is to get near " + bestPossible + " tiles");
		return solveRectangularAreaParallel(startTile, 0);
	}
	
	/**
	 * Split large tile into smaller parts with a simple split and solve the small parts using parallel stream.
	 * @param startTile the tile to split
	 * @param depth recursion depth
	 * @return solution
	 */
	private Solution solveRectangularAreaParallel(Tile startTile, int depth) {
		if (depth == 0 && (stopNumber > 0 || startTile.getCount() < 256 * maxNodes))
			return solveRectangularAreaOne(startTile);

		Solution res = new Solution(maxNodes);
		long partSize = 64 * maxNodes;
		if (depth > 0) {
			partSize = Math.max(1, startTile.getCount() - 1);
		}
		List<Tile> todo = startTile.divide(partSize);
		System.out.println("Initial simple split returned " + todo.size() + " tile(s)");
		List<Solver> solvers = new ArrayList<>();
		List<Area> initialAreas = new ArrayList<>();

		for (Tile t : todo) {
			if (t.outsidePolygon())
				continue;
			if (trimTiles)
				t = t.trim();
			int areaSize = t.width * t.height;
			boolean useSearchAll = areaSize < 32_000 || t.getCount() < 16 * maxNodes;
			boolean anyOutside = t.countElemsOutside() > 0;
			Solver solver = new Solver(++solverId, useSearchAll, maxNodes, t, shift, 0, trimTiles, startSearchLimit, allowEmptyPart);
			solver.maxAspectRatio = getStartRatio(startTile);
			
			System.out.println("Using " + solver.toString() + " on " + Utils.format(areaSize) + " grid elements"
					+ (trimTiles && anyOutside ? ", trim needed" : ", trim not needed"));
			
			Rectangle r = t.getRealBBox();
			Area area = new Area(r.y, r.x, (int) r.getMaxY(), (int) r.getMaxX());
			area.setMapId(solverId);
			initialAreas.add(area);
//			if (depth > 0 ||solver.name.startsWith("S19 "))
			solvers.add(solver);
		}

		solvers.parallelStream().forEach(Solver::solve);
		List<Solver> solvers2 = new ArrayList<>();
		if (enableExtraOpt) {
			for (int i = 0; i < solvers.size(); i++) {
				Solver solver = solvers.get(i);
				Solution s = solver.bestSolution;
				int goal = solver.startTile.getMinParts(maxNodes);
				int areaSize = solver.startTile.width * solver.startTile.height;
				if (areaSize > 200_000)
					continue;
				if (s.size() > 1 && (!s.isNice() || s.size() >= goal + 3)) {
					System.out.println("trying to improve poor solution from " + solver);
					Solver sv2 = new Solver(++solverId, !solver.searchAll, maxNodes, solver.startTile, shift, stopNumber, solver.trimTiles, startSearchLimit, allowEmptyPart);
					System.out.println("Starting " + sv2.toString());
					sv2.maxAspectRatio = getStartRatio(startTile);
					solvers2.add(sv2);
				}
			}
			
			solvers2.parallelStream().forEach(Solver::solve);
		}
		for (Solver sv : solvers) {
			Solution sol = sv.bestSolution;
			Optional<Solver> opt = solvers2.stream().filter(s2 -> sv.startTile.equals(s2.startTile)).findAny();
			if (opt.isPresent()) {
				Solution sol2 = opt.get().bestSolution;
				if (sol2.isNice() && sol2.isSmallerOrBetter(sol)) {
					System.out.println(opt.get().name + ": replaced solution from " + sv.name);
					sol = sol2;
				}
			}
			if (sol.isEmpty())
				sol = solveRectangularAreaParallel(sv.startTile, depth + 1);
			res.merge(sol);
		}
		return res;
	}

	/**
	 * Get a first solution and search for better ones until either a nice solution
	 * is found or no improvement was found.
	 * 
	 * @param startTile the tile to split
	 * @return a solution (maybe be empty)
	 */
	private Solution solveRectangularAreaOne(Tile startTile) {
		// start values for optimization process: we make little steps towards a good
		// solution
		if (startTile.getCount() == 0)
			return new Solution(maxNodes);
		
		List<Solver> solvers = new ArrayList<>();
		List<Future<?>> futures = new ArrayList<>();
		int numAlgos = 2;
		ExecutorService threadPool = Executors.newFixedThreadPool(numAlgos);
		
		for (int i = 0; i < numAlgos; i++) {
			Solver solver = new Solver(++solverId, i == 1, maxNodes, startTile, shift, stopNumber, trimTiles, startSearchLimit, allowEmptyPart);
			if (solver.searchAll && startTile.getCount() > 300 * maxNodes)
				continue; // too complex for FULL
			if (!solver.searchAll && stopNumber == 0 && startTile.getCount() < 10 * maxNodes)
				continue; // too simple for SOME
			solver.maxAspectRatio = getStartRatio(startTile);
			solvers.add(solver);
			futures.add(threadPool.submit(solver::solve));
		}
		
		threadPool.shutdown();
		
		Instant t1 = null;
		final double n75 = 0.75 * maxNodes;
		final double n85 = 0.85 * maxNodes;
		while (!threadPool.isTerminated()) {
			for (int i = 0; i < solvers.size(); i++) {
				Future<?> future = futures.get(i);
				if (future.isDone()) {
					try {
						future.get();
					} catch (InterruptedException | ExecutionException e) {
						throw new SplitFailedException("parallel solver crashed", e.getCause());
					}
					Solution sol = solvers.get(i).bestSolution;
					if (sol.isNice()) {
						if (t1 == null)
							t1 = Instant.now();
						long dt = Duration.between(t1, Instant.now()).getSeconds();
						boolean stop = false;
						if (sol.getWorstMinNodes() >= n85 && dt > 10) {
							stop = true; // all tiles have at least 85% of max-nodes
						} else {
							int num75 = 0;
							for (Tile tile : sol.getTiles()) {
								if (tile.getCount() < n75)
									num75++;
							}
							double below75 = 100.0 * num75 / sol.size();
							if (below75 > 5 && dt > 30) {
								// +5 percent of tiles are less the 75 percent but we waited +30 seconds
								stop = true;
							}
						}
						if (stop) {
							// stop the other solver
							solvers.forEach(Solver::stop);
						}
					}
				}
			}
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		// call get() on each future to recognise possible exceptions 
		futures.forEach(f -> {
			try {
				f.get();
			} catch (InterruptedException | ExecutionException e) {
				Thread.currentThread().interrupt();
				throw new SplitFailedException("parallel solver crashed", e.getCause());
			}
		});
		// sort by number of tiles so that the smaller number comes first
		// can't use compareTo here as it prefers the higher worstMinNodes value
		solvers.sort((o1, o2) -> {
			int d = Boolean.compare(o1.bestSolution.isNice(), o2.bestSolution.isNice());
			if (d != 0)
				return -d; // prefer nice
			d = Integer.compare(o1.bestSolution.size(), o2.bestSolution.size());
			if (d != 0)
				return d;
			// prefer higher min-nodes
			return Long.compare(o2.bestSolution.getWorstMinNodes(), o1.bestSolution.getWorstMinNodes()); 
		});
		Solver best = solvers.get(0);
		if (best.bestSolution.isEmpty()) {
			int highestCount = extraDensityInfo.getMaxNodesInDensityMapGridElement();
			// inform user about possible better options?
			double ratio = (double) highestCount / maxNodes;
			if (ratio > 4)
				System.err.printf(
						"max-nodes value %d is far below highest node count %d in single grid element, consider using a higher resolution.%n",
						maxNodes, highestCount);
			else if (ratio > 1)
				System.err.printf(
						"max-nodes value %d is below highest node count %d in single grid element, consider using a higher resolution.%n",
						maxNodes, highestCount);
			else if (ratio < 0.25)
				System.err.printf(
						"max-nodes value %d is far above highest node count %d in single grid element, consider using a lower resolution.%n",
						maxNodes, highestCount);


		}
		hasEmptyPart = best.hasEmptyPart;
		printFinishMsg(best.bestSolution, best.searchLimit);
		return best.bestSolution;
	}

	private double getStartRatio(Tile startTile) {
		if (extraDensityInfo.getNodeCount() / maxNodes < 4) {
			return 32;
		} 
		double startMaxAspectRatio = startTile.getAspectRatio();
		if (startMaxAspectRatio < 1)
			startMaxAspectRatio = 1 / startMaxAspectRatio ;
		if (startMaxAspectRatio < NICE_MAX_ASPECT_RATIO)
			startMaxAspectRatio = NICE_MAX_ASPECT_RATIO;
		return startMaxAspectRatio;
	}

	private void printFinishMsg(Solution solution, int searchLimit) {
		if (!beQuiet) {
			if (!solution.isEmpty()) {
				if (solution.getWorstMinNodes() > VERY_NICE_FILL_RATIO * maxNodes && solution.isNice())
					System.out.println(
							"Solution is very nice. No need to search for a better solution: " + solution.toString());
				else
					System.out.println("Solution is " + (solution.isNice() ? "" : "not ")
							+ "nice. Can't find a better solution with search-limit " + searchLimit + ": "
							+ solution.toString());
			}
		}
	}

	private static void printFinalSplitMsg(Solution solution) {
		System.out.println("Final solution: " + solution.toString());
		if (solution.isNice())
			System.out.println("This seems to be nice.");
	}

	/**
	 * Convert the list of Tile instances of a solution to Area instances, report
	 * some statistics.
	 * 
	 * @param sol         the solution
	 * @param polygonArea the split polygon
	 * 
	 * @return list of areas
	 */
	private List<Area> getAreas(Solution sol, java.awt.geom.Area polygonArea) {
		List<Area> result = new ArrayList<>();
		int minLat = allDensities.getBounds().getMinLat();
		int minLon = allDensities.getBounds().getMinLong();

		if (polygonArea != null) {
			System.out.println("Trying to cut the areas so that they fit into the polygon ...");
		} else {
			if (trimShape)
				sol.trimOuterTiles();
		}

		boolean fits = true;
		for (Tile tile : sol.getTiles()) {
			if (tile.getCount() == 0)
				continue;
			if (!tile.verify())
				throw new SplitFailedException("found invalid tile");
			Rectangle r = new Rectangle(minLon + (tile.x << shift), minLat + (tile.y << shift), tile.width << shift,
					tile.height << shift);

			if (polygonArea != null) {
				java.awt.geom.Area cutArea = new java.awt.geom.Area(r);
				cutArea.intersect(polygonArea);
				if (!cutArea.isEmpty() && cutArea.isRectangular()) {
					r = cutArea.getBounds();
				} else {
					fits = false;
				}
			}
			Area area = new Area(r.y, r.x, (int) r.getMaxY(), (int) r.getMaxX());
			if (!beQuiet) {
				String note;
				if (tile.getCount() > maxNodes)
					note = " but is already at the minimum size so can't be split further";
				else
					note = "";
				long percentage = 100 * tile.getCount() / maxNodes;
				System.out.println("Area " + currMapId++ + " covers " + area + " and contains " + tile.getCount()
						+ " nodes (" + percentage + " %)" + note);
			}
			result.add(area);
		}
		if (!fits) {
			System.out.println("One or more areas do not exactly fit into the bounding polygon");
		}
		return result;

	}

	private static class Solver {
		private final long myMaxNodes;
		private boolean hasEmptyPart;
		private double maxAspectRatio;
		private int countBad;
		private Long minNodes; 
		private int searchLimit;
		private LinkedHashMap<Tile, Integer> incomplete;
		private Map<Tile, Long> knownBad = new HashMap<>(50_000);
		static final  int MAX_SEARCH_LIMIT = 5_000_000;
		final String name;
		private boolean searchAll;
		private Solution bestSolution;
		private Solution smallestSolution;
		private boolean stopped;
		private long localOptMinNodes;
		private final Tile startTile;
		private int bestPossible;
		private long largestOptTileCount;
		private int largestOptSize;
		private long optLoops;
		private int[] lastGoodCounts;
		private final int maxTileHeight;
		private final int maxTileWidth;
		private final int stopNumber;
		private final boolean trimTiles;
		private final int startSearchLimit;
		private final boolean allowEmptyPart;
		

		public Solver(int id, boolean searchAll, long maxNodes, Tile startTile, int shift, int stopNumber,
				boolean trimTiles, int startSearchLimit, boolean allowEmptyPart) {
			this.searchAll = searchAll;
			this.myMaxNodes = maxNodes;
			this.startTile = startTile;
			this.stopNumber = stopNumber;
			this.trimTiles = trimTiles;
			this.startSearchLimit = startSearchLimit;
			this.allowEmptyPart = allowEmptyPart;
			incomplete = new LinkedHashMap<>();
			bestSolution = new Solution(myMaxNodes);
			name = "S" + id + " " + (searchAll ? "FULL" : "SOME");
			maxTileHeight = Utils.toMapUnit(MAX_LAT_DEGREES) / (1 << shift);
			maxTileWidth = Utils.toMapUnit(MAX_LON_DEGREES) / (1 << shift);
		}

		/**
		 * Try to split the tile into nice parts recursively.
		 * 
		 * @param depth the recursion depth
		 * @param tile  the tile to be split
		 * @param smiParent meta info for parent tile
		 * @return a solution instance or null
		 */
		private Solution findSolution(int depth, final Tile tile, Tile parent, TileMetaInfo smiParent) {
			if (stopped)
				return null;
			boolean addAndReturn = false;
			if (tile.getCount() == 0) {
				if (!allowEmptyPart) {
					hasEmptyPart = true;
					return null;
				}
				if (tile.width * tile.height <= 4)
					return null;
				return new Solution(myMaxNodes); // allow empty part of the world
			} else if (tile.getCount() > myMaxNodes && tile.width == 1 && tile.height == 1) {
				addAndReturn = true; // can't split further
			} else if (tile.getCount() < minNodes && depth == 0) {
				addAndReturn = true; // nothing to do
			} else if (tile.getCount() < minNodes) {
				return null;
			} else if (tile.getCount() <= myMaxNodes) {
				double ratio = tile.getAspectRatio();
				if (ratio < 1.0)
					ratio = 1.0 / ratio;
				if (ratio <= maxAspectRatio) {
					if (stopNumber > 0 || myMaxNodes >= LARGE_MAX_NODES || checkSize(tile))
						addAndReturn = true;
				} else {
					return null;
				}
			} else if (tile.width < 2 && tile.height < 2) {
				return null;
			}
			if (addAndReturn) {
				if (depth > 0 && smiParent.getNumOutside() > MAX_OUTSIDE_RATIO * tile.width * tile.height
						&& !tile.outsideRatioIsOK(MAX_OUTSIDE_RATIO)) { 				
					return null;
				}
				Solution solution = new Solution(myMaxNodes);
				solution.add(tile); // can't split further
				return solution;
			}
			if (tile.getCount() < minNodes * 2) {
				return null;
			}
			if (!trimTiles && tile.getMinParts(myMaxNodes) * minNodes > tile.getCount()) {
				return null;
			}
			
			// we have to split the tile
			Integer alreadyDone = null;
			if (countBad == 0 && !incomplete.isEmpty()) {
				alreadyDone = incomplete.remove(tile);
				if (alreadyDone == null)
					incomplete.clear(); // rest is not useful
			}
			final boolean isCacheCandidate = depth > 0 && tile.width * tile.height > MIN_TILE_AREA_BAD_CACHE;
			if (alreadyDone == null && isCacheCandidate) {
				Long x = knownBad.get(tile);
				if (x != null && x <= minNodes) {
					return null;
				}
			}

			// copy the existing density info from parent
			// typically, at least one half can be re-used
			TileMetaInfo smi = new TileMetaInfo(tile, parent, smiParent);
			smi.setMinNodes(minNodes);

			// we have to split the tile
			TestGenerator generator = new TestGenerator(searchAll, tile, smi);
			int countDone = 0;
			Solution bestSol = null;

			while (generator.hasNext()) {
				int splitPos = generator.next();
				countDone++;
				if (alreadyDone != null && countDone <= alreadyDone.intValue()) {
					continue;
				}
				// create the two parts of the tile
				int axis = generator.getAxis();
				boolean ok = axis == AXIS_HOR ? tile.splitHoriz(splitPos, smi) : tile.splitVert(splitPos, smi);
				if (!ok)
					continue;

				Tile[] parts = smi.getParts();
				if (parts[0].getCount() > parts[1].getCount()) {
					// first try the less populated part
					Tile help = parts[0];
					parts[0] = parts[1];
					parts[1] = help;
				}
				Solution[] sols = new Solution[2];
				int countOK = 0;
				for (int i = 0; i < 2; i++) {
					if (trimTiles && smi.getNumOutside() > 0) {
						parts[i] = parts[i].trim();
					}
					// depth first recursive search
					if (incomplete.isEmpty() || incomplete.containsKey(parts[i])) {
						sols[i] = findSolution(depth + 1, parts[i], tile, smi);
						if (sols[i] == null) {
							countBad++;
							break;
						}
						countOK++;
					}
				}
				if (countOK == 2) {
					Solution sol = sols[0];
					sol.merge(sols[1]);
					if (bestSol == null || bestSol.compareTo(sol) > 0)
						bestSol = sol;
					if (depth > 0 || tile.getCount() > 2 * myMaxNodes)
						break; // we found a valid split
				} else if (countBad >= searchLimit) {
					if (DEBUG)
						System.out.println(name + ": limit reached " + depth + " min-nodes " + minNodes);
					if (depth < MAX_DEPTH_STATS)
						lastGoodCounts[depth] = -1;
					incomplete.put(tile, countDone - 1);
					break;
				}
			}

			if (depth < MAX_DEPTH_STATS && countBad < searchLimit) {
				lastGoodCounts[depth] = countDone;
			}

			smi.propagateToParent(smiParent, tile, parent);

			if (bestSol == null && countBad < searchLimit && isCacheCandidate) {
				Long x = knownBad.get(tile);
				if (x == null || x > minNodes)
					knownBad.put(tile, minNodes);
			}
			
			// check if we should perform a local optimisation
			if (bestSol != null && localOptMinNodes > 0 && bestSol.size() > 2 && bestSol.size() <= 32) {
				long backupMinNodes = minNodes;
				boolean backupSearchAll = searchAll;
				int backupCountBad = countBad;
				int min = tile.getMinParts(myMaxNodes);
				int oldSize = bestSol.size();
				while (bestSol.size() > min) {
					localOptMinNodes = Math.max(tile.getCount() / bestSol.size(), bestSol.getWorstMinNodes() + 1);
					minNodes = localOptMinNodes;
					searchAll = false;
					countBad = 0;
					Solution sol2 = findSolution(depth, tile, parent, smiParent);
					if(DEBUG) {
						if (countBad > 200_000) {
							System.out.println(name + ": bad opt? tile " + tile + " required " + countBad + " bad tests for " + sol2);
						}
					}
					optLoops++;
					minNodes = backupMinNodes;
					searchAll = backupSearchAll;
					if (sol2 != null && sol2.isSmallerOrBetter(bestSol)) {
						if (tile.getCount() > largestOptTileCount)
							largestOptTileCount = tile.getCount();
						if (oldSize > largestOptSize)
							largestOptSize = oldSize;
						bestSol = sol2; // we found a better split
					} else
						break;
				}
				countBad = backupCountBad;
			}
			return bestSol;
		}

		private boolean checkSize(Tile tile) {
			return tile.height <= maxTileHeight && tile.width <= maxTileWidth;
		}

		@Override
		public String toString() {
			return name + " for tile " +startTile;
		}
		
		public void solve() {
			bestPossible = stopNumber > 0 ? stopNumber : startTile.getMinParts(myMaxNodes);
			solve0();
			if (smallestSolution.isSmallerOrBetter(bestSolution))
				bestSolution = smallestSolution;
			System.out.println(name + " goal was " + bestPossible + " tiles, solver "
					+ (stopped ? "was stopped" : "finished") + " with : " + bestSolution.toString());
			knownBad.clear();
			incomplete.clear();
		}
		
		private void solve0() {
			knownBad.clear();
			lastGoodCounts = new int[MAX_DEPTH_STATS];
			bestSolution = new Solution(myMaxNodes);
			smallestSolution = new Solution(myMaxNodes);
			minNodes = Math.max(Math.min((long) (0.05 * myMaxNodes), startTile.getLargestInfo()), 1);
					
			searchLimit = startSearchLimit; 
			TileMetaInfo smiStart = new TileMetaInfo(startTile, null, null);
			final long veryNiceMinNodes = (long) (VERY_NICE_FILL_RATIO * myMaxNodes);
			
			boolean clearIncomplete = false;
			for (int numLoops = 1; numLoops < MAX_LOOPS && !stopped; numLoops++) {
				if (clearIncomplete) {
					incomplete.clear();
				}
				// store values to be able to detect progress 
				double saveMaxAspectRatio = maxAspectRatio;
				long saveMinNodes = minNodes;
				
				countBad = 0;
				final String dbgPrefix = name + ": step " + numLoops; 
				if (DEBUG) {
					System.out.println(dbgPrefix + " searching for split with min-nodes " + minNodes + ", cache size " + Utils.format(knownBad.size()));
				}
				smiStart.setMinNodes(minNodes);
				int oldCacheSize = knownBad.size();
				largestOptTileCount = 0;
				largestOptSize = 0;
				Solution solution = findSolution(0, startTile, startTile, smiStart);
				if (stopped)
					return;
				if (DEBUG) {
					System.out.println(dbgPrefix + " positions " + Arrays.toString(lastGoodCounts));
					if (optLoops > 0) {
						System.out.println(dbgPrefix + " local opt. runs: " + optLoops
								+ ", worked up to count " + Utils.format(largestOptTileCount)
								+ ", worked up to old size " + largestOptSize
								);
					}
				}
				if (solution != null) {
					if (solution.isSmallerOrBetter(smallestSolution)) {
						smallestSolution = solution;
					}
					if (solution.size() < stopNumber) {
						minNodes = (bestSolution.getWorstMinNodes() + solution.getWorstMinNodes()) / 2;
						if(minNodes != saveMinNodes)
							continue;
						solution = null;
					}
					boolean foundBetter = bestSolution.compareTo(solution) > 0;
					if (solution != null) {
						if (foundBetter ) {
							Solution prevBest = bestSolution;
							bestSolution = solution;
							System.out.println(dbgPrefix+ " goal: " + bestPossible + " tiles, now: "
									+ bestSolution + ", cache size " + Utils.format(knownBad.size()));
							// change criteria to find a better(nicer) result
							double factor = 1.10;
							if (!prevBest.isEmpty() && prevBest.isNice())
								factor = Math.min(1.30, (double) bestSolution.getWorstMinNodes() / prevBest.getWorstMinNodes());
							minNodes = Math.max(myMaxNodes / 3, (long) (bestSolution.getWorstMinNodes() * factor));
							if (localOptMinNodes == 0) {
								// enable local optimisation
								minNodes = bestSolution.getWorstMinNodes() + 1;
								localOptMinNodes = minNodes;
							}

						} else {
							minNodes = solution.getWorstMinNodes() + 1;
						}
					}
				} else if (!bestSolution.isEmpty() && minNodes > bestSolution.getWorstMinNodes() + 1) {
					// reduce minNodes
					minNodes = (bestSolution.getWorstMinNodes() + minNodes) / 2;
					if (minNodes < bestSolution.getWorstMinNodes() * 1.001)
						minNodes = bestSolution.getWorstMinNodes() + 1;
				} else if (!searchAll && oldCacheSize < knownBad.size()) {
					if (bestSolution.isEmpty()) {
						clearIncomplete = false;
						continue;
					}
				}
				if (!bestSolution.isEmpty() ) {
					if (stopNumber * 0.95 > bestSolution.getTiles().size())
						return;
					if (bestSolution.size() == 1)
						return;
					if (bestSolution.size() == bestPossible && numLoops > 6) {
						return;
					}
				}
				if (stopNumber == 0 && minNodes > veryNiceMinNodes)
					minNodes = veryNiceMinNodes;
				clearIncomplete = true;
				maxAspectRatio = Math.min(32, Math.max(bestSolution.getWorstAspectRatio() / 2, NICE_MAX_ASPECT_RATIO));
				if (saveMaxAspectRatio == maxAspectRatio && saveMinNodes == minNodes) {
					// no improvement found
					boolean tryAgain = false;
					if (bestSolution.isEmpty() || bestSolution.getWorstMinNodes() < 0.5 * myMaxNodes) {
						// try to improve by adjusting threshold values
						if (countBad > searchLimit && searchLimit < MAX_SEARCH_LIMIT) {
							searchLimit *= 2;
							knownBad.clear();
							clearIncomplete = false;
							System.out.println(name + ": No good solution found, duplicated search-limit to " + searchLimit);
							tryAgain = true;
						} else if (bestSolution.isEmpty() && minNodes > 1) {
							minNodes = 1L;
							searchLimit = searchAll? startSearchLimit : Math.max(MAX_SEARCH_LIMIT, startSearchLimit);
							// sanity check
							System.out.println(name + ": No good solution found, trying to find one accepting anything");
							tryAgain = true;
						} else if (!bestSolution.isEmpty() && smallestSolution.size() < bestSolution.size()
								&& minNodes != smallestSolution.getWorstMinNodes() + 1) {
							minNodes = smallestSolution.getWorstMinNodes() + 1;
							if (DEBUG) {
								System.out.println(name + ": Trying to improve smallest solution");
							}
							tryAgain = true;
						}
					}
					if (!tryAgain) {
						return;
					}
				} 
			}
		}

		void stop() {
			stopped = true;
		}
	
		private class TestGenerator {
			final boolean searchAll;
			int axis;
			final Tile tile;
			final TileMetaInfo smi;
			int countAxis;
			int usedTestPos;
			private IntArrayList todoList;

			public TestGenerator(boolean searchAll, Tile tile, TileMetaInfo smi) {
				this.searchAll = searchAll;
				this.tile = tile;
				this.smi = smi;

				axis = (tile.getAspectRatio() >= 1.0) ? AXIS_HOR : AXIS_VERT;
				todoList = generateTestCases();
			}

			boolean hasNext() {
				if (usedTestPos >= todoList.size()) {
					countAxis++;
					if (countAxis > 1)
						return false;
					axis = axis == AXIS_HOR ? AXIS_VERT : AXIS_HOR;
					todoList = generateTestCases();
					usedTestPos = 0;
				}
				return usedTestPos < todoList.size();
			}

			int next() {
				return todoList.get(usedTestPos++);
			}

			public int getAxis() {
				return axis;
			}

			IntArrayList generateTestCases() {
				final int start = (axis == AXIS_HOR) ? tile.findValidStartX(smi) : tile.findValidStartY(smi);
				final int end = (axis == AXIS_HOR) ? tile.findValidEndX(smi) : tile.findValidEndY(smi);
				final int mid = (start + end) / 2;
				final int range = end - start;
				if (searchAll || range < 4) {
					return Tile.genTests(start, end);
				}
				double ratio = tile.getAspectRatio();
				IntArrayList tests = new IntArrayList();
				if (range < 0 || ratio < 1.0 / 32 || ratio > 32 || ratio < 1.0 / 16 && axis == AXIS_HOR || ratio > 16 && axis == AXIS_VERT) {
					return tests;
				} else if (range == 0) {
					tests.add(start);
					return tests;
				}

				if (range > 1024 && (axis == AXIS_HOR && tile.width >= maxTileWidth
						|| axis == AXIS_VERT && tile.height >= maxTileHeight)) {
					// large tile, just split at a few valid positions
					for (int i = 5; i > 1; --i)
						tests.add(start + range / i);

				} else if (tile.getCount() < myMaxNodes * 4 && range > 256) {
					// large tile with rather few nodes, allow more tests
					int step = (range) / 20;
					for (int pos = start; pos <= end; pos += step)
						tests.add(pos);
					if (tests.get(tests.size() - 1) != end)
						tests.add(end);
				} else if (tile.getCount() > myMaxNodes * 4) {
					int step = range / 7; // 7 turned out to be a good value here
					if (step < 1)
						step = 1;
					for (int pos = start; pos <= end; pos += step)
						tests.add(pos);
				} else { 
					// count <= 4 * max, this will be one of the last splits
					long minCount = smi.getNumOutside() > 0 ? tile.countInside() : tile.getCount();
					int nMin = (int) (minCount / myMaxNodes);
					if (nMin * myMaxNodes < minCount)
						nMin++;
					if (nMin == 0) {
						nMin++;
					}
					long limit = nMin == 0? 1 : minCount / nMin;
					double dMin = (double) minCount / myMaxNodes;
					final int around;
					if ((dMin > 1.8 && dMin < 2.0 && ratio > 0.125 && ratio < 8) || (dMin > 2.8 && dMin < 3.0)) {
						around = tile.findFirstHigher(axis, smi, limit);
					} else if (dMin > 3.8) {
						around = tile.findFirstHigher(axis, smi, 2 * limit);
					} else {
						around = -1;
					}
					if (around >= 0) {
						// this is likely to be a good split, generate more cases
						final int numAround = 20;
						final int p1 = Math.max(start, around - numAround / 2);
						final int p2 = Math.min(end, around + numAround / 2);
						final int toAdd = p2 - p1;
						if (toAdd > 16)
							tests = new IntArrayList(toAdd);
						for (int i = p1; i <= p2; i++) {
							tests.add(i);
						}
						tests.sort((o1, o2) -> Integer.compare(Math.abs(o1 - around), Math.abs(o2 - around)));
						return tests;
					}
					if (nMin == 4)
						tests.add(tile.findFirstHigher(axis, smi, 2 * limit));
					tests.add(tile.findFirstHigher(axis, smi, limit));
					
				}
				if (tests.size() > 4) {
					tests.sort((o1, o2) -> Integer.compare(Math.abs(o1 - mid), Math.abs(o2 - mid)));
					if (tests.getInt(0) != mid) {
						tests.add(0, mid);
					}
				}

				return tests;
			}
		}
	}
	
}
