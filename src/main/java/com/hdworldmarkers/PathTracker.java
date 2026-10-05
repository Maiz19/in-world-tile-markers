package com.hdworldmarkers;

import static net.runelite.api.MenuAction.*;

import com.hdworldmarkers.HdWorldMarkersConfig.DrawLocations;
import com.hdworldmarkers.HdWorldMarkersConfig.DrawMode;
import com.hdworldmarkers.HdWorldMarkersConfig.MarkerStyle;
import com.hdworldmarkers.HdWorldMarkersConfig.PathDisplaySetting;
import com.hdworldmarkers.RouteFinder.Target;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.function.BiConsumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.*;
import net.runelite.client.ui.overlay.*;

/**
 * The walking paths, with Path Marker's options: the active path, where your last click makes you walk, and the hover
 * path, where a left click would. Routes come from RouteFinder; every game tick the active path is checked against
 * where you are. The plugin draws the paths' scene tiles (markers); the minimap is drawn here.
 */
@Singleton
final class PathTracker extends Overlay implements KeyListener
{
    /** Options that are never the left click (they can sit above it) and options that never walk. */
    private static final Set<MenuAction> NOT_LEFT_CLICK = EnumSet.of(EXAMINE_ITEM_GROUND, EXAMINE_NPC, EXAMINE_OBJECT, RUNELITE,
        RUNELITE_HIGH_PRIORITY, RUNELITE_INFOBOX, RUNELITE_OVERLAY, RUNELITE_OVERLAY_CONFIG, RUNELITE_PLAYER), NOT_WALKING = EnumSet.of(CANCEL,
        CC_OP, CC_OP_LOW_PRIORITY, PLAYER_EIGHTH_OPTION, WIDGET_CLOSE, WIDGET_CONTINUE, WIDGET_FIRST_OPTION, WIDGET_SECOND_OPTION,
        WIDGET_THIRD_OPTION, WIDGET_FOURTH_OPTION, WIDGET_FIFTH_OPTION, WIDGET_TARGET, WIDGET_TARGET_ON_WIDGET, WIDGET_TYPE_1,
        WIDGET_TYPE_4, WIDGET_TYPE_5);
    /** Options on objects: where they walk, the client's destination tells best (see watching). */
    private static final Set<MenuAction> OBJECT_OPTIONS = EnumSet.of(GAME_OBJECT_FIRST_OPTION, GAME_OBJECT_SECOND_OPTION,
        GAME_OBJECT_THIRD_OPTION, GAME_OBJECT_FOURTH_OPTION, GAME_OBJECT_FIFTH_OPTION, WIDGET_TARGET_ON_GAME_OBJECT);
    /** Tiles a predicted path has at most. */
    static final int MAX_PREDICTED = 300;
    @Inject private Client client;
    @Inject private HdWorldMarkersConfig config;
    @Inject private OverlayManager overlays;
    @Inject private KeyManager keys;
    @Inject private MouseManager mouse;
    RouteFinder routes = new RouteFinder();
    /** The active and hover paths, and a predicted one to a walk target beyond what the client routes. */
    private final Path activePath = new Path(), hoverPath = new Path(), predictedPath = new Path();
    /**
     * Your tile at the last game tick; a click waiting for the next client tick, with the tile under an open menu. The
     * click is copied: RuneLite's menu entries show the menu as it is now, rebuilt by then.
     */
    private WorldPoint lastTile;
    private Click click;
    private Tile clickTile, lastHovered;
    /** A click found no route of its own: the active path follows where the client walks instead. */
    private boolean toDestination;
    /**
     * Client ticks (a game tick's worth) after a click on an object in which the active path takes the client's
     * destination once the click changes it: which side of an obstacle the game walks to, its own rules decide, and the
     * route found here first could lead around it until the next game tick. The destination before the click.
     */
    private int watching;
    private LocalPoint clickDestination;

    /** A clicked option: its kind, its parameters (scene x and y), its id and its character. */
    private static final class Click
    {
        final MenuAction type;
        final int x, y, id;
        final Actor actor;
        Click(MenuEntry e) { type = e.getType(); x = e.getParam0(); y = e.getParam1(); id = e.getIdentifier(); actor = e.getActor(); }
    }
    private Point mouseAt;
    /**
     * A route found at a click may start a tick late (fresh until found again once); mismatch: last tick you were not
     * where the active path put you. pressed: a left press, which may be a minimap click (it has no menu option).
     */
    private boolean fresh, mismatch, clickInMenu, clickRunning, ctrl, keyActive, keyHover, pressed;
    private final MouseAdapter mouseListener = new MouseAdapter()
    {
        @Override public MouseEvent mousePressed(MouseEvent e)
        {
            if (e.getButton() == MouseEvent.BUTTON1) { pressed = true; mouseAt = client.getMouseCanvasPosition(); }
            return e;
        }
    };

    {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    /** A route on show: what it leads to and from where, its turns, and the tiles a walk along it stands on or runs past. */
    static final class Path
    {
        Target target;
        WorldPoint from;
        List<WorldPoint> turns = Collections.emptyList();
        boolean running;
        final List<WorldPoint> main = new ArrayList<>(), passed = new ArrayList<>();

        void clear()
        {
            target = null; from = null; turns = Collections.emptyList();
            main.clear(); passed.clear();
        }
    }

    void startUp()
    {
        activePath.clear(); hoverPath.clear(); predictedPath.clear();
        click = null;
        ctrl = keyActive = keyHover = pressed = false;
        overlays.add(this);
        keys.registerKeyListener(this);
        mouse.registerMouseListener(mouseListener);
    }

    void shutDown()
    {
        overlays.remove(this);
        keys.unregisterKeyListener(this);
        mouse.unregisterMouseListener(mouseListener);
    }

    @Override public void keyTyped(KeyEvent e) { }
    @Override public void keyPressed(KeyEvent e) { key(e, true); }
    @Override public void keyReleased(KeyEvent e) { key(e, false); }

    /** Ctrl changes running; a path's keybind shows it while held, or toggles it, as its display option says. */
    private void key(KeyEvent e, boolean down)
    {
        if (e.getKeyCode() == KeyEvent.VK_CONTROL) { ctrl = down; }
        if (config.displayKeybindActivePath().matches(e)) { keyActive = keyed(config.activePathDisplaySetting(), keyActive, down); }
        if (config.displayKeybindHoverPath().matches(e)) { keyHover = keyed(config.hoverPathDisplaySetting(), keyHover, down); }
    }

    private static boolean keyed(PathDisplaySetting display, boolean shown, boolean down)
    {
        return display == PathDisplaySetting.WHILE_KEY_PRESSED ? down : display == PathDisplaySetting.TOGGLE_ON_KEYPRESS && down ? !shown : shown;
    }

    /** Whether a click now runs: the run option, which holding Ctrl inverts or overrides as the game's Ctrl option says. */
    private boolean runOnClick()
    {
        boolean run = client.getVarpValue(VarPlayerID.OPTION_RUN) == 1;
        switch (ctrl ? client.getVarbitValue(VarbitID.RUNINVERT_MODE) : 0)
        {
            case 1: return true;
            case 2: return false;
            case 3: return !run;
            default: return run;
        }
    }

    /** Running on the active path, or for a click now when there is none. */
    boolean running() { return activePath.turns.isEmpty() ? runOnClick() : activePath.running; }

    /** A key released while the client had no focus never arrives: Ctrl would stay held. */
    @Subscribe public void onFocusChanged(FocusChanged e) { if (!e.isFocused()) { ctrl = false; } }

    @Subscribe public void onVarbitChanged(VarbitChanged e)
    {
        if (e.getVarpId() == VarPlayerID.OPTION_RUN) { activePath.running = e.getValue() == 1; }
    }

    @Subscribe public void onGameStateChanged(GameStateChanged e)
    {
        if (e.getGameState() == GameState.HOPPING || e.getGameState() == GameState.LOGGING_IN) { activePath.clear(); }
    }

    /**
     * Found on the next client tick, when the game knows the tile a left-click Walk here goes to; an open menu keeps
     * the tile it was opened on.
     */
    @Subscribe public void onMenuOptionClicked(MenuOptionClicked e)
    {
        click = new Click(e.getMenuEntry());
        clickDestination = client.getLocalDestinationLocation();
        clickInMenu = client.isMenuOpen();
        clickTile = lastHovered;
        clickRunning = runOnClick();
    }

    @Subscribe public void onClientTick(ClientTick e)
    {
        Player me = client.getLocalPlayer();
        if (me == null || !routes.load(me.getWorldView())) { return; }
        Tile hovered = me.getWorldView().getSelectedSceneTile();
        if (click != null)
        {
            Click c = click;
            toDestination = !start(target(c.type, c.x, c.y, c.id, c.actor, clickInMenu ? clickTile : hovered, null), clickRunning)
                && !NOT_WALKING.contains(c.type) && !NOT_LEFT_CLICK.contains(c.type);
            watching = OBJECT_OPTIONS.contains(c.type) ? 30 : 0;
        }
        click = null;
        if (watching > 0)
        {
            watching--;
            LocalPoint destination = client.getLocalDestinationLocation();
            if (destination != null && !destination.equals(clickDestination))
            {
                watching = 0;
                WorldPoint to = WorldPoint.fromLocal(client, destination);
                if (activePath.turns.isEmpty() || !to.equals(last(activePath.turns))) { start(Target.tile(to), clickRunning); }
            }
        }
        // With only Cancel in the menu the mouse can be on the minimap, where a left press walks.
        MenuEntry[] entries = client.getMenu().getMenuEntries();
        WorldPoint minimap = entries.length == 1 && !client.isMenuOpen()
            && (pressed || config.hoverPathDisplaySetting() != PathDisplaySetting.NEVER) ? minimapTile(me) : null;
        // The hover path keeps its target while the mouse is on no tile.
        MenuEntry option = entries.length == 0 ? null : chosen(entries);
        Target t = minimap != null ? Target.tile(minimap) : hovered == null || option == null ? hoverPath.target
            : target(option.getType(), option.getParam0(), option.getParam1(), option.getIdentifier(), option.getActor(), hovered, hoverPath.target);
        if (minimap != null && pressed) { start(t, runOnClick()); }
        pressed = false;
        mouseAt = client.getMouseCanvasPosition();
        lastHovered = hovered;
        hoverTo(t, me.getWorldLocation(), false);
    }

    @Subscribe public void onGameTick(GameTick e)
    {
        Player me = client.getLocalPlayer();
        if (me == null || !routes.load(me.getWorldView())) { return; }
        routes.loadBlockers(me.getWorldView());
        WorldPoint now = me.getWorldLocation();
        LocalPoint destination = client.getLocalDestinationLocation();
        if (toDestination && activePath.turns.isEmpty() && destination != null) { start(Target.tile(WorldPoint.fromLocal(client, destination)), runOnClick()); }
        toDestination = false;
        if (!activePath.turns.isEmpty()) { follow(now); }
        walk(activePath, now, false);
        hoverTo(hoverPath.target, now, true);
        lastTile = now;
    }

    /** The hover path to t from your tile: found again when either changes, walked again also when running changes. */
    private void hoverTo(Target t, WorldPoint from, boolean walk)
    {
        // Shown nowhere (by default): no route search every time the mouse moves; found again once it is shown.
        if (!shows(false, false) && !shows(false, true)) { hoverPath.clear(); return; }
        if (t == null ? hoverPath.target != null : !t.same(hoverPath.target) || !from.equals(hoverPath.from))
        {
            List<WorldPoint> turns = t == null ? null : routes.find(from, t);
            hoverPath.turns = turns == null ? Collections.emptyList() : turns;
            hoverPath.target = t;
            hoverPath.from = from;
            walk = true;
        }
        boolean run = runOnClick();
        if (walk || run != hoverPath.running)
        {
            hoverPath.running = run;
            walk(hoverPath, from, false);
        }
    }

    /**
     * A click's route becomes the active path; when nothing near its target can be reached, the active path stays.
     * Returns whether there is a route.
     */
    private boolean start(Target t, boolean running)
    {
        WorldPoint from = client.getLocalPlayer().getWorldLocation();
        List<WorldPoint> turns = t == null ? null : routes.find(from, t);
        if (turns == null) { return false; }
        activePath.target = t;
        activePath.turns = turns;
        activePath.running = running;
        fresh = true;
        mismatch = false;
        lastTile = from;
        walk(activePath, from, false);
        return true;
    }

    /** Each game tick: the active path against where you are; it ends, or is found again where you left it or its target moved. */
    private void follow(WorldPoint now)
    {
        // The client walks elsewhere than this route ends: to its destination then.
        LocalPoint destination = client.getLocalDestinationLocation();
        WorldPoint to = destination == null ? null : WorldPoint.fromLocal(client, destination);
        if (to != null && !to.equals(last(activePath.turns))) { reroute(Target.tile(to), now); }
        // Arrived, or moved more than two tiles (teleported, or another level).
        if (activePath.turns.isEmpty() || now.equals(last(activePath.turns)) || now.distanceTo(lastTile) > 2) { activePath.clear(); return; }
        // Where the route puts you now: a step on from your last tile walking, two running; a blocked step stays put.
        WorldPoint at = lastTile, end = last(activePath.turns);
        int passed = 0, steps = 0;
        for (; steps < (activePath.running ? 2 : 1) && (at.getX() != end.getX() || at.getY() != end.getY()); steps++)
        {
            if (at.equals(activePath.turns.get(passed))) { passed++; }
            WorldPoint next = routes.step(at, activePath.turns.get(passed));
            at = next == null ? at : next;
        }
        boolean elsewhere = !at.equals(now);
        // You already stood at its end, or were elsewhere two ticks in a row: it is not what you walk.
        if (steps == 0 || elsewhere && !fresh && mismatch) { activePath.clear(); return; }
        // The first time you are elsewhere after a click, the route is found again from where you are.
        if (elsewhere && fresh) { reroute(activePath.target, now); }
        else { activePath.turns.subList(0, Math.min(passed, activePath.turns.size() - 1)).clear(); }
        mismatch = elsewhere;
        // To a character: on the last stretch, found again for where it stands now.
        if (activePath.target.actor != null && activePath.turns.size() < 2) { reroute(Target.of(activePath.target.actor), now); }
    }

    private void reroute(Target t, WorldPoint from)
    {
        List<WorldPoint> turns = routes.find(from, t);
        if (turns != null) { activePath.turns = turns; fresh = false; }
    }

    private static WorldPoint last(List<WorldPoint> points) { return points.get(points.size() - 1); }

    /**
     * Fills a path's tiles: from `from` through its turns, a step at a time as the game moves you, up to a blocked step;
     * blind (beyond the loaded area nothing is known) every step is taken, up to MAX_PREDICTED tiles. Running, every
     * other tile is only run past; the last tile is always stood on.
     */
    void walk(Path p, WorldPoint from, boolean blind)
    {
        p.main.clear();
        p.passed.clear();
        if (p.turns.isEmpty() || from.getPlane() != p.turns.get(0).getPlane()) { return; }
        WorldPoint at = from, end = last(p.turns);
        boolean pass = p.running;
        for (WorldPoint turn : p.turns)
        {
            while ((at.getX() != turn.getX() || at.getY() != turn.getY()) && (!blind || p.main.size() + p.passed.size() < MAX_PREDICTED))
            {
                at = blind ? at.dx(Integer.signum(turn.getX() - at.getX())).dy(Integer.signum(turn.getY() - at.getY())) : routes.step(at, turn);
                if (at == null) { return; }
                (pass && !at.equals(end) ? p.passed : p.main).add(at);
                pass = p.running && !pass;
            }
        }
    }

    /** The menu option a click chooses: the one under the mouse in an open menu, else the left-click option. */
    private MenuEntry chosen(MenuEntry[] entries)
    {
        Menu menu = client.getMenu();
        Point mouse = client.getMouseCanvasPosition();
        if (client.isMenuOpen())
        {
            // Rows of 15 pixels under a 19 pixel title, the last option at the top.
            int y = mouse.getY() - menu.getMenuY() - 19, i = entries.length - 1 - y / 15;
            return y >= 0 && i >= 0 && mouse.getX() > menu.getMenuX() && mouse.getX() < menu.getMenuX() + menu.getMenuWidth() ? entries[i] : entries[0];
        }
        int i = entries.length - 1;
        while (i > 0 && NOT_LEFT_CLICK.contains(entries[i].getType())) { i--; }
        return entries[i];
    }

    /**
     * Where a menu option walks: to an object, a ground item's tile, a character, or (Walk here, and options not named
     * here) the given tile. Null for options that never walk; keep when the target is not found.
     */
    private Target target(MenuAction type, int x, int y, int id, Actor actor, Tile tile, Target keep)
    {
        switch (type)
        {
            case GAME_OBJECT_FIRST_OPTION: case GAME_OBJECT_SECOND_OPTION: case GAME_OBJECT_THIRD_OPTION: case GAME_OBJECT_FOURTH_OPTION:
            case GAME_OBJECT_FIFTH_OPTION: case WIDGET_TARGET_ON_GAME_OBJECT:
                Target object = object(sceneTile(x, y), id);
                return object == null ? keep : object;
            case GROUND_ITEM_FIRST_OPTION: case GROUND_ITEM_SECOND_OPTION: case GROUND_ITEM_THIRD_OPTION: case GROUND_ITEM_FOURTH_OPTION:
            case GROUND_ITEM_FIFTH_OPTION: case WIDGET_TARGET_ON_GROUND_ITEM:
                Tile on = sceneTile(x, y);
                List<TileItem> items = on == null ? null : on.getGroundItems();
                return items != null && items.stream().anyMatch(i -> i != null && i.getId() == id) ? Target.tile(on.getWorldLocation()) : keep;
            case NPC_FIRST_OPTION: case NPC_SECOND_OPTION: case NPC_THIRD_OPTION: case NPC_FOURTH_OPTION: case NPC_FIFTH_OPTION:
            case WIDGET_TARGET_ON_NPC: case PLAYER_FIRST_OPTION: case PLAYER_SECOND_OPTION: case PLAYER_THIRD_OPTION:
            case PLAYER_FOURTH_OPTION: case PLAYER_FIFTH_OPTION: case PLAYER_SIXTH_OPTION: case PLAYER_SEVENTH_OPTION:
            case WIDGET_TARGET_ON_PLAYER:
                return actor == null ? keep : Target.of(actor);
            default:
                return NOT_LEFT_CLICK.contains(type) || NOT_WALKING.contains(type) ? null
                    : tile == null ? keep : Target.tile(tile.getWorldLocation());
        }
    }

    /** The object with this id on a scene tile, as a target, or null. */
    private static Target object(Tile tile, int id)
    {
        if (tile == null) { return null; }
        for (GameObject o : tile.getGameObjects())
        {
            if (o != null && o.getId() == id) { return Target.object(tile.getWorldLocation(), o.sizeX(), o.sizeY(), o.getConfig(), id); }
        }
        WallObject wall = tile.getWallObject();
        DecorativeObject decoration = tile.getDecorativeObject();
        GroundObject ground = tile.getGroundObject();
        int config = wall != null && wall.getId() == id ? wall.getConfig() : decoration != null && decoration.getId() == id
            ? decoration.getConfig() : ground != null && ground.getId() == id ? ground.getConfig() : -1;
        return config < 0 ? null : Target.object(tile.getWorldLocation(), 1, 1, config, id);
    }

    /** A tile of the scene on your level, or null. */
    private Tile sceneTile(int x, int y)
    {
        WorldView wv = client.getLocalPlayer().getWorldView();
        Tile[][] level = wv.getScene().getTiles()[wv.getPlane()];
        return x >= 0 && y >= 0 && x < level.length && y < level[x].length ? level[x][y] : null;
    }

    private Widget minimap()
    {
        return client.getWidget(!client.isResized() ? InterfaceID.Toplevel.MINIMAP : client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1
            ? InterfaceID.ToplevelPreEoc.MINIMAP : InterfaceID.ToplevelOsrsStretch.MINIMAP);
    }

    /** The tile under the mouse on the minimap, as the game maps it: turned with the camera, zoom pixels per tile from you. */
    private WorldPoint minimapTile(Player me)
    {
        Widget map = minimap();
        if (mouseAt == null || map == null || map.isHidden() || !map.contains(mouseAt)) { return null; }
        double a = client.getCameraYawTarget() * Perspective.UNIT14, k = 128 / client.getMinimapZoom();
        double x = mouseAt.getX() - map.getCanvasLocation().getX() - map.getWidth() / 2, y = mouseAt.getY() - map.getCanvasLocation().getY() - map.getHeight() / 2;
        LocalPoint at = me.getLocalLocation();
        return WorldPoint.fromScene(me.getWorldView(), Math.floorDiv(at.getX() + (int) ((x * Math.cos(a) + y * Math.sin(a)) * k), 128),
            Math.floorDiv(at.getY() + (int) ((x * Math.sin(a) - y * Math.cos(a)) * k), 128), me.getWorldView().getPlane());
    }

    private static boolean shown(PathDisplaySetting display, boolean key)
    {
        return display == PathDisplaySetting.ALWAYS || display != PathDisplaySetting.NEVER && key;
    }

    /** Whether the active or hover path shows on the minimap or in the game world, by its options (the active one if it exists). */
    private boolean shows(boolean active, boolean minimap)
    {
        DrawLocations where = active ? config.activePathDrawLocations() : config.hoverPathDrawLocations();
        if (where != DrawLocations.BOTH && where != (minimap ? DrawLocations.MINIMAP : DrawLocations.GAME_WORLD)) { return false; }
        if (active) { return shown(config.activePathDisplaySetting(), keyActive); }
        return (!minimap || config.hoverPathMinimap()) && shown(config.hoverPathDisplaySetting(), keyHover) && (!config.drawOnlyIfNoActivePath()
            || activePath.turns.isEmpty() || config.activePathDisplaySetting() == PathDisplaySetting.NEVER);
    }

    /** Passes each tile a path shows to draw, with whether it is stood on: all of them, or only the target tile. */
    private void each(Path p, boolean active, BiConsumer<WorldPoint, Boolean> draw)
    {
        if ((active ? config.activePathDrawMode() : config.hoverPathDrawMode()) == DrawMode.TARGET_TILE)
        {
            if (!p.main.isEmpty()) { draw.accept(last(p.main), true); }
            return;
        }
        p.main.forEach(tile -> draw.accept(tile, true));
        p.passed.forEach(tile -> draw.accept(tile, false));
    }

    /** A path's outline or fill colour, for the tiles stood on (main) or run past. */
    private Color color(boolean active, boolean main, boolean fill)
    {
        if (active) { return main ? fill ? config.activePathFill1() : config.activePathStroke1() : fill ? config.activePathFill2() : config.activePathStroke2(); }
        return main ? fill ? config.hoverPathFill1() : config.hoverPathStroke1() : fill ? config.hoverPathFill2() : config.hoverPathStroke2();
    }

    /**
     * The paths' tiles in the game world, hover path first, for the plugin's renderer. With a walk target predicted
     * beyond what the client routes (MarkerSources.predictedTarget), its path too, while no active path exists or the
     * target lies beyond the loaded area.
     */
    List<Marker> markers(WorldPoint guess)
    {
        List<Marker> out = new ArrayList<>();
        Player me = client.getLocalPlayer();
        if (me == null) { return out; }
        WorldView wv = me.getWorldView();
        if (shows(false, false)) { PathLook hover = new PathLook(false, wv); each(hoverPath, false, (tile, main) -> out.add(hover.marker(tile, main))); }
        if (!shows(true, false)) { return out; }
        PathLook active = new PathLook(true, wv);
        each(activePath, true, (tile, main) -> out.add(active.marker(tile, main)));
        if (guess != null && (activePath.turns.isEmpty() || MarkerSources.outside(wv, guess)))
        {
            if (config.activePathDrawMode() == DrawMode.TARGET_TILE) { out.add(active.marker(guess, true)); }
            else { predict(me, guess); each(predictedPath, true, (tile, main) -> out.add(active.marker(tile, main))); }
        }
        return out;
    }

    /** A path's options as its tiles need them, read once per path rather than per tile (a predicted path has hundreds). */
    private final class PathLook
    {
        final boolean active, dot;
        final Color stroke, fill, passedStroke, passedFill;
        final double width;
        final WorldView wv;

        PathLook(boolean active, WorldView wv)
        {
            this.active = active; this.wv = wv;
            dot = (active ? config.activePathMarkerStyle() : config.hoverPathMarkerStyle()) == MarkerStyle.DOT;
            stroke = color(active, true, false); fill = color(active, true, true);
            passedStroke = color(active, false, false); passedFill = color(active, false, true);
            width = dot ? 2 : config.pathBorderWidth();
        }

        /** A path tile, stood on (main) or run past. */
        Marker marker(WorldPoint tile, boolean main)
        {
            Marker m = new Marker((active ? "path:a:" : "path:h:") + tile.getX() + ":" + tile.getY(), MarkerSources.local(wv, tile), wv.getPlane(), 1, 1,
                main ? stroke : passedStroke, main ? fill : passedFill, width, null);
            m.dot = dot;
            m.layer = active ? Marker.PATH_ACTIVE : Marker.PATH_HOVER;
            return m;
        }
    }

    /**
     * The predicted path to a walk target: the route to the scene tile nearest it, then straight on, since beyond the
     * loaded area nothing is known. Found again only when the target, your tile or running changes.
     */
    private void predict(Player me, WorldPoint guess)
    {
        WorldPoint from = me.getWorldLocation();
        if (Target.tile(guess).same(predictedPath.target) && from.equals(predictedPath.from) && predictedPath.running == running()) { return; }
        predictedPath.target = Target.tile(guess);
        predictedPath.from = from;
        predictedPath.running = running();
        WorldView wv = me.getWorldView();
        List<WorldPoint> turns = new ArrayList<>();
        // This runs while every mark is collected: a failure leaves only the straight line.
        try
        {
            List<WorldPoint> route = routes.load(wv) ? routes.find(from, Target.tile(new WorldPoint(
                Math.max(wv.getBaseX(), Math.min(wv.getBaseX() + wv.getSizeX() - 1, guess.getX())),
                Math.max(wv.getBaseY(), Math.min(wv.getBaseY() + wv.getSizeY() - 1, guess.getY())), guess.getPlane()))) : null;
            if (route != null) { turns.addAll(route); }
        }
        catch (RuntimeException ex)
        {
            turns.clear();
        }
        turns.add(guess);
        predictedPath.turns = turns;
        walk(predictedPath, from, true);
    }

    /** The minimap: both paths' tiles in their fill colours, turned with the camera, up to the minimap's edge. */
    @Override public Dimension render(Graphics2D graphics)
    {
        Player me = client.getLocalPlayer();
        Widget map = minimap();
        if (me == null || map == null || map.isHidden()) { return null; }
        double zoom = client.getMinimapZoom() / 128;
        int cx = map.getCanvasLocation().getX() + map.getWidth() / 2, cy = map.getCanvasLocation().getY() + map.getHeight() / 2;
        LocalPoint at = me.getLocalLocation();
        Graphics2D g = (Graphics2D) graphics.create();
        g.rotate(client.getCameraYawTarget() * Perspective.UNIT14, cx, cy);
        for (boolean active : new boolean[]{false, true})
        {
            if (!shows(active, true)) { continue; }
            each(active ? activePath : hoverPath, active, (tile, main) -> {
                LocalPoint p = LocalPoint.fromWorld(me.getWorldView(), tile);
                if (p == null) { return; }
                double x = (p.getX() - at.getX()) * zoom, y = (at.getY() - p.getY()) * zoom;
                // The minimap shows about 74 pixels around its centre.
                if (Math.hypot(x, y) > 74) { return; }
                g.setColor(color(active, main, true));
                g.fill(new Rectangle2D.Double(cx + x - 64 * zoom, cy + y - 64 * zoom, 128 * zoom, 128 * zoom));
            });
        }
        g.dispose();
        return null;
    }
}
