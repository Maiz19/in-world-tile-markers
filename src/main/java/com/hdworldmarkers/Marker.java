package com.hdworldmarkers;

import java.awt.Color;
import net.runelite.api.coords.LocalPoint;

/** A rectangular tile footprint centered on point, or a shape standing around it (offX). */
final class Marker
{
    static final Color NO_FILL = new Color(0, 0, 0, 0);
    /** The fill RuneLite's marking plugins start from: black at alpha 50. */
    static final Color BLACK_FILL = new Color(0, 0, 0, 50);

    /*
     * The ranking of overlapping marks, low to high: where they overlap a higher layer is always drawn over a lower
     * one, and within a layer the later drawn over the earlier (SceneShapeRenderer keeps every scene object's faces
     * in this order). NPC highlights at the bottom, paths and other plugins' tiles above them, then your own marks,
     * and your tile indicators on top. Clickboxes, hulls and outlines use SceneShapeRenderer's CLICKBOX_LAYER, HULL_LAYER
     * and OUTLINE_LAYER, between the hovered tile and other plugins' timer pies.
     */
    /** Tagged NPCs' tile styles: tile, true tile, south-west tile, south-west true tile (+0..3). */
    static final int NPC_TILE = 1;
    static final int PATH_HOVER = 6, PATH_ACTIVE = 7;
    /** Tiles other plugins send (ExternalMarks): above Path Marker's path. */
    static final int EXTERNAL = 8;
    /** Marked tiles, then marked objects' tiles, then the hovered tile. */
    static final int GROUND = 9, OBJECT = 10, HOVER = 13;
    /** Timer pies other plugins send (ExternalMarks): over outlines, under your destination and current tile. */
    static final int PIE = SceneShapeRenderer.OUTLINE_LAYER + 1;
    static final int DESTINATION = PIE + 1, CURRENT = PIE + 2;

    final String key;
    final LocalPoint point;
    final int plane, width, height;
    final Color color, fill;
    /** Border width in screen pixels. */
    final float borderWidth;
    final String label;
    /** Path Marker dot style: a small circle at the tile center instead of the tile. */
    boolean dot;
    /** Depth layer: overlapping shapes of higher layers are drawn in front, without z-fighting. */
    int layer;
    /** Corners only: each corner line is 1/cornerDivisor of its side, as Corner Tile Indicators; 0 draws the full border. */
    int cornerDivisor;
    /**
     * A shape standing towards the camera around point instead of the rectangle (another plugin's timer pie): its
     * outline in local units from where point projects, so it grows and shrinks with the world as the camera zooms.
     */
    float[] offX, offY;
    /** How far above the ground the shape hangs, in local units. */
    int lift;

    Marker(String key, LocalPoint point, int plane, int width, int height, Color color, Color fill,
        double borderWidth, String label)
    {
        this.key = key;
        this.point = point;
        this.plane = plane;
        this.width = width;
        this.height = height;
        this.color = color == null ? NO_FILL : color;
        this.fill = fill == null ? NO_FILL : fill;
        this.borderWidth = (float) Math.max(0, Math.min(16, borderWidth));
        this.label = label;
    }
}
