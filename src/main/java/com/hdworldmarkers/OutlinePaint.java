package com.hdworldmarkers;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

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
    static final class Mesh
    {
        final float[] xy;
        final int[] tris;

        Mesh(float[] xy, int[] tris) { this.xy = xy; this.tris = tris; }
    }

    /** Holes in the silhouette smaller than this (square canvas pixels) count as part of the model. */
    private static final double SPECK = 4;

    /** How far a curve (a round corner of the border) may lie from its straight pieces, in canvas pixels. */
    private static final double FLATNESS = 0.25;

    /** The painted region of loops (the silhouette, its holes wound the other way) and a border this wide; null for none. */
    static Mesh paint(List<float[]> loops, float width)
    {
        if (loops.isEmpty() || !(width > 0)) { return null; }
        double outerSign = 0, largest = 0;
        for (float[] l : loops)
        {
            double a = FloatClickbox.area(l);
            if (Math.abs(a) > largest) { largest = Math.abs(a); outerSign = Math.signum(a); }
        }
        Path2D.Float silhouette = new Path2D.Float(Path2D.WIND_EVEN_ODD);
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
        }
        // Within the width of the silhouette's edge, on the outside: its edge stroked both ways, less the silhouette.
        Shape stroke = new BasicStroke(2 * width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(silhouette);
        // In straight pieces first: on the round corners' curves Area took many times as long.
        PathIterator curves = stroke.getPathIterator(null, FLATNESS);
        Path2D.Double flat = new Path2D.Double(curves.getWindingRule());
        flat.append(curves, false);
        Area painted = new Area(flat);
        painted.subtract(new Area(silhouette));
        return fill(painted);
    }

    /** A region (an Area of straight pieces) as triangles; null for an empty one. */
    static Mesh fill(Area region)
    {
        List<double[]> rings = rings(region);
        if (rings.isEmpty()) { return null; }
        // A ring inside an odd number of others is a hole, and goes with the smallest ring around it; the others are
        // outer rings.
        int count = rings.size();
        double[] areas = new double[count];
        for (int r = 0; r < count; r++) { areas[r] = Math.abs(area(rings.get(r))); }
        int[] parent = new int[count], depth = new int[count];
        for (int r = 0; r < count; r++)
        {
            parent[r] = -1;
            for (int o = 0; o < count; o++)
            {
                if (o == r || areas[o] <= areas[r] || !inside(rings.get(o), rings.get(r))) { continue; }
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
            if (made + t.length > tris.length) { tris = java.util.Arrays.copyOf(tris, (made + t.length) * 2); }
            for (int i : t) { tris[made++] = vertex + i; }
            vertex += data.length / 2;
        }
        return made == 0 ? null : new Mesh(xy, java.util.Arrays.copyOf(tris, made));
    }

    /** The closed rings of a shape, flattened, without repeated points: {x0, y0, ...} each. */
    private static List<double[]> rings(Shape shape)
    {
        List<double[]> rings = new ArrayList<>();
        double[] c = new double[6];
        double[] ring = new double[64];
        int n = 0;
        for (PathIterator it = shape.getPathIterator(null, FLATNESS); !it.isDone(); it.next())
        {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO)
            {
                if (n >= 6) { rings.add(java.util.Arrays.copyOf(ring, n)); }
                n = 0;
            }
            if (type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO)
            {
                if (n >= 2 && near(ring[n - 2], ring[n - 1], c[0], c[1])) { continue; }
                if (n + 2 > ring.length) { ring = java.util.Arrays.copyOf(ring, ring.length * 2); }
                ring[n++] = c[0];
                ring[n++] = c[1];
            }
            else if (type == PathIterator.SEG_CLOSE)
            {
                while (n >= 4 && near(ring[0], ring[1], ring[n - 2], ring[n - 1])) { n -= 2; }
                if (n >= 6) { rings.add(java.util.Arrays.copyOf(ring, n)); }
                n = 0;
            }
        }
        if (n >= 6) { rings.add(java.util.Arrays.copyOf(ring, n)); }
        return rings;
    }

    /**
     * Points this close count as one. Area leaves points a hair apart where pieces meet, and ear clipping then filled
     * across a notch outside the region.
     */
    private static final double SAME = 1e-3;

    private static boolean near(double ax, double ay, double bx, double by) { return Math.abs(ax - bx) < SAME && Math.abs(ay - by) < SAME; }

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
