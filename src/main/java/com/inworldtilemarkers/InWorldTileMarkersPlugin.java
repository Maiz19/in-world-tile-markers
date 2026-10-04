package com.inworldtilemarkers;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.groundmarkers.GroundMarkerPlugin;
import net.runelite.client.plugins.groundmarkers.GroundMarkerOverlay;
import net.runelite.client.plugins.npchighlight.NpcIndicatorsPlugin;
import net.runelite.client.plugins.objectindicators.ObjectIndicatorsPlugin;
import net.runelite.client.ui.overlay.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(name = "In-World Tile Markers", description = "Sharp tile, NPC, object and path markers drawn in the game world, also in stretched mode",
    tags = {"tiles", "markers", "npcs", "objects", "path", "stretched"})
// Better NPC Highlight's Slayer task highlights: the Slayer plugin's targets (SlayerPluginService).
@PluginDependency(net.runelite.client.plugins.slayer.SlayerPlugin.class)
public class InWorldTileMarkersPlugin extends Plugin
{
    /**
     * Hulls, clickboxes and outlines beyond MAX_MODELS (nearest first) fall back to 2D, where RuneLite's outline renderer
     * is far costlier than the scene: many NPCs marked with every style (a field of cows) passed 64 and slowed down.
     */
    static final int MAX_TILES = 1500, MAX_MODELS = 256;
    private static final Logger log = LoggerFactory.getLogger(InWorldTileMarkersPlugin.class);
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private EventBus eventBus;
    @Inject private InWorldTileMarkersConfig config;
    @Inject private MarkerSources sources;
    @Inject private ObjectMarkerSource objectMarkers;
    @Inject private SceneShapeRenderer renderer;
    @Inject private PathTracker paths;
    @Inject private ExternalMarks externalMarks;
    @Inject private AggroAreaSource aggroArea;
    @Inject private IndicatorOverlay overlay;
    @Inject private OverlayManager overlays;
    @Inject private PluginManager plugins;
    @Inject private ConfigManager configManager;
    /** Ground Markers and Object Markers, whose marks In-World Tile Markers draws from their saved settings. */
    private SourcePlugin groundPlugin, objectPlugin;
    /** The other plugins whose marks In-World Tile Markers draws, in the order they are collected. */
    private final List<SourcePlugin> sourcePlugins = new ArrayList<>();
    /**
     * Plugins whose marks are drawn from their own world overlays (TileCapture), by their name in the plugin list: every
     * tile, line, clickbox, hull and timer they draw in the world goes to the scene; their text and icons stay 2D.
     */
    private static final String[] CAPTURED = {
        "Ground Items", "Fishing", "Implings", "Cannon", "Party", "Herbiboar", "Pest Control", "Kourend Library", "Blast Mine",
        "Mage Training Arena", "Woodcutting", "Player Indicators", "Mining", "Motherlode Mine", "Motherlode Mine Improved",
        "Advanced Mining", "StopMisclickingTiles", "Game Tick Info", "Clue Details", "Better NPC Highlight", "Tile Packs",
        "Mahogany Homes", "Rooftop Agility Improved", "Agility", "Stealing Artefacts", "Blast Furnace", "Runecraft",
        "Pyramid Plunder", "Rogues' Den", "Star Info", "The Gauntlet", "Quest Helper", "Shortest Path", "Remaining Amethyst", "Port Tasks",
    };
    /** Sailing's sea overlays; the ones on the boat itself stay its own. */
    private static final java.util.Set<String> SAILING_OVERLAYS = new java.util.HashSet<>(java.util.Arrays.asList("RapidsOverlay",
        "LightningCloudsOverlay", "SalvagingHighlight", "LostCargoHighlighter", "TrueTileIndicator"));
    private volatile boolean running, dirty;
    /**
     * After a failure the marks are 2D for a moment, then tried again: 2 seconds, twice as long after each failure in a
     * row, at most a minute.
     */
    private volatile long failedUntil;
    private long retryDelay = FIRST_RETRY;
    private static final long FIRST_RETRY = 2_000, LAST_RETRY = 60_000;
    /** Each kind of failure is logged once. */
    private final java.util.Set<String> loggedFailures = new java.util.HashSet<>();
    private boolean sceneUnavailable;
    private List<Marker> markers = Collections.emptyList();
    private List<ModelTarget> modelTargets = Collections.emptyList();
    private Marker hover;

    @Provides InWorldTileMarkersConfig provideConfig(ConfigManager manager)
    { return manager.getConfig(InWorldTileMarkersConfig.class); }

    @Override protected void startUp()
    {
        running = true; dirty = true; failedUntil = 0; sceneUnavailable = false;
        // Config and plugin changes while stopped were not delivered to this instance.
        objectMarkers.clearPoints();
        SourcePlugin.pluginsChanged();
        // The shadow transparency notice shows again after turning the plugin on (or installing it).
        warnedShadowTransparency = false;
        warnedQuestHelper = false;
        if (sourcePlugins.isEmpty())
        {
            groundPlugin = new SourcePlugin(overlays, plugins, GroundMarkerPlugin.class.getName(), o -> o instanceof GroundMarkerOverlay);
            // Object Markers' overlay class is not public; RuneLite names overlays after their class.
            objectPlugin = new SourcePlugin(overlays, plugins, ObjectIndicatorsPlugin.class.getName(), o -> "ObjectIndicatorsOverlay".equals(o.getName()));
            // Sailing: its sea overlays; the ones on the boat itself stay its own.
            TileCapture.Index tiles = new TileCapture.Index();
            TileCapture sailing = new TileCapture(client, "sailing:", false, false, tiles);
            sailing.quads = true;
            hold("com.duckblade.osrs.sailing.SailingPlugin",
                o -> SAILING_OVERLAYS.contains(o.getClass().getSimpleName()) && o.getClass().getName().startsWith("com.duckblade.osrs.sailing."),
                sailing).stillShown = o -> sailingStillShown(o.getClass().getSimpleName());
            hold(AggroAreaSource.PLUGIN, o -> o.getClass().getName().equals(AggroAreaSource.OVERLAY), aggroArea);
            SourcePlugin rooftops = null;
            for (String name : CAPTURED)
            {
                // Quest Helper's and Port Tasks' lines may end anywhere on the ground, not only at tile centres.
                boolean groundLines = name.equals("Quest Helper") || name.equals("Port Tasks");
                TileCapture capture = new TileCapture(client, name.toLowerCase().replaceAll("[^a-z]", "") + ":", groundLines, true, tiles);
                SourcePlugin h = hold(name, o -> captures(name, o), capture);
                switch (name)
                {
                    case "Rooftop Agility Improved":
                        // Its highlights are drawn over the others, and the Agility plugin's marks for them are left to it.
                        capture.markLayer = Marker.OBJECT + 1;
                        capture.modelLayer = SceneShapeRenderer.HULL_LAYER + 1;
                        rooftops = h;
                        break;
                    case "Agility": capture.yieldTo = rooftops; capture.range = 2350; break;
                    // Plugins that draw their marks only near the player (Extend plugin ranges keeps them further).
                    case "Blast Furnace": case "Pyramid Plunder": capture.range = 2350; break;
                    case "Tile Packs": capture.range = 32 * 128; capture.squareRange = true; break;
                    // No player tiles in PvP: there Player Indicators draws them itself, as it always would.
                    case "Player Indicators":
                        capture.usableWhen = () -> !WorldType.isPvpWorld(client.getWorldType()) && client.getVarbitValue(net.runelite.api.gameval.VarbitID.INSIDE_WILDERNESS) == 0;
                        break;
                    // Better NPC Highlight: the NPCs it highlights by name or as the Slayer task; NPCs only in its ID
                    // lists stay its own drawing.
                    case "Better NPC Highlight": capture.npcs = this::namedInBetterNpcHighlight; break;
                    // Shortest Path's debug overlays (transports, collision map) are no path: then its overlay stays its own.
                    case "Shortest Path":
                        capture.usableWhen = () -> !"true".equals(configManager.getConfiguration("shortestpath", "drawTransports"))
                            && !"true".equals(configManager.getConfiguration("shortestpath", "drawCollisionMap"));
                        break;
                    default: break;
                }
            }
        }
        overlays.add(overlay);
        paths.startUp();
        for (Object o : subscribers()) { eventBus.register(o); }
    }

    @Override protected void shutDown()
    {
        running = false;
        overlays.remove(overlay);
        for (Object o : subscribers()) { eventBus.unregister(o); }
        paths.shutDown();
        externalMarks.clear();
        clientThread.invoke(() -> { if (!running) { reset(true); sources.clear(); } });
    }

    private Object[] subscribers() { return new Object[]{paths, externalMarks}; }

    private SourcePlugin hold(String plugin, Predicate<Overlay> match, SourcePlugin.Source... sources)
    {
        SourcePlugin h = new SourcePlugin(overlays, plugins, plugin, match, sources);
        sourcePlugins.add(h);
        return h;
    }

    private void reset() { reset(false); }

    /**
     * Drops every mark. The scene objects' models are kept for reuse (a scene load needed them all again, and making
     * and uploading them anew stalled the first frames after it), except when the plugin stops or fails (all).
     */
    private void reset(boolean all)
    {
        if (all) { renderer.reset(); } else { renderer.clear(); }
        markers = Collections.emptyList();
        modelTargets = Collections.emptyList();
        hover = null;
        if (groundPlugin != null) { groundPlugin.reset(); objectPlugin.reset(); }
        for (SourcePlugin h : sourcePlugins) { h.reset(); }
    }



    /** Everything from the scene again (after a load or a change). */
    private void rebuild()
    {
        reset();
        sources.rebuild();
        dirty = false;
    }

    /** Whether In-World Tile Markers draws this overlay of the plugin with this name: one it draws in the world, but... */
    static boolean captures(String name, Overlay o)
    {
        // ...of The Gauntlet its maze only, not the boss fight; of Quest Helper not its arrows, which point from the screen.
        return SourcePlugin.inWorld(o) && (!name.equals("The Gauntlet") || o.getClass().getName().contains(".maze."))
            && !(name.equals("Quest Helper") && o.getClass().getSimpleName().contains("Arrow"));
    }

    @Subscribe public void onPostClientTick(PostClientTick event)
    {
        if (!running || client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) { return; }
        try
        {
            // Not while the marks rest after a failure: the scene is read again when they are tried again.
            if (dirty && !failed()) { rebuild(); }
            sources.groundEnabled = enabled(GroundMarkerPlugin.class);
            sources.objectsEnabled = enabled(ObjectIndicatorsPlugin.class);
            sources.npcsEnabled = enabled(NpcIndicatorsPlugin.class);
            TileCapture.extendTo = config.extendRanges() ? MarkerSources.drawDistance(config) : 0;
            // Replace the original overlays unless the scene route has actually failed. Without GPU
            // In-World Tile Markers draws 2D itself; merely not having drawn a frame yet (start-up) is not a failure.
            boolean drawing = !client.isGpu() || sceneActive();
            // The integrations below add to these lists.
            List<Marker> tiles = sources.collect(pathTiles());
            List<ModelTarget> models = sources.modelTargets();
            // Other plugins' marks: only while that plugin runs and the scene route works; otherwise its own overlay draws.
            for (SourcePlugin h : sourcePlugins) { h.collect(sceneActive() && config.otherPlugins(), tiles, models); }
            warnQuestHelperOutlines();
            // Marks other plugins sent through PluginMessage.
            externalMarks.collect(tiles, models);
            markers = tiles;
            modelTargets = models;
            groundPlugin.update(config.ground() && config.replaceGround() && sources.validGround() && drawing);
            objectPlugin.update(config.objectMarkers() && config.replaceObjectMarkers() && sources.validObjects() && drawing);
        }
        catch (RuntimeException ex)
        {
            fail(ex);
        }
    }

    /**
     * Game ticks since logging in: nothing is drawn before the first, when the camera is not yet set and a mark was
     * drawn as a large square for a frame. Only after a login, not after loading a new area, which then drew nothing
     * for a moment. Counted here, as the client's tick count restarts at login; starts high, so turning the plugin on
     * while logged in draws at once.
     */
    private int ticksSinceLogin = Integer.MAX_VALUE;
    /** Time per frame for making models ahead while logging in. */
    private static final long PREWARM_NANOS = 3_000_000;
    /** Logging in, until logged in: a login may pass through LOADING first. */
    private boolean loggingIn;

    @Subscribe public void onBeforeRender(BeforeRender event)
    {
        if (!running || dirty || client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) { return; }
        if (ticksSinceLogin < 1)
        {
            renderer.clear();
            // Nothing is drawn yet: the scene objects' models are made now, a few per frame, not all in the first frame.
            if (client.isGpu() && !failed()) { renderer.prewarm(PREWARM_NANOS); }
            return;
        }
        if (!client.isGpu() || failed())
        {
            renderer.clear();
            return;
        }
        try
        {
            Player player = client.getLocalPlayer();
            ModelShapes.Camera camera = ModelShapes.Camera.of(client);
            LocalPoint xray = config.tilesThroughWalls() ? xrayAnchor(camera, player) : null;
            // 117 HD's shadow settings: without its shadows nothing needs stacking.
            boolean hdShadows = hdActive() && !"false".equals(configManager.getConfiguration("hd", "shadowsEnabled"));
            boolean transparentShadows = !"false".equals(configManager.getConfiguration("hd", "enableShadowTransparency"));
            // With its shadow transparency on every visible face casts (threshold 0.01): nothing to gain there.
            boolean capped = hdShadows && !transparentShadows;
            renderer.floatingAlphaCap = capped ? SceneShapeRenderer.HD_CAP_OPAQUE_SHADOWS : 255;
            // 117 HD's default shading caps the lightness of coloured faces; with its legacy grey colours even grey is
            // capped, so white cannot help there.
            String shading = configManager.getConfiguration("hd", "experimentalShadingMode");
            renderer.hdLightnessCap = hdActive() && (shading == null || "DEFAULT".equals(shading))
                && !"true".equals(configManager.getConfiguration("hd", "legacyGreyColors"));
            if (hdShadows && transparentShadows) { warnShadowTransparency(); }
            renderer.begin(camera, 1f / stretchScale(), xray, player.getWorldView().getPlane());
            hover = sources.hover();
            // The player's silhouette is left uncovered by marks through walls.
            renderer.cutAround(config.charactersInFrontOfTiles() ? player : null);
            for (Marker m : nearest(markers, player.getLocalLocation(), MAX_TILES)) { renderer.tile(m); }
            // The hovered tile last: shapes of one tile are drawn in the order they are added, so it lies over the
            // tiles under it with its own opacity.
            if (hover != null && !config.hoveredTileIn2d()) { renderer.tile(hover); }
            for (int i = 0; i < Math.min(MAX_MODELS, modelTargets.size()); i++) { renderer.model(modelTargets.get(i)); }
            boolean ok = renderer.end();
            sceneUnavailable = !ok;
            // A minute without failing: the next failure rests the shortest time again.
            if (ok && retryDelay > FIRST_RETRY && System.currentTimeMillis() > failedUntil + LAST_RETRY) { retryDelay = FIRST_RETRY; }
        }
        catch (RuntimeException ex)
        {
            fail(ex);
        }
    }

    /**
     * Where through-walls marks hang. The client draws see-through models zone by zone (8x8 tiles), per level,
     * from the zone whose centre is farthest from the camera to the nearest; 117 HD then sorts within a zone by
     * distance to the camera. Marks on the player's tile were drawn before see-through objects nearer the
     * camera, such as tree leaves, which then covered their fill. So they hang in the zone nearest the camera
     * that is surely drawn: on a tile you can walk to whose centre is on screen. The renderers draw a zone's
     * see-through models only while the zone is in view (a zone beside ground in view need not be, on a slope or at
     * some camera angles); 117 HD draws nothing outside the area you are in (a dungeon's other parts); a house's empty
     * zones have no tiles. The player's tile if there is none.
     */
    private LocalPoint xrayAnchor(ModelShapes.Camera camera, Player player)
    {
        WorldView wv = client.getTopLevelWorldView();
        LocalPoint at = player.getLocalLocation();
        if (player.getWorldView() != wv) { return at; }
        int plane = wv.getPlane();
        // Found again as you move, and every tick (doors open and close).
        long key = ((long) java.util.Objects.hash(at.getSceneX(), at.getSceneY(), plane, wv.getBaseX(), wv.getBaseY()) << 32) | client.getTickCount();
        if (key != anchorTilesKey)
        {
            anchorTilesKey = key;
            anchorTiles = anchorRoutes.load(wv) ? anchorRoutes.reachable(at.getSceneX(), at.getSceneY(), ANCHOR_STEPS) : Collections.emptyList();
        }
        int left = client.getViewportXOffset(), top = client.getViewportYOffset();
        int right = left + client.getViewportWidth(), bottom = top + client.getViewportHeight();
        float[] p = new float[3];
        // A house's empty zones may read as open ground, but have no tiles.
        Tile[][] scene = wv.getScene().getTiles()[plane];
        int[] anchor = nearestDrawnTile(camera.x, camera.y, anchorTiles, (x, y) -> {
            if (x < 0 || y < 0 || x >= scene.length || y >= scene[x].length || scene[x][y] == null) { return false; }
            camera.project(x * 128 + 64, y * 128 + 64, Terrain.height(wv, x * 128 + 64, y * 128 + 64, plane), p);
            return p[2] >= ModelShapes.NEAR && p[0] >= left && p[0] < right && p[1] >= top && p[1] < bottom;
        });
        return anchor == null ? at : new LocalPoint(anchor[0], anchor[1], wv);
    }

    /**
     * Beyond max marks, the nearest to at are drawn in the scene and the rest keep their 2D fallback; in their own
     * order, which decides what lies on top on one tile.
     */
    static List<Marker> nearest(List<Marker> marks, LocalPoint at, int max)
    {
        if (marks.size() <= max || at == null) { return marks; }
        int[] distances = new int[marks.size()];
        for (int i = 0; i < distances.length; i++) { distances[i] = distance(marks.get(i), at); }
        int[] sorted = distances.clone();
        java.util.Arrays.sort(sorted);
        List<Marker> out = new ArrayList<>(max);
        for (int i = 0; i < distances.length && out.size() < max; i++) { if (distances[i] <= sorted[max - 1]) { out.add(marks.get(i)); } }
        return out;
    }

    /** How far a mark lies from a point, in local units along both axes. */
    private static int distance(Marker m, LocalPoint at) { return Math.abs(m.point.getX() - at.getX()) + Math.abs(m.point.getY() - at.getY()); }

    private static final int ANCHOR_STEPS = 16;
    /** The tiles you can walk to near you, for xrayAnchor, and what they were found for. */
    private final RouteFinder anchorRoutes = new RouteFinder();
    private List<int[]> anchorTiles = Collections.emptyList();
    private long anchorTilesKey = Long.MIN_VALUE;

    /**
     * Of these tiles (scene x, y), those on screen, the one nearest the camera in the zone whose centre is nearest the
     * camera, the one the client draws last: its local {x, y}; null without one.
     */
    static int[] nearestDrawnTile(float camX, float camY, List<int[]> tiles, java.util.function.BiPredicate<Integer, Integer> onScreen)
    {
        int[] best = null;
        double bestZone = Double.MAX_VALUE, bestTile = Double.MAX_VALUE;
        for (int[] t : tiles)
        {
            double zone = Math.pow((t[0] >> 3) * 1024 + 512 - camX, 2) + Math.pow((t[1] >> 3) * 1024 + 512 - camY, 2);
            double tile = Math.pow(t[0] * 128 + 64 - camX, 2) + Math.pow(t[1] * 128 + 64 - camY, 2);
            if ((zone < bestZone || zone == bestZone && tile < bestTile) && onScreen.test(t[0], t[1])) { best = t; bestZone = zone; bestTile = tile; }
        }
        return best == null ? null : new int[]{best[0] * 128 + 64, best[1] * 128 + 64};
    }

    /** The path tiles, with a predicted path while a walk target is predicted (PathTracker.markers). */
    List<Marker> pathTiles()
    {
        return paths.markers(sources.predictedTarget(client.getLocalPlayer(), System.currentTimeMillis()));
    }

    private void fail(RuntimeException ex)
    {
        StackTraceElement[] at = ex.getStackTrace();
        if (loggedFailures.add(ex.getClass().getName() + (at.length > 0 ? at[0] : "")))
        { log.warn("Scene markers failed; 2D markers for a moment, then they are tried again", ex); }
        failedUntil = System.currentTimeMillis() + retryDelay;
        retryDelay = Math.min(LAST_RETRY, retryDelay * 2);
        reset(true);
    }

    /** Canvas pixels per screen pixel is 1 / this; the GPU scene is rasterized at the stretched size. */
    private float stretchScale()
    {
        if (!client.isStretchedEnabled()) { return 1f; }
        java.awt.Dimension real = client.getRealDimensions(), stretched = client.getStretchedDimensions();
        if (real == null || stretched == null || real.width <= 0 || stretched.width <= 0) { return 1f; }
        return stretched.width / (float) real.width;
    }

    /** Whether Sailing itself would still show this overlay: its feature toggles, as their isEnabled. */
    private boolean sailingStillShown(String overlay)
    {
        java.util.function.Predicate<String> on = key -> !"false".equals(configManager.getConfiguration("sailing", key));
        switch (overlay)
        {
            case "RapidsOverlay": return on.test("highlightRapids");
            case "LightningCloudsOverlay": return on.test("highlightLightningCloudStrikes");
            case "LostCargoHighlighter": return on.test("barracudaHighlightLostCrates");
            case "SalvagingHighlight": return on.test("salvagingHighlightActiveWrecks") || on.test("salvagingHighlightInactiveWrecks")
                || "true".equals(configManager.getConfiguration("sailing", "salvagingHideHighLevelWrecks"));
            case "TrueTileIndicator":
            {
                String mode = configManager.getConfiguration("sailing", "navigationTrueTileIndicator");
                return mode != null && !"OFF".equals(mode);
            }
            default: return true;
        }
    }

    /** Walk here: remember the clicked tile for the predicted destination. */
    @Subscribe public void onMenuOptionClicked(MenuOptionClicked e)
    {
        if (e.getMenuAction() != MenuAction.WALK || client.getLocalPlayer() == null) { return; }
        WorldView wv = client.getLocalPlayer().getWorldView();
        Tile tile = wv.getSelectedSceneTile();
        WorldPoint target = tile != null ? tile.getWorldLocation() : null;
        if (target == null && config.predictWalk() && client.getMouseCanvasPosition() != null)
        {
            // No tile under the mouse, such as 117 HD's extended terrain: find where the click meets the ground.
            target = WalkPredictor.raycast(ModelShapes.Camera.of(client), client.getMouseCanvasPosition().getX(), client.getMouseCanvasPosition().getY(),
                wv, wv.getPlane());
        }
        if (target != null) { sources.walkedTo(target); }
    }

    @Subscribe public void onGameStateChanged(GameStateChanged e)
    {
        if (e.getGameState() != GameState.LOGGED_IN) { reset(); sources.clear(); dirty = true; failedUntil = 0; }
        else if (loggingIn) { ticksSinceLogin = 0; loggingIn = false; }
        if (e.getGameState() == GameState.LOGGING_IN) { loggingIn = true; }
        else if (e.getGameState() == GameState.LOGIN_SCREEN) { loggingIn = false; }
    }
    /**
     * A boat (a world view inside the scene) is scanned on its own when it loads, and forgotten when it unloads:
     * sailing loads and unloads them all the time, and a full reset turned every mark 2D for a few frames each time.
     */
    @Subscribe public void onWorldViewLoaded(WorldViewLoaded e)
    {
        WorldView wv = e.getWorldView();
        if (wv != null && wv != client.getTopLevelWorldView() && !dirty) { sources.addWorldView(wv); return; }
        dirty = true;
    }

    @Subscribe public void onWorldViewUnloaded(WorldViewUnloaded e)
    {
        WorldView wv = e.getWorldView();
        sources.removeWorldView(wv);
        if (wv != null && wv != client.getTopLevelWorldView()) { return; }
        reset(); dirty = true;
    }
    @Subscribe public void onProfileChanged(ProfileChanged e)
    {
        clientThread.invoke(() -> {
            if (running)
            {
                objectMarkers.clearPoints();
                reset(); dirty = true; failedUntil = 0;
            }
        });
    }
    /**
     * Better NPC Highlight's name lists (lower case, wildcards), read once per change of its settings, and per NPC name
     * whether one matches (WildcardMatcher compiles a pattern per call).
     */
    private List<String> betterNpcNames;
    private final java.util.Map<String, Boolean> betterNpcNamed = new java.util.HashMap<>();
    @Inject private net.runelite.client.plugins.slayer.SlayerPluginService slayer;

    private boolean namedInBetterNpcHighlight(NPC npc)
    {
        if (betterNpcNames == null)
        {
            betterNpcNamed.clear();
            List<String> names = new ArrayList<>();
            for (String style : new String[]{"tile", "trueTile", "swTile", "swTrueTile", "hull", "area", "outline", "clickbox"})
            {
                String list = configManager.getConfiguration("BetterNpcHighlight", style + "Names");
                // Comma-separated; a comma in a name is escaped; an entry may end in ":preset".
                for (String entry : list == null ? new String[0] : list.split("(?<!\\\\),"))
                {
                    String name = entry.replace("\\,", ",").trim().toLowerCase().split(":")[0];
                    if (!name.isEmpty()) { names.add(name); }
                }
            }
            betterNpcNames = names;
        }
        List<String> patterns = betterNpcNames;
        String name = npc.getName();
        return name != null && betterNpcNamed.computeIfAbsent(name, n -> patterns.stream()
            .anyMatch(pattern -> net.runelite.client.util.WildcardMatcher.matches(pattern, n.toLowerCase())))
            || slayer.getTargets().contains(npc);
    }

    /** Whether 117 HD runs; checked on plugin changes, not every frame. */
    private Boolean hd;

    private boolean hdActive()
    {
        if (hd == null) { hd = active("rs117.hd.HdPlugin"); }
        return hd;
    }

    @Subscribe public void onPluginChanged(PluginChanged e)
    {
        hd = null;
        SourcePlugin.pluginsChanged();
        dirty = true;
        failedUntil = 0;
        // Quest Helper turned on: its highlight styles are checked again for the notice.
        if (e.isLoaded() && e.getPlugin().getClass().getName().equals(QUEST_HELPER)) { warnedQuestHelper = false; }
    }
    @Inject private net.runelite.client.chat.ChatMessageManager chatMessages;
    /**
     * The shadow transparency notice is shown once after the plugin starts, and again when 117 HD's
     * Shadow transparency is turned back on.
     */
    private boolean warnedShadowTransparency;

    /**
     * 117 HD with its "Shadow transparency" on lets every visible face cast a shadow, so marks drawn
     * through walls cast shadows that move with the camera: the player is told once, in the chat box.
     */
    private void warnShadowTransparency()
    {
        if (warnedShadowTransparency) { return; }
        warnedShadowTransparency = true;
        notice("turn off \"Shadow transparency\" in 117 HD's settings, or marks drawn through walls cast shadows that move with the camera.");
    }

    /** A notice in the chat box, local only. */
    private void notice(String text)
    {
        chatMessages.queue(net.runelite.client.chat.QueuedMessage.builder().type(ChatMessageType.CONSOLE)
            .runeLiteFormattedMessage(new net.runelite.client.chat.ChatMessageBuilder().append(net.runelite.client.chat.ChatColorType.HIGHLIGHT)
                .append("In-World Tile Markers: ").append(net.runelite.client.chat.ChatColorType.NORMAL).append(text).build()).build());
    }

    /** The Quest Helper notice is shown once after the plugin starts (or is installed), while logged in. */
    private boolean warnedQuestHelper;
    private static final String QUEST_HELPER = "com.questhelper.QuestHelperPlugin", QUEST_HELPER_GROUP = "questhelper";

    /**
     * Quest Helper's default outline style is drawn by RuneLite's outline renderer, past any overlay's graphics, so it
     * stays 2D; its convex hull and click box styles do not. The player is told once, in the chat box.
     */
    /** Whether a core plugin is switched on, found in the plugin list. */
    private boolean enabled(Class<? extends Plugin> type)
    {
        for (Plugin p : plugins.getPlugins()) { if (type.isInstance(p)) { return plugins.isPluginEnabled(p); } }
        return false;
    }

    /** Whether the plugin of this class runs. */
    private boolean active(String type)
    {
        for (Plugin p : plugins.getPlugins()) { if (p.getClass().getName().equals(type)) { return plugins.isPluginActive(p); } }
        return false;
    }

    private void warnQuestHelperOutlines()
    {
        if (warnedQuestHelper || config.ignoreQuestHelperWarning() || !sceneActive()) { return; }
        // Checked once; turning Quest Helper on or changing its styles checks again (onPluginChanged, onConfigChanged).
        warnedQuestHelper = true;
        if (!active(QUEST_HELPER)) { return; }
        List<String> outlined = new ArrayList<>();
        // Unset means its default, OUTLINE.
        String[][] styles = {{"highlightStyleNpcs", "NPCs"}, {"highlightStyleObjects", "objects"}, {"highlightStyleGroundItems", "ground items"}};
        for (String[] style : styles)
        {
            String value = configManager.getConfiguration(QUEST_HELPER_GROUP, style[0]);
            if (value == null || "OUTLINE".equals(value)) { outlined.add(style[1]); }
        }
        if (outlined.isEmpty()) { return; }
        notice("Quest Helper highlights " + String.join(", ", outlined) + " as outlines, which cannot be drawn sharp."
            + " Set its highlight style to \"Convex hull\" (NPCs) or \"Click box\" (objects, ground items) for sharp highlights."
            + " Turn this notice off with \"Ignore Quest Helper notice\" in In-World Tile Markers' settings.");
    }

    @Subscribe public void onConfigChanged(ConfigChanged e)
    {
        // A plugin may add or remove an overlay for a setting (Sailing): its overlays are looked for again.
        SourcePlugin.pluginsChanged();
        if ("hd".equals(e.getGroup()) && "enableShadowTransparency".equals(e.getKey())) { warnedShadowTransparency = false; return; }
        if (QUEST_HELPER_GROUP.equals(e.getGroup()) && e.getKey().startsWith("highlightStyle")) { warnedQuestHelper = false; return; }
        // Settings change on the thread of the settings panel; what the client thread uses is reset there.
        if ("BetterNpcHighlight".equals(e.getGroup())) { clientThread.invokeLater(() -> { betterNpcNames = null; }); return; }
        String group = e.getGroup();
        // In-World Tile Markers' own settings are read every tick or frame: no rebuild (dragging a colour picker sent one
        // per step), only another try after a failure.
        if (group.equals(InWorldTileMarkersConfig.GROUP)) { failedUntil = 0; return; }
        // Marks and settings of the plugins In-World Tile Markers reads: rebuild when they change.
        if (group.equals(ObjectMarkerSource.GROUP)) { clientThread.invokeLater(objectMarkers::clearPoints); }
        if (group.equals("groundMarker") || group.equals(ObjectMarkerSource.GROUP))
        { dirty = true; failedUntil = 0; }
        // NPC Indicators (a tag, Tag-All, its styles): only its NPCs are matched again, the shapes drawn so far are kept.
        // A full rebuild per tag made the renderer start over, the stall of the first frames after a scene load each time.
        if (group.equals(net.runelite.client.plugins.npchighlight.NpcIndicatorsConfig.GROUP))
        {
            failedUntil = 0;
            clientThread.invokeLater(() -> { if (running && !dirty) { sources.refreshNpcs(); } });
        }
    }
    @Subscribe public void onGameTick(GameTick e) { if (ticksSinceLogin < Integer.MAX_VALUE) { ticksSinceLogin++; } }
    @Subscribe public void onHitsplatApplied(HitsplatApplied e) { sources.hitsplat(e.getActor(), System.currentTimeMillis()); }
    @Subscribe public void onNpcSpawned(NpcSpawned e) { sources.add(e.getNpc()); }
    @Subscribe public void onNpcChanged(NpcChanged e) { sources.add(e.getNpc()); }
    @Subscribe public void onNpcDespawned(NpcDespawned e) { sources.remove(e.getNpc()); }
    @Subscribe public void onGameObjectSpawned(GameObjectSpawned e) { sources.add(e.getGameObject()); }
    @Subscribe public void onGameObjectDespawned(GameObjectDespawned e) { sources.remove(e.getGameObject()); }
    @Subscribe public void onWallObjectSpawned(WallObjectSpawned e) { sources.add(e.getWallObject()); }
    @Subscribe public void onWallObjectDespawned(WallObjectDespawned e) { sources.remove(e.getWallObject()); }
    @Subscribe public void onDecorativeObjectSpawned(DecorativeObjectSpawned e) { sources.add(e.getDecorativeObject()); }
    @Subscribe public void onDecorativeObjectDespawned(DecorativeObjectDespawned e) { sources.remove(e.getDecorativeObject()); }
    @Subscribe public void onGroundObjectSpawned(GroundObjectSpawned e) { sources.add(e.getGroundObject()); }
    @Subscribe public void onGroundObjectDespawned(GroundObjectDespawned e) { sources.remove(e.getGroundObject()); }

    List<Marker> markers() { return markers; }
    List<ModelTarget> modelTargets() { return modelTargets; }
    boolean hoverIn2d() { return config.hoveredTileIn2d(); }



    /** The hovered tile for the overlay: read at overlay time when drawn in 2D, for zero delay. */
    Marker hover() { return config.hoveredTileIn2d() || !client.isGpu() || failed() ? sources.hover() : hover; }
    boolean replacedGround() { return groundPlugin != null && groundPlugin.drawing(); }

    /** Runs the paused overlays of the other plugins once (IndicatorOverlay): their marks go to the scene, the rest is drawn on g. */
    void renderCaptured(java.awt.Graphics2D g) { for (SourcePlugin h : sourcePlugins) { h.render(g); } }

    /** The scene route works: GPU on, no rendering error, carrier models available. */
    boolean sceneActive() { return client.isGpu() && !failed() && !sceneUnavailable; }

    private boolean failed() { return System.currentTimeMillis() < failedUntil; }

    /** Tile markers go to 2D only without the scene route or above the tile limit. */
    boolean tilesIn2d() { return !sceneActive() || markers.size() > MAX_TILES; }

    /** Includes intentionally culled offscreen models; omitted/unsupported marks stay in 2D. */
    boolean markerInScene(String key) { return sceneActive() && renderer.drawn(key); }

    java.util.Set<TileObject> objectOutlinesInScene()
    {
        java.util.Set<TileObject> handled = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Set<TileObject> missing = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (ModelTarget t : modelTargets)
        {
            if (t.object != null && t.outline)
            {
                if (markerInScene(t.key)) { handled.add(t.object); }
                else { missing.add(t.object); }
            }
        }
        handled.removeAll(missing);
        return handled;
    }

}
