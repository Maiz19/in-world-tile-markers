package com.hdworldmarkers;

import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * Where a ray from the camera meets the ground: for example the tile a Walk here click goes to when the client has
 * no tile under the mouse (117 HD's extended terrain beyond the loaded area). Beyond the loaded area heights are
 * unknown, so the height of the nearest edge is used (Terrain.height).
 */
final class WalkPredictor
{
    /**
     * The world tile where the ray through canvas point (mx, my) meets the
     * terrain, or null. Marches along the ray and refines the crossing.
     */
    static WorldPoint raycast(ModelShapes.Camera camera, float mx, float my, WorldView wv, int plane)
    {
        float[] p = ground(camera, mx, my, wv, plane);
        return p == null ? null
            : new WorldPoint(wv.getBaseX() + (int) Math.floor(p[0] / 128), wv.getBaseY() + (int) Math.floor(p[1] / 128), plane);
    }

    /** The local point {x, y, height} where the ray through canvas point (mx, my) meets the terrain, or null. */
    static float[] ground(ModelShapes.Camera camera, float mx, float my, WorldView wv, int plane)
    {
        float[] p = new float[3];
        float previous = ModelShapes.NEAR;
        for (float depth = ModelShapes.NEAR; depth < 40000; depth += 32)
        {
            camera.unproject(mx, my, depth, p);
            // Heights grow downwards: the ray is below the terrain once its height passes it.
            if (p[2] >= Terrain.height(wv, (int) p[0], (int) p[1], plane))
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
}
