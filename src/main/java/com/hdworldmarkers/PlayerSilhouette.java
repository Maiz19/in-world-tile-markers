package com.hdworldmarkers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Model;

/**
 * An actor's model (the local player's, or an NPC's put In front) as the client draws it this frame, copied (the
 * client's models share their arrays), and the part of the canvas it covers with the camera the client draws with: its
 * front faces, as Improved Tile Indicators clears them from its overlay, traced into a silhouette. Marks drawn through
 * walls leave that out, so the actor stands in front of them (SceneShapeRenderer.Bucket.pullToCamera). Where a wall or
 * object nearer the camera covers the actor, the actor is not seen: that part is left out of the silhouette, so the
 * marks show there as everywhere else.
 */
final class PlayerSilhouette
{
    private final Shape body = new Shape();
    /** The walls and objects of this frame that may stand between the camera and the actors, shared by their silhouettes. */
    private Occluders occluders;
    private final Silhouette.Scratch scratch = new Silhouette.Scratch();
    /** Per raster cell, 1 / depth of the player's nearest face there; 0 for none. */
    private float[] near = new float[0];
    private ModelShapes.Camera camera, lastCamera;
    private PlayerCut cut, lastCut;
    /** Faces of walls and objects taken at most: their models are copied every frame and projected for the cut. */
    static final int MAX_OCCLUDER_FACES = 20000;
    /**
     * How much nearer the camera than the actor a wall or object must be to hide it, in local units; the ground, which
     * is no part of any actor, hides what lies under it (a model's soles, a few units down) by a unit.
     */
    private static final float IN_FRONT = 8, GROUND_IN_FRONT = 1;

    /** A model where it stands, copied, and as projected with the last camera. */
    private static final class Shape
    {
        float[] x = new float[0], y = x, z = x, px = x, py = x, pd = x;
        int[] a = new int[0], b = a, c = a;
        boolean[] skip = new boolean[0], hidden = skip;
        int n, faces, localX, localY, height, orientation;
        /** As an occluder: how much nearer than the actor it must be to hide it. */
        float margin = IN_FRONT;

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
            margin = IN_FRONT;
            return true;
        }

        /** Takes faces given as model points (x, height as y, z) in place, as the ground around an actor; false for none. */
        boolean take(float[] mx, float[] my, float[] mz, int n, int[] fa, int[] fb, int[] fc, int faces)
        {
            this.n = n; this.faces = faces;
            if (n <= 0 || faces <= 0) { return false; }
            if (x.length < n) { x = new float[n * 2]; y = new float[n * 2]; z = new float[n * 2]; px = new float[n * 2]; py = new float[n * 2]; pd = new float[n * 2]; }
            if (a.length < faces) { a = new int[faces * 2]; b = new int[faces * 2]; c = new int[faces * 2]; skip = new boolean[faces * 2]; hidden = new boolean[faces * 2]; }
            System.arraycopy(mx, 0, x, 0, n); System.arraycopy(my, 0, y, 0, n); System.arraycopy(mz, 0, z, 0, n);
            System.arraycopy(fa, 0, a, 0, faces); System.arraycopy(fb, 0, b, 0, faces); System.arraycopy(fc, 0, c, 0, faces);
            Arrays.fill(skip, 0, faces, false);
            localX = 0; localY = 0; height = 0; orientation = 0;
            margin = GROUND_IN_FRONT;
            return true;
        }

        /**
         * Projects it with this camera, vertices nearer than near left out; faces turned away from the camera are not
         * drawn (the renderers cull them).
         */
        void project(ModelShapes.Camera cam, float near)
        {
            ModelShapes.projectModel(cam, x, y, z, n, localX, localY, height, orientation, px, py, pd, near);
            // The ground (margin GROUND_IN_FRONT) hides from either side; a model's faces turned away are not drawn.
            boolean both = margin == GROUND_IN_FRONT;
            for (int f = 0; f < faces; f++)
            {
                int i = a[f], j = b[f], k = c[f];
                hidden[f] = skip[f] || !both && (px[j] - px[i]) * (py[k] - py[i]) - (py[j] - py[i]) * (px[k] - px[i]) >= 0;
            }
        }
    }

    /** The walls and objects that may stand between the camera and the actors this frame, copied and projected once for all of them. */
    static final class Occluders
    {
        private final List<Shape> shapes = new ArrayList<>();
        private int count, faces;
        private ModelShapes.Camera camera;

        /** Leaves out last frame's walls and objects; this frame's come with add, before the actors' models are taken. */
        synchronized void clear() { count = 0; faces = 0; camera = null; }

        /** A wall's or object's model where it stands, which may hide part of an actor; false once there is no room left. */
        synchronized boolean add(Model model, int localX, int localY, int height, int orientation)
        {
            if (faces + model.getFaceCount() > MAX_OCCLUDER_FACES) { return false; }
            if (count == shapes.size()) { shapes.add(new Shape()); }
            if (shapes.get(count).take(model, localX, localY, height, orientation)) { faces += model.getFaceCount(); count++; }
            return true;
        }

        /** The ground's faces (model points: x, height as y, z) around an actor, which hide what lies under it; false once there is no room left. */
        synchronized boolean addGround(float[] mx, float[] my, float[] mz, int n, int[] fa, int[] fb, int[] fc, int faceCount)
        {
            if (faces + faceCount > MAX_OCCLUDER_FACES) { return false; }
            if (count == shapes.size()) { shapes.add(new Shape()); }
            if (shapes.get(count).take(mx, my, mz, n, fa, fb, fc, faceCount)) { faces += faceCount; count++; }
            return true;
        }

        /**
         * Projects them with this camera, once per camera. Walls and objects right at the camera too: a face with a corner
         * nearer than the renderers draw still covers the actor with the rest of it (only corners behind the camera are
         * left out, with their faces).
         */
        synchronized void project(ModelShapes.Camera cam)
        {
            if (cam.same(camera)) { return; }
            camera = cam;
            for (int i = 0; i < count; i++) { shapes.get(i).project(cam, 1); }
        }
    }

    /** Takes the model, standing at this place, with the walls and objects that may hide it; false when it has nothing to draw. */
    synchronized boolean take(Model model, int localX, int localY, int height, int orientation, Occluders occluders)
    {
        if (!body.take(model, localX, localY, height, orientation)) { return false; }
        this.occluders = occluders;
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
        boolean occluded = occluders != null && occluders.count > 0;
        if (occluded) { occluders.project(cam); }
        // The cell staircase, not the exact edge: a cell of precision does not show in the cut, made every frame.
        cut = PlayerCut.of(Silhouette.trace(body.px, body.py, body.a, body.b, body.c, body.faces, body.hidden, scratch, false,
            occluded ? this::unseen : null));
        return cut;
    }

    /**
     * How much nearer the camera than the actor a mark's face must lie, in local units, to count as in front of it: a
     * mark hanging right at the actor's nearest point (an NPC's own hull or clickbox) is cut around it still.
     */
    static final float CLEARANCE = 24;
    private final float[] nearestPoint = new float[3];

    /**
     * How near this camera the actor comes: the depth of its nearest vertex among those the silhouette is traced from
     * (nearer than SceneShapeRenderer.PARTIAL_NEAR left out), as the marks' projections measure it; 0 for none. A mark
     * whose face lies more than CLEARANCE nearer is in front of the actor, as a chest's hull while you stand behind the
     * chest, and is not cut around it (SceneShapeRenderer.front).
     */
    synchronized float nearest(ModelShapes.Camera cam)
    {
        double angle = (body.orientation & 2047) * Math.PI / 1024;
        float sin = (float) Math.sin(angle), cos = (float) Math.cos(angle);
        float[] p = nearestPoint;
        float nearest = Float.MAX_VALUE;
        for (int i = 0; i < body.n; i++)
        {
            float rx = body.z[i] * sin + body.x[i] * cos, rz = body.z[i] * cos - body.x[i] * sin;
            cam.project(body.localX + rx, body.localY + rz, body.height + body.y[i], p);
            if (p[2] >= SceneShapeRenderer.PARTIAL_NEAR) { nearest = Math.min(nearest, p[2]); }
        }
        return nearest == Float.MAX_VALUE ? 0 : nearest;
    }

    /** Leaves the actor's cells out where a wall or object nearer the camera covers them. */
    private void unseen(long[] bits, int words, int w, int h, float ox, float oy, float scale)
    {
        int cells = w * h;
        if (near.length < cells) { near = new float[cells]; }
        Arrays.fill(near, 0, cells, 0);
        float nearest = 0;
        for (int i = 0; i < body.n; i++) { if (body.pd[i] > 0 && !Float.isNaN(body.px[i])) { nearest = Math.max(nearest, 1 / body.pd[i]); } }
        cover(body, w, h, ox, oy, scale, (cell, inverse) -> { if (inverse > near[cell]) { near[cell] = inverse; } });
        // A cell of the raster no face centre fell in (an edge) counts as at the actor's nearest point.
        float fallback = nearest;
        for (int i = 0; i < occluders.count; i++)
        {
            Shape s = occluders.shapes.get(i);
            float margin = s.margin;
            cover(s, w, h, ox, oy, scale, (cell, inverse) -> {
                int column = cell % w, word = cell / w * words + (column >>> 6);
                long bit = 1L << (column & 63);
                if ((bits[word] & bit) == 0) { return; }
                float player = near[cell] > 0 ? near[cell] : fallback;
                // At least the margin nearer: 1 / depth beyond 1 / (actor depth - margin).
                if (player > 0 && inverse * (1 - margin * player) > player) { bits[word] &= ~bit; }
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
