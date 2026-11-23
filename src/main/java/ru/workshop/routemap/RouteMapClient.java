package ru.workshop.routemap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class RouteMapClient implements ClientModInitializer {
    private static KeyBinding openMapKey;
    private static KeyBinding addMarkKey;
    private static MapStorage.Waypoint selectedMark = null;

    // 0 = выкл, 1 = ждём 1-й сундук, 2 = активно записываем
    private static int recordMode = 0;
    private static int recordedChestsCount = 0;
    private static Screen lastHandledScreen = null;

    // Режим прохождения записанного маршрута (-1 = выключен, 0+ = индекс текущего сундука)
    private static int activeRunIndex = -1;
    // Таймер отображения плашки "Все сундуки собраны!" (в тиках, 80 тиков = 4 сек)
    private static int finishedBannerTicks = 0;

    private int tickCounter = 0;
    private int terrainSaveCounter = 0;
    private boolean terrainDirty = false;

    @Override
    public void onInitializeClient() {
        MapStorage.load();

        openMapKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.routemap.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, KeyBinding.Category.MISC
        ));
        addMarkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.routemap.mark", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, KeyBinding.Category.MISC
        ));

        // HUD-рендер: 3D-рамка целевого сундука + аккуратный бейдж статуса
        HudRenderCallback.EVENT.register((ctx, renderTickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null || client.currentScreen != null) return;

            int sw = client.getWindow().getScaledWidth();
            int sh = client.getWindow().getScaledHeight();

            // 1. Бейдж статуса в левом верхнем углу
            MapStorage.Waypoint target = getCurrentRunTarget();
            if (recordMode != 0) {
                String badge = (recordMode == 1)
                        ? "● ЗАПИСЬ ЛУТА: открой 1-й сундук"
                        : "● ЗАПИСЬ ЛУТА: найдено " + recordedChestsCount;
                int bw = client.textRenderer.getWidth(badge) + 12;
                ctx.fill(8, 8, 8 + bw, 22, 0xCC141820);
                drawBorder(ctx, 8, 8, bw, 14, recordMode == 1 ? 0xFFFACC15 : 0xFF4ADE80);
                ctx.drawTextWithShadow(client.textRenderer, badge, 14, 11, recordMode == 1 ? 0xFFFACC15 : 0xFF4ADE80);
            } else if (target != null) {
                int dist = (int) Math.hypot(target.x + 0.5 - client.player.getX(), target.z + 0.5 - client.player.getZ());
                String badge = "★ МАРШРУТ: " + target.name + " (" + dist + " м)";
                int bw = client.textRenderer.getWidth(badge) + 12;
                ctx.fill(8, 8, 8 + bw, 22, 0xCC141820);
                drawBorder(ctx, 8, 8, bw, 14, 0xFFFFD700);
                ctx.drawTextWithShadow(client.textRenderer, badge, 14, 11, 0xFFFFD700);
            } else if (finishedBannerTicks > 0) {
                String badge = "✔ ВСЕ СУНДУКИ СОБРАНЫ!";
                int bw = client.textRenderer.getWidth(badge) + 12;
                ctx.fill(8, 8, 8 + bw, 22, 0xCC141820);
                drawBorder(ctx, 8, 8, bw, 14, 0xFF4ADE80);
                ctx.drawTextWithShadow(client.textRenderer, badge, 14, 11, 0xFF4ADE80);
            }

            // 2. 3D-подсветка целевого сундука в мире
            if (target == null) return;

            Camera camera = client.gameRenderer.getCamera();
            Vec3d camPos = client.player.getEyePos();

            double dx = (target.x + 0.5) - camPos.x;
            double dy = (target.y + 0.5) - camPos.y;
            double dz = (target.z + 0.5) - camPos.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            Vector3f rel = new Vector3f((float) dx, (float) dy, (float) dz);
            Quaternionf invRot = new Quaternionf(camera.getRotation()).conjugate();
            invRot.transform(rel);

            if (rel.z < -0.1f) {
                double fov = Math.toRadians(client.options.getFov().getValue());
                double scale = (sh / 2.0) / Math.tan(fov / 2.0);

                int sx = (int) (sw / 2.0 + (rel.x / -rel.z) * scale);
                int sy = (int) (sh / 2.0 - (rel.y / -rel.z) * scale);

                sx = Math.max(24, Math.min(sw - 24, sx));
                sy = Math.max(24, Math.min(sh - 24, sy));

                int box = (int) Math.max(7, Math.min(28, 140.0 / Math.max(3.0, dist)));

                ctx.fill(sx - 1, Math.max(10, sy - box - 48), sx + 1, sy - box, 0x99FFD700);
                ctx.fill(sx - box, sy - box, sx + box, sy + box, 0x26FFD700);
                drawBorder(ctx, sx - box, sy - box, box * 2, box * 2, 0xFFFFD700);
                drawBorder(ctx, sx - box - 2, sy - box - 2, box * 2 + 4, box * 2 + 4, 0x66FFD700);

                String label = "★ " + target.name + " • " + (int) dist + " м";
                int tw = client.textRenderer.getWidth(label);
                int lx = Math.max(6, Math.min(sw - tw - 6, sx - tw / 2));
                int ly = Math.max(8, sy - box - 16);

                ctx.fill(lx - 4, ly - 2, lx + tw + 4, ly + 10, 0xCC0D1117);
                drawBorder(ctx, lx - 4, ly - 2, tw + 8, 12, 0xAAFFD700);
                ctx.drawTextWithShadow(client.textRenderer, label, lx, ly, 0xFFFFD700);
            } else {
                String backLabel = "▼ " + target.name + " сзади • " + (int) dist + " м";
                int tw = client.textRenderer.getWidth(backLabel);
                int lx = (sw - tw) / 2;
                int ly = sh - 54;
                ctx.fill(lx - 5, ly - 2, lx + tw + 5, ly + 10, 0xCC0D1117);
                drawBorder(ctx, lx - 5, ly - 2, tw + 10, 12, 0xAAFFD700);
                ctx.drawTextWithShadow(client.textRenderer, backLabel, lx, ly, 0xFFFFD700);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            if (finishedBannerTicks > 0) {
                finishedBannerTicks--;
            }

            while (openMapKey.wasPressed()) {
                client.setScreen(new FullMapScreen());
            }
            while (addMarkKey.wasPressed()) {
                client.setScreen(new AddMarkScreen(
                        (int) Math.floor(client.player.getX()),
                        (int) Math.floor(client.player.getY()),
                        (int) Math.floor(client.player.getZ())
                ));
            }

            checkOpenedContainer(client);

            tickCounter++;
            if (tickCounter >= 10) {
                tickCounter = 0;
                recordStep(client);
                scanTerrain(client);
            }

            terrainSaveCounter++;
            if (terrainSaveCounter >= 200 && terrainDirty) {
                terrainSaveCounter = 0;
                terrainDirty = false;
                MapStorage.saveTerrain();
            }
        });
    }

    private static void drawBorder(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y, x + 1, y + h, color);
        ctx.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static List<MapStorage.Waypoint> getChestMarks() {
        List<MapStorage.Waypoint> chests = new ArrayList<>();
        for (MapStorage.Waypoint wp : MapStorage.data.marks) {
            if ("CHEST".equals(wp.type)) {
                chests.add(wp);
            }
        }
        return chests;
    }

    private static MapStorage.Waypoint getCurrentRunTarget() {
        if (activeRunIndex < 0) return null;
        List<MapStorage.Waypoint> chests = getChestMarks();
        if (chests.isEmpty() || activeRunIndex >= chests.size()) {
            activeRunIndex = -1;
            return null;
        }
        return chests.get(activeRunIndex);
    }

    private void checkOpenedContainer(MinecraftClient client) {
        if (client.player == null) {
            lastHandledScreen = null;
            return;
        }

        Screen current = client.currentScreen;
        if (current instanceof HandledScreen<?> && !(current instanceof InventoryScreen) && !(current instanceof CreativeInventoryScreen)) {
            if (current != lastHandledScreen) {
                lastHandledScreen = current;

                BlockPos targetPos = client.player.getBlockPos();
                if (client.crosshairTarget != null && client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
                    targetPos = ((BlockHitResult) client.crosshairTarget).getBlockPos();
                }

// А. Если запущено ПРОХОЖДЕНИЕ маршрута
MapStorage.Waypoint runTarget = getCurrentRunTarget();
if (runTarget != null) {
double d = Math.hypot(client.player.getX() - (runTarget.x + 0.5), client.player.getZ() - (runTarget.z + 0.5));
double dBlock = Math.hypot(targetPos.getX() - runTarget.x, targetPos.getZ() - runTarget.z);
if (d <= 6.5 || dBlock <= 4.0) {
List<MapStorage.Waypoint> chests = getChestMarks();
activeRunIndex++;
if (activeRunIndex >= chests.size()) {
// Все сундуки собраны! Отключаем подсветку и выводим крупное уведомление на экран
activeRunIndex = -1;
selectedMark = null;
finishedBannerTicks = 100; // 5 секунд плашка в углу
client.inGameHud.setTitleTicks(5, 45, 10);
client.inGameHud.setTitle(Text.literal("§6★ Все сундуки собраны! ★"));
client.inGameHud.setSubtitle(Text.literal("§aМаршрут фарма завершён"));
client.player.sendMessage(Text.literal("§6[Мастерская] §aВсе сундуки собраны! Подсветка выключена."), true);
} else {
client.player.sendMessage(Text.literal("§6[Мастерская] §aСобрано! Следующая цель: §e" + chests.get(activeRunIndex).name), true);
}
}
}
// Б. Если включена ЗАПИСЬ нового маршрута
if (recordMode != 0) {
if (recordMode == 1) {
recordMode = 2;
recordedChestsCount = 0;
MapStorage.data.farmRoute.clear();
MapStorage.data.farmRoute.add(new MapStorage.RoutePoint(targetPos.getX(), targetPos.getZ()));
}
boolean alreadyMarked = false;
for (MapStorage.Waypoint wp : MapStorage.data.marks) {
if ("CHEST".equals(wp.type)
&& Math.abs(wp.x - targetPos.getX()) <= 2
&& Math.abs(wp.z - targetPos.getZ()) <= 2
&& Math.abs(wp.y - targetPos.getY()) <= 3) {
alreadyMarked = true;
break;
}
}
if (!alreadyMarked) {
recordedChestsCount++;
MapStorage.data.marks.add(new MapStorage.Waypoint(
"Сундук #" + recordedChestsCount, "CHEST",
targetPos.getX(), targetPos.getY(), targetPos.getZ()
));
MapStorage.data.farmRoute.add(new MapStorage.RoutePoint(targetPos.getX(), targetPos.getZ()));
MapStorage.save();
client.player.sendMessage(Text.literal("§6[Мастерская] §aОтмечен Сундук #" + recordedChestsCount + " §7(" + targetPos.getX() + ", " + targetPos.getZ() + ")"), true);
}
}
}
} else if (current == null) {
lastHandledScreen = null;
}
}
private void scanTerrain(MinecraftClient client) {
World world = client.world;
if (world == null || client.player == null) return;
int px = (int) Math.floor(client.player.getX());
int pz = (int) Math.floor(client.player.getZ());
int radius = 48;
BlockPos.Mutable pos = new BlockPos.Mutable();
for (int dx = -radius; dx <= radius; dx += 2) {
for (int dz = -radius; dz <= radius; dz += 2) {
int wx = (px + dx) & ~1;
int wz = (pz + dz) & ~1;
int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, wx, wz) - 1;
if (topY < world.getBottomY()) continue;
pos.set(wx, topY, wz);
BlockState state = world.getBlockState(pos);
MapColor mapColor = state.getMapColor(world, pos);
if (mapColor == MapColor.CLEAR) continue;
int rgb = mapColor.color & 0xFFFFFF;
int packedVal = ((topY & 0xFF) << 24) | rgb;
long key = MapStorage.packGrid(wx >> 1, wz >> 1);
Integer old = MapStorage.terrain.get(key);
if (old == null || old != packedVal) {
MapStorage.terrain.put(key, packedVal);
terrainDirty = true;
}
}
}
}
private void recordStep(MinecraftClient client) {
int px = (int) Math.floor(client.player.getX());
int pz = (int) Math.floor(client.player.getZ());
appendPoint(MapStorage.data.trail, px, pz, 15000);
if (recordMode == 2) {
appendPoint(MapStorage.data.farmRoute, px, pz, 10000);
}
}
private void appendPoint(List<MapStorage.RoutePoint> list, int px, int pz, int maxLen) {
if (list.isEmpty()) {
list.add(new MapStorage.RoutePoint(px, pz));
MapStorage.save();
return;
}
MapStorage.RoutePoint last = list.get(list.size() - 1);
double dist = Math.hypot(px - last.x, pz - last.z);
if (dist > 120) {
list.add(new MapStorage.RoutePoint(Integer.MIN_VALUE, Integer.MIN_VALUE));
list.add(new MapStorage.RoutePoint(px, pz));
MapStorage.save();
} else if (dist >= 3) {
list.add(new MapStorage.RoutePoint(px, pz));
if (list.size() > maxLen) {
list.remove(0);
}
MapStorage.save();
}
}
// ================= СТИЛЬНЫЙ ЭКРАН КАРТЫ v3.0 =================
public static class FullMapScreen extends Screen {
private double centerX, centerZ;
private double zoom = 1.5;
private boolean showSidebar = true;
private int listScroll = 0;
private record UiBtn(int x, int y, int w, int h, String label, int accentColor, Runnable action) {
boolean contains(double mx, double my) {
return mx >= x && mx <= x + w && my >= y && my <= y + h;
}
}
private final List buttons = new ArrayList<>();
public FullMapScreen() {
super(Text.literal("Картограф Мастерской"));
}
@Override
protected void init() {
if (client != null && client.player != null) {
centerX = client.player.getX();
centerZ = client.player.getZ();
}
}
private void rebuildButtons() {
buttons.clear();
int y = 8;
int h = 20;
buttons.add(new UiBtn(10, y, 22, h, "+", 0xFF38BDF8, () -> zoom = Math.min(8.0, zoom * 1.4)));
buttons.add(new UiBtn(35, y, 22, h, "-", 0xFF38BDF8, () -> zoom = Math.max(0.25, zoom / 1.4)));
buttons.add(new UiBtn(60, y, 22, h, "Я", 0xFFEF4444, () -> {
if (client != null && client.player != null) {
centerX = client.player.getX();
centerZ = client.player.getZ();
}
}));
buttons.add(new UiBtn(88, y, 60, h, "+ Метка", 0xFF22D3EE, () -> {
if (client != null && client.player != null) {
client.setScreen(new AddMarkScreen(
(int) Math.floor(client.player.getX()),
(int) Math.floor(client.player.getY()),
(int) Math.floor(client.player.getZ())
));
}
}));
String recLabel = switch (recordMode) {
case 1 -> "Жду 1-й сундук...";
case 2 -> "● Запись (" + recordedChestsCount + ")";
default -> "Записать лут";
};
int recColor = (recordMode == 2) ? 0xFF4ADE80 : (recordMode == 1 ? 0xFFFACC15 : 0xFFE2E8F0);
buttons.add(new UiBtn(154, y, 102, h, recLabel, recColor, () -> {
if (recordMode == 0) {
recordMode = 1;
activeRunIndex = -1;
finishedBannerTicks = 0;
} else {
recordMode = 0;
MapStorage.save();
}
}));
MapStorage.Waypoint target = getCurrentRunTarget();
String runLabel = (target == null) ? "Запустить маршрут" : "★ " + target.name;
int runColor = (target != null) ? 0xFFFFD700 : 0xFFE2E8F0;
buttons.add(new UiBtn(262, y, 118, h, runLabel, runColor, () -> {
if (activeRunIndex < 0) {
if (!getChestMarks().isEmpty()) {
recordMode = 0;
activeRunIndex = 0;
finishedBannerTicks = 0;
}
} else {
activeRunIndex = -1;
}
}));
buttons.add(new UiBtn(width - 66, y, 56, h, showSidebar ? "Список ▸" : "Список ◂", 0xFFD4AF37, () -> showSidebar = !showSidebar));
buttons.add(new UiBtn(width - 144, y, 72, h, "Сброс лута", 0xFFF87171, () -> {
recordMode = 0;
activeRunIndex = -1;
finishedBannerTicks = 0;
recordedChestsCount = 0;
MapStorage.data.farmRoute.clear();
MapStorage.save();
}));
buttons.add(new UiBtn(width - 230, y, 80, h, "Очистить след", 0xFF94A3B8, () -> {
MapStorage.data.trail.clear();
MapStorage.save();
}));
}
@Override
public boolean mouseClicked(Click click, boolean doubled) {
double mx = click.x();
double my = click.y();
if (click.button() == 0) {
for (UiBtn btn : buttons) {
if (btn.contains(mx, my)) {
btn.action.run();
return true;
}
}
}
int sidebarW = showSidebar ? 155 : 0;
if (showSidebar && mx >= width - sidebarW - 6 && my > 36 && my < height - 20) {
int listStartY = 58;
int itemH = 18;
List<MapStorage.Waypoint> marks = MapStorage.data.marks;
for (int i = listScroll; i < marks.size(); i++) {
int iy = listStartY + (i - listScroll) * itemH;
if (iy + itemH > height - 24) break;
if (my >= iy && my <= iy + itemH) {
MapStorage.Waypoint wp = marks.get(i);
if (click.button() == 0) {
centerX = wp.x;
centerZ = wp.z;
selectedMark = wp;
if ("CHEST".equals(wp.type)) {
int idx = getChestMarks().indexOf(wp);
if (idx >= 0) activeRunIndex = idx;
}
return true;
} else if (click.button() == 1) {
if (selectedMark == wp) selectedMark = null;
marks.remove(i);
MapStorage.save();
return true;
}
}
}
return true;
}
if (client == null || client.player == null) return false;
if (my <= 36 || my >= height - 20) return false;
int mapRight = width - sidebarW;
int midX = mapRight / 2;
int midY = height / 2;
List<MapStorage.Waypoint> marks = MapStorage.data.marks;
for (int i = marks.size() - 1; i >= 0; i--) {
MapStorage.Waypoint wp = marks.get(i);
int sx = (int) (midX + (wp.x - centerX) * zoom);
int sy = (int) (midY + (wp.z - centerZ) * zoom);
if (Math.hypot(mx - sx, my - sy) <= 9) {
if (click.button() == 0) {
selectedMark = (selectedMark == wp) ? null : wp;
if ("CHEST".equals(wp.type) && activeRunIndex >= 0) {
int idx = getChestMarks().indexOf(wp);
if (idx >= 0) activeRunIndex = idx;
}
return true;
} else if (click.button() == 1) {
if (selectedMark == wp) selectedMark = null;
marks.remove(i);
MapStorage.save();
return true;
}
}
}
if (click.button() == 1 && mx < mapRight) {
int wx = (int) Math.round(centerX + (mx - midX) / zoom);
int wz = (int) Math.round(centerZ + (my - midY) / zoom);
client.setScreen(new AddMarkScreen(wx, (int) Math.floor(client.player.getY()), wz));
return true;
}
return super.mouseClicked(click, doubled);
}
@Override
public boolean mouseDragged(Click click, double deltaX, double deltaY) {
centerX -= deltaX / zoom;
centerZ -= deltaY / zoom;
return true;
}
@Override
public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
int sidebarW = showSidebar ? 155 : 0;
if (showSidebar && mouseX >= width - sidebarW - 6) {
if (verticalAmount < 0 && listScroll < Math.max(0, MapStorage.data.marks.size() - 5)) listScroll++;
else if (verticalAmount > 0 && listScroll > 0) listScroll--;
return true;
}
if (verticalAmount > 0) zoom = Math.min(8.0, zoom * 1.2);
else if (verticalAmount < 0) zoom = Math.max(0.25, zoom / 1.2);
return true;
}
@Override
public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
rebuildButtons();
if (client == null || client.player == null) return;
ctx.fill(0, 0, width, height, 0xF50B0E14);
int sidebarW = showSidebar ? 155 : 0;
int mapRight = width - sidebarW;
int midX = mapRight / 2;
int midY = height / 2;
int minGX = (int) Math.floor((centerX - midX / zoom) / 2.0) - 1;
int maxGX = (int) Math.ceil((centerX + (mapRight - midX) / zoom) / 2.0) + 1;
int minGZ = (int) Math.floor((centerZ - (midY - 36) / zoom) / 2.0) - 1;
int maxGZ = (int) Math.ceil((centerZ + (midY - 20) / zoom) / 2.0) + 1;
for (int gx = minGX; gx <= maxGX; gx++) {
int sx1 = (int) Math.floor(midX + (gx * 2 - centerX) * zoom);
int sx2 = (int) Math.ceil(midX + ((gx + 1) * 2 - centerX) * zoom);
if (sx2 <= 0 || sx1 >= mapRight) continue;
for (int gz = minGZ; gz <= maxGZ; gz++) {
Integer val = MapStorage.terrain.get(MapStorage.packGrid(gx, gz));
if (val == null) continue;
int sy1 = (int) Math.floor(midY + (gz * 2 - centerZ) * zoom);
int sy2 = (int) Math.ceil(midY + ((gz + 1) * 2 - centerZ) * zoom);
if (sy2 <= 36 || sy1 >= height - 20) continue;
int y = (val >> 24) & 0xFF;
int r = (val >> 16) & 0xFF;
int g = (val >> 8) & 0xFF;
int b = val & 0xFF;
Integer northVal = MapStorage.terrain.get(MapStorage.packGrid(gx, gz - 1));
float shade = 1.0f;
if (northVal != null) {
int ny = (northVal >> 24) & 0xFF;
if (y > ny) shade = 1.15f;
else if (y < ny) shade = 0.82f;
}
r = Math.min(255, (int) (r * shade));
g = Math.min(255, (int) (g * shade));
b = Math.min(255, (int) (b * shade));
int argb = 0xFF000000 | (r << 16) | (g << 8) | b;
ctx.fill(Math.max(0, sx1), Math.max(36, sy1), Math.min(mapRight, Math.max(sx1 + 1, sx2)), Math.min(height - 20, Math.max(sy1 + 1, sy2)), argb);
}
}
renderPolyline(ctx, MapStorage.data.trail, midX, midY, mapRight, 0x55FFFFFF, 1);
renderPolyline(ctx, MapStorage.data.farmRoute, midX, midY, mapRight, 0xFFFFD700, 3);
int px = (int) (midX + (client.player.getX() - centerX) * zoom);
int py = (int) (midY + (client.player.getZ() - centerZ) * zoom);
MapStorage.Waypoint runTarget = getCurrentRunTarget();
MapStorage.Waypoint lineTarget = (runTarget != null) ? runTarget : selectedMark;
if (lineTarget != null && MapStorage.data.marks.contains(lineTarget)) {
int tx = (int) (midX + (lineTarget.x - centerX) * zoom);
int ty = (int) (midY + (lineTarget.z - centerZ) * zoom);
drawLine(ctx, px, py, tx, ty, mapRight, 0xFFFF5500, 2);
} else {
selectedMark = null;
}
for (MapStorage.Waypoint wp : MapStorage.data.marks) {
int sx = (int) (midX + (wp.x - centerX) * zoom);
int sy = (int) (midY + (wp.z - centerZ) * zoom);
if (sx < 6 || sx > mapRight - 6 || sy <= 40 || sy >= height - 24) continue;
boolean isHighlighted = (wp == selectedMark || wp == runTarget);
boolean isHovered = Math.hypot(mouseX - sx, mouseY - sy) <= 9;
drawMarkIcon(ctx, sx, sy, wp.type, isHighlighted);
if (zoom >= 0.85 || isHighlighted || isHovered) {
int color = switch (wp.type) {
case "CHEST" -> 0xFFFACC15;
case "FARM" -> 0xFF4ADE80;
default -> 0xFF22D3EE;
};
String tag = isHovered ? wp.name + " (" + wp.x + ", " + wp.z + ")" : wp.name;
int tw = textRenderer.getWidth(tag);
if (sx + 8 + tw < mapRight - 4) {
ctx.fill(sx + 6, sy - 6, sx + 10 + tw, sy + 5, 0xBB0B0E14);
drawBorder(ctx, sx + 6, sy - 6, tw + 4, 11, isHighlighted ? 0xFFFFD700 : 0x44FFFFFF);
ctx.drawTextWithShadow(textRenderer, tag, sx + 8, sy - 4, color);
}
}
}
if (px >= 4 && px <= mapRight - 4 && py >= 38 && py <= height - 22) {
drawPlayerArrow(ctx, px, py, client.player.getYaw());
}
ctx.fill(0, 0, width, 36, 0xF212161F);
ctx.fill(0, 35, width, 36, 0xFFD4AF37);
for (UiBtn btn : buttons) {
boolean hover = btn.contains(mouseX, mouseY);
ctx.fill(btn.x, btn.y, btn.x + btn.w, btn.y + btn.h, hover ? 0xFF262F40 : 0xFF1A202C);
drawBorder(ctx, btn.x, btn.y, btn.w, btn.h, hover ? 0xFFFFD700 : 0x66D4AF37);
int tw = textRenderer.getWidth(btn.label);
ctx.drawTextWithShadow(textRenderer, btn.label, btn.x + (btn.w - tw) / 2, btn.y + 6, btn.accentColor);
}
if (showSidebar) {
int sx = width - sidebarW - 4;
ctx.fill(sx, 38, width - 4, height - 22, 0xE612161F);
drawBorder(ctx, sx, 38, sidebarW, height - 60, 0x88D4AF37);
ctx.drawTextWithShadow(textRenderer, "★ Точки (" + MapStorage.data.marks.size() + ")", sx + 8, 44, 0xFFFFD700);
ctx.fill(sx + 4, 55, width - 8, 56, 0x44D4AF37);
List<MapStorage.Waypoint> marks = MapStorage.data.marks;
for (int i = listScroll; i < marks.size(); i++) {
int iy = 58 + (i - listScroll) * 18;
if (iy + 18 > height - 24) break;
MapStorage.Waypoint wp = marks.get(i);
boolean active = (wp == selectedMark || wp == runTarget);
boolean rowHover = mouseX >= sx && mouseX <= width - 4 && mouseY >= iy && mouseY <= iy + 17;
if (active || rowHover) {
ctx.fill(sx + 3, iy, width - 7, iy + 16, active ? 0x44FFD700 : 0x22FFFFFF);
}
int dotColor = switch (wp.type) {
case "CHEST" -> 0xFFFACC15;
case "FARM" -> 0xFF4ADE80;
default -> 0xFF22D3EE;
};
ctx.fill(sx + 7, iy + 5, sx + 12, iy + 10, dotColor);
String shortName = wp.name.length() > 17 ? wp.name.substring(0, 16) + "…" : wp.name;
ctx.drawTextWithShadow(textRenderer, shortName, sx + 16, iy + 4, active ? 0xFFFFD700 : 0xFFE2E8F0);
}
}
ctx.fill(0, height - 20, width, height, 0xF212161F);
ctx.fill(0, height - 20, width, height - 19, 0x88D4AF37);
String targetText = "";
if (lineTarget != null) {
int dist = (int) Math.hypot(lineTarget.x - client.player.getX(), lineTarget.z - client.player.getZ());
targetText = "  |  Цель: §6" + lineTarget.name + " (" + dist + " м)§r";
}
String info = String.format("Позиция: %d, %d%s  |  ЛКМ = выбрать, ПКМ = поставить/удалить",
(int) client.player.getX(), (int) client.player.getZ(), targetText);
ctx.drawTextWithShadow(textRenderer, info, 10, height - 14, 0xFFCBD5E1);
}
private void drawMarkIcon(DrawContext ctx, int sx, int sy, String type, boolean highlighted) {
if (highlighted) {
drawBorder(ctx, sx - 7, sy - 7, 14, 14, 0xFFFF5500);
}
switch (type) {
case "CHEST" -> {
ctx.fill(sx - 5, sy - 4, sx + 5, sy + 4, 0xFF1E1B18);
ctx.fill(sx - 4, sy - 3, sx + 4, sy + 3, 0xFFD97706);
ctx.fill(sx - 4, sy - 1, sx + 4, sy, 0xFF78350F);
ctx.fill(sx - 1, sy - 2, sx + 1, sy + 1, 0xFFFDE047);
}
case "FARM" -> {
ctx.fill(sx - 4, sy - 4, sx + 4, sy + 4, 0xFF052E16);
ctx.fill(sx - 1, sy - 3, sx + 1, sy + 3, 0xFF4ADE80);
ctx.fill(sx - 3, sy - 2, sx - 1, sy, 0xFF86EFAC);
ctx.fill(sx + 1, sy - 1, sx + 3, sy + 1, 0xFF86EFAC);
}
default -> {
ctx.fill(sx - 4, sy - 4, sx + 4, sy + 4, 0xFF083344);
ctx.fill(sx - 3, sy - 3, sx + 3, sy + 3, 0xFF22D3EE);
ctx.fill(sx - 1, sy - 1, sx + 1, sy + 1, 0xFFFFFFFF);
}
}
}
private void drawPlayerArrow(DrawContext ctx, int px, int py, float yaw) {
double rad = Math.toRadians(yaw + 90);
int tipX = px + (int) Math.round(Math.cos(rad) * 7);
int tipY = py + (int) Math.round(Math.sin(rad) * 7);
drawLine(ctx, px, py, tipX, tipY, width, 0xFFFFD700, 2);
ctx.fill(px - 4, py - 4, px + 4, py + 4, 0xFF000000);
ctx.fill(px - 3, py - 3, px + 3, py + 3, 0xFFEF4444);
ctx.fill(tipX - 1, tipY - 1, tipX + 2, tipY + 2, 0xFFFFD700);
}
private void renderPolyline(DrawContext ctx, List<MapStorage.RoutePoint> list, int midX, int midY, int maxX, int color, int thickness) {
for (int i = 1; i < list.size(); i++) {
MapStorage.RoutePoint p1 = list.get(i - 1);
MapStorage.RoutePoint p2 = list.get(i);
if (p1.x == Integer.MIN_VALUE || p2.x == Integer.MIN_VALUE) continue;
int x1 = (int) (midX + (p1.x - centerX) * zoom);
int y1 = (int) (midY + (p1.z - centerZ) * zoom);
int x2 = (int) (midX + (p2.x - centerX) * zoom);
int y2 = (int) (midY + (p2.z - centerZ) * zoom);
drawLine(ctx, x1, y1, x2, y2, maxX, color, thickness);
}
}
private void drawLine(DrawContext ctx, int x0, int y0, int x1, int y1, int maxX, int color, int thickness) {
int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
int err = dx + dy, e2;
while (true) {
if (x0 >= 0 && x0 < maxX && y0 >= 36 && y0 < height - 20) {
ctx.fill(x0, y0, x0 + thickness, y0 + thickness, color);
}
if (x0 == x1 && y0 == y1) break;
e2 = 2 * err;
if (e2 >= dy) { err += dy; x0 += sx; }
if (e2 <= dx) { err += dx; y0 += sy; }
}
}
}
// ================= СТИЛЬНОЕ ОКНО СОЗДАНИЯ МЕТКИ =================
public static class AddMarkScreen extends Screen {
private TextFieldWidget nameField;
private final int targetX, targetY, targetZ;
private record ModalBtn(int x, int y, int w, int h, String label, int color, Runnable action) {
boolean contains(double mx, double my) {
return mx >= x && mx <= x + w && my >= y && my <= y + h;
}
}
private final List modalButtons = new ArrayList<>();
public AddMarkScreen(int x, int y, int z) {
super(Text.literal("Новая метка"));
this.targetX = x;
this.targetY = y;
this.targetZ = z;
}
@Override
protected void init() {
int cx = width / 2;
int cy = height / 2;
nameField = new TextFieldWidget(textRenderer, cx - 105, cy - 22, 210, 20, Text.literal("Название"));
nameField.setPlaceholder(Text.literal("Например: Сундук у бархана"));
addDrawableChild(nameField);
setInitialFocus(nameField);
modalButtons.clear();
modalButtons.add(new ModalBtn(cx - 105, cy + 8, 66, 22, "Сундук", 0xFFFACC15, () -> saveMark("CHEST")));
modalButtons.add(new ModalBtn(cx - 33, cy + 8, 66, 22, "Ферма", 0xFF4ADE80, () -> saveMark("FARM")));
modalButtons.add(new ModalBtn(cx + 39, cy + 8, 66, 22, "Точка", 0xFF22D3EE, () -> saveMark("POINT")));
}
@Override
public boolean mouseClicked(Click click, boolean doubled) {
if (click.button() == 0) {
for (ModalBtn btn : modalButtons) {
if (btn.contains(click.x(), click.y())) {
btn.action.run();
return true;
}
}
}
return super.mouseClicked(click, doubled);
}
private void saveMark(String type) {
String title = nameField.getText().trim();
if (title.isEmpty()) {
title = switch (type) {
case "CHEST" -> "Сундук";
case "FARM" -> "Ферма";
default -> "Точка";
};
}
MapStorage.Waypoint wp = new MapStorage.Waypoint(title, type, targetX, targetY, targetZ);
MapStorage.data.marks.add(wp);
selectedMark = wp;
MapStorage.save();
if (client != null) client.setScreen(new FullMapScreen());
}
@Override
public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
ctx.fill(0, 0, width, height, 0xBB07090D);
int cx = width / 2;
int cy = height / 2;
ctx.fill(cx - 120, cy - 55, cx + 120, cy + 44, 0xF512161F);
drawBorder(ctx, cx - 120, cy - 55, 240, 99, 0xFFD4AF37);
super.render(ctx, mouseX, mouseY, delta);
ctx.drawCenteredTextWithShadow(textRenderer, "★ Новая метка (" + targetX + ", " + targetZ + ")", cx, cy - 42, 0xFFFFD700);
for (ModalBtn btn : modalButtons) {
boolean hover = btn.contains(mouseX, mouseY);
ctx.fill(btn.x, btn.y, btn.x + btn.w, btn.y + btn.h, hover ? 0xFF262F40 : 0xFF1A202C);
drawBorder(ctx, btn.x, btn.y, btn.w, btn.h, hover ? btn.color : 0x66D4AF37);
int tw = textRenderer.getWidth(btn.label);
ctx.drawTextWithShadow(textRenderer, btn.label, btn.x + (btn.w - tw) / 2, btn.y + 7, btn.color);
}
}
}
}
