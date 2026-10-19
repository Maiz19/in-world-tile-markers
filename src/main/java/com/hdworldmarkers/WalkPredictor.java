package com.hdworldmarkers;

import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * Where a ray from the camera meets what is drawn: for example the tile a Walk here click goes to when the client has
 * no tile under the mouse (117 HD's extended terrain beyond the loaded area). The ray stops at the first object or
 * wall it passes through, else at the ground; it never goes past the drawn tiles (the loaded area and the extended view).
 */
final class WalkPredictor
{
    /**
     * The world tile where the ray through canvas point (mx, my) meets an object or the
     * terrain, or null. Marches along the ray and refines the crossing.
     */
    static WorldPoint raycast(ModelShapes.Camera camera, float mx, float my, WorldView wv, int plane)
    {
        float[] p = ground(camera, mx, my, wv, plane);
        return p == null ? null
            : new WorldPoint(wv.getBaseX() + (int) Math.floor(p[0] / 128), wv.getBaseY() + (int) Math.floor(p[1] / 128), plane);
    }

    /** The local point {x, y, height} where the ray through canvas point (mx, my) meets an object or the terrain, or null. */
    static float[] ground(ModelShapes.Camera camera, float mx, float my, WorldView wv, int plane)
    {
        Scene scene = wv.getScene();
        Tile[][][] extended = scene == null ? null : scene.getExtendedTiles();
        Tile[][] tiles = scene == null ? null : extended != null && plane < extended.length && extended[plane].length > wv.getSizeX()
            ? extended[plane] : scene.getTiles() == null ? null : scene.getTiles()[plane];
        // Scene tile (0, 0) at tiles[offset][offset]; without tiles only the loaded area is known.
        int offset = tiles == null ? 0 : (tiles.length - wv.getSizeX()) / 2;
        float[] p = new float[3];
        float previous = ModelShapes.NEAR;
        boolean entered = false;
        for (float depth = ModelShapes.NEAR; depth < 40000; depth += 32)
        {
            camera.unproject(mx, my, depth, p);
            int x = (int) Math.floor(p[0] / 128) + offset, y = (int) Math.floor(p[1] / 128) + offset;
            int size = tiles == null ? wv.getSizeX() : tiles.length;
            if (x < 0 || y < 0 || x >= size || y >= size)
            {
                // Once the ray has left the drawn tiles it meets nothing that is drawn.
                if (entered) { return null; }
                previous = depth;
                continue;
            }
            entered = true;
            Tile tile = tiles == null ? null : tiles[x][y];
            if (tile != null && hitsObject(tile, p[2])) { return p; }
            // Heights grow downwards: the ray is below the terrain once its height passes it. A tile without terrain
            // (the void around a cave) is not drawn, so the ray goes on past it.
            if ((tiles == null || tile != null) && p[2] >= Terrain.height(wv, (int) p[0], (int) p[1], plane))
            {
                float low = previous, high = depth;
                for (int i = 0; i < 12; i++)
                {
                    float mid = (low + high) / 2;
                    camera.unproject(mx, my, mid, p);
                    if (p[2] >= Terrain.height(wv, (int) p[0], (int) p[1], plane)) { high = mid; } else { low = mid; }
                }
                camera.unproject(mx, my, high, p);
                return p;
            }
            previous = depth;
        }
        return null;
    }

    /** Whether height h on this tile lies inside one of its objects or its wall, from its base up to its model's top. */
    private static boolean hitsObject(Tile tile, float h)
    {
        for (GameObject o : tile.getGameObjects())
        {
            if (o != null && inside(o.getRenderable(), o.getZ(), h)) { return true; }
        }
        WallObject w = tile.getWallObject();
        return w != null && inside(w.getRenderable1(), w.getZ(), h);
    }

    private static boolean inside(Renderable r, int base, float h)
    {
        if (r == null) { return false; }
        int top = r.getModelHeight();
        if (top <= 0 && r instanceof Model)
        {
            // Heights grow downwards: a model's top is its lowest vertex y.
            Model m = (Model) r;
            float[] ys = m.getVerticesY();
            for (int i = 0; ys != null && i < m.getVerticesCount() && i < ys.length; i++) { top = Math.max(top, (int) -ys[i]); }
        }
        return top > 0 && h <= base && h >= base - top;
    }
}
