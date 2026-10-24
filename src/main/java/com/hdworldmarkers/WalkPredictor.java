package com.hdworldmarkers;

import java.util.IdentityHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * Where a ray from the camera meets what is drawn: for example the tile a Walk here click goes to when the client has
 * no tile under the mouse (117 HD's extended terrain beyond the loaded area). The ray stops at the first object or
 * wall it passes through, else at the ground; it never goes past the drawn tiles (the loaded area and the extended view).
 */
final class WalkPredictor
{
    /** What the ray met: its tile, and the object or wall there as a route target (null where it met the ground). */
    @RequiredArgsConstructor
    static final class Hit
    {
        final WorldPoint tile;
        final RouteFinder.Target object;
    }

    /** Ray depth samples the ground is checked at, at most this far apart along the ray. */
    private static final float STEP = 32, FAR = 40000;

    /**
     * What the ray through canvas point (mx, my) meets first, an object or the terrain; null where it meets nothing
     * drawn. The ray is walked a tile at a time: a tile's objects are checked once, against the heights the ray has
     * across that tile; the ground every STEP along the ray, and found exactly between the last two.
     */
    static Hit hit(ModelShapes.Camera camera, float mx, float my, WorldView wv, int plane)
    {
        Scene scene = wv.getScene();
        Tile[][][] extended = scene == null ? null : scene.getExtendedTiles();
        Tile[][] tiles = scene == null ? null : extended != null && plane < extended.length && extended[plane].length > wv.getSizeX()
            ? extended[plane] : scene.getTiles() == null ? null : scene.getTiles()[plane];
        // Scene tile (0, 0) at tiles[offset][offset]; without tiles only the loaded area is known.
        int offset = tiles == null ? 0 : (tiles.length - wv.getSizeX()) / 2, size = tiles == null ? wv.getSizeX() : tiles.length;
        // The ray is the camera plus depth times a direction: unproject is linear in depth.
        float[] o = new float[3], d = new float[3], p = new float[3];
        camera.unproject(mx, my, 0, o);
        camera.unproject(mx, my, 1, d);
        for (int i = 0; i < 3; i++) { d[i] -= o[i]; }
        float speed = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        // Each model's height, found once per ray.
        Map<Renderable, Integer> tops = new IdentityHashMap<>();
        // The tile the ray is in, and the depths at which it next crosses a tile's side along x and along y.
        float t = ModelShapes.NEAR;
        int tx = (int) Math.floor((o[0] + t * d[0]) / 128), ty = (int) Math.floor((o[1] + t * d[1]) / 128);
        int stepX = d[0] > 0 ? 1 : -1, stepY = d[1] > 0 ? 1 : -1;
        float nextX = d[0] == 0 ? Float.POSITIVE_INFINITY : ((tx + (d[0] > 0 ? 1 : 0)) * 128 - o[0]) / d[0];
        float nextY = d[1] == 0 ? Float.POSITIVE_INFINITY : ((ty + (d[1] > 0 ? 1 : 0)) * 128 - o[1]) / d[1];
        float acrossX = d[0] == 0 ? Float.POSITIVE_INFINITY : 128 / Math.abs(d[0]), acrossY = d[1] == 0 ? Float.POSITIVE_INFINITY : 128 / Math.abs(d[1]);
        boolean entered = false;
        while (t < FAR)
        {
            float leave = Math.min(FAR, Math.min(nextX, nextY));
            int x = tx + offset, y = ty + offset;
            if (x < 0 || y < 0 || x >= size || y >= size)
            {
                // Once the ray has left the drawn tiles it meets nothing that is drawn.
                if (entered) { return null; }
            }
            else
            {
                entered = true;
                Tile tile = tiles == null ? null : tiles[x][y];
                WorldPoint at = new WorldPoint(wv.getBaseX() + tx, wv.getBaseY() + ty, plane);
                // Heights grow downwards; the ray's heights across this tile.
                float h0 = o[2] + t * d[2], h1 = o[2] + leave * d[2];
                RouteFinder.Target object = tile == null ? null : object(tile, at, Math.min(h0, h1), Math.max(h0, h1), wv, tops);
                if (object != null) { return new Hit(at, object); }
                // A tile without terrain (the void around a cave) is not drawn, so the ray goes on past it.
                if (tiles == null || tile != null)
                {
                    int samples = Math.max(1, (int) Math.ceil((leave - t) * speed / STEP));
                    float before = t;
                    for (int i = 1; i <= samples; i++)
                    {
                        float depth = t + (leave - t) * i / samples;
                        if (below(o, d, depth, wv, plane, p))
                        {
                            // Between the last sample above the ground and this one below it.
                            float low = before, high = depth;
                            for (int k = 0; k < 12; k++)
                            {
                                float mid = (low + high) / 2;
                                if (below(o, d, mid, wv, plane, p)) { high = mid; } else { low = mid; }
                            }
                            below(o, d, high, wv, plane, p);
                            return new Hit(new WorldPoint(wv.getBaseX() + (int) Math.floor(p[0] / 128), wv.getBaseY() + (int) Math.floor(p[1] / 128), plane), null);
                        }
                        before = depth;
                    }
                }
            }
            t = leave;
            if (nextX < nextY) { tx += stepX; nextX += acrossX; } else { ty += stepY; nextY += acrossY; }
        }
        return null;
    }

    /** Puts the ray's point at this depth in p; whether it lies below the terrain (heights grow downwards). */
    private static boolean below(float[] o, float[] d, float depth, WorldView wv, int plane, float[] p)
    {
        for (int i = 0; i < 3; i++) { p[i] = o[i] + depth * d[i]; }
        return p[2] >= Terrain.height(wv, (int) p[0], (int) p[1], plane);
    }

    /**
     * The object or wall on this tile (at) that the ray's heights from high to low pass through, from its base up to its
     * model's top, as a route target from its south-west tile; null when there is none.
     */
    private static RouteFinder.Target object(Tile tile, WorldPoint at, float high, float low, WorldView wv, Map<Renderable, Integer> tops)
    {
        for (GameObject o : tile.getGameObjects())
        {
            if (o == null || !inside(o.getRenderable(), o.getZ(), high, low, tops)) { continue; }
            LocalPoint local = o.getLocalLocation();
            WorldPoint sw = local == null ? at : new WorldPoint(wv.getBaseX() + Math.floorDiv(local.getX() - (o.sizeX() - 1) * 64, 128),
                wv.getBaseY() + Math.floorDiv(local.getY() - (o.sizeY() - 1) * 64, 128), at.getPlane());
            return RouteFinder.Target.object(sw, o.sizeX(), o.sizeY(), o.getConfig(), o.getId());
        }
        WallObject w = tile.getWallObject();
        return w != null && inside(w.getRenderable1(), w.getZ(), high, low, tops) ? RouteFinder.Target.object(at, 1, 1, w.getConfig(), w.getId()) : null;
    }

    /** Whether heights high to low (high the smaller number) reach into the model between its base and its top. */
    private static boolean inside(Renderable r, int base, float high, float low, Map<Renderable, Integer> tops)
    {
        if (r == null) { return false; }
        int top = tops.computeIfAbsent(r, WalkPredictor::top);
        return top > 0 && high <= base && low >= base - top;
    }

    /** A model's height above its base; heights grow downwards, so where it is not set, its lowest vertex y. */
    private static int top(Renderable r)
    {
        int top = r.getModelHeight();
        if (top > 0 || !(r instanceof Model)) { return top; }
        Model m = (Model) r;
        float[] ys = m.getVerticesY();
        int count = ys == null ? 0 : Math.min(m.getVerticesCount(), ys.length);
        for (int i = 0; i < count; i++) { top = Math.max(top, (int) -ys[i]); }
        return top;
    }
}
