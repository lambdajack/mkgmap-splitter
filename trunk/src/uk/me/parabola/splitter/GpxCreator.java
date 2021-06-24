package uk.me.parabola.splitter;

import java.awt.Point;
import java.awt.Shape;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;


public class GpxCreator {

	private static void addTrkPoint(PrintWriter pw, Point co) {
		addGpxPoint(pw, "trkpt", co);
	}

	private static void addWptPoint(PrintWriter pw, Point co) {
		addGpxPoint(pw, "wpt", co);
	}

	private static void addGpxPoint(PrintWriter pw, String type, Point co) {
		pw.print("<");
		pw.print(type);
		pw.print(" lat=\"");
		pw.print(Utils.toDegrees(co.y));
		pw.print("\" lon=\"");
		pw.print(Utils.toDegrees(co.x));
		pw.print("\"/>");
	}

	public static void createAreaGpx(String name, Area bbox) {
		GpxCreator.createGpx(name, bbox.toPoints());
	}

	/**
	 * Create gpx file(s) for java Shape. 
	 * @param baseDir the base directory name
	 * @param shape the shape to convert
	 */
	public static void createShapeGpx(String baseDir, Shape shape) {
		// have to convert to area to make sure that clockwise/counterclockwise idea works for inner/outer
		java.awt.geom.Area area = shape instanceof java.awt.geom.Area ? (java.awt.geom.Area) shape
				: new java.awt.geom.Area(shape);
		List<List<Point>> shapes = Utils.areaToShapes(area);
		for (int i = 0; i < shapes.size(); i++) {
			List<Point> points = shapes.get(i);
			String extName = baseDir + Integer.toString(i) + "_" + (Utils.clockwise(points) ? "o" : "i");
			GpxCreator.createGpx(extName, points);
		}
	}	

	public static void createGpx(String name, List<Point> points) {
		createGpx(name, points, Collections.emptyList());
	}
	
	public static void createGpx(String name, List<Point> polygonpoints, Point... singlePoints) {
		createGpx(name, polygonpoints, Arrays.asList(singlePoints));
	}

	public static void createGpx(String fname, List<Point> polygonpoints, List<Point> singlePoints) {
		File f = new File(fname);
		f.getParentFile().mkdirs();
		try (PrintWriter pw = new PrintWriter(new FileWriter(fname + ".gpx"))) {
			pw.print("<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" creator=\"mkgmap\" ");
			pw.print("version=\"1.1\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ");
			pw.print("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\"> ");

			if (singlePoints != null) {
				for (Point c : singlePoints) {
					addWptPoint(pw, c);
				}
			}

			if (polygonpoints != null && !polygonpoints.isEmpty()) {
				pw.print("<trk><name>");
				pw.print(fname);
				pw.print("</name><trkseg>");

				for (Point c : polygonpoints) {
					addTrkPoint(pw, c);
				}
				pw.print("</trkseg></trk>");
			}
			pw.print("</gpx>");
		} catch (Exception exp) {
			// only for debugging so just log
			System.err.println("Could not create gpx file " + fname);
		}
	}
}
