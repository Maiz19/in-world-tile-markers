package com.inworldtilemarkers;

import net.runelite.api.Model;

/**
 * The local player's model as the client draws it this frame, copied (the client's models share their arrays), and the
 * part of the canvas it covers with the camera the client draws with: its front faces, as Improved Tile Indicators
 * clears them from its overlay, traced into a silhouette. Marks drawn through walls leave that out, so the player
 * stands in front of them (SceneShapeRenderer.Bucket.pullToCamera).
 */
final class PlayerSilhouette
{
    private float[] x = new float[0], y = x, z = x, px = x, py = x;
    private int[] a = new int[0], b = a, c = a;
    private boolean[] skip = new boolean[0], hidden = skip;
    private int n, faces, localX, localY, height, orientation;
    private final Silhouette.Scratch scratch = new Silhouette.Scratch();
    private ModelShapes.Camera camera, lastCamera;
    private PlayerCut cut, lastCut;

    /** Takes the model, standing at this place; false when it has nothing to draw. */
    synchronized boolean take(Model model, int localX, int localY, int height, int orientation)
    {
        n = model.getVerticesCount();
        faces = model.getFaceCount();
        if (n <= 0 || faces <= 0) { return false; }
        if (x.length < n) { x = new float[n * 2]; y = new float[n * 2]; z = new float[n * 2]; px = new float[n * 2]; py = new float[n * 2]; }
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
        ModelShapes.projectModel(cam, x, y, z, n, localX, localY, height, orientation, px, py);
        for (int f = 0; f < faces; f++)
        {
            int i = a[f], j = b[f], k = c[f];
            // Faces turned away from the camera are not drawn (the renderers cull them; Improved Tile Indicators skips them).
            hidden[f] = skip[f] || (px[j] - px[i]) * (py[k] - py[i]) - (py[j] - py[i]) * (px[k] - px[i]) >= 0;
        }
        // One cell per pixel: half the points of the finer trace, and so about half the pieces marks are cut into.
        cut = PlayerCut.of(Silhouette.trace(px, py, a, b, c, faces, hidden, scratch, 1));
        return cut;
    }
}
