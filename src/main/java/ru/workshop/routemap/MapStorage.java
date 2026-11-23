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
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("routemap.json");

    public static RootData data = new RootData();

    public static class RootData {
        public Map<String, DimensionData> worlds = new HashMap<>();
    }

    public static class DimensionData {
        public List<RoutePoint> trail = new ArrayList<>();
        public List<Waypoint> marks = new ArrayList<>();
    }

    public static class RoutePoint {
        public int x, z;
        public RoutePoint(int x, int z) { this.x = x; this.z = z; }
    }

    public static class Waypoint {
        public String name;
        public String type; // CHEST, FARM, POINT
        public int x, y, z;

        public Waypoint(String name, String type, int x, int y, int z) {
            this.name = name;
            this.type = type;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static DimensionData getWorld(String dimId) {
        return data.worlds.computeIfAbsent(dimId, k -> new DimensionData());
    }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                data = GSON.fromJson(Files.readString(FILE), RootData.class);
                if (data == null) data = new RootData();
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
}
