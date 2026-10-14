/*
 * Copyright (c) 2017, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
/*
 * Height interpolation follows RuneLite's Perspective.getTileHeight (https://github.com/runelite/runelite),
 * BSD 2-Clause License, copyright the RuneLite contributors; see META-INF/LICENSE-runelite and
 * THIRD_PARTY_NOTICES.md. Changes for HD World Markers: no LocalPoint per sample, a fixed level per footprint.
 */
package com.hdworldmarkers;

import net.runelite.api.Scene;
import net.runelite.api.WorldView;

/**
 * Terrain height and render level, equivalent to Perspective.getTileHeight but
 * without allocating a LocalPoint per sample. On bridge tiles the walkable
 * surface and the render level are one plane higher than the logical plane.
 */
final class Terrain
{
    private Terrain() { }

    static int height(WorldView wv, int localX, int localY, int plane)
    {
        return heightOnLevel(wv, localX, localY, level(wv, localX >> 7, localY >> 7, plane));
    }

    /**
     * Height on a fixed level. A footprint samples all its corners on its own
     * tile's level: a corner on the edge of a bridge tile belongs to the
     * neighbouring tile, which may have no bridge flag.
     */
    static int heightOnLevel(WorldView wv, int localX, int localY, int z)
    {
        int[][][] heights = wv.getTileHeights();
        int offset = 0;
        if (localX < 0 || localY < 0 || localX >= wv.getSizeX() * 128 || localY >= wv.getSizeY() * 128)
        {
            // Beyond the loaded area: the scene's extended heights (a renderer's extended view, 40 tiles each way) where
            // there are any, else the nearest edge's height is the best estimate.
            int[][][] extended = extendedHeights(wv);
            if (extended != null) { heights = extended; offset = extendedOffset(wv, extended); }
            localX = Math.max(-offset * 128, Math.min((wv.getSizeX() + offset) * 128 - 1, localX));
            localY = Math.max(-offset * 128, Math.min((wv.getSizeY() + offset) * 128 - 1, localY));
        }
        int sceneX = (localX >> 7) + offset, sceneY = (localY >> 7) + offset;
        // Heights are stored per corner, one more than the tile count.
        int x1 = Math.min(sceneX + 1, heights[z].length - 1), y1 = Math.min(sceneY + 1, heights[z][0].length - 1);
        int fx = localX & 127, fy = localY & 127;
        int south = fx * heights[z][x1][sceneY] + (128 - fx) * heights[z][sceneX][sceneY] >> 7;
        int north = fx * heights[z][x1][y1] + (128 - fx) * heights[z][sceneX][y1] >> 7;
        return fy * north + (128 - fy) * south >> 7;
    }

    /** The scene's heights for its extended area, or null where the scene is no larger than the loaded area. */
    private static int[][][] extendedHeights(WorldView wv)
    {
        Scene scene = wv.getScene();
        int[][][] extended = scene == null ? null : scene.getTileHeights();
        return extended != null && extended.length > 0 && extended[0].length > wv.getSizeX() + 1 ? extended : null;
    }

    /** How many tiles the extended arrays reach beyond the loaded area on each side. */
    static int extendedOffset(WorldView wv, int[][][] extended) { return (extended[0].length - 1 - wv.getSizeX()) / 2; }

    /** The plane the client renders a tile's contents on. */
    static int level(WorldView wv, int sceneX, int sceneY, int plane)
    {
        byte[][][] settings = wv.getTileSettings();
        if (plane >= 3 || settings == null) { return plane; }
        if (sceneX < 0 || sceneY < 0 || sceneX >= settings[1].length || sceneY >= settings[1][sceneX].length)
        {
            // Beyond the loaded area: the scene's extended settings, where there are any.
            Scene scene = wv.getScene();
            byte[][][] extended = scene == null ? null : scene.getExtendedTileSettings();
            if (extended == null || extended.length < 2 || extended[1].length <= settings[1].length) { return plane; }
            int offset = (extended[1].length - settings[1].length) / 2;
            sceneX += offset; sceneY += offset;
            settings = extended;
            if (sceneX < 0 || sceneY < 0 || sceneX >= settings[1].length || sceneY >= settings[1][sceneX].length) { return plane; }
        }
        return (settings[1][sceneX][sceneY] & 2) != 0 ? plane + 1 : plane;
    }
}
