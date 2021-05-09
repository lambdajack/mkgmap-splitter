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

import java.util.concurrent.BlockingQueue;

public abstract class AbstractMapProcessor implements MapProcessor {
	public static final int UNASSIGNED = Short.MIN_VALUE;

	@Override
	public boolean skipTags(){
		return false;
	}
	@Override
	public boolean skipNodes(){
		return false;
	}
	@Override
	public boolean skipWays(){
		return false;
	}
	@Override
	public boolean skipRels(){
		return false;
	}

	@Override
	public void boundTag(OsmBounds bounds) {
	}

	@Override
	public void processNode(Node n) {
	}

	@Override
	public void processWay(Way w) {
	}

	@Override
	public void processRelation(Relation r) {
	}

	@Override
	public boolean endMap() {
		return true;
	}

	@Override
	public int getPhase() {
		return 1;
	}

	@Override
	public void startFile() {
	}
		
	/**
	 * Simple method that allows all processors to use the producer/consumer pattern
	 */
	@Override
	public final boolean consume(BlockingQueue<OSMMessage> queue) {
		while (true) {
			try {
				OSMMessage msg = queue.take();
				switch (msg.type) {
				case ELEMENTS:
					for (Element el : msg.elements) {
						if (el == null)
							break;
						if (el instanceof Node)
							processNode((Node) el);
						else if (el instanceof Way)
							processWay((Way) el);
						else if (el instanceof Relation)
							processRelation((Relation) el);
					}
					break;
				case BOUNDS:
					boundTag(msg.bounds);
					break;
				case END_MAP:
					return endMap();
				case START_FILE:
					startFile();
					break;
				case EXIT:
					return true;
				default:
					break;
				}
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
	}

}
