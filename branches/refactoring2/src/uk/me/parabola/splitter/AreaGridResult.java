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

import it.unimi.dsi.fastutil.shorts.ShortArrayList;

/**
 * A helper class to combine the results of the {@link AreaGrid} 
 * @author GerdP
 *
 */
public class AreaGridResult{
	ShortArrayList l;	// list of indexes to the area dictionary
	boolean testNeeded; // true: the list must be checked with the Area.contains() method 
}
