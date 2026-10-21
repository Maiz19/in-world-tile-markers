/*
 * ISC License
 *
 * Copyright (c) 2016, Mapbox
 *
 * Permission to use, copy, modify, and/or distribute this software for any purpose
 * with or without fee is hereby granted, provided that the above copyright notice
 * and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
 * REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
 * INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS
 * OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
 * TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF
 * THIS SOFTWARE.
 */
package com.hdworldmarkers;

import java.util.ArrayList;
import java.util.List;

/**
 * Triangles for a polygon with holes, by ear clipping: a Java port of mapbox's earcut 2.2.4 (ISC licence, Copyright (c)
 * 2016, Mapbox; META-INF/LICENSE-earcut), with its z-order index for larger polygons (an outline's region has up to a
 * few thousand points). See THIRD_PARTY_NOTICES.md.
 */
final class Earcut
{
    private Earcut() { }

    private static final class Node
    {
        final int i;
        final double x, y;
        Node prev, next;
        /** The z-order curve value, and the previous and next nodes in z-order. */
        int z;
        Node prevZ, nextZ;
        boolean steiner;

        Node(int i, double x, double y) { this.i = i; this.x = x; this.y = y; }
    }

    /** Triangles (point indices, three each) over points {x0, y0, ...}: the outer ring first, then the holes from holeStarts (point indices). */
    static int[] earcut(double[] data, int[] holeStarts)
    {
        int outerLength = holeStarts.length > 0 ? holeStarts[0] * 2 : data.length;
        Node outer = linkedList(data, 0, outerLength, true);
        List<Integer> triangles = new ArrayList<>();
        if (outer == null || outer.next == outer.prev) { return new int[0]; }
        if (holeStarts.length > 0) { outer = eliminateHoles(data, holeStarts, outer); }
        // Beyond a few points, ears are checked against the points near them only (a z-order curve over the outer ring's box).
        double minX = 0, minY = 0, invSize = 0;
        if (data.length > 80 * 2)
        {
            double maxX, maxY;
            minX = maxX = data[0];
            minY = maxY = data[1];
            for (int i = 2; i < outerLength; i += 2)
            {
                minX = Math.min(minX, data[i]); minY = Math.min(minY, data[i + 1]);
                maxX = Math.max(maxX, data[i]); maxY = Math.max(maxY, data[i + 1]);
            }
            invSize = Math.max(maxX - minX, maxY - minY);
            invSize = invSize != 0 ? 32767 / invSize : 0;
        }
        earcutLinked(outer, triangles, minX, minY, invSize, 0);
        int[] out = new int[triangles.size()];
        for (int k = 0; k < out.length; k++) { out[k] = triangles.get(k); }
        return out;
    }

    private static Node linkedList(double[] data, int start, int end, boolean clockwise)
    {
        Node last = null;
        if (clockwise == (signedArea(data, start, end) > 0))
        {
            for (int i = start; i < end; i += 2) { last = insertNode(i / 2, data[i], data[i + 1], last); }
        }
        else
        {
            for (int i = end - 2; i >= start; i -= 2) { last = insertNode(i / 2, data[i], data[i + 1], last); }
        }
        if (last != null && equals(last, last.next))
        {
            removeNode(last);
            last = last.next;
        }
        return last;
    }

    private static Node filterPoints(Node start, Node end)
    {
        if (start == null) { return null; }
        if (end == null) { end = start; }
        Node p = start;
        boolean again;
        do
        {
            again = false;
            if (!p.steiner && (equals(p, p.next) || area(p.prev, p, p.next) == 0))
            {
                removeNode(p);
                p = end = p.prev;
                if (p == p.next) { break; }
                again = true;
            }
            else { p = p.next; }
        }
        while (again || p != end);
        return end;
    }

    private static void earcutLinked(Node ear, List<Integer> triangles, double minX, double minY, double invSize, int pass)
    {
        if (ear == null) { return; }
        if (pass == 0 && invSize != 0) { indexCurve(ear, minX, minY, invSize); }
        Node stop = ear;
        while (ear.prev != ear.next)
        {
            Node prev = ear.prev, next = ear.next;
            if (invSize != 0 ? isEarHashed(ear, minX, minY, invSize) : isEar(ear))
            {
                triangles.add(prev.i);
                triangles.add(ear.i);
                triangles.add(next.i);
                removeNode(ear);
                ear = next.next;
                stop = next.next;
                continue;
            }
            ear = next;
            if (ear == stop)
            {
                if (pass == 0) { earcutLinked(filterPoints(ear, null), triangles, minX, minY, invSize, 1); }
                else if (pass == 1) { earcutLinked(cureLocalIntersections(filterPoints(ear, null), triangles), triangles, minX, minY, invSize, 2); }
                else if (pass == 2) { splitEarcut(ear, triangles, minX, minY, invSize); }
                break;
            }
        }
    }

    private static boolean isEar(Node ear)
    {
        Node a = ear.prev, b = ear, c = ear.next;
        if (area(a, b, c) >= 0) { return false; }
        double x0 = Math.min(a.x, Math.min(b.x, c.x)), y0 = Math.min(a.y, Math.min(b.y, c.y));
        double x1 = Math.max(a.x, Math.max(b.x, c.x)), y1 = Math.max(a.y, Math.max(b.y, c.y));
        for (Node p = c.next; p != a; p = p.next)
        {
            if (p.x >= x0 && p.x <= x1 && p.y >= y0 && p.y <= y1 && pointInTriangle(a.x, a.y, b.x, b.y, c.x, c.y, p.x, p.y)
                && area(p.prev, p, p.next) >= 0) { return false; }
        }
        return true;
    }

    private static boolean isEarHashed(Node ear, double minX, double minY, double invSize)
    {
        Node a = ear.prev, b = ear, c = ear.next;
        if (area(a, b, c) >= 0) { return false; }
        double x0 = Math.min(a.x, Math.min(b.x, c.x)), y0 = Math.min(a.y, Math.min(b.y, c.y));
        double x1 = Math.max(a.x, Math.max(b.x, c.x)), y1 = Math.max(a.y, Math.max(b.y, c.y));
        int minZ = zOrder(x0, y0, minX, minY, invSize), maxZ = zOrder(x1, y1, minX, minY, invSize);
        Node p = ear.prevZ, n = ear.nextZ;
        while (p != null && p.z >= minZ && n != null && n.z <= maxZ)
        {
            if (blocks(p, a, c, x0, y0, x1, y1)) { return false; }
            p = p.prevZ;
            if (blocks(n, a, c, x0, y0, x1, y1)) { return false; }
            n = n.nextZ;
        }
        while (p != null && p.z >= minZ)
        {
            if (blocks(p, a, c, x0, y0, x1, y1)) { return false; }
            p = p.prevZ;
        }
        while (n != null && n.z <= maxZ)
        {
            if (blocks(n, a, c, x0, y0, x1, y1)) { return false; }
            n = n.nextZ;
        }
        return true;
    }

    /** Whether a point keeps the triangle a, a.next, c from being an ear: inside it and not reflex. */
    private static boolean blocks(Node p, Node a, Node c, double x0, double y0, double x1, double y1)
    {
        Node b = a.next;
        return p.x >= x0 && p.x <= x1 && p.y >= y0 && p.y <= y1 && p != a && p != c
            && pointInTriangle(a.x, a.y, b.x, b.y, c.x, c.y, p.x, p.y) && area(p.prev, p, p.next) >= 0;
    }

    /** Links the nodes in z-order. */
    private static void indexCurve(Node start, double minX, double minY, double invSize)
    {
        Node p = start;
        do
        {
            if (p.z == 0) { p.z = zOrder(p.x, p.y, minX, minY, invSize); }
            p.prevZ = p.prev;
            p.nextZ = p.next;
            p = p.next;
        }
        while (p != start);
        p.prevZ.nextZ = null;
        p.prevZ = null;
        sortLinked(p);
    }

    /** Simon Tatham's linked list merge sort, by z. */
    private static void sortLinked(Node list)
    {
        int inSize = 1, merges;
        do
        {
            Node p = list, tail = null;
            list = null;
            merges = 0;
            while (p != null)
            {
                merges++;
                Node q = p;
                int pSize = 0;
                for (int i = 0; i < inSize; i++)
                {
                    pSize++;
                    q = q.nextZ;
                    if (q == null) { break; }
                }
                int qSize = inSize;
                while (pSize > 0 || qSize > 0 && q != null)
                {
                    Node e;
                    if (pSize != 0 && (qSize == 0 || q == null || p.z <= q.z))
                    {
                        e = p;
                        p = p.nextZ;
                        pSize--;
                    }
                    else
                    {
                        e = q;
                        q = q.nextZ;
                        qSize--;
                    }
                    if (tail != null) { tail.nextZ = e; }
                    else { list = e; }
                    e.prevZ = tail;
                    tail = e;
                }
                p = q;
            }
            tail.nextZ = null;
            inSize *= 2;
        }
        while (merges > 1);
    }

    /** The z-order of a point: its coordinates as 15-bit integers in the box, their bits interleaved. */
    private static int zOrder(double px, double py, double minX, double minY, double invSize)
    {
        int x = (int) ((px - minX) * invSize), y = (int) ((py - minY) * invSize);
        x = (x | x << 8) & 0x00FF00FF;
        x = (x | x << 4) & 0x0F0F0F0F;
        x = (x | x << 2) & 0x33333333;
        x = (x | x << 1) & 0x55555555;
        y = (y | y << 8) & 0x00FF00FF;
        y = (y | y << 4) & 0x0F0F0F0F;
        y = (y | y << 2) & 0x33333333;
        y = (y | y << 1) & 0x55555555;
        return x | y << 1;
    }

    private static Node cureLocalIntersections(Node start, List<Integer> triangles)
    {
        Node p = start;
        do
        {
            Node a = p.prev, b = p.next.next;
            if (!equals(a, b) && intersects(a, p, p.next, b) && locallyInside(a, b) && locallyInside(b, a))
            {
                triangles.add(a.i);
                triangles.add(p.i);
                triangles.add(b.i);
                removeNode(p);
                removeNode(p.next);
                p = start = b;
            }
            p = p.next;
        }
        while (p != start);
        return filterPoints(p, null);
    }

    private static void splitEarcut(Node start, List<Integer> triangles, double minX, double minY, double invSize)
    {
        Node a = start;
        do
        {
            Node b = a.next.next;
            while (b != a.prev)
            {
                if (a.i != b.i && isValidDiagonal(a, b))
                {
                    Node c = splitPolygon(a, b);
                    a = filterPoints(a, a.next);
                    c = filterPoints(c, c.next);
                    earcutLinked(a, triangles, minX, minY, invSize, 0);
                    earcutLinked(c, triangles, minX, minY, invSize, 0);
                    return;
                }
                b = b.next;
            }
            a = a.next;
        }
        while (a != start);
    }

    private static Node eliminateHoles(double[] data, int[] holeStarts, Node outer)
    {
        List<Node> queue = new ArrayList<>();
        for (int h = 0; h < holeStarts.length; h++)
        {
            int start = holeStarts[h] * 2, end = h < holeStarts.length - 1 ? holeStarts[h + 1] * 2 : data.length;
            Node list = linkedList(data, start, end, false);
            if (list == null) { continue; }
            if (list == list.next) { list.steiner = true; }
            queue.add(leftmost(list));
        }
        queue.sort((p, q) -> p.x != q.x ? Double.compare(p.x, q.x) : Double.compare(p.y, q.y));
        for (Node hole : queue) { outer = eliminateHole(hole, outer); }
        return outer;
    }

    private static Node eliminateHole(Node hole, Node outer)
    {
        Node bridge = findHoleBridge(hole, outer);
        if (bridge == null) { return outer; }
        Node reverse = splitPolygon(bridge, hole);
        filterPoints(reverse, reverse.next);
        return filterPoints(bridge, bridge.next);
    }

    private static Node findHoleBridge(Node hole, Node outer)
    {
        Node p = outer, m = null;
        double hx = hole.x, hy = hole.y, qx = Double.NEGATIVE_INFINITY;
        do
        {
            if (hy <= p.y && hy >= p.next.y && p.next.y != p.y)
            {
                double x = p.x + (hy - p.y) * (p.next.x - p.x) / (p.next.y - p.y);
                if (x <= hx && x > qx)
                {
                    qx = x;
                    m = p.x < p.next.x ? p : p.next;
                    if (x == hx) { return m; }
                }
            }
            p = p.next;
        }
        while (p != outer);
        if (m == null) { return null; }
        Node stop = m;
        double mx = m.x, my = m.y, tanMin = Double.POSITIVE_INFINITY;
        p = m;
        do
        {
            if (hx >= p.x && p.x >= mx && hx != p.x
                && pointInTriangle(hy < my ? hx : qx, hy, mx, my, hy < my ? qx : hx, hy, p.x, p.y))
            {
                double tan = Math.abs(hy - p.y) / (hx - p.x);
                if (locallyInside(p, hole) && (tan < tanMin || tan == tanMin && (p.x > m.x || p.x == m.x && sectorContainsSector(m, p))))
                {
                    m = p;
                    tanMin = tan;
                }
            }
            p = p.next;
        }
        while (p != stop);
        return m;
    }

    private static boolean sectorContainsSector(Node m, Node p) { return area(m.prev, m, p.prev) < 0 && area(p.next, m, m.next) < 0; }

    private static Node leftmost(Node start)
    {
        Node p = start, left = start;
        do
        {
            if (p.x < left.x || p.x == left.x && p.y < left.y) { left = p; }
            p = p.next;
        }
        while (p != start);
        return left;
    }

    private static boolean pointInTriangle(double ax, double ay, double bx, double by, double cx, double cy, double px, double py)
    {
        return (cx - px) * (ay - py) >= (ax - px) * (cy - py) && (ax - px) * (by - py) >= (bx - px) * (ay - py)
            && (bx - px) * (cy - py) >= (cx - px) * (by - py);
    }

    private static boolean isValidDiagonal(Node a, Node b)
    {
        return a.next.i != b.i && a.prev.i != b.i && !intersectsPolygon(a, b)
            && (locallyInside(a, b) && locallyInside(b, a) && middleInside(a, b) && (area(a.prev, a, b.prev) != 0 || area(a, b.prev, b) != 0)
            || equals(a, b) && area(a.prev, a, a.next) > 0 && area(b.prev, b, b.next) > 0);
    }

    private static double area(Node p, Node q, Node r) { return (q.y - p.y) * (r.x - q.x) - (q.x - p.x) * (r.y - q.y); }

    private static boolean equals(Node p, Node q) { return p.x == q.x && p.y == q.y; }

    private static boolean intersects(Node p1, Node q1, Node p2, Node q2)
    {
        double o1 = Math.signum(area(p1, q1, p2)), o2 = Math.signum(area(p1, q1, q2)), o3 = Math.signum(area(p2, q2, p1)), o4 = Math.signum(area(p2, q2, q1));
        if (o1 != o2 && o3 != o4) { return true; }
        if (o1 == 0 && onSegment(p1, p2, q1)) { return true; }
        if (o2 == 0 && onSegment(p1, q2, q1)) { return true; }
        if (o3 == 0 && onSegment(p2, p1, q2)) { return true; }
        return o4 == 0 && onSegment(p2, q1, q2);
    }

    private static boolean onSegment(Node p, Node q, Node r)
    {
        return q.x <= Math.max(p.x, r.x) && q.x >= Math.min(p.x, r.x) && q.y <= Math.max(p.y, r.y) && q.y >= Math.min(p.y, r.y);
    }

    private static boolean intersectsPolygon(Node a, Node b)
    {
        Node p = a;
        do
        {
            if (p.i != a.i && p.next.i != a.i && p.i != b.i && p.next.i != b.i && intersects(p, p.next, a, b)) { return true; }
            p = p.next;
        }
        while (p != a);
        return false;
    }

    private static boolean locallyInside(Node a, Node b)
    {
        return area(a.prev, a, a.next) < 0 ? area(a, b, a.next) >= 0 && area(a, a.prev, b) >= 0 : area(a, b, a.prev) < 0 || area(a, a.next, b) < 0;
    }

    private static boolean middleInside(Node a, Node b)
    {
        Node p = a;
        boolean inside = false;
        double px = (a.x + b.x) / 2, py = (a.y + b.y) / 2;
        do
        {
            if (p.y > py != p.next.y > py && p.next.y != p.y && px < (p.next.x - p.x) * (py - p.y) / (p.next.y - p.y) + p.x) { inside = !inside; }
            p = p.next;
        }
        while (p != a);
        return inside;
    }

    private static Node splitPolygon(Node a, Node b)
    {
        Node a2 = new Node(a.i, a.x, a.y), b2 = new Node(b.i, b.x, b.y), an = a.next, bp = b.prev;
        a.next = b;
        b.prev = a;
        a2.next = an;
        an.prev = a2;
        b2.next = a2;
        a2.prev = b2;
        bp.next = b2;
        b2.prev = bp;
        return b2;
    }

    private static Node insertNode(int i, double x, double y, Node last)
    {
        Node p = new Node(i, x, y);
        if (last == null)
        {
            p.prev = p;
            p.next = p;
        }
        else
        {
            p.next = last.next;
            p.prev = last;
            last.next.prev = p;
            last.next = p;
        }
        return p;
    }

    private static void removeNode(Node p)
    {
        p.next.prev = p.prev;
        p.prev.next = p.next;
        if (p.prevZ != null) { p.prevZ.nextZ = p.nextZ; }
        if (p.nextZ != null) { p.nextZ.prevZ = p.prevZ; }
    }

    private static double signedArea(double[] data, int start, int end)
    {
        double sum = 0;
        for (int i = start, j = end - 2; i < end; j = i, i += 2) { sum += (data[j] - data[i]) * (data[i + 1] + data[j + 1]); }
        return sum;
    }
}
