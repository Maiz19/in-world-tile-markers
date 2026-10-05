/*
 * Copyright (c) 2017, Adam <Adam@sigterm.info>
 * Copyright (c) 2019 Abex
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
 * RuneLite's clickbox (Perspective.getClickbox, calculateAABB and calculate2DBounds, and SimplePolygon.intersectWithConvex
 * of https://github.com/runelite/runelite, tag runelite-parent-1.12.39), copyright (c) 2019 Abex and the RuneLite
 * contributors, BSD 2-Clause License; see META-INF/LICENSE-runelite and THIRD_PARTY_NOTICES.md. Changes for HD World
 * Markers: computed from HD World Markers' float projection at a quarter of a pixel, the clip in floats.
 */
package com.hdworldmarkers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RuneLite's clickbox of a detailed model, the same shape, at a quarter of a pixel. RuneLite takes one rectangle around
 * each visible face (5 pixels wider on every side), unites them and clips the union to the convex hull of the model's
 * bounding box; it rounds every vertex to a whole pixel first, so each rectangle moved by a pixel on its own as the
 * camera moved and the drawn edge wobbled. Here the rectangles are rounded to quarters of a pixel, so the edge moves in
 * steps too small to see, and united with RuneLite's scan line (RectangleUnion) whose segment lookup, a walk from the
 * first segment that made it quadratic and most of the cost, is a bitset search instead.
 */
final class FloatClickbox
{
    /** Calculate2DBounds' margin around each face. */
    static final float RADIUS = 5;
    /** How far beyond the viewport the union reaches (pixels); farther, a border would still be off screen. */
    static final float OFF_SCREEN = 64;
    /** Rectangle corners are rounded to 1 / SUBPIXELS of a pixel. */
    static final int SUBPIXELS = 4;

    private FloatClickbox() { }

    /**
     * The clickbox polygons {x0, y0, x1, y1, ...} from projected vertices (NaN: not projected) and faces, hidden faces
     * left out, clipped to the convex polygon bounds {x0, y0, ...} when given; faces entirely outside the viewport are
     * skipped. Null when no face is visible.
     */
    static List<float[]> of(float[] x, float[] y, int[] a, int[] b, int[] c, int faces, boolean[] hidden, float[] bounds,
        float vpX1, float vpY1, float vpX2, float vpY2)
    {
        int[] rects = new int[faces * 4];
        int count = 0;
        for (int f = 0; f < faces; f++)
        {
            if (hidden != null && hidden[f]) { continue; }
            int i = a[f], j = b[f], k = c[f];
            if (Float.isNaN(x[i]) || Float.isNaN(x[j]) || Float.isNaN(x[k])) { continue; }
            float minX = Math.min(x[i], Math.min(x[j], x[k])) - RADIUS, maxX = Math.max(x[i], Math.max(x[j], x[k])) + RADIUS;
            float minY = Math.min(y[i], Math.min(y[j], y[k])) - RADIUS, maxY = Math.max(y[i], Math.max(y[j], y[k])) + RADIUS;
            if (vpX1 > maxX || vpX2 < minX || vpY1 > maxY || vpY2 < minY) { continue; }
            // Parts far off screen are cut at a margin beyond it: a face near the camera can reach tens of thousands of
            // pixels, and the union's scan line takes memory for every quarter pixel of the height. Never seen.
            minX = Math.max(minX, vpX1 - OFF_SCREEN); maxX = Math.min(maxX, vpX2 + OFF_SCREEN);
            minY = Math.max(minY, vpY1 - OFF_SCREEN); maxY = Math.min(maxY, vpY2 + OFF_SCREEN);
            rects[count * 4] = Math.round(minX * SUBPIXELS);
            rects[count * 4 + 1] = Math.round(minY * SUBPIXELS);
            rects[count * 4 + 2] = Math.round(maxX * SUBPIXELS);
            rects[count * 4 + 3] = Math.round(maxY * SUBPIXELS);
            count++;
        }
        List<int[]> union = union(rects, inside(rects, count));
        if (union == null) { return null; }
        List<float[]> out = new ArrayList<>(union.size());
        for (int[] polygon : union)
        {
            float[] p = new float[polygon.length];
            for (int i = 0; i < polygon.length; i++) { p[i] = polygon[i] / (float) SUBPIXELS; }
            float[] clipped = bounds == null ? p : clip(p, bounds);
            if (clipped != null) { clipped = tidy(clipped); }
            if (clipped != null && clipped.length >= 6 && Math.abs(area(clipped)) >= MIN_AREA) { out.add(clipped); }
        }
        return out.isEmpty() ? null : out;
    }

    /** Pieces and holes smaller than this (square pixels) are left out: specks no one could click, drawn as a border. */
    static final float MIN_AREA = 12;
    /** A step (an edge between two turns the opposite way) shorter than this is taken out... */
    private static final float STEP = 1.5f;
    /** ...when no point it removes lies farther than this from the line that replaces it. */
    private static final float STEP_ERROR = 0.75f;

    /**
     * The polygon without steps too small to see: a quarter-pixel step between two long edges (rectangles of faces a
     * fraction of a pixel apart), where the border, centred on the edges and wider than the step, lay twice over itself
     * and showed as a brighter, thicker piece of edge. Each such step b-c (a short edge whose ends turn opposite ways, a
     * Z; a curve turns one way) is replaced by the line from a to d, only when b and c lie within STEP_ERROR of it, and
     * a and d stay: no removal builds on another, so the shape moves by at most STEP_ERROR. Spurs (a turn straight back),
     * points on a straight line and repeated points go too. Decided on the input from its longest edge on, so the result does not depend on where
     * the polygon starts. Linear in the number of points.
     */
    static float[] tidy(float[] p)
    {
        int n = p.length / 2;
        if (n <= 4) { return p; }
        int start = 0;
        double longest = -1;
        for (int i = 0; i < n; i++)
        {
            int j = (i + 1) % n;
            double dx = p[j * 2] - p[i * 2], dy = p[j * 2 + 1] - p[i * 2 + 1], l = dx * dx + dy * dy;
            if (l > longest) { longest = l; start = j; }
        }
        boolean[] drop = new boolean[n], keep = new boolean[n];
        int dropped = 0;
        for (int k = 0; k < n; k++)
        {
            int b = (start + k) % n, c = (b + 1) % n, a = (b + n - 1) % n, d = (c + 1) % n;
            if (drop[b] || keep[b] || drop[c] || keep[c] || drop[a] || drop[d] || a == d || n - dropped <= 4) { continue; }
            float ex = p[c * 2] - p[b * 2], ey = p[c * 2 + 1] - p[b * 2 + 1];
            if (ex * ex + ey * ey >= STEP * STEP) { continue; }
            double turnB = cross(p, a, b, c), turnC = cross(p, b, c, d);
            if (!(turnB * turnC < 0)) { continue; }
            if (Silhouette.distance(p, b, a, d) > STEP_ERROR || Silhouette.distance(p, c, a, d) > STEP_ERROR) { continue; }
            drop[b] = drop[c] = true;
            keep[a] = keep[d] = true;
            dropped += 2;
        }
        // Repeated points and spurs, against the points that remain.
        float[] q = new float[(n - dropped) * 2];
        for (int k = 0, m = 0; k < n; k++) { int i = (start + k) % n; if (!drop[i]) { q[m++] = p[i * 2]; q[m++] = p[i * 2 + 1]; } }
        float[] s = despur(q);
        return s == null ? p : s;
    }

    /**
     * The polygon without repeated points, points on a straight line and spurs (a turn straight back): the border mitre
     * at such a point shoots out as a spike. Null when fewer than three points remain.
     */
    static float[] despur(float[] q)
    {
        float[] s = new float[q.length];
        int m = 0;
        for (int i = 0; i < q.length / 2; i++)
        {
            s[m * 2] = q[i * 2]; s[m * 2 + 1] = q[i * 2 + 1]; m++;
            while (m >= 3 && spur(s, m - 3, m - 2, m - 1)) { s[(m - 2) * 2] = s[(m - 1) * 2]; s[(m - 2) * 2 + 1] = s[(m - 1) * 2 + 1]; m--; }
        }
        while (m > 3 && spur(s, m - 2, m - 1, 0)) { m--; }
        while (m > 3 && spur(s, m - 1, 0, 1)) { System.arraycopy(s, 2, s, 0, (m - 1) * 2); m--; }
        return m < 3 ? null : Arrays.copyOf(s, m * 2);
    }

    private static double cross(float[] p, int a, int b, int c)
    {
        return (double) (p[b * 2] - p[a * 2]) * (p[c * 2 + 1] - p[b * 2 + 1]) - (double) (p[b * 2 + 1] - p[a * 2 + 1]) * (p[c * 2] - p[b * 2]);
    }

    /** A repeated point, a point on a straight line, or a turn straight back. */
    private static boolean spur(float[] s, int a, int b, int c)
    {
        float ax = s[b * 2] - s[a * 2], ay = s[b * 2 + 1] - s[a * 2 + 1];
        float bx = s[c * 2] - s[b * 2], by = s[c * 2 + 1] - s[b * 2 + 1];
        float la = (float) Math.sqrt(ax * ax + ay * ay), lb = (float) Math.sqrt(bx * bx + by * by);
        return la < 1e-3f || lb < 1e-3f || ax * bx + ay * by < -0.94f * la * lb || Math.abs(ax * by - ay * bx) < 1e-4f * la * lb;
    }

    static double area(float[] p)
    {
        double sum = 0;
        for (int i = 0, n = p.length / 2; i < n; i++) { int j = (i + 1) % n; sum += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1]; }
        return sum / 2;
    }

    /**
     * Leaves out the rectangles {x1, y1, x2, y2, ...} that lie wholly inside the others: they do not change the union,
     * and each walked every scan line segment it spans (most of a model's faces lie inside it, most of the clickbox's
     * time). A grid counts per cell the rectangles that cover all of it; a rectangle goes when every cell it touches is
     * covered all by another one still in. Returns how many are left, moved to the front in their order.
     */
    static int inside(int[] r, int count)
    {
        if (count < 16) { return count; }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++)
        {
            minX = Math.min(minX, r[i * 4]); minY = Math.min(minY, r[i * 4 + 1]);
            maxX = Math.max(maxX, r[i * 4 + 2]); maxY = Math.max(maxY, r[i * 4 + 3]);
        }
        // Cells of at least four pixels, larger for a large union: at most about 64k of them.
        int size = Math.max(4 * SUBPIXELS, (int) Math.ceil(Math.sqrt((double) (maxX - minX) * (maxY - minY) / 65536)));
        int cols = (maxX - minX) / size + 1, rows = (maxY - minY) / size + 1;
        int[] covered = new int[cols * rows];
        for (int i = 0; i < count; i++)
        {
            int c0 = ceil(r[i * 4] - minX, size), c1 = (r[i * 4 + 2] - minX) / size - 1;
            int r0 = ceil(r[i * 4 + 1] - minY, size), r1 = (r[i * 4 + 3] - minY) / size - 1;
            for (int row = r0; row <= r1; row++) { for (int col = c0; col <= c1; col++) { covered[row * cols + col]++; } }
        }
        int kept = 0;
        for (int i = 0; i < count; i++)
        {
            int x1 = r[i * 4] - minX, y1 = r[i * 4 + 1] - minY, x2 = r[i * 4 + 2] - minX, y2 = r[i * 4 + 3] - minY;
            int c0 = ceil(x1, size), c1 = x2 / size - 1, r0 = ceil(y1, size), r1 = y2 / size - 1;
            // Every cell it touches, each covered all by another rectangle (its own count left out where it covers it).
            boolean within = x2 > x1 && y2 > y1;
            for (int row = y1 / size, lastRow = ceil(y2, size) - 1; within && row <= lastRow; row++)
            {
                for (int col = x1 / size, lastCol = ceil(x2, size) - 1; col <= lastCol; col++)
                {
                    boolean own = row >= r0 && row <= r1 && col >= c0 && col <= c1;
                    if (covered[row * cols + col] - (own ? 1 : 0) < 1) { within = false; break; }
                }
            }
            if (within)
            {
                for (int row = r0; row <= r1; row++) { for (int col = c0; col <= c1; col++) { covered[row * cols + col]--; } }
                continue;
            }
            if (kept != i) { System.arraycopy(r, i * 4, r, kept * 4, 4); }
            kept++;
        }
        return kept;
    }

    private static int ceil(int a, int b) { return (a + b - 1) / b; }

    /**
     * RectangleUnion.union: the union of rectangles {x1, y1, x2, y2, ...} (count of them) as polygons {x0, y0, ...}, by
     * a scan line left to right over their vertical edges; null without rectangles.
     */
    static List<int[]> union(int[] r, int count)
    {
        if (count == 0) { return null; }
        // Rectangles by left edge and by right edge: the edge in the high bits, the index in the low ones.
        long[] lefts = new long[count], rights = new long[count];
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++)
        {
            lefts[i] = (long) r[i * 4] << 32 | i;
            minY = Math.min(minY, r[i * 4 + 1]);
            maxY = Math.max(maxY, r[i * 4 + 3]);
        }
        Arrays.sort(lefts);
        // As RectangleUnion, right edges that tie keep the order of the left-edge sort: keyed by that position.
        for (int k = 0; k < count; k++) { int i = (int) lefts[k]; rights[k] = (long) r[i * 4 + 2] << 32 | k; }
        Arrays.sort(rights);
        for (int k = 0; k < count; k++) { rights[k] = (rights[k] & 0xFFFFFFFF00000000L) | (int) lefts[(int) rights[k]]; }
        Segments segments = new Segments(minY, maxY);
        List<int[]> out = new ArrayList<>();
        ChangingState cs = new ChangingState(out);
        for (int l = 0, rr = 0; l < count || rr < count; )
        {
            // The next edge, preferring + edges.
            int rect;
            boolean remove = l >= count || (rr < count && (int) (rights[rr] >> 32) < (int) (lefts[l] >> 32));
            if (remove) { rect = (int) rights[rr++]; cs.delta = -1; cs.x = r[rect * 4 + 2]; }
            else { rect = (int) lefts[l++]; cs.delta = 1; cs.x = r[rect * 4]; }
            int y1 = r[rect * 4 + 1], y2 = r[rect * 4 + 3];
            Segment n = segments.findLE(y1);
            if (n == null) { n = segments.insertAfter(null, y1); }
            if (n.y != y1)
            {
                n = segments.insertAfter(n, y1);
                n.value = n.previous.value;
            }
            for (; ; )
            {
                if (n.next == null || n.next.y > y2) { segments.insertAfter(n, y2); }
                cs.touch(n);
                n = n.next;
                if (n.y == y2)
                {
                    cs.finish(n);
                    break;
                }
            }
        }
        return out;
    }

    private static final class ChangingState
    {
        final List<int[]> out;
        int x, delta;
        Segment first;

        ChangingState(List<int[]> out) { this.out = out; }

        void touch(Segment s)
        {
            int oldValue = s.value;
            s.value += delta;
            if (oldValue <= 0 ^ s.value <= 0)
            {
                if (first == null) { first = s; }
            }
            else { finish(s); }
        }

        void finish(Segment s)
        {
            if (first == null) { return; }
            if (first.chunk != null && s.chunk != null)
            {
                push(first);
                push(s);
                if (first.chunk == s.chunk)
                {
                    Chunk c = first.chunk;
                    first.chunk = null;
                    s.chunk = null;
                    c.left = null;
                    c.right = null;
                    out.add(c.toArray());
                }
                else
                {
                    Chunk leftChunk, rightChunk;
                    if (!s.left) { leftChunk = s.chunk; rightChunk = first.chunk; }
                    else { leftChunk = first.chunk; rightChunk = s.chunk; }
                    if (first.left == s.left)
                    {
                        if (first.left) { leftChunk.reverse(); }
                        else { rightChunk.reverse(); }
                    }
                    rightChunk.appendTo(leftChunk);
                    first.chunk = null;
                    s.chunk = null;
                    leftChunk.right.chunk = null;
                    rightChunk.left.chunk = null;
                    leftChunk.right = rightChunk.right;
                    leftChunk.left.chunk = leftChunk;
                    leftChunk.right.chunk = leftChunk;
                }
            }
            else if (first.chunk == null && s.chunk == null)
            {
                first.chunk = new Chunk();
                first.chunk.right = first;
                first.left = false;
                s.chunk = first.chunk;
                first.chunk.left = s;
                s.left = true;
                push(first);
                push(s);
            }
            else if (first.chunk == null)
            {
                push(s);
                move(first, s);
                push(first);
            }
            else
            {
                push(first);
                move(s, first);
                push(s);
            }
            first = null;
        }

        private static void move(Segment dst, Segment src)
        {
            dst.chunk = src.chunk;
            dst.left = src.left;
            src.chunk = null;
            if (dst.left) { dst.chunk.left = dst; }
            else { dst.chunk.right = dst; }
        }

        private void push(Segment s)
        {
            if (s.left) { s.chunk.pushLeft(x, s.y); }
            else { s.chunk.pushRight(x, s.y); }
        }
    }

    private static final class Segment
    {
        Segment next, previous;
        Chunk chunk;
        boolean left;
        int y, value;
    }

    /**
     * The scan line's segments, sorted by y, with the y values present as a bitset over minY..maxY: the segment at or
     * just before a y is a word search, where RectangleUnion walked the list from its first segment.
     */
    private static final class Segments
    {
        private final int base;
        private final long[] present;
        private final Segment[] at;
        Segment first;

        Segments(int minY, int maxY)
        {
            base = minY;
            int size = maxY - minY + 1;
            present = new long[(size + 63) >>> 6];
            at = new Segment[size];
        }

        Segment findLE(int y)
        {
            int i = y - base, word = i >>> 6;
            long bits = present[word] & (-1L >>> (63 - (i & 63)));
            while (bits == 0)
            {
                if (--word < 0) { return null; }
                bits = present[word];
            }
            return at[(word << 6) + 63 - Long.numberOfLeadingZeros(bits)];
        }

        Segment insertAfter(Segment before, int y)
        {
            Segment n = new Segment();
            n.y = y;
            int i = y - base;
            present[i >>> 6] |= 1L << (i & 63);
            at[i] = n;
            if (before != null)
            {
                if (before.next != null)
                {
                    n.next = before.next;
                    n.next.previous = n;
                }
                n.value = before.value;
                before.next = n;
                n.previous = before;
            }
            else
            {
                if (first != null)
                {
                    n.next = first;
                    first.previous = n;
                }
                first = n;
            }
            return n;
        }
    }

    /** SimplePolygon's double-ended point list (lo..hi), with the scan line's two open ends (left, right). */
    private static final class Chunk
    {
        private static final int GROW = 16;
        int[] x = new int[32], y = new int[32];
        int lo = 16, hi = 15;
        Segment left, right;

        void pushLeft(int px, int py)
        {
            if (--lo < 0) { expandLeft(GROW); }
            x[lo] = px;
            y[lo] = py;
        }

        void pushRight(int px, int py)
        {
            if (++hi >= x.length) { expandRight(GROW); }
            x[hi] = px;
            y[hi] = py;
        }

        private void expandLeft(int grow)
        {
            int[] nx = new int[x.length + grow], ny = new int[y.length + grow];
            System.arraycopy(x, 0, nx, grow, x.length);
            System.arraycopy(y, 0, ny, grow, y.length);
            x = nx;
            y = ny;
            lo += grow;
            hi += grow;
        }

        private void expandRight(int grow)
        {
            x = Arrays.copyOf(x, x.length + grow);
            y = Arrays.copyOf(y, y.length + grow);
        }

        int size() { return hi - lo + 1; }

        void appendTo(Chunk other)
        {
            int size = size();
            if (size <= 0) { return; }
            other.expandRight(size);
            System.arraycopy(x, lo, other.x, other.hi + 1, size);
            System.arraycopy(y, lo, other.y, other.hi + 1, size);
            other.hi += size;
        }

        void reverse()
        {
            for (int i = 0, half = size() / 2; i < half; i++)
            {
                int li = lo + i, ri = hi - i;
                int tx = x[li], ty = y[li];
                x[li] = x[ri]; y[li] = y[ri];
                x[ri] = tx; y[ri] = ty;
            }
            Segment tr = left;
            left = right;
            right = tr;
            right.left = false;
            left.left = true;
        }

        int[] toArray()
        {
            int[] out = new int[size() * 2];
            for (int i = 0; i < size(); i++) { out[i * 2] = x[lo + i]; out[i * 2 + 1] = y[lo + i]; }
            return out;
        }
    }

    /**
     * SimplePolygon.intersectWithConvex in floats (Sutherland-Hodgman): p clipped to the convex polygon, whatever its
     * winding; null when nothing is left.
     */
    static float[] clip(float[] p, float[] convex)
    {
        int cn = convex.length / 2;
        float sign = area(convex) >= 0 ? 1 : -1;
        float[] cur = p;
        int n = p.length / 2;
        for (int ci = 0; ci < cn && n >= 3; ci++)
        {
            float cx1 = convex[ci * 2], cy1 = convex[ci * 2 + 1], cx2 = convex[(ci + 1) % cn * 2], cy2 = convex[(ci + 1) % cn * 2 + 1];
            float[] next = new float[n * 4];
            int m = 0;
            float px = cur[(n - 1) * 2], py = cur[(n - 1) * 2 + 1];
            float pd = sign * ((cx2 - cx1) * (py - cy1) - (cy2 - cy1) * (px - cx1));
            for (int i = 0; i < n; i++)
            {
                float qx = cur[i * 2], qy = cur[i * 2 + 1];
                float qd = sign * ((cx2 - cx1) * (qy - cy1) - (cy2 - cy1) * (qx - cx1));
                if (qd >= 0)
                {
                    if (pd < 0) { float t = pd / (pd - qd); next[m++] = px + (qx - px) * t; next[m++] = py + (qy - py) * t; }
                    next[m++] = qx; next[m++] = qy;
                }
                else if (pd >= 0) { float t = pd / (pd - qd); next[m++] = px + (qx - px) * t; next[m++] = py + (qy - py) * t; }
                px = qx; py = qy; pd = qd;
            }
            cur = java.util.Arrays.copyOf(next, m);
            n = m / 2;
        }
        return n >= 3 ? cur : null;
    }
}
