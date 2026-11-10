package com.hdworldmarkers;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * What an outline paints: everything outside the silhouette within the border's width of it, as triangles. A border
 * drawn along each loop of the silhouette ran across a gap narrower than itself onto the model beyond, and around a hole
 * over the model around it; whether a gap or hole was there changed with every small move, and the outline jumped. This
 * region changes only as much as the silhouette does, as RuneLite's own outlines (which mark the pixels near the
 * model's): a gap narrower than the border is simply covered, as the model moves it opens and closes smoothly.
 */
final class OutlinePaint
{
    private OutlinePaint() { }

    /** Triangles over points {x0, y0, x1, y1, ...}: vertices of each triangle in tris. */
    @RequiredArgsConstructor
    static final class Mesh
    {
        final float[] xy;
        final int[] tris;
    }

    /** Holes in the silhouette smaller than this (square canvas pixels) count as part of the model. */
    private static final double SPECK = 4;

    /** How far a curve (a round corner of the border) may lie from its straight pieces, in canvas pixels. */
    private static final double FLATNESS = 0.25;

    /**
     * The painted region of loops (the silhouette, its holes wound the other way) and a border this wide; null for none.
     * A border thinner than fill tells points apart (SAME) is none: its rings were too close to nest, and the model was
     * painted.
     */
    static Mesh paint(List<float[]> loops, float width)
    {
        return loops.isEmpty() || !(width >= SAME) ? null : fill(region(loops, width));
    }

    /** The painted region (see paint) as an Area of straight pieces. */
    static Area region(List<float[]> loops, float width)
    {
        double outerSign = 0, largest = 0;
        for (float[] l : loops)
        {
            double a = FloatClickbox.area(l);
            if (Math.abs(a) > largest) { largest = Math.abs(a); outerSign = Math.signum(a); }
        }
        Path2D.Float silhouette = new Path2D.Float(Path2D.WIND_EVEN_ODD);
        Path2D.Double grown = new Path2D.Double();
        double sign = outerSign == 0 ? 1 : outerSign, sum = 0;
        for (float[] l : loops)
        {
            if (l.length < 6) { continue; }
            // A hole of a pixel or two is a crack between the model's parts: painted, it was a dot that blinked as the
            // crack opened and closed.
            double a = FloatClickbox.area(l);
            if (Math.signum(a) != outerSign && Math.abs(a) < SPECK) { continue; }
            silhouette.moveTo(l[0], l[1]);
            for (int k = 2; k < l.length; k += 2) { silhouette.lineTo(l[k], l[k + 1]); }
            silhouette.closePath();
            sum += sign * grow(l, sign, width, grown);
        }
        Area model = new Area(silhouette);
        double area = 0;
        for (double[] r : rings(model, FLATNESS, Double.MIN_VALUE)) { area += area(r); }
        // Within the width of the silhouette, less the silhouette. The loops moved out (see grow) cover that when they
        // neither cross nor nest the wrong way round, as then the silhouette's area is the sum of theirs (a simplified
        // outline crossed itself in about one model in 600). Else within the width of its edge either way: the edge
        // stroked both ways, which took Area three times as long.
        Shape near = Math.abs(Math.abs(area) - sum) < 1e-3 ? grown
            : new BasicStroke(2 * width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(silhouette);
        // In straight pieces first: on the round corners' curves Area took many times as long.
        PathIterator curves = near.getPathIterator(null, FLATNESS);
        Path2D.Double flat = new Path2D.Double(curves.getWindingRule());
        flat.append(curves, false);
        Area painted = new Area(flat);
        painted.subtract(model);
        return painted;
    }

    /**
     * Adds a loop moved out by w to path, returns its area. Outward is to the right of its edges for sign 1 in y-up terms,
     * as for every loop of a silhouette whose holes are wound the other way. Inside the loops added (non-zero) lies then
     * all within w of the silhouette: each edge's strip on the outside, and where the loop turns away from the outside
     * the round corner between two strips. Where it turns towards the outside the strips overlap: they go through the
     * loop's point, or are cut where they cross when the corner is no sharper than a right angle and the cut within half
     * of either edge (what is cut off then lies in both strips, and in no other cut).
     */
    private static double grow(float[] l, double sign, double w, Path2D.Double path)
    {
        // Without points repeated: an edge of no length has no outside.
        int n = 0;
        double[] x = new double[l.length / 2], y = new double[x.length];
        for (int k = 0; k < l.length; k += 2)
        {
            if (n == 0 || l[k] != x[n - 1] || l[k + 1] != y[n - 1]) { x[n] = l[k]; y[n++] = l[k + 1]; }
        }
        while (n > 1 && x[n - 1] == x[0] && y[n - 1] == y[0]) { n--; }
        if (n < 2) { return 0; }
        double area = 0, ux = x[1] - x[0], uy = y[1] - y[0], ulen = Math.hypot(ux, uy);
        ux /= ulen; uy /= ulen;
        // From the middle of the first edge moved out (no cut reaches it) round to it.
        path.moveTo((x[0] + x[1]) / 2 + w * sign * uy, (y[0] + y[1]) / 2 - w * sign * ux);
        for (int k = 1; k <= n; k++)
        {
            int i = k % n, next = (k + 1) % n;
            double vx = x[next] - x[i], vy = y[next] - y[i], vlen = Math.hypot(vx, vy);
            vx /= vlen; vy /= vlen;
            area += x[i] * y[next] - x[next] * y[i];
            double cross = ux * vy - uy * vx, dot = ux * vx + uy * vy, turn = sign * cross;
            // The outward normals of the edge before and after.
            double ax = sign * uy, ay = -sign * ux, bx = sign * vy, by = -sign * vx;
            if (turn > 0 || turn == 0 && dot < 0)
            {
                path.lineTo(x[i] + w * ax, y[i] + w * ay);
                if (dot < 0)
                {
                    // Wider than a right angle: in two halves, as BasicStroke does.
                    double mx = sign * (by - ay), my = sign * (ax - bx), m = Math.hypot(mx, my);
                    arc(path, x[i], y[i], w, sign, ax, ay, mx / m, my / m);
                    arc(path, x[i], y[i], w, sign, mx / m, my / m, bx, by);
                }
                else { arc(path, x[i], y[i], w, sign, ax, ay, bx, by); }
            }
            else if (dot >= 0 && 2 * w * Math.abs(cross) <= Math.min(ulen, vlen))
            {
                path.lineTo(x[i] + w * (ax + bx) / (1 + dot), y[i] + w * (ay + by) / (1 + dot));
            }
            else
            {
                path.lineTo(x[i] + w * ax, y[i] + w * ay);
                path.lineTo(x[i], y[i]);
                path.lineTo(x[i] + w * bx, y[i] + w * by);
            }
            ux = vx; uy = vy; ulen = vlen;
        }
        path.closePath();
        return area / 2;
    }

    /**
     * The curve BasicStroke draws for a round corner (at most a right angle) around (cx, cy) from normal a to normal b,
     * sign's way round: flattened, the same pieces as the stroke's.
     */
    private static void arc(Path2D.Double path, double cx, double cy, double w, double sign, double ax, double ay, double bx, double by)
    {
        double half = (ax * bx + ay * by) / 2;
        if (half >= 0.5) { path.lineTo(cx + w * bx, cy + w * by); return; }
        double k = sign * w * 4 / 3 * Math.sqrt(0.5 - half) / (1 + Math.sqrt(half + 0.5));
        path.curveTo(cx + w * ax - k * ay, cy + w * ay + k * ax, cx + w * bx + k * by, cy + w * by - k * bx, cx + w * bx, cy + w * by);
    }

    /** A region (an Area of straight pieces) as triangles; null for an empty one. */
    static Mesh fill(Area region)
    {
        List<double[]> rings = rings(region, FLATNESS, SAME);
        if (rings.isEmpty()) { return null; }
        // A ring inside an odd number of others is a hole, and goes with the smallest ring around it; the others are
        // outer rings.
        int count = rings.size();
        double[] areas = new double[count], box = new double[count * 4];
        for (int r = 0; r < count; r++)
        {
            double[] g = rings.get(r);
            areas[r] = Math.abs(area(g));
            box[r * 4] = box[r * 4 + 1] = Double.MAX_VALUE;
            box[r * 4 + 2] = box[r * 4 + 3] = -Double.MAX_VALUE;
            for (int k = 0; k < g.length; k += 2)
            {
                box[r * 4] = Math.min(box[r * 4], g[k]); box[r * 4 + 1] = Math.min(box[r * 4 + 1], g[k + 1]);
                box[r * 4 + 2] = Math.max(box[r * 4 + 2], g[k]); box[r * 4 + 3] = Math.max(box[r * 4 + 3], g[k + 1]);
            }
        }
        int[] parent = new int[count], depth = new int[count];
        for (int r = 0; r < count; r++)
        {
            parent[r] = -1;
            for (int o = 0; o < count; o++)
            {
                // Not inside a ring whose box (and side's margin) it leaves: the test walked all of that ring's edges.
                if (o == r || areas[o] <= areas[r] || box[o * 4] > box[r * 4] + 1e-4 || box[o * 4 + 1] > box[r * 4 + 1] + 1e-4
                    || box[o * 4 + 2] < box[r * 4 + 2] - 1e-4 || box[o * 4 + 3] < box[r * 4 + 3] - 1e-4 || !inside(rings.get(o), rings.get(r)))
                {
                    continue;
                }
                depth[r]++;
                if (parent[r] < 0 || areas[o] < areas[parent[r]]) { parent[r] = o; }
            }
        }
        List<double[]> outers = new ArrayList<>();
        List<List<double[]>> holes = new ArrayList<>();
        int[] slot = new int[count];
        for (int r = 0; r < count; r++)
        {
            if (depth[r] % 2 == 0) { slot[r] = outers.size(); outers.add(rings.get(r)); holes.add(new ArrayList<>()); }
        }
        for (int r = 0; r < count; r++)
        {
            if (depth[r] % 2 == 1 && parent[r] >= 0 && depth[parent[r]] % 2 == 0) { holes.get(slot[parent[r]]).add(rings.get(r)); }
        }
        // The points of the rings drawn only: a hole without an outer ring around it is left out.
        int points = 0;
        for (int o = 0; o < outers.size(); o++)
        {
            points += outers.get(o).length / 2;
            for (double[] h : holes.get(o)) { points += h.length / 2; }
        }
        float[] xy = new float[points * 2];
        int[] tris = new int[(points + 2 * rings.size()) * 3];
        int vertex = 0, made = 0;
        for (int o = 0; o < outers.size(); o++)
        {
            List<double[]> parts = new ArrayList<>();
            parts.add(outers.get(o));
            parts.addAll(holes.get(o));
            int length = 0;
            int[] holeStarts = new int[parts.size() - 1];
            for (int p = 0; p < parts.size(); p++)
            {
                if (p > 0) { holeStarts[p - 1] = length / 2; }
                length += parts.get(p).length;
            }
            double[] data = new double[length];
            int at = 0;
            for (double[] p : parts) { System.arraycopy(p, 0, data, at, p.length); at += p.length; }
            int[] t = Earcut.earcut(data, holeStarts);
            for (int i = 0; i < data.length; i++) { xy[vertex * 2 + i] = (float) data[i]; }
            if (made + t.length > tris.length) { tris = Arrays.copyOf(tris, (made + t.length) * 2); }
            for (int i : t) { tris[made++] = vertex + i; }
            vertex += data.length / 2;
        }
        return made == 0 ? null : new Mesh(xy, Arrays.copyOf(tris, made));
    }

    /**
     * The closed rings of a shape, flattened this finely, without repeated points (those nearer than same: a point
     * is only the same as itself at Double.MIN_VALUE), nor the closing point: {x0, y0, ...} each.
     */
    static List<double[]> rings(Shape shape, double flatness, double same)
    {
        List<double[]> rings = new ArrayList<>();
        double[] c = new double[6];
        double[] ring = new double[64];
        int n = 0;
        for (PathIterator it = shape.getPathIterator(null, flatness); !it.isDone(); it.next())
        {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO)
            {
                if (n >= 6) { rings.add(Arrays.copyOf(ring, n)); }
                n = 0;
            }
            if (type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO)
            {
                if (n >= 2 && near(ring[n - 2], ring[n - 1], c[0], c[1], same)) { continue; }
                if (n + 2 > ring.length) { ring = Arrays.copyOf(ring, ring.length * 2); }
                ring[n++] = c[0];
                ring[n++] = c[1];
            }
            else if (type == PathIterator.SEG_CLOSE)
            {
                while (n >= 4 && near(ring[0], ring[1], ring[n - 2], ring[n - 1], same)) { n -= 2; }
                if (n >= 6) { rings.add(Arrays.copyOf(ring, n)); }
                n = 0;
            }
        }
        if (n >= 6) { rings.add(Arrays.copyOf(ring, n)); }
        return rings;
    }

    /**
     * Points this close count as one. Area leaves points a hair apart where pieces meet, and ear clipping then filled
     * across a notch outside the region.
     */
    private static final double SAME = 1e-3;

    private static boolean near(double ax, double ay, double bx, double by, double same) { return Math.abs(ax - bx) < same && Math.abs(ay - by) < same; }

    private static double area(double[] r)
    {
        double sum = 0;
        for (int i = 0, n = r.length / 2; i < n; i++)
        {
            int j = (i + 1) % n;
            sum += r[i * 2] * r[j * 2 + 1] - r[j * 2] * r[i * 2 + 1];
        }
        return sum / 2;
    }

    /** Whether a ring lies inside another, by its points (rings of one area never cross). */
    private static boolean inside(double[] outer, double[] ring)
    {
        // A point of the ring not on the outer ring's edge decides; they may share points.
        for (int k = 0; k < ring.length; k += 2)
        {
            int side = side(outer, ring[k], ring[k + 1]);
            if (side != 0) { return side > 0; }
        }
        return false;
    }

    /** 1 inside, -1 outside, 0 on the edge (within a tiny distance). */
    private static int side(double[] r, double px, double py)
    {
        boolean in = false;
        for (int i = 0, n = r.length / 2, j = n - 1; i < n; j = i++)
        {
            double xi = r[i * 2], yi = r[i * 2 + 1], xj = r[j * 2], yj = r[j * 2 + 1];
            double dx = xj - xi, dy = yj - yi, len2 = dx * dx + dy * dy;
            double t = len2 > 0 ? Math.max(0, Math.min(1, ((px - xi) * dx + (py - yi) * dy) / len2)) : 0;
            double ex = xi + t * dx - px, ey = yi + t * dy - py;
            if (ex * ex + ey * ey < 1e-8) { return 0; }
            if ((yi > py) != (yj > py) && px < xi + (py - yi) / (yj - yi) * dx) { in = !in; }
        }
        return in ? 1 : -1;
    }
}
