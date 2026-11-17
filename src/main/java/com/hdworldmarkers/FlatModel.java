package com.hdworldmarkers;

import java.awt.Color;
import net.runelite.api.JagexColor;
import net.runelite.api.Model;

/**
 * Makes carrier model faces render as one flat color. Lit colors, unlit colors
 * and vertex normals are all set, because renderers differ in which they read:
 * the vanilla GPU uses faceColors1-3, 117 HD may use the unlit colors and
 * lights faces from the vertex normals.
 */
final class FlatModel
{
    private FlatModel() { }

    /** Packed HSL white: no saturation, full lightness. */
    static final int WHITE = 127;

    /** The least white alpha drawn as a layer of its own. */
    static final int MIN_WHITE = 16;

    /** WHITE as sRGB, as 117 HD converts it. */
    private static final float[] WHITE_RGB = rgb(WHITE);

    /**
     * The colour as up to two layers, written to out as {hsl, alpha, white alpha}. With 117 HD's lightness cap
     * (SceneShapeRenderer.hdLightnessCap) a colour lighter than the cap allows is drawn as its hue and
     * saturation at the highest lightness allowed, with white over it at an alpha chosen so that both
     * together look like the colour at the given alpha. Otherwise one layer: white alpha 0.
     */
    static void layers(Color color, int alpha, boolean lightnessCap, int[] out)
    {
        int hsl = JagexColor.rgbToHSL(color.getRGB(), 1.0) & 0xFFFF;
        out[0] = hsl; out[1] = alpha; out[2] = 0;
        if (!lightnessCap || alpha <= 0) { return; }
        int max = maxLightness(hsl >> 7 & 7);
        if ((hsl & 127) <= max) { return; }
        int capped = (hsl & ~127) | max;
        // The colour as a mix of the capped colour and white: C = t * W + (1 - t) * P.
        float[] p = rgb(capped);
        float cr = color.getRed() / 255f, cg = color.getGreen() / 255f, cb = color.getBlue() / 255f;
        float num = (cr - p[0]) * (WHITE_RGB[0] - p[0]) + (cg - p[1]) * (WHITE_RGB[1] - p[1]) + (cb - p[2]) * (WHITE_RGB[2] - p[2]);
        float den = 0;
        for (int i = 0; i < 3; i++) { den += (WHITE_RGB[i] - p[i]) * (WHITE_RGB[i] - p[i]); }
        float t = den > 0 ? Math.max(0, Math.min(1, num / den)) : 0;
        // No white in it (a pure colour just over the cap, such as red): as before, 117 HD caps it itself.
        if (t <= 0) { return; }
        // White at alpha a*t over the capped colour at a*(1-t)/(1-a*t) blends like the colour at alpha a.
        float a = alpha / 255f, whiteAlpha = a * t;
        // So little white that it would not show (cyan: about 7) is left out: it doubled the geometry of every such mark.
        if (whiteAlpha * 255 < MIN_WHITE) { return; }
        float colourAlpha = whiteAlpha >= 1 ? 0 : a * (1 - t) / (1 - whiteAlpha);
        out[0] = capped; out[1] = Math.round(colourAlpha * 255); out[2] = Math.round(whiteAlpha * 255);
    }

    /**
     * The ambient light (linear) a steady colour is made for: between 117 HD's overworld (#97baff, strength 1) and
     * the middle of all its areas. A face with its normal straight down gets no sun and no shadow from 117 HD,
     * only this light, so its colour on the screen no longer changes between sun and shade.
     */
    static final float[] STEADY_AMBIENT = {0.38f, 0.49f, 0.95f};

    /** Per lightness wanted (0-127), the lightness to send for a face with its normal down (see sentDown). */
    private static final int[] SENT_DOWN = new int[128];
    static
    {
        for (int want = 0; want < 128; want++)
        {
            int best = 0;
            for (int l = 1; l < 128; l++)
            {
                if (Math.abs(lightenedDown(l) - want) < Math.abs(lightenedDown(best) - want)) { best = l; }
            }
            SENT_DOWN[want] = best;
        }
    }

    /**
     * What 117 HD's default shading makes of a lightness on a face with its normal straight down, before its cap
     * (undoVanillaShading: it lightens faces the game's own light would have darkened, by the normal).
     */
    static int lightenedDown(int l) { return l + (int) (0.57735026f * (10 - l + (l < 3 ? 0 : (l - 3) * 3))); }

    /** The packed colour to send so that 117 HD draws a face with its normal down in the given packed colour. */
    static int sentDown(int hsl) { return (hsl & ~127) | SENT_DOWN[hsl & 127]; }

    /**
     * As layers, for a face with its normal straight down under 117 HD's default shading: the colour is divided
     * by the ambient light it is made for, the white share of a colour over the lightness cap is solved as both
     * are lit, and the lightness is the one 117 HD turns into the wanted one. Written to out as layers does.
     */
    static void steadyLayers(Color color, int alpha, int[] out)
    {
        float[] c = {color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f};
        int rgb = 0;
        for (int i = 0; i < 3; i++)
        {
            rgb = rgb << 8 | Math.round(encode(Math.min(1, decode(c[i]) / STEADY_AMBIENT[i])) * 255);
        }
        int hsl = JagexColor.rgbToHSL(rgb, 1.0) & 0xFFFF;
        int max = maxLightness(hsl >> 7 & 7);
        boolean over = (hsl & 127) > max;
        int capped = over ? (hsl & ~127) | max : hsl;
        out[0] = sentDown(capped); out[1] = alpha; out[2] = 0;
        if (!over || alpha <= 0) { return; }
        float[] p = lit(rgb(capped)), w = lit(WHITE_RGB);
        float num = 0, den = 0;
        for (int i = 0; i < 3; i++) { num += (c[i] - p[i]) * (w[i] - p[i]); den += (w[i] - p[i]) * (w[i] - p[i]); }
        float t = den > 0 ? Math.max(0, Math.min(1, num / den)) : 0;
        float a = alpha / 255f, whiteAlpha = a * t;
        if (whiteAlpha * 255 < MIN_WHITE) { return; }
        float colourAlpha = whiteAlpha >= 1 ? 0 : a * (1 - t) / (1 - whiteAlpha);
        out[1] = Math.round(colourAlpha * 255); out[2] = Math.round(whiteAlpha * 255);
    }

    /** sRGB 0-1 as 117 HD shows a face of that colour under the steady ambient light alone. */
    static float[] lit(float[] srgb)
    {
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) { out[i] = encode(Math.min(1, decode(srgb[i]) * STEADY_AMBIENT[i])); }
        return out;
    }

    private static float decode(float c) { return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4); }

    private static float encode(float c) { return c <= 0.0031308f ? c * 12.92f : 1.055f * (float) Math.pow(c, 1 / 2.4) - 0.055f; }

    /** 117 HD's lightness cap for a saturation (0-7), as its undoVanillaShading. */
    static int maxLightness(int saturation) { return (int) (127 - 72 * Math.pow(saturation / 7.0, 0.05)); }

    /** Packed HSL as sRGB 0-1, as 117 HD converts it (convertHsl, hslToSrgb). */
    static float[] rgb(int hsl)
    {
        float h = (hsl >> 10 & 63) / 64f + 0.0078125f, s = (hsl >> 7 & 7) / 8f + 0.0625f, l = (hsl & 127) / 128f;
        float q = l < 0.5f ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
        return new float[]{hue(p, q, h + 1 / 3f), hue(p, q, h), hue(p, q, h - 1 / 3f)};
    }

    private static float hue(float p, float q, float t)
    {
        if (t < 0) { t += 1; }
        if (t > 1) { t -= 1; }
        if (t < 1 / 6f) { return p + (q - p) * 6 * t; }
        if (t < 1 / 2f) { return q; }
        if (t < 2 / 3f) { return p + (q - p) * (2 / 3f - t) * 6; }
        return p;
    }

    static void paint(Model m, int face, int hsl, int alpha)
    {
        m.getFaceColors1()[face] = hsl;
        m.getFaceColors2()[face] = hsl;
        // Three equal corner colours draw the face in one colour; -2 hides it. Not -1 ("flat"): for flat
        // faces 117 HD ignores the vertex normals and takes the triangle's own, which turns with the camera.
        m.getFaceColors3()[face] = alpha == 0 ? -2 : hsl;
        byte[] transparencies = m.getFaceTransparencies();
        if (transparencies != null) { transparencies[face] = (byte) (255 - alpha); }
        short[] unlit = m.getUnlitFaceColors();
        if (unlit != null) { unlit[face] = (short) hsl; }
    }

    static void hide(Model m, int face)
    {
        m.getFaceIndices1()[face] = 0;
        m.getFaceIndices2()[face] = 0;
        m.getFaceIndices3()[face] = 0;
        m.getFaceColors3()[face] = -2;
        byte[] transparencies = m.getFaceTransparencies();
        if (transparencies != null) { transparencies[face] = (byte) 255; }
    }

    /** Points every vertex normal straight up (model y is down, unit length 256). */
    static void normals(Model m) { normals(m, false); }

    /** Points every vertex normal straight up, or straight down: away from every sun, so 117 HD's sun and shadows skip it. */
    static void normals(Model m, boolean down)
    {
        int[] nx = m.getVertexNormalsX(), ny = m.getVertexNormalsY(), nz = m.getVertexNormalsZ();
        if (nx == null || ny == null || nz == null) { return; }
        for (int i = 0; i < nx.length; i++) { nx[i] = 0; ny[i] = down ? 256 : -256; nz[i] = 0; }
    }

    /** Recomputes the cached bounding box after vertices changed in place; the bounds cylinder stays fixed. */
    static void bounds(Model m)
    {
        // The box is cached per orientation; switch away and back.
        m.getAABB(1);
        m.getAABB(0);
    }
}
