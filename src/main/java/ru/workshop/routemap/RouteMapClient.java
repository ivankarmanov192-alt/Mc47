package ru.workshop.routemap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class RouteMapClient implements ClientModInitializer {
    private static KeyBinding openMapKey;
    private static KeyBinding addMarkKey;
    private static MapStorage.Waypoint selectedMark = null;
    private int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        MapStorage.load();

        openMapKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.routemap.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, KeyBinding.Category.MISC
        ));
        addMarkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.routemap.mark", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

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

            tickCounter++;
            if (tickCounter >= 10) {
                tickCounter = 0;
                recordStep(client);
            }
        });
    }

    private void recordStep(MinecraftClient client) {
        int px = (int) Math.floor(client.player.getX());
        int pz = (int) Math.floor(client.player.getZ());

        List<MapStorage.RoutePoint> trail = MapStorage.data.trail;
        if (trail.isEmpty()) {
            trail.add(new MapStorage.RoutePoint(px, pz));
            MapStorage.save();
            return;
        }

        MapStorage.RoutePoint last = trail.get(trail.size() - 1);
        double dist = Math.hypot(px - last.x, pz - last.z);

        if (dist > 120) {
            trail.add(new MapStorage.RoutePoint(Integer.MIN_VALUE, Integer.MIN_VALUE));
            trail.add(new MapStorage.RoutePoint(px, pz));
            MapStorage.save();
        } else if (dist >= 3) {
            trail.add(new MapStorage.RoutePoint(px, pz));
            if (trail.size() > 15000) {
                trail.remove(0);
            }
            MapStorage.save();
        }
    }

    public static class FullMapScreen extends Screen {
        private double centerX, centerZ;
        private double zoom = 1.0;

        public FullMapScreen() {
            super(Text.literal("Карта маршрута"));
        }

        @Override
        protected void init() {
            if (client != null && client.player != null) {
                centerX = client.player.getX();
                centerZ = client.player.getZ();
            }

            addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> zoom = Math.min(8.0, zoom * 1.4))
                    .dimensions(10, 10, 24, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> zoom = Math.max(0.15, zoom / 1.4))
                    .dimensions(38, 10, 24, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Я"), b -> {
                if (client != null && client.player != null) {
                    centerX = client.player.getX();
                    centerZ = client.player.getZ();
                }
            }).dimensions(66, 10, 24, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("+ Метка (B)"), b -> {
                if (client != null && client.player != null) {
                    client.setScreen(new AddMarkScreen(
                            (int) Math.floor(client.player.getX()),
                            (int) Math.floor(client.player.getY()),
                            (int) Math.floor(client.player.getZ())
                    ));
                }
            }).dimensions(94, 10, 80, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Очистить след"), b -> {
                MapStorage.data.trail.clear();
                MapStorage.save();
            }).dimensions(width - 105, 10, 95, 20).build());
        }

        @Override
        public boolean mouseClicked(Click click, boolean doubled) {
            if (super.mouseClicked(click, doubled)) return true;
            if (client == null || client.player == null) return false;

            double mx = click.x();
            double my = click.y();
            if (my <= 35 || my >= height - 18) return false;

            int midX = width / 2;
            int midY = height / 2;
            List<MapStorage.Waypoint> marks = MapStorage.data.marks;

            // Клик по существующей метке
            for (int i = marks.size() - 1; i >= 0; i--) {
                MapStorage.Waypoint wp = marks.get(i);
                int sx = (int) (midX + (wp.x - centerX) * zoom);
                int sy = (int) (midY + (wp.z - centerZ) * zoom);

                if (Math.hypot(mx - sx, my - sy) <= 8) {
                    if (click.button() == 0) { // ЛКМ - выбрать как цель
                        selectedMark = (selectedMark == wp) ? null : wp;
                        return true;
                    } else if (click.button() == 1) { // ПКМ - удалить метку
                        if (selectedMark == wp) selectedMark = null;
                        marks.remove(i);
                        MapStorage.save();
                        return true;
                    }
                }
            }

            // ПКМ по пустому месту - поставить новую метку
            if (click.button() == 1) {
                int wx = (int) Math.round(centerX + (mx - midX) / zoom);
                int wz = (int) Math.round(centerZ + (my - midY) / zoom);
                client.setScreen(new AddMarkScreen(wx, (int) Math.floor(client.player.getY()), wz));
                return true;
            }

            return false;
        }

        @Override
        public boolean mouseDragged(Click click, double deltaX, double deltaY) {
            centerX -= deltaX / zoom;
            centerZ -= deltaY / zoom;
            return true;
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (verticalAmount > 0) zoom = Math.min(8.0, zoom * 1.2);
            else if (verticalAmount < 0) zoom = Math.max(0.15, zoom / 1.2);
            return true;
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            ctx.fill(0, 0, width, height, 0xE6101318);
            super.render(ctx, mouseX, mouseY, delta);

            if (client == null || client.player == null) return;

            int midX = width / 2;
            int midY = height / 2;

            // Сетка каждые 100 блоков
            int gridStep = 100;
            int startWorldX = (int) Math.floor((centerX - midX / zoom) / gridStep) * gridStep;
            int endWorldX = (int) Math.ceil((centerX + midX / zoom) / gridStep) * gridStep;
            for (int wx = startWorldX; wx <= endWorldX; wx += gridStep) {
                int sx = (int) (midX + (wx - centerX) * zoom);
                ctx.fill(sx, 36, sx + 1, height - 20, 0x22FFFFFF);
            }
            int startWorldZ = (int) Math.floor((centerZ - midY / zoom) / gridStep) * gridStep;
            int endWorldZ = (int) Math.ceil((centerZ + midY / zoom) / gridStep) * gridStep;
            for (int wz = startWorldZ; wz <= endWorldZ; wz += gridStep) {
                int sy = (int) (midY + (wz - centerZ) * zoom);
                ctx.fill(0, sy, width, sy + 1, 0x22FFFFFF);
            }

            // След маршрута
            List<MapStorage.RoutePoint> trail = MapStorage.data.trail;
            for (int i = 1; i < trail.size(); i++) {
                MapStorage.RoutePoint p1 = trail.get(i - 1);
                MapStorage.RoutePoint p2 = trail.get(i);
                if (p1.x == Integer.MIN_VALUE || p2.x == Integer.MIN_VALUE) continue;

                int x1 = (int) (midX + (p1.x - centerX) * zoom);
                int y1 = (int) (midY + (p1.z - centerZ) * zoom);
                int x2 = (int) (midX + (p2.x - centerX) * zoom);
                int y2 = (int) (midY + (p2.z - centerZ) * zoom);
                drawLine(ctx, x1, y1, x2, y2, 0xFF38BDF8);
            }

            int px = (int) (midX + (client.player.getX() - centerX) * zoom);
            int py = (int) (midY + (client.player.getZ() - centerZ) * zoom);

            // Линия до выбранной цели
            if (selectedMark != null && MapStorage.data.marks.contains(selectedMark)) {
                int tx = (int) (midX + (selectedMark.x - centerX) * zoom);
                int ty = (int) (midY + (selectedMark.z - centerZ) * zoom);
                drawLine(ctx, px, py, tx, ty, 0xFFFB923C);
            } else {
                selectedMark = null;
            }

            // Метки
            for (MapStorage.Waypoint wp : MapStorage.data.marks) {
                int sx = (int) (midX + (wp.x - centerX) * zoom);
                int sy = (int) (midY + (wp.z - centerZ) * zoom);
                int color = switch (wp.type) {
                    case "CHEST" -> 0xFFFACC15;
case "FARM" -> 0xFF4ADE80;
default -> 0xFF22D3EE;
};
int size = (wp == selectedMark) ? 5 : 3;
ctx.fill(sx - size - 1, sy - size - 1, sx + size + 1, sy + size + 1, 0xFF000000);
ctx.fill(sx - size, sy - size, sx + size, sy + size, color);
ctx.drawTextWithShadow(textRenderer, wp.name + " (" + wp.x + ", " + wp.z + ")", sx + 6, sy - 4, color);
}
// Игрок
ctx.fill(px - 4, py - 4, px + 4, py + 4, 0xFFFFFFFF);
ctx.fill(px - 3, py - 3, px + 3, py + 3, 0xFFEF4444);
// Нижняя панель
String targetText = "";
if (selectedMark != null) {
int dist = (int) Math.hypot(selectedMark.x - client.player.getX(), selectedMark.z - client.player.getZ());
targetText = " | Цель: " + selectedMark.name + " (" + dist + " бл.)";
}
String info = String.format("Корды: %d, %d%s | ЛКМ по метке = цель, ПКМ = поставить/удалить",
(int) client.player.getX(), (int) client.player.getZ(), targetText);
ctx.drawTextWithShadow(textRenderer, info, 10, height - 14, 0xFFCBD5E1);
}
private void drawLine(DrawContext ctx, int x0, int y0, int x1, int y1, int color) {
int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
int err = dx + dy, e2;
while (true) {
if (x0 >= 0 && x0 < width && y0 >= 34 && y0 < height - 18) {
ctx.fill(x0, y0, x0 + 2, y0 + 2, color);
}
if (x0 == x1 && y0 == y1) break;
e2 = 2 * err;
if (e2 >= dy) { err += dy; x0 += sx; }
if (e2 <= dx) { err += dx; y0 += sy; }
}
}
}
public static class AddMarkScreen extends Screen {
private TextFieldWidget nameField;
private final int targetX, targetY, targetZ;
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
nameField = new TextFieldWidget(textRenderer, cx - 100, cy - 35, 200, 20, Text.literal("Название"));
nameField.setPlaceholder(Text.literal("Например: Сундук у скалы"));
addDrawableChild(nameField);
setInitialFocus(nameField);
addDrawableChild(ButtonWidget.builder(Text.literal("Сундук"), b -> saveMark("CHEST"))
.dimensions(cx - 100, cy - 5, 64, 20).build());
addDrawableChild(ButtonWidget.builder(Text.literal("Ферма"), b -> saveMark("FARM"))
.dimensions(cx - 32, cy - 5, 64, 20).build());
addDrawableChild(ButtonWidget.builder(Text.literal("Точка"), b -> saveMark("POINT"))
.dimensions(cx + 36, cy - 5, 64, 20).build());
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
ctx.fill(0, 0, width, height, 0xAA000000);
super.render(ctx, mouseX, mouseY, delta);
ctx.drawCenteredTextWithShadow(textRenderer,
"Метка на координатах: " + targetX + ", " + targetZ,
width / 2, height / 2 - 55, 0xFFFFFFFF);
}
}
}
