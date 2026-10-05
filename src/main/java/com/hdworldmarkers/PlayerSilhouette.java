package com.hdworldmarkers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Model;

/**
 * The local player's model as the client draws it this frame, copied (the client's models share their arrays), and the
 * part of the canvas it covers with the camera the client draws with: its front faces, as Improved Tile Indicators
 * clears them from its overlay, traced into a silhouette. Marks drawn through walls leave that out, so the player
 * stands in front of them (SceneShapeRenderer.Bucket.pullToCamera). Where a wall or object nearer the camera covers the
 * player, the player is not seen: that part is left out of the silhouette, so the marks show there as everywhere else.
 */
final class PlayerSilhouette
{
    private final Shape body = new Shape();
    /** Walls and objects that may stand between the camera and the player this frame; the first occluderCount count. */
    private final List<Shape> occluders = new ArrayList<>();
    private int occluderCount, occluderFaces;
    private final Silhouette.Scratch scratch = new Silhouette.Scratch();
    /** Per raster cell, 1 / depth of the player's nearest face there; 0 for none. */
    private float[] near = new float[0];
    private ModelShapes.Camera camera, lastCamera;
    private PlayerCut cut, lastCut;
    /** Faces of walls and objects taken at most: their models are copied every frame and projected for the cut. */
    static final int MAX_OCCLUDER_FACES = 20000;
    /** How much nearer the camera than the player a wall or object must be to hide it, in local units. */
    private static final float IN_FRONT = 8;

    /** A model where it stands, copied, and as projected with the last camera. */
    private static final class Shape
    {
        float[] x = new float[0], y = x, z = x, px = x, py = x, pd = x;
        int[] a = new int[0], b = a, c = a;
        boolean[] skip = new boolean[0], hidden = skip;
        int n, faces, localX, localY, height, orientation;

        /** Copies the model; false when it has nothing to draw. */
        boolean take(Model model, int localX, int localY, int height, int orientation)
        {
            n = model.getVerticesCount();
            faces = model.getFaceCount();
            if (n <= 0 || faces <= 0) { return false; }
            if (x.length < n) { x = new float[n * 2]; y = new float[n * 2]; z = new float[n * 2]; px = new float[n * 2]; py = new float[n * 2]; pd = new float[n * 2]; }
            if (a.length < faces) { a = new int[faces * 2]; b = new int[faces * 2]; c = new int[faces * 2]; skip = new boolean[faces * 2]; hidden = new boolean[faces * 2]; }
            System.arraycopy(model.getVerticesX(), 0, x, 0, n);
            System.arraycopy(model.getVerticesY(), 0, y, 0, n);
            System.arraycopy(model.getVerticesZ(), 0, z, 0, n);
            System.arraycopy(model.getFaceIndices1(), 0, a, 0, faces);
            System.arraycopy(model.getFaceIndices2(), 0, b, 0, faces);
            System.arraycopy(model.getFaceIndices3(), 0, c, 0, faces);
            int[] colors = model.getFaceColors3();
            byte[] alpha = model.getFaceTransparencies();
            for (int f = 0; f < faces; f++)
            {
                // Not drawn: hidden faces, and nearly see-through ones, which Improved Tile Indicators leaves too.
                skip[f] = colors != null && f < colors.length && colors[f] == -2 || alpha != null && f < alpha.length && (alpha[f] & 255) >= 254
                    || a[f] < 0 || b[f] < 0 || c[f] < 0 || a[f] >= n || b[f] >= n || c[f] >= n;
                if (skip[f]) { a[f] = b[f] = c[f] = 0; }
            }
            this.localX = localX; this.localY = localY; this.height = height; this.orientation = orientation;
            return true;
        }

        /**
         * Projects it with this camera, vertices nearer than near left out; faces turned away from the camera are not
         * drawn (the renderers cull them).
         */
        void project(ModelShapes.Camera cam, float near)
        {
            ModelShapes.projectModel(cam, x, y, z, n, localX, localY, height, orientation, px, py, pd, near);
            for (int f = 0; f < faces; f++)
            {
                int i = a[f], j = b[f], k = c[f];
                hidden[f] = skip[f] || (px[j] - px[i]) * (py[k] - py[i]) - (py[j] - py[i]) * (px[k] - px[i]) >= 0;
            }
        }
    }

    /** Leaves out last frame's walls and objects; this frame's come with occluder, before take. */
    synchronized void clearOccluders() { occluderCount = 0; occluderFaces = 0; }

    /** A wall's or object's model where it stands, which may hide part of the player; false once there is no room left. */
    synchronized boolean occluder(Model model, int localX, int localY, int height, int orientation)
    {
        if (occluderFaces + model.getFaceCount() > MAX_OCCLUDER_FACES) { return false; }
        if (occluderCount == occluders.size()) { occluders.add(new Shape()); }
        if (occluders.get(occluderCount).take(model, localX, localY, height, orientation)) { occluderFaces += model.getFaceCount(); occluderCount++; }
        return true;
    }

    /** Takes the model, standing at this place; false when it has nothing to draw. */
    synchronized boolean take(Model model, int localX, int localY, int height, int orientation)
    {
        if (!body.take(model, localX, localY, height, orientation)) { return false; }
        if (cut != null) { lastCamera = camera; lastCut = cut; }
        camera = null;
        cut = null;
        return true;
    }

    /**
     * The silhouette as last drawn if that was with this camera, else as cut would trace it: to size the marks' cuts. A
     * frame is built with the camera the client drew the last one with, so the pose of a frame before costs no trace.
     */
    synchronized PlayerCut estimate(ModelShapes.Camera cam)
    {
        return cam.same(lastCamera) && cut == null ? lastCut : cut(cam);
    }

    /** The silhouette as drawn with this camera, traced once per camera; null when nothing of it projects. */
    synchronized PlayerCut cut(ModelShapes.Camera cam)
    {
        if (cam.same(camera)) { return cut; }
        camera = cam;
        body.project(cam, SceneShapeRenderer.PARTIAL_NEAR);
        // Walls and objects right at the camera too: a face with a corner nearer than the renderers draw still covers the
        // player with the rest of it (only corners behind the camera are left out, with their faces).
        for (int i = 0; i < occluderCount; i++) { occluders.get(i).project(cam, 1); }
        // The cell staircase, not the exact edge: a cell of precision does not show in the cut, made every frame.
        cut = PlayerCut.of(Silhouette.trace(body.px, body.py, body.a, body.b, body.c, body.faces, body.hidden, scratch, false,
            occluderCount == 0 ? null : this::unseen));
        return cut;
    }

    /** Leaves the player's cells out where a wall or object nearer the camera covers them. */
    private void unseen(long[] bits, int words, int w, int h, float ox, float oy, float scale)
    {
        int cells = w * h;
        if (near.length < cells) { near = new float[cells]; }
        Arrays.fill(near, 0, cells, 0);
        float nearest = 0;
        for (int i = 0; i < body.n; i++) { if (body.pd[i] > 0 && !Float.isNaN(body.px[i])) { nearest = Math.max(nearest, 1 / body.pd[i]); } }
        cover(body, w, h, ox, oy, scale, (cell, inverse) -> { if (inverse > near[cell]) { near[cell] = inverse; } });
        // A cell of the raster no face centre fell in (an edge) counts as at the player's nearest point.
        float fallback = nearest;
        for (int i = 0; i < occluderCount; i++)
        {
            cover(occluders.get(i), w, h, ox, oy, scale, (cell, inverse) -> {
                int column = cell % w, word = cell / w * words + (column >>> 6);
                long bit = 1L << (column & 63);
                if ((bits[word] & bit) == 0) { return; }
                float player = near[cell] > 0 ? near[cell] : fallback;
                // At least IN_FRONT nearer: 1 / depth beyond 1 / (player depth - IN_FRONT).
                if (player > 0 && inverse * (1 - IN_FRONT * player) > player) { bits[word] &= ~bit; }
            });
        }
    }

    private interface CellVisit { void at(int cell, float inverse); }

    /** Calls back for every raster cell whose centre one of the shape's faces covers, with 1 / depth there (flat faces). */
    private static void cover(Shape s, int w, int h, float ox, float oy, float scale, CellVisit visit)
    {
        for (int f = 0; f < s.faces; f++)
        {
            if (s.hidden[f]) { continue; }
            int i = s.a[f], j = s.b[f], k = s.c[f];
            float x0 = (s.px[i] - ox) * scale, y0 = (s.py[i] - oy) * scale, x1 = (s.px[j] - ox) * scale, y1 = (s.py[j] - oy) * scale;
            float x2 = (s.px[k] - ox) * scale, y2 = (s.py[k] - oy) * scale;
            if (Float.isNaN(x0 + x1 + x2) || !(s.pd[i] > 0 && s.pd[j] > 0 && s.pd[k] > 0)) { continue; }
            float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
            if (Math.abs(area) < 1e-6f) { continue; }
            int cx0 = Math.max(0, (int) Math.ceil(Math.min(x0, Math.min(x1, x2)) - 0.5f)), cx1 = Math.min(w - 1, (int) Math.floor(Math.max(x0, Math.max(x1, x2)) - 0.5f));
            int cy0 = Math.max(0, (int) Math.ceil(Math.min(y0, Math.min(y1, y2)) - 0.5f)), cy1 = Math.min(h - 1, (int) Math.floor(Math.max(y0, Math.max(y1, y2)) - 0.5f));
            if (cx0 > cx1 || cy0 > cy1) { continue; }
            // 1 / depth is linear across the canvas for a flat face.
            float w0 = 1 / s.pd[i], w1 = 1 / s.pd[j], w2 = 1 / s.pd[k];
            float dx = ((w1 - w0) * (y2 - y0) - (w2 - w0) * (y1 - y0)) / area, dy = ((x1 - x0) * (w2 - w0) - (x2 - x0) * (w1 - w0)) / area;
            float sign = Math.signum(area);
            for (int cy = cy0; cy <= cy1; cy++)
            {
                float py = cy + 0.5f;
                for (int cx = cx0; cx <= cx1; cx++)
                {
                    float px = cx + 0.5f;
                    if (sign * ((x1 - x0) * (py - y0) - (y1 - y0) * (px - x0)) < 0 || sign * ((x2 - x1) * (py - y1) - (y2 - y1) * (px - x1)) < 0
                        || sign * ((x0 - x2) * (py - y2) - (y0 - y2) * (px - x2)) < 0) { continue; }
                    visit.at(cy * w + cx, w0 + (px - x0) * dx + (py - y0) * dy);
                }
            }
        }
    }
}
