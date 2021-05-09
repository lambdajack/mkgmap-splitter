/*
 * Copyright (c) 2021
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

import java.io.File;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;

import uk.me.parabola.splitter.writer.AbstractOSMWriter;
import uk.me.parabola.splitter.writer.BinaryMapWriter;
import uk.me.parabola.splitter.writer.O5mMapWriter;
import uk.me.parabola.splitter.writer.OSMWriter;
import uk.me.parabola.splitter.writer.OSMXMLWriter;

/**
 * Convert single input file to o5m format (like osmconvert --drop-version --drop-author) which also removes any
 * created_by=* tag.  
 * 
 * @author Gerd Petermann
 */
public class Converter {

	private final OSMFileHandler osmFileHandler = new OSMFileHandler();

	/**
	 * Used for unit tests
	 */
	public static void mainNoSystemExit(String... args) {
		Converter m = new Converter();
		try {
			m.start(args);	
		} catch (StopNoErrorException e) {
			if (e.getMessage() != null)
				System.out.println(e.getMessage());
		}
	}  
	
	public static void main(String[] args) {
		Converter m = new Converter();
		try {
			int rc = m.start(args);
			if (rc != 0)
				System.exit(1);
		} catch (StopNoErrorException e) {
			if (e.getMessage() != null)
				System.out.println(e.getMessage());
		}
	}
	
	private int start(String[] args) {
		if (args.length < 2 || args.length == 1 && "--help".equals(args[0])) {
			System.out.println("Usage: java -cp " + getClass().getName() + " <inputfile> <outputfile>");
			return 1;
		}
		
		String inFileName = args[0];
		String outFileName = args[1];
		if (!Main.testAndReportFname(inFileName, "input file"))
			return 1;
		Instant start = Instant.now();
		System.out.println("Time started: " + new Date());
		
		try {
			
			// configure the input file handler
			osmFileHandler.setFileNames(Arrays.asList(inFileName));
			osmFileHandler.setMaxThreads(2);
			OSMWriter w = createWriter(outFileName);
			w.setVersionMethod(AbstractOSMWriter.REMOVE_VERSION);
			MapProcessor processor = new CopyProcessor(w);
			osmFileHandler.execute(processor);
			
		} catch (CopyFailedExeption e) {
			if (e.getMessage() != null && e.getMessage().length() > 0)
				e.printStackTrace();
			return 1;
		} catch (RuntimeException e) {
			e.printStackTrace();
			return 1;
		}
		Main.reportTime(start);
		return 0;
	}

	private static OSMWriter createWriter(String path) {
		final OSMWriter writer;
		File oFile = new File(path);
		if (path.endsWith("o5m")) {
			writer = new O5mMapWriter(oFile);
		} else if (path.endsWith("pbf")) {
			writer = new BinaryMapWriter(oFile);
		} else {
			writer = new OSMXMLWriter(oFile);
		}
		writer.initForWrite();
		return writer;
	}
}
