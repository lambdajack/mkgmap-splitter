/*
 * Copyright (c) 2009, Francisco Moraes
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

package uk.me.parabola.splitter.writer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import crosby.binary.BinarySerializer;
import crosby.binary.Osmformat;
import crosby.binary.Osmformat.DenseInfo;
import crosby.binary.Osmformat.Relation.MemberType;
import crosby.binary.StringTable;
import crosby.binary.file.BlockOutputStream;
import crosby.binary.file.FileBlock;
import uk.me.parabola.splitter.Element;
import uk.me.parabola.splitter.Node;
import uk.me.parabola.splitter.OsmBounds;
import uk.me.parabola.splitter.Relation;
import uk.me.parabola.splitter.Relation.Member;
import uk.me.parabola.splitter.Version;
import uk.me.parabola.splitter.Way;

public class BinaryMapWriter extends AbstractOSMWriter {

	protected PBFSerializer serializer;

	private BlockOutputStream output;

	protected boolean useDense = true;

	protected boolean headerWritten = false;

	private class PBFSerializer extends BinarySerializer {

		public PBFSerializer(BlockOutputStream output) {
			super(output);
			configBatchLimit(1000);
			// omit_metadata = true;
		}

		/**
		 * Base class containing common code needed for serializing each type of
		 * primitives.
		 */
		private abstract class Prim<T extends Element> {
			/** Queue that tracks the list of all primitives. */
			ArrayList<T> contents = new ArrayList<>();

			/**
			 * Add to the queue.
			 * 
			 * @param item The entity to add
			 */
			public void add(T item) {
				contents.add(item);
			}

			/**
			 * Add all of the tags of all entities in the queue to the string table.
			 */
			public void addStringsToStringtable() {
				StringTable stable = getStringTable();
				for (T i : contents) {
					Iterator<Element.Tag> tags = i.tagsIterator();
					while (tags.hasNext()) {
						Element.Tag tag = tags.next();
						stable.incr(tag.getKey());
						stable.incr(tag.getValue());
					}
					if (!omit_metadata) {
						// stable.incr(i.getUser().getName());
					}
				}
			}

			// private static final int MAXWARN = 100;

			public void serializeMetadataDense(DenseInfo.Builder b, List<? extends Element> entities) {
				if (omit_metadata) {
					return;
				}

				// long lasttimestamp = 0, lastchangeset = 0;
				// int lastuserSid = 0, lastuid = 0;
				// StringTable stable = serializer.getStringTable();
				// for(Element e : entities) {
				//
				// if(e.getUser() == OsmUser.NONE && warncount < MAXWARN) {
				// LOG
				// .warning("Attention: Data being output lacks metadata. Please
				// use omitmetadata=true");
				// warncount++;
				// }
				// int uid = e.getUser().getId();
				// int userSid = stable.getIndex(e.getUser().getName());
				// int timestamp = (int)(e.getTimestamp().getTime() /
				// date_granularity);
				// int version = e.getVersion();
				// long changeset = e.getChangesetId();
				//
				// b.addVersion(version);
				// b.addTimestamp(timestamp - lasttimestamp);
				// lasttimestamp = timestamp;
				// b.addChangeset(changeset - lastchangeset);
				// lastchangeset = changeset;
				// b.addUid(uid - lastuid);
				// lastuid = uid;
				// b.addUserSid(userSid - lastuserSid);
				// lastuserSid = userSid;
				// }

				for (Element e : entities) {
					int version = getWriteVersion(e);
					if (versionMethod != KEEP_VERSION || version == 0)
						version = 1; // JOSM requires a fake version
					b.addVersion(version);
					b.addTimestamp(0);
					b.addChangeset(0);
					b.addUid(0);
					b.addUserSid(0);
				}
			}

			public Osmformat.Info.Builder serializeMetadata(Element e) {
				// StringTable stable = serializer.getStringTable();
				Osmformat.Info.Builder b = Osmformat.Info.newBuilder();
				// if(!omit_metadata) {
				// if(e.getUser() == OsmUser.NONE && warncount < MAXWARN) {
				// LOG
				// .warning("Attention: Data being output lacks metadata. Please
				// use omitmetadata=true");
				// warncount++;
				// }
				// if(e.getUser() != OsmUser.NONE) {
				// b.setUid(e.getUser().getId());
				// b.setUserSid(stable.getIndex(e.getUser().getName()));
				// }
				// b.setTimestamp((int)(e.getTimestamp().getTime() /
				// date_granularity));
				// b.setVersion(e.getVersion());
				// b.setChangeset(e.getChangesetId());
				// }
				if (versionMethod != REMOVE_VERSION) {
					int version = getWriteVersion(e);
					if (version != 0) {
						b.setVersion(version);
						b.setTimestamp(0);
						b.setChangeset(0);
						b.setUid(0);
						b.setUserSid(0);
					}
				}
				return b;
			}
		}

		private class NodeGroup extends Prim<Node> implements PrimGroupWriterInterface {

			@Override
			public Osmformat.PrimitiveGroup serialize() {
				if (useDense)
					return serializeDense();
				return serializeNonDense();
			}

			/**
			 * Serialize all nodes in the 'dense' format.
			 */
			public Osmformat.PrimitiveGroup serializeDense() {
				if (contents.isEmpty()) {
					return null;
				}
				// System.out.format("%d Dense ",nodes.size());
				Osmformat.PrimitiveGroup.Builder builder = Osmformat.PrimitiveGroup.newBuilder();
				StringTable stable = serializer.getStringTable();

				long lastlat = 0, lastlon = 0, lastid = 0;
				Osmformat.DenseNodes.Builder bi = Osmformat.DenseNodes.newBuilder();
				boolean doesBlockHaveTags = false;
				// Does anything in this block have tags?
				for (Node i : contents) {
					doesBlockHaveTags = doesBlockHaveTags || (i.tagsIterator().hasNext());
				}
				if (!omit_metadata) {
					Osmformat.DenseInfo.Builder bdi = Osmformat.DenseInfo.newBuilder();
					serializeMetadataDense(bdi, contents);
					bi.setDenseinfo(bdi);
				}

				for (Node i : contents) {
					long id = i.getId();
					int lat = mapDegrees(i.getLat());
					int lon = mapDegrees(i.getLon());
					bi.addId(id - lastid);
					lastid = id;
					bi.addLon(lon - lastlon);
					lastlon = lon;
					bi.addLat(lat - lastlat);
					lastlat = lat;

					// Then we must include tag information.
					if (doesBlockHaveTags) {
						Iterator<Element.Tag> tags = i.tagsIterator();
						while (tags.hasNext()) {
							Element.Tag t = tags.next();
							bi.addKeysVals(stable.getIndex(t.getKey()));
							bi.addKeysVals(stable.getIndex(t.getValue()));
						}
						bi.addKeysVals(0); // Add delimiter.
					}
				}
				builder.setDense(bi);
				return builder.build();
			}

			/**
			 * Serialize all nodes in the non-dense format.
			 * 
			 * @param parentbuilder Add to this PrimitiveBlock.
			 */
			public Osmformat.PrimitiveGroup serializeNonDense() {
				if (contents.isEmpty()) {
					return null;
				}
				// System.out.format("%d Nodes ",nodes.size());
				StringTable stable = serializer.getStringTable();
				Osmformat.PrimitiveGroup.Builder builder = Osmformat.PrimitiveGroup.newBuilder();
				for (Node i : contents) {
					long id = i.getId();
					int lat = mapDegrees(i.getLat());
					int lon = mapDegrees(i.getLon());
					Osmformat.Node.Builder bi = Osmformat.Node.newBuilder();
					bi.setId(id);
					bi.setLon(lon);
					bi.setLat(lat);
					Iterator<Element.Tag> tags = i.tagsIterator();
					while (tags.hasNext()) {
						Element.Tag t = tags.next();
						bi.addKeys(stable.getIndex(t.getKey()));
						bi.addVals(stable.getIndex(t.getValue()));
					}
					if (!omit_metadata) {
						bi.setInfo(serializeMetadata(i));
					}
					builder.addNodes(bi);
				}
				return builder.build();
			}

		}

		private class WayGroup extends Prim<Way> implements PrimGroupWriterInterface {
			@Override
			public Osmformat.PrimitiveGroup serialize() {
				if (contents.isEmpty()) {
					return null;
				}

				// System.out.format("%d Ways ",contents.size());
				StringTable stable = serializer.getStringTable();
				Osmformat.PrimitiveGroup.Builder builder = Osmformat.PrimitiveGroup.newBuilder();
				for (Way i : contents) {
					Osmformat.Way.Builder bi = Osmformat.Way.newBuilder();
					bi.setId(i.getId());
					long lastid = 0;
					for (long j : i.getRefs()) {
						long id = j;
						bi.addRefs(id - lastid);
						lastid = id;
					}
					Iterator<Element.Tag> tags = i.tagsIterator();
					while (tags.hasNext()) {
						Element.Tag t = tags.next();
						bi.addKeys(stable.getIndex(t.getKey()));
						bi.addVals(stable.getIndex(t.getValue()));
					}
					if (!omit_metadata) {
						bi.setInfo(serializeMetadata(i));
					}
					builder.addWays(bi);
				}
				return builder.build();
			}
		}

		private class RelationGroup extends Prim<Relation> implements PrimGroupWriterInterface {
			@Override
			public void addStringsToStringtable() {
				StringTable stable = serializer.getStringTable();
				super.addStringsToStringtable();
				for (Relation i : contents) {
					for (Member j : i.getMembers()) {
						stable.incr(j.getRole());
					}
				}
			}

			@Override
			public Osmformat.PrimitiveGroup serialize() {
				if (contents.isEmpty()) {
					return null;
				}

				// System.out.format("%d Relations ",contents.size());
				StringTable stable = serializer.getStringTable();
				Osmformat.PrimitiveGroup.Builder builder = Osmformat.PrimitiveGroup.newBuilder();
				for (Relation i : contents) {
					Osmformat.Relation.Builder bi = Osmformat.Relation.newBuilder();
					bi.setId(i.getId());
					Member[] arr = new Member[i.getMembers().size()];
					i.getMembers().toArray(arr);
					long lastid = 0;
					for (Member j : i.getMembers()) {
						long id = j.getRef();
						bi.addMemids(id - lastid);
						lastid = id;
						if ("node".equals(j.getType())) {
							bi.addTypes(MemberType.NODE);
						} else if ("way".equals(j.getType())) {
							bi.addTypes(MemberType.WAY);
						} else if ("relation".equals(j.getType())) {
							bi.addTypes(MemberType.RELATION);
						} else {
							assert (false); // Software bug: Unknown entity.
						}
						bi.addRolesSid(stable.getIndex(j.getRole()));
					}

					Iterator<Element.Tag> tags = i.tagsIterator();
					while (tags.hasNext()) {
						Element.Tag t = tags.next();
						bi.addKeys(stable.getIndex(t.getKey()));
						bi.addVals(stable.getIndex(t.getValue()));
					}
					if (!omit_metadata) {
						bi.setInfo(serializeMetadata(i));
					}
					builder.addRelations(bi);
				}
				return builder.build();
			}
		}

		/* One list for each type */
		protected WayGroup ways;

		protected NodeGroup nodes;

		protected RelationGroup relations;

		protected Processor processor = new Processor();

		/**
		 * Buffer up events into groups that are all of the same type, or all of the
		 * same length, then process each buffer.
		 */
		public class Processor {

			/**
			 * Check if we've reached the batch size limit and process the batch if we have.
			 */
			public void checkLimit() {
				total_entities++;
				if (++batch_size < batch_limit) {
					return;
				}
				switchTypes();
				processBatch();
			}

			public void process(Node node) {
				if (nodes == null) {
					writeEmptyHeaderIfNeeded();
					// Need to switch types.
					switchTypes();
					nodes = new NodeGroup();
				}
				nodes.add(node);
				checkLimit();
			}

			public void process(Way way) {
				if (ways == null) {
					writeEmptyHeaderIfNeeded();
					switchTypes();
					ways = new WayGroup();
				}
				ways.add(way);
				checkLimit();
			}

			public void process(Relation relation) {
				if (relations == null) {
					writeEmptyHeaderIfNeeded();
					switchTypes();
					relations = new RelationGroup();
				}
				relations.add(relation);
				checkLimit();
			}
		}

		/**
		 * At the end of this function, all of the lists of unprocessed 'things' must be
		 * null
		 */
		protected void switchTypes() {
			if (nodes != null) {
				groups.add(nodes);
				nodes = null;
			} else if (ways != null) {
				groups.add(ways);
				ways = null;
			} else if (relations != null) {
				groups.add(relations);
				relations = null;
			} else {
				// No data. Is this an empty file?
			}
		}

		/** Write empty header block when there's no bounds entity. */
		public void writeEmptyHeaderIfNeeded() {
			if (headerWritten) {
				return;
			}
			Osmformat.HeaderBlock.Builder headerblock = Osmformat.HeaderBlock.newBuilder();
			finishHeader(headerblock);
		}
	}

	public BinaryMapWriter(File oFile) {
		super(oFile);
	}

	public BinaryMapWriter(String baseName) {
		super(new File(baseName + ".osm.pbf"));
	}

	@Override
	public void initForWrite() {
		try {
			output = new BlockOutputStream(new FileOutputStream(outputFile));
			serializer = new PBFSerializer(output);
		} catch (IOException e) {
			System.out.println("Could not open or write file header. Reason: " + e.getMessage());
			throw new RuntimeException(e);
		}

	}

	private void writeHeader(OsmBounds osmBounds) {
		Osmformat.HeaderBlock.Builder headerblock = Osmformat.HeaderBlock.newBuilder();

		Osmformat.HeaderBBox.Builder pbfBbox = Osmformat.HeaderBBox.newBuilder();
		pbfBbox.setLeft(serializer.mapRawDegrees(osmBounds.getMinLong()));
		pbfBbox.setBottom(serializer.mapRawDegrees(osmBounds.getMinLat()));
		pbfBbox.setRight(serializer.mapRawDegrees(osmBounds.getMaxLong()));
		pbfBbox.setTop(serializer.mapRawDegrees(osmBounds.getMaxLat()));
		headerblock.setBbox(pbfBbox);

		finishHeader(headerblock);
	}

	/**
	 * Write the header fields that are always needed.
	 * 
	 * @param headerblock Incomplete builder to complete and write.
	 */
	public void finishHeader(Osmformat.HeaderBlock.Builder headerblock) {
		headerblock.setWritingprogram("splitter-r" + Version.VERSION);
		headerblock.addRequiredFeatures("OsmSchema-V0.6");
		if (useDense) {
			headerblock.addRequiredFeatures("DenseNodes");
		}
		Osmformat.HeaderBlock message = headerblock.build();
		try {
			output.write(FileBlock.newInstance("OSMHeader", message.toByteString(), null));
		} catch (IOException e) {
			throw new RuntimeException("Unable to write OSM header.", e);
		}
		headerWritten = true;
	}

	@Override
	public void finishWrite() {
		try {
			serializer.switchTypes();
			serializer.processBatch();
			serializer.close();
			serializer = null;
		} catch (IOException e) {
			System.out.println("Could not write end of file: " + e);
		}
	}

	@Override
	public void write(Node node) {
		serializer.processor.process(node);
	}

	@Override
	public void write(Way way) {
		serializer.processor.process(way);
	}

	@Override
	public void write(Relation relation) {
		serializer.processor.process(relation);
	}

	@Override
	public void write(OsmBounds osmBounds) throws IOException {
		writeHeader(osmBounds);
	}
}
