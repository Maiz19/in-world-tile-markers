package com.hdworldmarkers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The part of the canvas around the local player that marks drawn through walls may cover: its bounding box minus the
 * player's silhouette, as convex pieces (trapezoids between horizontal lines through the silhouette's points). A mark
 * keeps what lies outside the box and, inside it, only what lies in these pieces, so the player shows in front of it
 * with its exact outline, as Improved Tile Indicators clears the player's triangles from its overlay.
 */
final class PlayerCut
{
    /** The box: left, top, right, bottom. */
    final float minX, minY, maxX, maxY;
    /** Trapezoids {x0, y0, x1, y1, x2, y2, x3, y3} (convex, clockwise on the canvas), and their bounds {minX, maxX}. */
    final List<float[]> pieces = new ArrayList<>();

    private PlayerCut(float minX, float minY, float maxX, float maxY)
    {
        this.minX = minX; this.minY = minY; this.maxX = maxX; this.maxY = maxY;
    }

    /** From the silhouette's loops (outer boundaries and holes, any winding); null without any. */
    static PlayerCut of(List<float[]> loops)
    {
        if (loops == null || loops.isEmpty()) { return null; }
        int edges = 0;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] loop : loops)
        {
            edges += loop.length / 2;
            for (int i = 0; i < loop.length; i += 2)
            {
                minX = Math.min(minX, loop[i]); maxX = Math.max(maxX, loop[i]);
                minY = Math.min(minY, loop[i + 1]); maxY = Math.max(maxY, loop[i + 1]);
            }
        }
        if (!(minX < maxX) || !(minY < maxY)) { return null; }
        PlayerCut cut = new PlayerCut(minX - 1, minY - 1, maxX + 1, maxY + 1);
        // Edges as {x0, y0, x1, y1} with y0 < y1; horizontal ones bound no band.
        float[] e = new float[edges * 4];
        float[] ys = new float[edges + 2];
        int ne = 0, ny = 0;
        for (float[] loop : loops)
        {
            int n = loop.length / 2;
            for (int i = 0; i < n; i++)
            {
                int j = (i + 1) % n;
                float x0 = loop[i * 2], y0 = loop[i * 2 + 1], x1 = loop[j * 2], y1 = loop[j * 2 + 1];
                ys[ny++] = y0;
                if (y0 == y1) { continue; }
                if (y0 > y1) { float t = x0; x0 = x1; x1 = t; t = y0; y0 = y1; y1 = t; }
                e[ne * 4] = x0; e[ne * 4 + 1] = y0; e[ne * 4 + 2] = x1; e[ne * 4 + 3] = y1;
                ne++;
            }
        }
        ys[ny++] = cut.minY;
        ys[ny++] = cut.maxY;
        Arrays.sort(ys, 0, ny);
        // Open trapezoids by their left and right boundary (edge index, or -1 / -2 for the box's sides).
        Map<Long, float[]> open = new HashMap<>(), next = new HashMap<>();
        float[] cross = new float[ne];
        int[] crossEdge = new int[ne];
        int[] order = new int[ne];
        for (int band = 0; band + 1 < ny; band++)
        {
            float ya = ys[band], yb = ys[band + 1];
            if (!(yb > ya)) { continue; }
            float ym = (ya + yb) / 2;
            int k = 0;
            for (int i = 0; i < ne; i++)
            {
                if (e[i * 4 + 1] <= ya && e[i * 4 + 3] >= yb)
                {
                    cross[k] = x(e, i, ym);
                    crossEdge[k] = i;
                    order[k] = k;
                    k++;
                }
            }
            // By crossing, in place: a few per band, sorted without boxing each index (this runs every frame the camera moves).
            for (int i = 1; i < k; i++)
            {
                int o = order[i], j = i - 1;
                while (j >= 0 && cross[order[j]] > cross[o]) { order[j + 1] = order[j]; j--; }
                order[j + 1] = o;
            }
            // Outside the silhouette (even-odd): from the box's left side to the first crossing, between the second
            // and third, and so on, to the box's right side.
            next.clear();
            for (int i = 0; i <= k; i += 2)
            {
                int left = i == 0 ? -1 : crossEdge[order[i - 1]];
                int right = i >= k ? -2 : crossEdge[order[i]];
                long key = ((long) left << 32) ^ (right & 0xffffffffL);
                float[] t = open.remove(key);
                if (t == null) { t = new float[]{left, right, ya, yb}; }
                else { t[3] = yb; }
                next.put(key, t);
            }
            for (float[] t : open.values()) { cut.add(e, t); }
            Map<Long, float[]> swap = open; open = next; next = swap;
        }
        for (float[] t : open.values()) { cut.add(e, t); }
        return cut;
    }

    private void add(float[] e, float[] t)
    {
        int left = (int) t[0], right = (int) t[1];
        float ya = t[2], yb = t[3];
        float la = boundary(e, left, ya), lb = boundary(e, left, yb), ra = boundary(e, right, ya), rb = boundary(e, right, yb);
        if (ra - la <= 1e-3f && rb - lb <= 1e-3f) { return; }
        pieces.add(new float[]{la, ya, ra, ya, rb, yb, lb, yb, Math.min(la, lb), Math.max(ra, rb)});
    }

    private float boundary(float[] e, int edge, float y)
    {
        return edge == -1 ? minX : edge == -2 ? maxX : x(e, edge, y);
    }

    private static float x(float[] e, int i, float y)
    {
        float y0 = e[i * 4 + 1], y1 = e[i * 4 + 3];
        float t = (y - y0) / (y1 - y0);
        return e[i * 4] + (e[i * 4 + 2] - e[i * 4]) * t;
    }
}
