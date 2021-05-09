package uk.me.parabola.splitter;

import java.io.IOException;

import uk.me.parabola.splitter.writer.OSMWriter;

public class CopyProcessor extends AbstractMapProcessor {

	private OSMWriter writer;

	public CopyProcessor(OSMWriter w) {
		this.writer = w;
	}

	@Override
	public void boundTag(OsmBounds bounds) {
		try {
			writer.write(bounds);
		} catch (IOException e) {
			throw new CopyFailedExeption("Failed while writing bounds");
		}
	}

	@Override
	public void processNode(Node n) {
		try {
			writer.write(n);
		} catch (IOException e) {
			throw new CopyFailedExeption("Failed while writing node " + n);
		}
	}

	@Override
	public void processWay(Way w) {
		try {
			writer.write(w);
		} catch (IOException e) {
			throw new CopyFailedExeption("Failed while writing way " + w);
		}
	}

	@Override
	public void processRelation(Relation r) {
		try {
			writer.write(r);
		} catch (IOException e) {
			throw new CopyFailedExeption("Failed while writing relation " + r);
		}
	}

	@Override
	public boolean endMap() {
		writer.finishWrite();
		return true;
	}

}
