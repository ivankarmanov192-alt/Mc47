package ru.workshop.routemap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MapStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson COMPACT_GSON = new Gson();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("routemap.json");
    private static final Path TERRAIN_FILE = FabricLoader.getInstance().getConfigDir().resolve("routemap_terrain.json");

    public static MapData data = new MapData();
    public static Map<Long, Integer> terrain = new HashMap<>();

    public static class MapData {
        public List<RoutePoint> trail = new ArrayList<>();
        public List<RoutePoint> farmRoute = new ArrayList<>();
        public List<Waypoint> marks = new ArrayList<>();
        public HudConfig config = new HudConfig();
    }

    public static class HudConfig {
        // Позиции элементов в процентах экрана (0.0 .. 1.0), чтобы работало при любом разрешении
        public float badgeX = 0.50f;
        public float badgeY = 0.08f; // По центру под серверным компасом, не мешает полоске HP слева
        public float backX = 0.50f;
        public float backY = 0.82f;
        public int themeIndex = 0;   // 0=Золотой, 1=Бирюзовый, 2=Зелёный, 3=Красный, 4=Фиолетовый, 5=Белый
        public boolean smoothAnim = false; // По умолчанию выключено, без "резиновой" задержки
        public boolean showBadge = true;
    }

    public static class TerrainData {
        public Map<String, Integer> cells = new HashMap<>();
    }

    public static class RoutePoint {
        public int x, z;
        public RoutePoint(int x, int z) { this.x = x; this.z = z; }
    }

    public static class Waypoint {
        public String name;
        public String type;
        public int x, y, z;

        public Waypoint(String name, String type, int x, int y, int z) {
            this.name = name;
            this.type = type;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static long packGrid(int gx, int gz) {
        return (((long) gx) << 32) | (gz & 0xFFFFFFFFL);
    }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                data = GSON.fromJson(Files.readString(FILE), MapData.class);
                if (data == null) data = new MapData();
                if (data.trail == null) data.trail = new ArrayList<>();
                if (data.farmRoute == null) data.farmRoute = new ArrayList<>();
                if (data.marks == null) data.marks = new ArrayList<>();
                if (data.config == null) data.config = new HudConfig();
            }
            if (Files.exists(TERRAIN_FILE)) {
                TerrainData td = COMPACT_GSON.fromJson(Files.readString(TERRAIN_FILE), TerrainData.class);
                terrain.clear();
                if (td != null && td.cells != null) {
                    for (Map.Entry<String, Integer> e : td.cells.entrySet()) {
                        terrain.put(Long.parseLong(e.getKey()), e.getValue());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void save() {
        try {
            Files.writeString(FILE, GSON.toJson(data));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void saveTerrain() {
        try {
            TerrainData td = new TerrainData();
            for (Map.Entry<Long, Integer> e : terrain.entrySet()) {
                td.cells.put(Long.toString(e.getKey()), e.getValue());
            }
            Files.writeString(TERRAIN_FILE, COMPACT_GSON.toJson(td));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
