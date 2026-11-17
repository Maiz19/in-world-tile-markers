package com.hdworldmarkers;

import java.awt.Color;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Actor;
import net.runelite.api.coords.LocalPoint;

/**
 * A flat sign another plugin sends (ExternalMarks, {@code boards}): coloured rectangles and texts above a character or
 * a point, such as a health bar with its numbers. It faces the camera as a label does and is drawn in the scene, so at
 * the screen's full sharpness in stretched mode. Its parts keep their place from where its point projects, in canvas
 * pixels: x to the right, y down.
 */
@RequiredArgsConstructor
final class Board
{
    final String key;
    final LocalPoint point;
    final int plane;
    /** How far above the ground its point hangs, in local units. */
    final float lift;
    /** The character it follows, or null: see Marker.follow. */
    final Actor follow;
    /** Its place between the character's steps, or null: then it stands on the character's step, as a label. */
    final Glide glide;
    /**
     * Whether it stays on whole screen pixels: its point is held on one (SceneShapeRenderer.snap) and every edge lies a
     * whole number of them from it, so a line of one pixel is one pixel wherever the board is, never two half ones.
     */
    final boolean snap;
    /**
     * Whether it hangs on the game's own overhead point of the character it follows, where the game hangs that
     * character's health bar and head icons: the character's place of this game step (no glide), the height of its
     * model in the pose of this frame + {@link #OVERHEAD_Z} (lift is not used), on the whole canvas pixel the game
     * itself works out (ModelShapes.Camera.gamePixel). Such a board never moves against the game's own overhead
     * things; it hops with the character's steps and bobs with its poses exactly as they do.
     */
    final boolean overhead;
    /** Local units above the top of a model at which the game hangs its health bar and head icons. */
    static final int OVERHEAD_Z = 15;
    /** Its rectangles, four numbers each: x, y, width, height. Drawn in this order, a later one over an earlier. */
    final float[] rects;
    final Color[] colors;
    /** Its texts, in the Text settings' font: each centred on x with its baseline on y (textAt: x, y). */
    final String[] texts;
    final float[] textAt;
    final Color[] textColors;
    /** Per text its own size (0: the Text settings') and font (null: the Text settings'). */
    final int[] textSizes;
    final HdWorldMarkersConfig.TextFont[] textFonts;
    /** Per text its own edge width in canvas pixels (0: the size's own, one at 16): a stronger shadow or outline. Null: none set. */
    int[] textEdges;

    /** Where it stands at this game step: its character's place, else its point. */
    LocalPoint at()
    {
        LocalPoint now = follow == null ? null : follow.getLocalLocation();
        return now != null ? now : point;
    }

    /**
     * A followed character's place between two game steps. The game moves a character 50 times a second, in steps;
     * with an unlocked frame rate the camera is drawn far more often and glides between its own steps. A sign exactly
     * on the character then jumps ahead at every step and slides back as the camera catches up, which on a sharp sign
     * reads as shaking. So its place glides from the character's last place to the new one over the 20 ms of a step.
     */
    static final class Glide
    {
        static final long STEP_NANOS = 20_000_000L;
        private static final long PAUSE_NANOS = 1_000_000_000L;
        /** Frames a second from which the frame rate counts as unlocked, and under which it no longer does. */
        private static final int UNLOCKED_FPS = 58, LOCKED_FPS = 53;
        /** Local units; further than this in one step is a teleport, not a walk. */
        private static final int JUMP = 300;

        private boolean placed, gliding;
        private float fromX, fromY, toX, toY;
        private long since, last;

        /** The character's place at a game step. */
        void step(int x, int y, long nanos)
        {
            if (!placed || nanos - last > PAUSE_NANOS || nanos < last || Math.abs(x - toX) > JUMP || Math.abs(y - toY) > JUMP)
            {
                placed = true;
                fromX = toX = x; fromY = toY = y;
                since = nanos;
            }
            else if (x != toX || y != toY)
            {
                fromX = toX; fromY = toY;
                toX = x; toY = y;
                since = nanos;
            }
            last = nanos;
        }

        /** Where it is now (out: x, y); false while the frame rate is locked: a frame per step, nothing to glide. */
        boolean at(long nanos, int fps, float[] out)
        {
            gliding = fps >= UNLOCKED_FPS || gliding && fps > LOCKED_FPS;
            if (!placed || !gliding) { return false; }
            float part = Math.max(0f, Math.min(1f, (nanos - since) / (float) STEP_NANOS));
            out[0] = fromX + (toX - fromX) * part;
            out[1] = fromY + (toY - fromY) * part;
            return true;
        }
    }
}
