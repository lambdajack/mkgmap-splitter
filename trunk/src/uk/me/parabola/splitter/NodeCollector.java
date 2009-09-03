package uk.me.parabola.splitter;

/**
 * Collects node coordinates in a map.
 */
class NodeCollector implements MapCollector {

	private SplitIntList coords = new SplitIntList();
	private final MapDetails details = new MapDetails();

	@Override
	public boolean isStartNodeOnly() {
		return true;
	}

	@Override
	public void startNode(int id, double lat, double lon) {
		// Since we are rounding areas to fit on a low zoom boundary we
		// can drop the bottom 8 bits of the lat and lon and then fit
		// the whole lot into a single int.
		int glat = Utils.toMapUnit(lat);
		int glon = Utils.toMapUnit(lon);
		int coord = ((glat << 8) & 0xffff0000) + ((glon >> 8) & 0xffff);

		coords.add(coord);
		details.addToBounds(glat, glon);
	}

	@Override
	public void startWay(int id) {}

	@Override
	public void startRelation(int id) {}

	@Override
	public void nodeTag(String key, String value) {}

	@Override
	public void wayTag(String key, String value) {}

	@Override
	public void relationTag(String key, String value) {}

	@Override
	public void wayNode(int nodeId) {}

	@Override
	public void relationNode(int nodeId, String role) {}

	@Override
	public void relationWay(int wayId, String role) {}

	@Override
	public void endNode() {}

	@Override
	public void endWay() {}

	@Override
	public void endRelation() {}

	@Override
	public void endMap() {}

	@Override
	public Area getExactArea() {
		return details.getBounds();
	}

	@Override
	public SplittableArea getRoundedArea(int resolution) {
		Area bounds = RoundingUtils.round(details.getBounds(), resolution);
		SplittableArea result = new SplittableNodeArea(bounds, coords, resolution);
		coords = null;
		return result;
	}
}