/*
 * Copyright (c) 2012, Gerd Petermann
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

import uk.me.parabola.splitter.Relation.Member;
import uk.me.parabola.splitter.args.SplitterParams;
import uk.me.parabola.splitter.tools.SparseLong2IntMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.regex.Pattern;

/**
 * Find ways and relations that will be incomplete.
 * Strategy:
 * - calculate the areas of each node, calculate and store an integer that represents the combination of areas
 *    (this is done by the AreaDictionary)  
 * - a way is a problem way if its nodes are found in different combinations of areas
 * - a relation is a problem relation if its members are found in different combinations of areas
 * 
 */
class ProblemListProcessor extends AbstractMapProcessor {
	private static final int PHASE1_NODES_AND_WAYS = 1;
	private static final int PHASE2_RELS_ONLY = 2;

	private final SparseLong2IntMap coords;
	private final SparseLong2IntMap ways;
	
	private final AreaDictionary areaDictionary;
	private final DataStorer dataStorer;
	private final LongArrayList problemWays = new LongArrayList(); 
	private final LongArrayList problemRels = new LongArrayList();

	/** each bit represents one distinct area */
	private final AreaSet areaSet = new AreaSet();
	
	private int phase = PHASE1_NODES_AND_WAYS;
	private long countCoords = 0;
	private final int areaOffset;
	private final int lastAreaOffset;
	private boolean isFirstPass;
	private boolean isLastPass;
	private AreaIndex areaIndex;
	private final HashSet<String> wantedBoundaryAdminLevels = new HashSet<>();
	
	private final HashSet<String> wantedBoundaryTagValues;
	private final HashSet<String> wantedRouteTagValues;
	
	ProblemListProcessor(DataStorer dataStorer, int areaOffset,
			int numAreasThisPass, SplitterParams mainOptions) {
		this.dataStorer = dataStorer;
		this.areaDictionary = dataStorer.getAreaDictionary();
		if (dataStorer.getUsedWays() == null){
			ways = new SparseLong2IntMap("way");
			ways.defaultReturnValue(UNASSIGNED);
			dataStorer.setUsedWays(ways);
		}
		else 
			ways = dataStorer.getUsedWays(); 
		
		this.areaIndex = dataStorer.getGrid();
		this.coords = new SparseLong2IntMap("coord");
		this.coords.defaultReturnValue(UNASSIGNED);
		this.isFirstPass = (areaOffset == 0);
		this.areaOffset = areaOffset;
		this.lastAreaOffset = areaOffset + numAreasThisPass - 1;
		this.isLastPass = (areaOffset + numAreasThisPass == dataStorer.getNumOfAreas());
		String boundaryTagsParm = mainOptions.getBoundaryTags();
		if ("use-exclude-list".equals(boundaryTagsParm)) 
			wantedBoundaryTagValues = null;
		else { 
			String[] boundaryTags = boundaryTagsParm.split(Pattern.quote(","));
			wantedBoundaryTagValues = new HashSet<>(Arrays.asList(boundaryTags));
		}
		setWantedAdminLevel(mainOptions.getWantedAdminLevel());
		String routeRelationValuesParm = mainOptions.getRouteRelValues();
		if (routeRelationValuesParm.isEmpty()) {
			wantedRouteTagValues = null;
		} else {
			String[] routeValues = routeRelationValuesParm.split(Pattern.quote(","));
			wantedRouteTagValues = new HashSet<>(Arrays.asList(routeValues));
		}
	}
	
	public void setWantedAdminLevel(int adminLevel) {
		int min, max = 11;
		min = Math.max(2, adminLevel);
		wantedBoundaryAdminLevels.clear();
		for (int i = min; i <= max; i++){
			wantedBoundaryAdminLevels.add(Integer.toString(i));
		}
	}

	@Override
	public boolean skipTags() {
		return phase == PHASE1_NODES_AND_WAYS;
	}

	@Override
	public boolean skipNodes() {
		return phase == PHASE2_RELS_ONLY;
	}
	@Override
	public boolean skipWays() {
		return phase == PHASE2_RELS_ONLY;
	}
	
	@Override
	public boolean skipRels() {
		return phase != PHASE2_RELS_ONLY;
	}
		
	@Override
	public int getPhase(){
		return phase;
	}
	
	@Override
	public void processNode(Node node) {
		if (phase == PHASE2_RELS_ONLY)
			return;
		int countAreas = 0;
		int lastUsedArea = UNASSIGNED;
		AreaGridResult areaCandidates = areaIndex.get(node);
		if (areaCandidates == null) 
			return;
		
		areaSet.clear();
		
		for (int n : areaCandidates.set) {
			if (n >= areaOffset && n <= lastAreaOffset
					&& (!areaCandidates.testNeeded || areaDictionary.getArea(n).contains(node))) {
				areaSet.set(n);
				++countAreas;
				lastUsedArea = n;
			}
		}
		if (countAreas > 0) {
			int areaIdx;
			if (countAreas > 1)
				areaIdx = areaDictionary.translate(areaSet);
			else  
				areaIdx = AreaDictionary.translate(lastUsedArea); // no need to do lookup in the dictionary 
			coords.put(node.getId(), areaIdx);
			++countCoords;
			if (countCoords % 10_000_000 == 0) {
				System.out.println("coord MAP occupancy: " + Utils.format(countCoords)
						+ ", number of area dictionary entries: " + areaDictionary.size());
			}
		}
	}
	
	@Override
	public void processWay(Way way) {
		if (phase == PHASE2_RELS_ONLY)
			return;
		boolean maybeChanged = false;
		int oldclIndex = UNASSIGNED;
		areaSet.clear();
		for (long id : way.getRefs()){ 
			// Get the list of areas that the way is in. 
			int clIdx = coords.get(id);
			if (clIdx != UNASSIGNED && oldclIndex != clIdx){
				areaSet.or(areaDictionary.getSet(clIdx));
				oldclIndex = clIdx;
				maybeChanged = true;
			}
		}
		if (!isFirstPass && maybeChanged || (isLastPass && !isFirstPass)){
			int wayAreaIdx = ways.get(way.getId());
			if (wayAreaIdx != UNASSIGNED)
				areaSet.or(areaDictionary.getSet(wayAreaIdx));
		}
		
		if (isLastPass && checkIfMultipleAreas(areaSet)){
			problemWays.add(way.getId());
		}
		if (maybeChanged && !areaSet.isEmpty()){
			ways.put(way.getId(), areaDictionary.translate(areaSet));
		}
	}
	
	// default exclude list for boundary tag
	private static final HashSet<String> unwantedBoundaryTagValues = new HashSet<>(
			Arrays.asList("administrative", "postal_code", "political"));

	@Override
	public void processRelation(Relation rel) {
		if (phase == PHASE1_NODES_AND_WAYS)
			return;
		boolean useThis = false;
		boolean isMPRelType = false;
		boolean hasBoundaryTag = false;
		boolean isWantedBoundary = wantedBoundaryTagValues == null;
		boolean isRouteRelType = false;
		boolean isWantedRoute = wantedRouteTagValues != null;
		Iterator<Element.Tag> tags = rel.tagsIterator();
		String admin_level = null;
		while(tags.hasNext()) {
			Element.Tag t = tags.next();
			if ("type".equals(t.key)) {
				if ("restriction".equals((t.value)) || "through_route".equals((t.value)) || t.value.startsWith("restriction:"))
					useThis= true; // no need to check other tags
				else if ("multipolygon".equals((t.value))  || "boundary".equals((t.value)))
					isMPRelType= true;
				else if ("route".equals(t.value))
					isRouteRelType = true;
				else if ("associatedStreet".equals((t.value))  || "street".equals((t.value)))
					useThis= true; // no need to check other tags
			} else if ("boundary".equals(t.key)){
				hasBoundaryTag = true;
				if (wantedBoundaryTagValues != null){
					if (wantedBoundaryTagValues.contains(t.value))
						isWantedBoundary = true;
				} else {
					if (unwantedBoundaryTagValues.contains(t.value))
						isWantedBoundary = false;
				}
			} else if ("admin_level".equals(t.key)){
				admin_level = t.value;
			}
			if (wantedRouteTagValues != null && "route".equals((t.key)) && wantedRouteTagValues.contains(t.value)) {
				isWantedRoute = true;
			} 			
			if (useThis)
				break;
		}
		if (isMPRelType && (isWantedBoundary || !hasBoundaryTag))
			useThis = true;
		else if (isMPRelType && hasBoundaryTag && admin_level != null) {
			if (wantedBoundaryAdminLevels.contains(admin_level))
				useThis = true;
		} else if (isRouteRelType && isWantedRoute) {
			useThis = true;
		}
		if (!useThis) {
			return;
		}
		areaSet.clear();
		Integer relAreaIdx;
		if (!isFirstPass) {
			relAreaIdx = dataStorer.getUsedRels().get(rel.getId());
			if (relAreaIdx != null)
				areaSet.or(areaDictionary.getSet(relAreaIdx));
		}
		int oldclIndex = UNASSIGNED;
		int oldwlIndex = UNASSIGNED;
		for (Member mem : rel.getMembers()) {
			long id = mem.getRef();
			if ("node".equals(mem.getType())) {
				int clIdx = coords.get(id);

				if (clIdx != UNASSIGNED){
					if (oldclIndex != clIdx){ 
						areaSet.or(areaDictionary.getSet(clIdx));
					}
					oldclIndex = clIdx;

				}

			} else if ("way".equals(mem.getType())) {
				int wlIdx = ways.get(id);

				if (wlIdx != UNASSIGNED){
					if (oldwlIndex != wlIdx){ 
						areaSet.or(areaDictionary.getSet(wlIdx));
					}
					oldwlIndex = wlIdx;
				}
			}
			// ignore relation here
		}
		if (areaSet.isEmpty())
			return;
		if (isLastPass){
			if (checkIfMultipleAreas(areaSet)){
				problemRels.add(rel.getId());
			} else {
			    
				// the relation is only in one distinct area
				// store the info that the rel is only in one distinct area
				dataStorer.storeRelationAreas(rel.getId(), areaSet);
			}
			return;
		}
		
		relAreaIdx = areaDictionary.translate(areaSet);
		dataStorer.getUsedRels().put(rel.getId(), relAreaIdx);
	}
	
	@Override
	public boolean endMap() {
		if (phase == PHASE1_NODES_AND_WAYS){
			phase++;
			return false;
		}
		coords.stats(0);
		ways.stats(0);
		if (isLastPass){
			System.out.println("");
			System.out.println("  Number of stored area combis for nodes: " + Utils.format(coords.size()));
			System.out.println("  Number of stored area combis for ways: " + Utils.format(dataStorer.getUsedWays().size()));
			System.out.println("  Number of stored Integers for rels: " + Utils.format(dataStorer.getUsedRels().size()));
			System.out.println("  Number of stored combis in dictionary: " + Utils.format(areaDictionary.size()));
			System.out.println("  Number of detected problem ways: " + Utils.format(problemWays.size()));
			System.out.println("  Number of detected problem rels: " + Utils.format(problemRels.size()));
			Utils.printMem();
			System.out.println("");
			dataStorer.getUsedWays().clear();
			dataStorer.getUsedRels().clear();
		}
		return true;
	}
	
	/** 
	 * @param areaCombis
	 * @return true if the combination of distinct areas can contain a problem polygon
	 */
	static boolean checkIfMultipleAreas(AreaSet areaCombis){
		// this returns a few false positives for those cases
		// where a way or rel crosses two pseudo-areas at a 
		// place that is far away from the real areas
		// but it is difficult to detect these cases.
		return areaCombis.cardinality() > 1;
	}

	public LongArrayList getProblemWays() {
		return problemWays;
	}
	
	public LongArrayList getProblemRels() {
		return problemRels;
	}
}
