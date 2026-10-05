/*
 * Copyright (c) 2017, Adam <Adam@sigterm.info>
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
 * Camera.project uses the same math as RuneLite's Perspective.localToCanvasGpu and modelToCanvas
 * (https://github.com/runelite/runelite), BSD 2-Clause License, copyright the RuneLite contributors;
 * see META-INF/LICENSE-runelite and THIRD_PARTY_NOTICES.md. Unproject and the convex hull are HD World Markers' own.
 */
package com.hdworldmarkers;

/**
 * Camera projection matching the client, and convex hulls in canvas space.
 */
final class ModelShapes
{
    static final float NEAR = 50f;
    static final int MAX_HULL = 256;

    private ModelShapes() { }

    /** Camera state in the same units and conventions as Perspective's GPU projection. */
    static final class Camera
    {
        final float x, y, z, pitch, yaw, pitchSin, pitchCos, yawSin, yawCos, scale, centerX, centerY;

        Camera(float x, float y, float z, float pitch, float yaw, int scale,
            int viewportX, int viewportY, int viewportWidth, int viewportHeight)
        {
            this.x = x; this.y = y; this.z = z; this.pitch = pitch; this.yaw = yaw;
            pitchSin = (float) Math.sin(pitch); pitchCos = (float) Math.cos(pitch);
            yawSin = (float) Math.sin(yaw); yawCos = (float) Math.cos(yaw);
            this.scale = scale;
            centerX = viewportX + viewportWidth / 2f;
            centerY = viewportY + viewportHeight / 2f;
        }

        /** The client's camera and viewport now. */
        static Camera of(net.runelite.api.Client client) { return of(client, null); }

        /** The client's camera and viewport now: last when it is the same (no new camera, nor its sines and cosines). */
        static Camera of(net.runelite.api.Client client, Camera last)
        {
            float x = client.getCameraFpX(), y = client.getCameraFpY(), z = client.getCameraFpZ();
            float pitch = client.getCameraFpPitch(), yaw = client.getCameraFpYaw();
            int scale = client.getScale(), vx = client.getViewportXOffset(), vy = client.getViewportYOffset();
            int vw = client.getViewportWidth(), vh = client.getViewportHeight();
            if (last != null && last.x == x && last.y == y && last.z == z && last.pitch == pitch && last.yaw == yaw && last.scale == scale
                && last.centerX == vx + vw / 2f && last.centerY == vy + vh / 2f) { return last; }
            return new Camera(x, y, z, pitch, yaw, scale, vx, vy, vw, vh);
        }

        /** Whether another camera projects exactly as this one. */
        boolean same(Camera o)
        {
            return o == this || o != null && x == o.x && y == o.y && z == o.z && pitchSin == o.pitchSin && pitchCos == o.pitchCos
                && yawSin == o.yawSin && yawCos == o.yawCos && scale == o.scale && centerX == o.centerX && centerY == o.centerY;
        }

        /**
         * Whether a camera at these values jumped from this one: set anew (at login, or a teleport), not moved
         * between two frames. Borders made for this camera would be far too wide or narrow for that one.
         */
        boolean jumpedTo(Camera o)
        {
            float dx = o.x - x, dy = o.y - y, dz = o.z - z;
            double turn = Math.abs(Math.IEEEremainder(o.yaw - yaw, 2 * Math.PI));
            return dx * dx + dy * dy + dz * dz > JUMP * JUMP || o.scale > scale * 1.5f || scale > o.scale * 1.5f
                || turn > JUMP_TURN || Math.abs(o.pitch - pitch) > JUMP_TURN;
        }

        /** A camera farther than this (local units) or turned more than JUMP_TURN (radians) since the frame jumped. */
        static final float JUMP = 512, JUMP_TURN = 0.5f;

        /** Writes canvas x, canvas y and depth into out. Local x/y are horizontal, z is height (down positive). */
        void project(float lx, float ly, float lz, float[] out)
        {
            float dx = lx - x, dy = ly - y, dz = lz - z;
            float x1 = dx * yawCos + dy * yawSin;
            float y1 = dy * yawCos - dx * yawSin;
            float y2 = dz * pitchCos - y1 * pitchSin;
            float depth = y1 * pitchCos + dz * pitchSin;
            out[0] = centerX + x1 * scale / depth;
            out[1] = centerY + y2 * scale / depth;
            out[2] = depth;
        }

        /** Inverse of project for a canvas point at the given depth. Writes local x, y and z into out. */
        void unproject(float sx, float sy, float depth, float[] out)
        {
            float x1 = (sx - centerX) * depth / scale;
            float y2 = (sy - centerY) * depth / scale;
            float y1 = depth * pitchCos - y2 * pitchSin;
            float dz = y2 * pitchCos + depth * pitchSin;
            out[0] = x + x1 * yawCos - y1 * yawSin;
            out[1] = y + x1 * yawSin + y1 * yawCos;
            out[2] = z + dz;
        }
    }

    /**
     * Convex hull of the first n points (Andrew's monotone chain), unprojected (NaN) points left out. Returns hull
     * points as {x0, y0, x1, y1, ...} with a positive signed area, at most MAX_HULL points, or null with fewer than three.
     */
    static float[] convexHull(float[] xs, float[] ys, int n)
    {
        // Sort by x then y without boxing: coordinates quantized to 1/16 pixel
        // and the vertex index packed into one long. The turn tests below use
        // the same quantized values: with exact floats, points in one 1/16 px
        // column would be out of order and the chain would zigzag.
        long[] keys = new long[n];
        int[] qx = new int[n], qy = new int[n];
        int m = 0;
        for (int i = 0; i < n; i++)
        {
            if (Float.isNaN(xs[i])) { continue; }
            qx[i] = Math.max(0, Math.min((1 << 21) - 1, Math.round(xs[i] * 16) + (1 << 20)));
            qy[i] = Math.max(0, Math.min((1 << 21) - 1, Math.round(ys[i] * 16) + (1 << 20)));
            keys[m++] = (long) qx[i] << 42 | (long) qy[i] << 21 | i;
        }
        java.util.Arrays.sort(keys, 0, m);
        int[] order = new int[m];
        for (int i = 0; i < m; i++) { order[i] = (int) (keys[i] & ((1 << 21) - 1)); }
        int[] hull = new int[2 * m + 1];
        int k = 0;
        for (int i = 0; i < m; i++)
        {
            int p = order[i];
            while (k >= 2 && cross(qx, qy, hull[k - 2], hull[k - 1], p) <= 0) { k--; }
            hull[k++] = p;
        }
        for (int i = m - 2, lower = k + 1; i >= 0; i--)
        {
            int p = order[i];
            while (k >= lower && cross(qx, qy, hull[k - 2], hull[k - 1], p) <= 0) { k--; }
            hull[k++] = p;
        }
        int count = k - 1;
        if (count < 3) { return null; }
        // Keep the shape bounded for the carrier model. Dropping points from a
        // convex polygon keeps it convex.
        int step = (count + MAX_HULL - 1) / MAX_HULL;
        int kept = (count + step - 1) / step;
        float[] result = new float[kept * 2];
        for (int i = 0; i < kept; i++)
        {
            result[i * 2] = xs[hull[i * step]];
            result[i * 2 + 1] = ys[hull[i * step]];
        }
        return result;
    }

    private static long cross(int[] xs, int[] ys, int o, int a, int b)
    {
        return (long) (xs[a] - xs[o]) * (ys[b] - ys[o]) - (long) (ys[a] - ys[o]) * (xs[b] - xs[o]);
    }

    /**
     * Projects model vertices placed at local (x, y), base height and orientation, like Perspective.modelToCanvas.
     * Vertices nearer the camera than PARTIAL_NEAR are not projected (NaN) instead of failing the whole model, as
     * RuneLite skips faces with vertices behind the camera. Returns the nearest depth, NaN when no vertex is projected.
     */
    static float projectModel(Camera camera, float[] vx, float[] vy, float[] vz, int n,
        int localX, int localY, int height, int orientation, float[] outX, float[] outY)
    {
        return projectModel(camera, vx, vy, vz, n, localX, localY, height, orientation, outX, outY, null, SceneShapeRenderer.PARTIAL_NEAR);
    }

    /** As projectModel, with every vertex's depth in outD too (null: not wanted), and vertices nearer than near left out. */
    static float projectModel(Camera camera, float[] vx, float[] vy, float[] vz, int n,
        int localX, int localY, int height, int orientation, float[] outX, float[] outY, float[] outD, float near)
    {
        double angle = (orientation & 2047) * Math.PI / 1024;
        float sin = (float) Math.sin(angle), cos = (float) Math.cos(angle);
        float[] p = new float[3];
        float nearest = Float.MAX_VALUE;
        for (int i = 0; i < n; i++)
        {
            float rx = vz[i] * sin + vx[i] * cos;
            float rz = vz[i] * cos - vx[i] * sin;
            camera.project(localX + rx, localY + rz, height + vy[i], p);
            if (!(p[2] >= near)) { outX[i] = Float.NaN; outY[i] = Float.NaN; continue; }
            outX[i] = p[0]; outY[i] = p[1];
            if (outD != null) { outD[i] = p[2]; }
            nearest = Math.min(nearest, p[2]);
        }
        return nearest == Float.MAX_VALUE ? Float.NaN : nearest;
    }
}
