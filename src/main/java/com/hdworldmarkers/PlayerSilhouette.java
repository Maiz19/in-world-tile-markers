package com.hdworldmarkers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Model;

/**
 * An actor's model (the local player's, or an NPC's put In front) as the client draws it this frame, copied (the
 * client's models share their arrays), and the part of the canvas it covers with the camera the client draws with: its
 * front faces, as Improved Tile Indicators clears them from its overlay, traced into a silhouette. Marks drawn through
 * walls leave that out, so the actor stands in front of them (SceneShapeRenderer.Bucket.pullToCamera): their borders
 * around the whole actor, hidden parts too (no border runs over the actor behind a post), their fills only around what
 * is seen of it (visibleCut: a hole in a fill over the post showed the actor's shape). Where a wall or object nearer the
 * camera covers the actor, that part is left out of the seen silhouette.
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
    /** As cut and lastCut, of the seen part only. */
    private PlayerCut visibleCut, lastVisibleCut;
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
            room(n, faces);
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

        /** Room for n points and this many faces. */
        private void room(int n, int faces)
        {
            if (x.length < n) { x = new float[n * 2]; y = new float[n * 2]; z = new float[n * 2]; px = new float[n * 2]; py = new float[n * 2]; pd = new float[n * 2]; }
            if (a.length < faces) { a = new int[faces * 2]; b = new int[faces * 2]; c = new int[faces * 2]; skip = new boolean[faces * 2]; hidden = new boolean[faces * 2]; }
        }

        /** Takes faces given as model points (x, height as y, z) in place, as the ground around an actor; false for none. */
        boolean take(float[] mx, float[] my, float[] mz, int n, int[] fa, int[] fb, int[] fc, int faces)
        {
            this.n = n; this.faces = faces;
            if (n <= 0 || faces <= 0) { return false; }
            room(n, faces);
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
                hidden[f] = skip[f] || !both && !Silhouette.front(px, py, a[f], b[f], c[f]);
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
        if (cut != null) { lastCamera = camera; lastCut = cut; lastVisibleCut = visibleCut; }
        camera = null;
        cut = null;
        visibleCut = null;
        return true;
    }

    /** Puts the model taken in the pose it is drawn in (see DrawnPose): its vertices, at least as many as the model's. */
    synchronized void drawnAs(float[] x, float[] y, float[] z)
    {
        System.arraycopy(x, 0, body.x, 0, body.n);
        System.arraycopy(y, 0, body.y, 0, body.n);
        System.arraycopy(z, 0, body.z, 0, body.n);
    }

    /**
     * The silhouette as last drawn if that was with this camera, else as cut would trace it: to size the marks' cuts. A
     * frame is built with the camera the client drew the last one with, so the pose of a frame before costs no trace.
     */
    synchronized PlayerCut estimate(ModelShapes.Camera cam)
    {
        return cam.same(lastCamera) && cut == null ? lastCut : cut(cam);
    }

    /** As estimate, of the seen part only. */
    synchronized PlayerCut estimateVisible(ModelShapes.Camera cam)
    {
        return cam.same(lastCamera) && cut == null ? lastVisibleCut : visibleCut(cam);
    }

    /** As cut, of the seen part only: what a wall or object nearer the camera covers is left out. */
    synchronized PlayerCut visibleCut(ModelShapes.Camera cam)
    {
        cut(cam);
        return visibleCut;
    }

    /** The whole silhouette as drawn with this camera, traced once per camera (with visibleCut); null when nothing of it projects. */
    synchronized PlayerCut cut(ModelShapes.Camera cam)
    {
        if (cam.same(camera)) { return cut; }
        camera = cam;
        body.project(cam, SceneShapeRenderer.PARTIAL_NEAR);
        boolean occluded = occluders != null && occluders.count > 0;
        if (occluded) { occluders.project(cam); }
        // The cell staircase, not the exact edge: a cell of precision does not show in the cut, made every frame. Both
        // silhouettes from one raster: the whole one, and the one with what the walls and objects cover left out.
        List<float[]> whole = occluded ? new ArrayList<>() : null;
        List<float[]> seen = Silhouette.trace(body.px, body.py, body.a, body.b, body.c, body.faces, body.hidden, scratch, false,
            occluded ? this::unseen : null, whole);
        cut = PlayerCut.of(occluded ? whole : seen);
        visibleCut = occluded ? PlayerCut.of(seen) : cut;
        return cut;
    }

    /**
     * How much nearer the camera than the actor a mark's face must lie, in local units, to count as in front of it: a
     * mark hanging right at the actor's nearest point (an NPC's own hull or clickbox) is cut around it still.
     */
    static final float CLEARANCE = 24;
    private final float[] nearestPoint = new float[3];

    /**
     * How near this camera the actor comes: the depth of its nearest vertex (nearer than SceneShapeRenderer.PARTIAL_NEAR
     * left out), as the marks' projections measure it; 0 for none. A model mark whose face lies more than CLEARANCE
     * nearer is in front of the actor, as a chest's hull while you stand behind the chest, and is not cut around it
     * (SceneShapeRenderer.front).
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

    /**
     * Leaves the actor's cells out where a wall or object nearer the camera covers them. Only the actor's own cells are
     * visited (Cells): zoomed in, the ground's faces cover the whole raster, and visiting every cell of it took a few
     * milliseconds a frame (issue 18).
     */
    private void unseen(long[] bits, int words, int w, int h, float ox, float oy, float scale)
    {
        float nearest = 0, farthest = Float.MAX_VALUE;
        for (int i = 0; i < body.n; i++)
        {
            if (body.pd[i] > 0 && !Float.isNaN(body.px[i])) { float inverse = 1 / body.pd[i]; nearest = Math.max(nearest, inverse); farthest = Math.min(farthest, inverse); }
        }
        // Nothing of the actor projects: no cell is at any depth, none is left out.
        if (nearest == 0) { return; }
        // The faces of walls, objects and ground that come near enough the camera to hide any of the actor: no cell of
        // it has a smaller 1 / depth than its farthest point (less a little for rounding). Zoomed in, the ground behind
        // the actor covers most of the raster; it hides nothing and is not visited at all.
        float least = farthest * (1 - ROUNDING);
        int[] box = reach;
        box[0] = w; box[1] = h; box[2] = -1; box[3] = -1;
        for (int i = 0; i < occluders.count; i++)
        {
            Shape s = occluders.shapes.get(i);
            reach(s, camera, hides(least, s.margin), ox, oy, scale, box);
        }
        if (box[2] < box[0] || box[3] < box[1]) { return; }
        int cells = w * h;
        if (near.length < cells) { near = new float[cells]; }
        Arrays.fill(near, 0, cells, 0);
        // The actor's depth is only read where those faces lie.
        actorCells.of(bits, words, w, h, box);
        cover(body, actorCells, ox, oy, scale, 0, NEAREST, 0, 0);
        // A cell of the raster no face centre fell in (an edge) counts as at the actor's nearest point.
        for (int i = 0; i < occluders.count; i++)
        {
            Shape s = occluders.shapes.get(i);
            cover(s, actorCells, ox, oy, scale, hides(least, s.margin), HIDE, s.margin, nearest);
        }
    }

    private final Cells actorCells = new Cells();
    /** What cover does in each cell: keep the actor's nearest 1 / depth (near), or leave the cell out where the face hides it. */
    private static final int NEAREST = 0, HIDE = 1;
    /** The raster cells (first column, first row, last column, last row) the faces that can hide the actor reach. */
    private final int[] reach = new int[4];
    /** Relative rounding allowed for when a face is known not to reach a cell's depth (far beyond float's error). */
    private static final float ROUNDING = 1e-3f;

    /**
     * The 1 / depth a face must pass somewhere to hide an actor cell, with the margin, where the actor's least 1 / depth
     * is least: at a cell whose actor 1 / depth is p the face hides it when 1 / depth > p / (1 - margin p), which grows
     * with p. 0 (no face is passed over) when the actor reaches about the margin from the camera.
     */
    private static float hides(float least, float margin)
    {
        float room = 1 - margin * least;
        return room > 0.01f ? least / room : 0;
    }

    /**
     * The raster's cells of the actor (its bits set) within a box, the only ones unseen reads (near) or clears: per row
     * the first and last of them, and the bits themselves, so a visit skips the rest a word at a time.
     */
    private static final class Cells
    {
        long[] bits;
        int words, w, h;
        int[] first = new int[0], last = first;

        /** Takes the set bits within the box. */
        void of(long[] bits, int words, int w, int h, int[] box)
        {
            this.bits = bits; this.words = words; this.w = w; this.h = h;
            if (first.length < h) { first = new int[h * 2]; last = new int[h * 2]; }
            for (int r = 0; r < h; r++)
            {
                int row = r * words, lo = w, hi = -1;
                if (r >= box[1] && r <= box[3])
                {
                    for (int k = 0; k < words; k++) { if (bits[row + k] != 0) { lo = k * 64 + Long.numberOfTrailingZeros(bits[row + k]); break; } }
                    for (int k = words - 1; lo < w && k >= 0; k--) { if (bits[row + k] != 0) { hi = k * 64 + 63 - Long.numberOfLeadingZeros(bits[row + k]); break; } }
                }
                first[r] = Math.max(lo, box[0]); last[r] = Math.min(hi, box[2]);
            }
        }
    }

    /**
     * Grows the box (raster cells: first column, first row, last column, last row) by the cells the shape's faces cover
     * that come nearer the camera than 1 / depth limit anywhere (all faces for limit 0).
     */
    private static void reach(Shape s, ModelShapes.Camera cam, float limit, float ox, float oy, float scale, int[] box)
    {
        float[] poly = new float[12], view = new float[9];
        int[] v = new int[3];
        for (int f = 0; f < s.faces; f++)
        {
            int n = polygon(s, f, cam, poly, view, v);
            if (n == 0 || passed(poly, n, limit)) { continue; }
            for (int q = 0; q < n; q++)
            {
                float x = (poly[q * 3] - ox) * scale, y = (poly[q * 3 + 1] - oy) * scale;
                // A cell is visited when its centre is in the face: within a cell of the corners' extremes.
                box[0] = Math.min(box[0], (int) Math.max(0, Math.floor(x) - 1)); box[2] = Math.max(box[2], (int) Math.min(Integer.MAX_VALUE / 2, Math.ceil(x) + 1));
                box[1] = Math.min(box[1], (int) Math.max(0, Math.floor(y) - 1)); box[3] = Math.max(box[3], (int) Math.min(Integer.MAX_VALUE / 2, Math.ceil(y) + 1));
            }
        }
    }

    /** Whether the face (n corners with 1 / depth) stays beyond 1 / depth limit everywhere: it hides nothing (never for limit 0). */
    private static boolean passed(float[] poly, int n, float limit)
    {
        if (limit <= 0) { return false; }
        float most = 0;
        for (int q = 0; q < n; q++) { most = Math.max(most, poly[q * 3 + 2]); }
        return most * (1 + ROUNDING) <= limit;
    }

    /** Nearer than this the renderers draw nothing (117 HD clips at twice its near plane): a face is cut off there. */
    private static final float NEAR = 2 * ModelShapes.NEAR;

    /**
     * Does what mode says (NEAREST, HIDE with the shape's margin and the actor's fallback 1 / depth) in every actor cell
     * (set in the raster now) whose centre one of the shape's faces covers, with 1 / depth there (flat faces); faces
     * staying beyond 1 / depth limit are passed over (passed).
     * A face reaching nearer the camera than NEAR is clipped there first, as the renderers clip it: projecting a corner
     * that near throws the face's canvas triangle far out, and it covered the actor where the renderers draw nothing of
     * it (a railing post beside a low camera wiped the silhouette, so the marks came over the actor).
     */
    private void cover(Shape s, Cells cells, float ox, float oy, float scale, float limit, int mode, float margin, float fallback)
    {
        ModelShapes.Camera cam = camera;
        float[] poly = new float[12], view = new float[9];
        int[] v = new int[3];
        for (int f = 0; f < s.faces; f++)
        {
            int n = polygon(s, f, cam, poly, view, v);
            if (n == 0 || passed(poly, n, limit)) { continue; }
            for (int t = 1; t + 1 < n; t++)
            {
                triangle((poly[0] - ox) * scale, (poly[1] - oy) * scale, poly[2], (poly[t * 3] - ox) * scale, (poly[t * 3 + 1] - oy) * scale, poly[t * 3 + 2],
                    (poly[t * 3 + 3] - ox) * scale, (poly[t * 3 + 4] - oy) * scale, poly[t * 3 + 5], cells, mode, margin, fallback);
            }
        }
    }

    /**
     * The face as drawn, into poly (canvas x, y, 1 / depth per corner): clipped at NEAR, with up to four corners; 0 when
     * nothing of it is.
     */
    private static int polygon(Shape s, int f, ModelShapes.Camera cam, float[] poly, float[] view, int[] v)
    {
        if (s.hidden[f]) { return 0; }
        int i = s.a[f], j = s.b[f], k = s.c[f];
        if (Float.isNaN(s.px[i] + s.px[j] + s.px[k]) || !(s.pd[i] > 0 && s.pd[j] > 0 && s.pd[k] > 0)) { return 0; }
        v[0] = i; v[1] = j; v[2] = k;
        if (s.pd[i] >= NEAR && s.pd[j] >= NEAR && s.pd[k] >= NEAR)
        {
            for (int q = 0; q < 3; q++) { poly[q * 3] = s.px[v[q]]; poly[q * 3 + 1] = s.py[v[q]]; poly[q * 3 + 2] = 1 / s.pd[v[q]]; }
            return 3;
        }
        // In view space (x right, y down, depth), where edges stay straight, the part at depth >= NEAR.
        for (int q = 0; q < 3; q++)
        {
            float d = s.pd[v[q]];
            view[q * 3] = (s.px[v[q]] - cam.centerX) * d / cam.scale; view[q * 3 + 1] = (s.py[v[q]] - cam.centerY) * d / cam.scale; view[q * 3 + 2] = d;
        }
        int n = 0;
        for (int q = 0; q < 3; q++)
        {
            int r = (q + 1) % 3;
            float da = view[q * 3 + 2], db = view[r * 3 + 2];
            if (da >= NEAR) { n = put(poly, n, cam, view[q * 3], view[q * 3 + 1], da); }
            if ((da >= NEAR) != (db >= NEAR))
            {
                float t = (NEAR - da) / (db - da);
                n = put(poly, n, cam, view[q * 3] + (view[r * 3] - view[q * 3]) * t, view[q * 3 + 1] + (view[r * 3 + 1] - view[q * 3 + 1]) * t, NEAR);
            }
        }
        return n < 3 ? 0 : n;
    }

    /** Adds a view-space point to the polygon as its canvas point and 1 / depth; returns the new count. */
    private static int put(float[] poly, int n, ModelShapes.Camera cam, float vx, float vy, float d)
    {
        poly[n * 3] = cam.centerX + vx * cam.scale / d; poly[n * 3 + 1] = cam.centerY + vy * cam.scale / d; poly[n * 3 + 2] = 1 / d;
        return n + 1;
    }

    /** Does as cover says in the actor cells a triangle (raster coordinates, 1 / depth per corner) covers. */
    private void triangle(float x0, float y0, float w0, float x1, float y1, float w1, float x2, float y2, float w2, Cells cells, int mode,
        float margin, float fallback)
    {
        float[] near = this.near;
        float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-6f) { return; }
        int w = cells.w, words = cells.words;
        long[] bits = cells.bits;
        int cx0 = Math.max(0, (int) Math.ceil(Math.min(x0, Math.min(x1, x2)) - 0.5f)), cx1 = Math.min(w - 1, (int) Math.floor(Math.max(x0, Math.max(x1, x2)) - 0.5f));
        int cy0 = Math.max(0, (int) Math.ceil(Math.min(y0, Math.min(y1, y2)) - 0.5f)), cy1 = Math.min(cells.h - 1, (int) Math.floor(Math.max(y0, Math.max(y1, y2)) - 0.5f));
        if (cx0 > cx1 || cy0 > cy1) { return; }
        // 1 / depth is linear across the canvas for a flat face.
        float dx = ((w1 - w0) * (y2 - y0) - (w2 - w0) * (y1 - y0)) / area, dy = ((x1 - x0) * (w2 - w0) - (x2 - x0) * (w1 - w0)) / area;
        float sign = Math.signum(area);
        // Per row, the columns that can pass the edge tests: where each edge function, worked out exactly, is at least
        // -slack, which is far more than the tests' rounding (a cell they pass is never left out), so the rest of the
        // bounding box, half of it or more for a slanted face, is not visited.
        double edges = Math.abs(x1 - x0) + Math.abs(y1 - y0) + Math.abs(x2 - x1) + Math.abs(y2 - y1) + Math.abs(x0 - x2) + Math.abs(y0 - y2);
        double reach = Math.max(Math.max(w, cells.h), Math.max(Math.max(Math.abs(x0), Math.abs(x1)), Math.max(Math.max(Math.abs(x2), Math.abs(y0)), Math.max(Math.abs(y1), Math.abs(y2)))));
        double slack = 1e-5 * (1 + edges) * (1 + reach);
        for (int cy = cy0; cy <= cy1; cy++)
        {
            int from = Math.max(cx0, cells.first[cy]), to = Math.min(cx1, cells.last[cy]), row = cy * words;
            float py = cy + 0.5f;
            double lo = from, hi = to;
            for (int e = 0; e < 3 && lo <= hi; e++)
            {
                float xa = e == 0 ? x0 : e == 1 ? x1 : x2, ya = e == 0 ? y0 : e == 1 ? y1 : y2;
                float xb = e == 0 ? x1 : e == 1 ? x2 : x0, yb = e == 0 ? y1 : e == 1 ? y2 : y0;
                // sign * edge function at column cx (centre cx + 0.5): k * (cx + 0.5) + c.
                double k = -sign * ((double) yb - ya), c = sign * (((double) xb - xa) * ((double) py - ya) + ((double) yb - ya) * xa);
                if (k > 0) { lo = Math.max(lo, Math.ceil((-slack - c) / k - 0.5)); }
                else if (k < 0) { hi = Math.min(hi, Math.floor((-slack - c) / k - 0.5)); }
                else if (c < -slack) { hi = lo - 1; }
            }
            if (lo > hi) { continue; }
            from = (int) lo; to = (int) hi;
            for (int cx = from; cx <= to; cx++)
            {
                int word = row + (cx >>> 6);
                long set = bits[word];
                // A word without actor cells (left) is skipped whole.
                if (set == 0) { cx |= 63; continue; }
                long bit = 1L << (cx & 63);
                if ((set & bit) == 0) { continue; }
                float px = cx + 0.5f;
                if (sign * ((x1 - x0) * (py - y0) - (y1 - y0) * (px - x0)) < 0 || sign * ((x2 - x1) * (py - y1) - (y2 - y1) * (px - x1)) < 0
                    || sign * ((x0 - x2) * (py - y2) - (y0 - y2) * (px - x2)) < 0) { continue; }
                int cell = cy * w + cx;
                float inverse = w0 + (px - x0) * dx + (py - y0) * dy;
                if (mode == NEAREST) { if (inverse > near[cell]) { near[cell] = inverse; } continue; }
                float player = near[cell] > 0 ? near[cell] : fallback;
                // At least the margin nearer: 1 / depth beyond 1 / (actor depth - margin).
                if (player > 0 && inverse * (1 - margin * player) > player) { bits[word] &= ~bit; }
            }
        }
    }
}
