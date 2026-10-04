package com.inworldtilemarkers;

import java.awt.Color;
import net.runelite.api.Actor;
import net.runelite.api.coords.LocalPoint;

/** A rectangular tile footprint centered on point. */
final class Marker
{
    static final Color NO_FILL = new Color(0, 0, 0, 0);

    /*
     * The ranking of overlapping marks, low to high: where they overlap a higher layer is always drawn over a lower
     * one, and within a layer the later drawn over the earlier (SceneShapeRenderer keeps every scene object's faces
     * in this order). NPC highlights at the bottom, paths and other plugins' tiles above them, then your own marks,
     * and your tile indicators on top. Hulls, clickboxes and outlines use SceneShapeRenderer.HULL_LAYER.
     */
    /** NPC tile styles: tile, true tile, south-west tile, south-west true tile (+0..3). */
    static final int NPC_TILE = 1;
    static final int PATH_HOVER = 6, PATH_ACTIVE = 7;
    /** Tiles other plugins send (ExternalMarks, Shortest Path, captured overlays): above Path Marker's path. */
    static final int EXTERNAL = 8;
    /** NPC Aggression Timer's area lines: with other plugins' tiles. */
    static final int AGGRO_AREA = EXTERNAL;
    /** Saved ground marks and tile packs, then object marks (OBJECT + 1: Rooftop Agility Improved's), Sailing, hover. */
    static final int GROUND = 9, OBJECT = 10, HOVER = 13;
    static final int DESTINATION = SceneShapeRenderer.HULL_LAYER + 2, CURRENT = SceneShapeRenderer.HULL_LAYER + 3;

    final String key;
    final LocalPoint point;
    final int plane, width, height;
    final Color color, fill;
    /** Border width in screen pixels, as in the source plugin's options. */
    final float borderWidth;
    final String label;
    final boolean ground;
    /** Path Marker dot style: a small circle at the tile center instead of the tile. */
    boolean dot;
    /** Depth layer: overlapping shapes of higher layers are drawn in front, without z-fighting. */
    int layer;
    /** Corners only: each corner line is 1/cornerDivisor of its side, as Corner Tile Indicators and Better NPC Highlight; 0 draws the full border. */
    int cornerDivisor;
    /** A free quadrilateral in local coordinates (Sailing's rotated boat bounds) instead of the rectangle; point is its center. */
    int[] quadX, quadY;
    /**
     * A shape drawn on the screen around point (another plugin's timer pie or dot) instead of the rectangle: its outline
     * in canvas pixels from where point projects, or with worldSized in world units at that point, which every frame
     * scales with its own camera.
     */
    float[] offX, offY;
    boolean worldSized;
    /** How far above the ground the screen shape hangs (local units), as its plugin placed it. */
    int lift;
    /** An open polyline on the ground in local coordinates (NPC Aggression Timer's area lines) instead of the rectangle. */
    int[] lineX, lineY;
    /**
     * The actor this footprint stands under (a walking NPC's tile captured from another plugin): drawn where it is each
     * frame rather than where it was when the mark was made; point is the fallback.
     */
    Actor follow;
    /** Local units the footprint lies south-west of the followed actor's centre (a large NPC's south-west tile). */
    int followShift;

    /** Where the footprint is now: the followed actor's location, else point. */
    LocalPoint where()
    {
        LocalPoint now = follow == null ? null : follow.getLocalLocation();
        if (now == null || now.getWorldView() != point.getWorldView()) { return point; }
        return followShift == 0 ? now : new LocalPoint(now.getX() - followShift, now.getY() - followShift, now.getWorldView());
    }

    Marker(String key, LocalPoint point, int plane, int width, int height, Color color, Color fill,
        double borderWidth, String label, boolean ground)
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
        this.ground = ground;
    }
}
