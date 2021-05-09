package uk.me.parabola.splitter;

public class OsmBounds {
	private final double minLat;
	private final double minLong;
	private final double maxLat;
	private final double maxLong;
	
	public OsmBounds(double minLat, double minLong, double maxLat, double maxLong) {
		this.minLat = minLat;
		this.minLong = minLong;
		this.maxLat = maxLat;
		this.maxLong = maxLong;
	}
	
	public OsmBounds(Area bounds) {
		this.minLat = Utils.toDegrees(bounds.getMinLat());
		this.minLong = Utils.toDegrees(bounds.getMinLong());
		this.maxLat = Utils.toDegrees(bounds.getMaxLat());
		this.maxLong = Utils.toDegrees(bounds.getMaxLong());
	}

	public double getMinLat() {
		return minLat;
	}
	public double getMinLong() {
		return minLong;
	}
	public double getMaxLat() {
		return maxLat;
	}
	public double getMaxLong() {
		return maxLong;
	}
	
	public Area toArea() {
		return new Area(Utils.toMapUnit(minLat), Utils.toMapUnit(minLong),
						Utils.toMapUnit(maxLat), Utils.toMapUnit(maxLong));

	}
}
