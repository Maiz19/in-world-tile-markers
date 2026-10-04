package com.inworldtilemarkers;

import java.util.*;

/**
 * The on-screen silhouette of a projected model, as closed polygons in canvas
 * coordinates. Faces are rasterized onto a grid finer than a pixel and the
 * boundary between covered and empty cells is traced into loops. Each step of
 * a loop is then placed where the faces' own edges cross it, in floats, so the
 * outline lies on the model's exact edge and moves smoothly with it instead of
 * jumping from cell to cell (as the float clickbox does for RuneLite's
 * whole-pixel one). Used for outlines, which RuneLite otherwise draws pixel by
 * pixel in 2D.
 */
final class Silhouette
{
    /** Grid cells per canvas pixel: the outline's points lie on the exact edge, so one is enough. */
    static final int CELLS_PER_PIXEL = 1;
    /** Screen area (pixels) above which a shape is traced at one cell per pixel. */
    static final int LARGE_SHAPE_PIXELS = 150 * 150;
    static final int MAX_CELLS = 1 << 20;
    private static final long MAX_RASTER_WORK = 8L * MAX_CELLS;

    private Silhouette() { }

    static final class Scratch
    {
        /** The raster, one bit per cell, rows of (w + 63) / 64 words. */
        long[] bits = new long[0];
        /** Per lattice corner, the corners its boundary edges lead to (-1: none); all -1 between traces. */
        int[] out0 = new int[0], out1 = new int[0], loop = new int[64];
        final float[] intersections = new float[3];
        /** The cells with an edge (row * w + column, ascending), and where the faces' edges leave them: west, east, top, bottom. */
        int[] cells = new int[64];
        /** Per cell (row * w + column), its place in cells, or -1; only the cells with an edge are set, and reset after. */
        int[] index = new int[0];
        /** Per block of 8 x 8 cells, whether a cell with an edge lies in it: faces away from every edge are skipped. */
        boolean[] blocks = new boolean[0];
        /** Per cell, where the faces' spans along its row start (when they start in it) and end: filled with the raster. */
        float[] rowStart = new float[0], rowEnd = new float[0];
        float[] west = new float[64], east = new float[64], top = new float[64], bottom = new float[64];
        int cellCount;
        /** Spans between two cell centres (covering neither): line (row or column, negative for columns), start and end. */
        int[] gapLine = new int[64];
        float[] gapLo = new float[64], gapHi = new float[64];
        int gapCount;
    }

    /**
     * The outline, its points on the exact edge. x, y: projected vertices (NaN: not projected); faces a/b/c index them;
     * hidden[f] skips a face.
     */
    static List<float[]> trace(float[] x, float[] y, int[] a, int[] b, int[] c, int faces, boolean[] hidden, Scratch scratch)
    {
        return trace(x, y, a, b, c, faces, hidden, scratch, CELLS_PER_PIXEL, true);
    }

    /**
     * As trace, at most this many cells per pixel; without exact, the cell staircase simplified (the player's cut,
     * made every frame, where a cell of precision does not show).
     */
    static List<float[]> trace(float[] x, float[] y, int[] a, int[] b, int[] c, int faces, boolean[] hidden, Scratch scratch, float cellsPerPixel,
        boolean exact)
    {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int f = 0; f < faces; f++)
        {
            if (hidden != null && hidden[f]) { continue; }
            int i = a[f], j = b[f], k = c[f];
            // Faces with a vertex that was not projected (at or behind the camera) are left out, as RuneLite does.
            if (unprojected(x, i, j, k)) { continue; }
            minX = Math.min(minX, Math.min(x[i], Math.min(x[j], x[k])));
            maxX = Math.max(maxX, Math.max(x[i], Math.max(x[j], x[k])));
            minY = Math.min(minY, Math.min(y[i], Math.min(y[j], y[k])));
            maxY = Math.max(maxY, Math.max(y[i], Math.max(y[j], y[k])));
        }
        if (minX > maxX) { return Collections.emptyList(); }
        double width = (double) maxX - minX, height = (double) maxY - minY;
        // Large shapes on screen trace at one cell per pixel: four times less work, and half a
        // pixel of precision is not visible at that size.
        float scale = width * height > LARGE_SHAPE_PIXELS ? 1 : cellsPerPixel;
        // Coarser still for very large shapes, to bound the work per frame.
        while (paddedCells(width, height, scale) > MAX_CELLS && scale > 0.25f) { scale /= 2; }
        // Oversized/elongated projections use the source's 2D outline instead. Check the
        // padded dimensions before narrowing to ints or allocating any raster memory.
        if (paddedCells(width, height, scale) > MAX_CELLS) { return Collections.emptyList(); }
        // One empty cell of padding on every side, so every loop is closed. The cells lie on a grid fixed to the canvas,
        // not to the shape, so a shape that moves keeps its cells where it does not change.
        int x0 = (int) Math.floor(minX * scale) - 1, y0 = (int) Math.floor(minY * scale) - 1;
        int w = (int) Math.ceil(maxX * scale) + 2 - x0, h = (int) Math.ceil(maxY * scale) + 2 - y0;
        float ox = x0 / scale, oy = y0 / scale;
        // Repeated overlapping faces must not multiply raster work without a limit.
        double work = 0;
        for (int f = 0; f < faces; f++)
        {
            if (hidden != null && hidden[f]) { continue; }
            int i = a[f], j = b[f], k = c[f];
            if (unprojected(x, i, j, k)) { continue; }
            double fw = (double) Math.max(x[i], Math.max(x[j], x[k])) - Math.min(x[i], Math.min(x[j], x[k]));
            double fh = (double) Math.max(y[i], Math.max(y[j], y[k])) - Math.min(y[i], Math.min(y[j], y[k]));
            work += paddedCells(fw, fh, scale);
            if (work > MAX_RASTER_WORK) { return Collections.emptyList(); }
        }
        // The raster as bits, 64 cells a word: filling, and finding the edges, work a word at a time.
        int words = (w + 63) >>> 6, total = Math.multiplyExact(words, h);
        if (scratch.bits.length < total) { scratch.bits = new long[total]; }
        else { Arrays.fill(scratch.bits, 0, total, 0); }
        long[] grid = scratch.bits;
        int cells = w * h;
        if (exact && scratch.rowStart.length < cells) { scratch.rowStart = new float[cells]; scratch.rowEnd = new float[cells]; }
        if (exact)
        {
            Arrays.fill(scratch.rowStart, 0, cells, Float.POSITIVE_INFINITY);
            Arrays.fill(scratch.rowEnd, 0, cells, Float.NEGATIVE_INFINITY);
        }
        scratch.gapCount = 0;
        for (int f = 0; f < faces; f++)
        {
            if (hidden != null && hidden[f] || unprojected(x, a[f], b[f], c[f])) { continue; }
            fill(grid, words, w, h, (x[a[f]] - ox) * scale, (y[a[f]] - oy) * scale, (x[b[f]] - ox) * scale, (y[b[f]] - oy) * scale,
                (x[c[f]] - ox) * scale, (y[c[f]] - oy) * scale, scratch, exact);
        }
        List<int[]> corners = boundaries(grid, words, w, h, scratch);
        if (exact)
        {
            exact(x, y, a, b, c, faces, hidden, ox, oy, scale, w, h, scratch);
            extend(scratch, w);
        }
        List<float[]> loops = new ArrayList<>();
        for (int[] loop : corners)
        {
            float[] canvas;
            if (exact) { canvas = place(loop, w, scratch); }
            else { canvas = new float[loop.length]; for (int i = 0; i < loop.length; i++) { canvas[i] = loop[i]; } }
            for (int i = 0; i < canvas.length; i += 2) { canvas[i] = ox + canvas[i] / scale; canvas[i + 1] = oy + canvas[i + 1] / scale; }
            // Exact points need half a cell of tolerance to merge straight runs; a staircase one cell.
            float[] simple = simplify(canvas, (exact ? 0.5 : 1) / scale);
            if (simple.length >= 6) { loops.add(simple); }
        }
        // The index is left all -1 for the next trace.
        for (int i = 0; i < scratch.cellCount; i++) { scratch.index[scratch.cells[i]] = -1; }
        return loops;
    }

    /**
     * Where the faces' edges leave each cell with an edge (grid coordinates): along its row (west, east) and its column
     * (top, bottom). A cell whose neighbour is empty is covered only by faces whose span ends within that cell, so the
     * extreme end of those spans is where the silhouette crosses between the two cell centres; spans that fit between
     * two centres (thin slivers along the edge) extend it where they touch.
     */
    private static void exact(float[] x, float[] y, int[] a, int[] b, int[] c, int faces, boolean[] hidden, float ox, float oy, float scale,
        int w, int h, Scratch scratch)
    {
        int n = scratch.cellCount;
        Arrays.fill(scratch.west, 0, n, Float.NaN);
        Arrays.fill(scratch.east, 0, n, Float.NaN);
        Arrays.fill(scratch.top, 0, n, Float.NaN);
        Arrays.fill(scratch.bottom, 0, n, Float.NaN);
        float[] xs = scratch.intersections;
        // Along the rows, from the raster.
        for (int i = 0; i < n; i++)
        {
            int cell = scratch.cells[i];
            float start = scratch.rowStart[cell], end = scratch.rowEnd[cell];
            if (start != Float.POSITIVE_INFINITY) { scratch.west[i] = start; }
            if (end != Float.NEGATIVE_INFINITY) { scratch.east[i] = end; }
        }
        int bw = (w + 7) >> 3, bh = (h + 7) >> 3;
        if (scratch.blocks.length < bw * bh) { scratch.blocks = new boolean[bw * bh]; }
        else { Arrays.fill(scratch.blocks, 0, bw * bh, false); }
        for (int i = 0; i < n; i++) { int cell = scratch.cells[i]; scratch.blocks[(cell / w >> 3) * bw + (cell % w >> 3)] = true; }
        for (int f = 0; f < faces; f++)
        {
            if (hidden != null && hidden[f] || unprojected(x, a[f], b[f], c[f])) { continue; }
            float ax = (x[a[f]] - ox) * scale, ay = (y[a[f]] - oy) * scale, bx = (x[b[f]] - ox) * scale, by = (y[b[f]] - oy) * scale;
            float cx = (x[c[f]] - ox) * scale, cy = (y[c[f]] - oy) * scale;
            if (!nearEdge(scratch.blocks, bw, bh, Math.min(ax, Math.min(bx, cx)), Math.min(ay, Math.min(by, cy)),
                Math.max(ax, Math.max(bx, cx)), Math.max(ay, Math.max(by, cy)))) { continue; }
            int first, last;
            // Columns: the span at each column centre.
            first = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx)) - 0.5f));
            last = Math.min(w - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx)) - 0.5f));
            for (int col = first; col <= last; col++)
            {
                float s = col + 0.5f;
                // The same crossing with x and y swapped: where the edges meet the column centre.
                int k = cross(ay, ax, by, bx, s, xs, cross(by, bx, cy, cx, s, xs, cross(cy, cx, ay, ax, s, xs, 0)));
                if (k < 2) { continue; }
                float lo = Math.min(xs[0], xs[1]), hi = Math.max(xs[0], xs[1]);
                if (k == 3) { lo = Math.min(lo, xs[2]); hi = Math.max(hi, xs[2]); }
                int from = (int) Math.ceil(lo - 0.5f), to = (int) Math.floor(hi - 0.5f);
                if (from > to)
                {
                    if (to >= 0 && find(scratch, to * w + col) >= 0 || from < h && find(scratch, from * w + col) >= 0) { gap(scratch, -1 - col, lo, hi); }
                    continue;
                }
                if (to < 0 || from >= h) { continue; }
                int i = find(scratch, Math.max(0, from) * w + col);
                if (i >= 0) { scratch.top[i] = Float.isNaN(scratch.top[i]) ? lo : Math.min(scratch.top[i], lo); }
                i = find(scratch, Math.min(h - 1, to) * w + col);
                if (i >= 0) { scratch.bottom[i] = Float.isNaN(scratch.bottom[i]) ? hi : Math.max(scratch.bottom[i], hi); }
            }
        }
    }

    /** Whether a block within a cell of this box holds a cell with an edge. */
    private static boolean nearEdge(boolean[] blocks, int bw, int bh, float minX, float minY, float maxX, float maxY)
    {
        int c0 = Math.max(0, ((int) minX - 1) >> 3), c1 = Math.min(bw - 1, ((int) maxX + 1) >> 3);
        int r0 = Math.max(0, ((int) minY - 1) >> 3), r1 = Math.min(bh - 1, ((int) maxY + 1) >> 3);
        for (int r = r0; r <= r1; r++) { for (int c = c0; c <= c1; c++) { if (blocks[r * bw + c]) { return true; } } }
        return false;
    }

    private static int find(Scratch scratch, int cell) { return cell < 0 || cell >= scratch.index.length ? -1 : scratch.index[cell]; }

    private static void gap(Scratch scratch, int line, float lo, float hi)
    {
        int n = scratch.gapCount;
        if (n == scratch.gapLine.length)
        {
            scratch.gapLine = Arrays.copyOf(scratch.gapLine, n * 2);
            scratch.gapLo = Arrays.copyOf(scratch.gapLo, n * 2);
            scratch.gapHi = Arrays.copyOf(scratch.gapHi, n * 2);
        }
        scratch.gapLine[n] = line; scratch.gapLo[n] = lo; scratch.gapHi[n] = hi;
        scratch.gapCount = n + 1;
    }

    /** Edges of adjacent faces, computed from either face, can differ by rounding. */
    private static final float TOUCH = 1e-3f;

    /**
     * Spans between two cell centres extend the crossing of the covered cell beside them where they touch it; a few
     * passes for slivers touching each other.
     */
    private static void extend(Scratch scratch, int w)
    {
        for (int pass = 0; pass < 4; pass++)
        {
            boolean changed = false;
            for (int g = 0; g < scratch.gapCount; g++)
            {
                int line = scratch.gapLine[g];
                float lo = scratch.gapLo[g], hi = scratch.gapHi[g];
                // The centres it lies between: before (on its low side) and after.
                int before = (int) Math.floor(hi - 0.5f), after = before + 1;
                boolean row = line >= 0;
                int i = find(scratch, row ? line * w + before : before * w + (-1 - line));
                float[] high = row ? scratch.east : scratch.bottom, low = row ? scratch.west : scratch.top;
                if (i >= 0 && !Float.isNaN(high[i]) && lo <= high[i] + TOUCH && hi > high[i]) { high[i] = hi; changed = true; }
                i = find(scratch, row ? line * w + after : after * w + (-1 - line));
                if (i >= 0 && !Float.isNaN(low[i]) && hi >= low[i] - TOUCH && lo < low[i]) { low[i] = lo; changed = true; }
            }
            if (!changed) { return; }
        }
    }

    /**
     * A traced loop (lattice corners {x0, y0, ...}) as points on the silhouette (grid coordinates): one per step, where
     * the silhouette crosses between the covered cell beside that step and the empty one across it; the step's middle
     * when no face edge was found there.
     */
    private static float[] place(int[] loop, int w, Scratch scratch)
    {
        int n = loop.length / 2;
        float[] out = new float[loop.length];
        for (int i = 0; i < n; i++)
        {
            int cx = loop[i * 2], cy = loop[i * 2 + 1], nx = loop[(i + 1) % n * 2], ny = loop[(i + 1) % n * 2 + 1];
            float px = (cx + nx) / 2f, py = (cy + ny) / 2f, v;
            if (nx > cx) { v = value(scratch, scratch.top, cy * w + cx); if (!Float.isNaN(v)) { px = cx + 0.5f; py = v; } }
            else if (ny > cy) { v = value(scratch, scratch.east, cy * w + cx - 1); if (!Float.isNaN(v)) { px = v; py = cy + 0.5f; } }
            else if (nx < cx) { v = value(scratch, scratch.bottom, (cy - 1) * w + cx - 1); if (!Float.isNaN(v)) { px = cx - 0.5f; py = v; } }
            else { v = value(scratch, scratch.west, (cy - 1) * w + cx); if (!Float.isNaN(v)) { px = v; py = cy - 0.5f; } }
            out[i * 2] = px;
            out[i * 2 + 1] = py;
        }
        return out;
    }

    private static float value(Scratch scratch, float[] values, int cell)
    {
        int i = find(scratch, cell);
        return i < 0 ? Float.NaN : values[i];
    }

    private static boolean unprojected(float[] x, int i, int j, int k)
    {
        return Float.isNaN(x[i]) || Float.isNaN(x[j]) || Float.isNaN(x[k]);
    }

    private static double paddedCells(double width, double height, float scale)
    {
        return (Math.ceil(width * scale) + 2) * (Math.ceil(height * scale) + 2);
    }

    /**
     * Marks cells whose centres lie inside the triangle (grid coordinates), and keeps where its span starts and ends in
     * the first and last cell of each row (and spans between two centres), for exact.
     */
    private static void fill(long[] grid, int words, int w, int h, float x0, float y0, float x1, float y1, float x2, float y2, Scratch scratch,
        boolean exact)
    {
        float[] xs = scratch.intersections;
        int top = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2)) - 0.5f));
        int bottom = Math.min(h - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2)) - 0.5f));
        for (int row = top; row <= bottom; row++)
        {
            float sy = row + 0.5f;
            int k = 0;
            k = cross(x0, y0, x1, y1, sy, xs, k);
            k = cross(x1, y1, x2, y2, sy, xs, k);
            k = cross(x2, y2, x0, y0, sy, xs, k);
            if (k < 2) { continue; }
            float left = Math.min(xs[0], xs[1]), right = Math.max(xs[0], xs[1]);
            if (k == 3) { left = Math.min(left, xs[2]); right = Math.max(right, xs[2]); }
            int from = Math.max(0, (int) Math.ceil(left - 0.5f)), to = Math.min(w - 1, (int) Math.floor(right - 0.5f));
            if (from <= to)
            {
                setRange(grid, row * words, from, to);
                if (!exact) { continue; }
                int base = row * w;
                scratch.rowStart[base + from] = Math.min(scratch.rowStart[base + from], left);
                scratch.rowEnd[base + to] = Math.max(scratch.rowEnd[base + to], right);
            }
            else if (exact) { gap(scratch, row, left, right); }
        }
    }

    private static int cross(float xa, float ya, float xb, float yb, float sy, float[] xs, int k)
    {
        if ((ya <= sy && yb > sy) || (yb <= sy && ya > sy))
        {
            xs[k++] = xa + (sy - ya) / (yb - ya) * (xb - xa);
        }
        return k;
    }

    /** Sets the bits of columns from..to (inclusive) in the row starting at word base. */
    private static void setRange(long[] grid, int base, int from, int to)
    {
        int fw = from >>> 6, tw = to >>> 6;
        long fm = -1L << (from & 63), tm = -1L >>> (63 - (to & 63));
        if (fw == tw) { grid[base + fw] |= fm & tm; return; }
        grid[base + fw] |= fm;
        for (int i = fw + 1; i < tw; i++) { grid[base + i] = -1L; }
        grid[base + tw] |= tm;
    }

    /** Word i of a row shifted so each column holds its left (col - 1) neighbour; nothing enters at column 0. */
    private static long left(long[] g, int base, int i) { return g[base + i] << 1 | (i > 0 ? g[base + i - 1] >>> 63 : 0); }

    /** Word i of a row shifted so each column holds its right (col + 1) neighbour; the row's end brings nothing. */
    private static long right(long[] g, int base, int i, int words) { return g[base + i] >>> 1 | (i < words - 1 ? g[base + i + 1] << 63 : 0); }

    /**
     * Loops along cell edges between covered and empty cells, as lattice corner coordinates {x0, y0, x1, y1, ...}.
     * Each directed edge keeps the covered cell on the same side, so edges chain into closed loops. Only words with an
     * edge are visited; edges are kept per start corner in two flat arrays (a corner starts two at a diagonal touch),
     * without allocating per edge.
     */
    private static List<int[]> boundaries(long[] grid, int words, int w, int h, Scratch scratch)
    {
        int cw = w + 1, corners = cw * (h + 1);
        if (scratch.out0.length < corners)
        {
            scratch.out0 = new int[corners + corners / 2];
            scratch.out1 = new int[corners + corners / 2];
            Arrays.fill(scratch.out0, -1);
            Arrays.fill(scratch.out1, -1);
        }
        int[] out0 = scratch.out0, out1 = scratch.out1;
        int edges = 0;
        scratch.cellCount = 0;
        if (scratch.index.length < w * h)
        {
            scratch.index = new int[w * h + w * h / 2];
            Arrays.fill(scratch.index, -1);
        }
        for (int row = 0; row < h; row++)
        {
            int base = row * words;
            for (int i = 0; i < words; i++)
            {
                long cur = grid[base + i];
                if (cur == 0) { continue; }
                long up = row > 0 ? grid[base - words + i] : 0, down = row < h - 1 ? grid[base + words + i] : 0;
                long top = cur & ~up, bottom = cur & ~down, west = cur & ~left(grid, base, i), east = cur & ~right(grid, base, i, words);
                for (long any = top | bottom | west | east; any != 0; any &= any - 1)
                {
                    int bit = Long.numberOfTrailingZeros(any);
                    long m = 1L << bit;
                    int c = row * cw + (i << 6) + bit;
                    // The cell, in ascending order (rows, then columns), for exact.
                    if (scratch.cellCount == scratch.cells.length)
                    {
                        int size = scratch.cells.length * 2;
                        scratch.cells = Arrays.copyOf(scratch.cells, size);
                        scratch.west = new float[size]; scratch.east = new float[size]; scratch.top = new float[size]; scratch.bottom = new float[size];
                    }
                    scratch.index[row * w + (i << 6) + bit] = scratch.cellCount;
                    scratch.cells[scratch.cellCount++] = row * w + (i << 6) + bit;
                    // Per cell in the same order as before: top, right, bottom, left.
                    if ((top & m) != 0) { edges += add(out0, out1, c, c + 1); }
                    if ((east & m) != 0) { edges += add(out0, out1, c + 1, c + 1 + cw); }
                    if ((bottom & m) != 0) { edges += add(out0, out1, c + 1 + cw, c + cw); }
                    if ((west & m) != 0) { edges += add(out0, out1, c + cw, c); }
                }
            }
        }
        List<int[]> loops = new ArrayList<>();
        int scan = 0;
        while (edges > 0)
        {
            while (out0[scan] < 0 && out1[scan] < 0) { scan++; }
            int start = scan, from = start;
            int[] loop = scratch.loop;
            int size = 0;
            while (true)
            {
                int to = take(out0, out1, from);
                edges--;
                if (size + 2 > loop.length) { loop = scratch.loop = Arrays.copyOf(loop, loop.length * 2); }
                loop[size++] = from % cw;
                loop[size++] = from / cw;
                if (to == start || out0[to] < 0 && out1[to] < 0) { break; }
                from = to;
            }
            if (size >= 6) { loops.add(Arrays.copyOf(loop, size)); }
        }
        return loops;
    }

    private static int add(int[] out0, int[] out1, int from, int to)
    {
        if (out0[from] < 0) { out0[from] = to; }
        else { out1[from] = to; }
        return 1;
    }

    /** An edge from this corner, the one added last first. */
    private static int take(int[] out0, int[] out1, int from)
    {
        int to;
        if (out1[from] >= 0) { to = out1[from]; out1[from] = -1; }
        else { to = out0[from]; out0[from] = -1; }
        return to;
    }

    /** Douglas-Peucker on a closed polygon {x0, y0, ...}, tolerance in its own units. */
    static float[] simplify(float[] loop, double tolerance)
    {
        int n = loop.length / 2;
        // Split the loop at its first point and the one farthest from it, and simplify both halves.
        int far = 0;
        double best = -1;
        for (int i = 1; i < n; i++)
        {
            double d = Math.hypot(loop[i * 2] - loop[0], loop[i * 2 + 1] - loop[1]);
            if (d > best) { best = d; far = i; }
        }
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[far] = true;
        mark(loop, 0, far, tolerance, keep);
        mark(loop, far, n, tolerance, keep);
        // The two split points are always kept; drop them too when they lie on a straight edge.
        for (int pass = 0; pass < 2; pass++)
        {
            for (int i : new int[]{0, far})
            {
                if (!keep[i]) { continue; }
                int prev = i, next = i;
                do { prev = (prev + n - 1) % n; } while (!keep[prev] && prev != i);
                do { next = (next + 1) % n; } while (!keep[next] && next != i);
                if (prev == i || next == i || prev == next) { continue; }
                if (distance(loop, i, prev, next) <= tolerance) { keep[i] = false; }
            }
        }
        int count = 0;
        for (boolean k : keep) { if (k) { count++; } }
        float[] out = new float[count * 2];
        int j = 0;
        for (int i = 0; i < n; i++)
        {
            if (keep[i]) { out[j++] = loop[i * 2]; out[j++] = loop[i * 2 + 1]; }
        }
        return out;
    }

    static double distance(float[] loop, int i, int a, int b)
    {
        double ax = loop[a * 2], ay = loop[a * 2 + 1], dx = loop[b * 2] - ax, dy = loop[b * 2 + 1] - ay;
        double px = loop[i * 2] - ax, py = loop[i * 2 + 1] - ay, length = Math.hypot(dx, dy);
        return length > 0 ? Math.abs(px * dy - py * dx) / length : Math.hypot(px, py);
    }

    /** Keeps points between from and to (to == n wraps to 0) farther than tolerance from the chord. */
    private static void mark(float[] loop, int from, int to, double tolerance, boolean[] keep)
    {
        int index = -1;
        double max = tolerance;
        for (int i = from + 1; i < to; i++)
        {
            double d = distance(loop, i, from, to % (loop.length / 2));
            if (d > max) { max = d; index = i; }
        }
        if (index < 0) { return; }
        keep[index] = true;
        mark(loop, from, index, tolerance, keep);
        mark(loop, index, to, tolerance, keep);
    }
}
