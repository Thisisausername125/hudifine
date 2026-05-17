package com.hudifine.client.hud;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;

public final class HudDataSource {
    private static final DateTimeFormatter CLOCK_FORMAT_24 = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter CLOCK_FORMAT_12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ROOT);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Minecraft client;
    private final Deque<Integer> fpsWindow = new ArrayDeque<>();
    private final Deque<Double> frameMsWindow = new ArrayDeque<>();
    private final Deque<Long> leftClickTimes = new ArrayDeque<>();
    private final Deque<Long> rightClickTimes = new ArrayDeque<>();

    private long sessionStartMs = System.currentTimeMillis();
    private long lastTickMs = 0L;

    private double lastPlayerX;
    private double lastPlayerZ;
    private double playerSpeed;

    private double lastMouseX;
    private double lastMouseY;
    private double mouseDeltaX;
    private double mouseDeltaY;

    private float lastHealth = 20.0f;
    private float lastDamage;
    private String lastDamageSource = "none";

    private long lastFrameNs = 0L;
    private double lastFrameMs = 0.0;

    public HudDataSource(Minecraft client) {
        this.client = client;
    }

    public void tick() {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (lastTickMs <= 0L) {
            lastTickMs = now;
            lastPlayerX = player.getX();
            lastPlayerZ = player.getZ();
            lastHealth = player.getHealth();
            return;
        }

        double dt = Math.max(0.001, (now - lastTickMs) / 1000.0);
        double dx = player.getX() - lastPlayerX;
        double dz = player.getZ() - lastPlayerZ;
        playerSpeed = Math.sqrt(dx * dx + dz * dz) / dt;

        lastPlayerX = player.getX();
        lastPlayerZ = player.getZ();
        lastTickMs = now;

        float health = player.getHealth();
        if (health < lastHealth) {
            lastDamage = lastHealth - health;
            lastDamageSource = player.isOnFire() ? "fire" : (player.fallDistance > 0.0f ? "fall" : "generic");
        }
        lastHealth = health;

        pruneClickWindow(leftClickTimes, now);
        pruneClickWindow(rightClickTimes, now);

        double scaledMouseX = getScaledMouseX();
        double scaledMouseY = getScaledMouseY();
        mouseDeltaX = scaledMouseX - lastMouseX;
        mouseDeltaY = scaledMouseY - lastMouseY;
        lastMouseX = scaledMouseX;
        lastMouseY = scaledMouseY;
    }

    public void beginFrame() {
        long now = System.nanoTime();
        if (lastFrameNs > 0L) {
            lastFrameMs = (now - lastFrameNs) / 1_000_000.0;
            frameMsWindow.addLast(lastFrameMs);
            while (frameMsWindow.size() > 100) {
                frameMsWindow.removeFirst();
            }
        }
        lastFrameNs = now;

        int fps = client.getFps();
        fpsWindow.addLast(fps);
        while (fpsWindow.size() > 100) {
            fpsWindow.removeFirst();
        }
    }

    public void recordClick(int button) {
        long now = System.currentTimeMillis();
        if (button == 0) {
            leftClickTimes.addLast(now);
            pruneClickWindow(leftClickTimes, now);
        } else if (button == 1) {
            rightClickTimes.addLast(now);
            pruneClickWindow(rightClickTimes, now);
        }
    }

    public Object getValue(String path) {
        LocalPlayer player = client.player;
        ClientLevel world = client.level;

        if (path == null || path.isBlank()) {
            return "";
        }

        return switch (path) {
            case "player.health" -> player != null ? (double) player.getHealth() : 0.0;
            case "player.maxHealth" -> player != null ? (double) (player.getMaxHealth() + player.getAbsorptionAmount()) : 0.0;
            case "player.absorption" -> player != null ? (double) player.getAbsorptionAmount() : 0.0;
            case "player.hunger" -> player != null ? player.getFoodData().getFoodLevel() : 0;
            case "player.saturation" -> player != null ? (double) player.getFoodData().getSaturationLevel() : 0.0;
            case "player.exhaustion" -> 0.0;
            case "player.air" -> player != null ? player.getAirSupply() : 0;
            case "player.xp" -> player != null ? player.totalExperience : 0;
            case "player.xpLevel" -> player != null ? player.experienceLevel : 0;
            case "player.xpProgress" -> player != null ? (double) player.experienceProgress : 0.0;
            case "player.speed" -> playerSpeed;
            case "player.isSprinting" -> player != null && player.isSprinting();
            case "player.isSneaking" -> player != null && player.isCrouching();
            case "player.isSwimming" -> player != null && player.isSwimming();
            case "player.isFlying" -> player != null && player.getAbilities().flying;
            case "player.isFalling" -> player != null && !player.onGround() && player.getDeltaMovement().y < 0.0;
            case "player.isOnGround" -> player != null && player.onGround();
            case "player.isInWater" -> player != null && player.isInWater();
            case "player.isInLava" -> player != null && player.isInLava();
            case "player.fallDistance" -> player != null ? (double) player.fallDistance : 0.0;
            case "player.reachDistance" -> 0.0;
            case "player.name" -> player != null ? player.getGameProfile().name() : "";
            case "player.uuid" -> player != null ? player.getStringUUID() : "";
            case "player.gamemode" -> client.gameMode != null && client.gameMode.getPlayerMode() != null
                ? client.gameMode.getPlayerMode().getName().toLowerCase(Locale.ROOT)
                : "unknown";
            case "player.score" -> player != null ? player.getScore() : 0;
            case "player.ping" -> getPlayerPing();

            case "world.x" -> player != null ? player.getX() : 0.0;
            case "world.y" -> player != null ? player.getY() : 0.0;
            case "world.z" -> player != null ? player.getZ() : 0.0;
            case "world.blockX" -> player != null ? player.getBlockX() : 0;
            case "world.blockY" -> player != null ? player.getBlockY() : 0;
            case "world.blockZ" -> player != null ? player.getBlockZ() : 0;
            case "world.chunkX" -> player != null ? player.chunkPosition().x() : 0;
            case "world.chunkZ" -> player != null ? player.chunkPosition().z() : 0;
            case "world.facing" -> player != null ? getCardinalDirection(player.getYRot()) : "N";
            case "world.facingDegrees" -> player != null ? normalizeYaw(player.getYRot()) : 0.0;
            case "world.pitch" -> player != null ? (double) player.getXRot() : 0.0;
            case "world.yaw" -> player != null ? normalizeYaw(player.getYRot()) : 0.0;
            case "world.dimension" -> world != null ? world.dimension().identifier().toString() : "minecraft:overworld";
            case "world.biome" -> getBiomeName();
            case "world.lightLevel" -> getLightLevel(LightLayer.BLOCK);
            case "world.skyLightLevel" -> getLightLevel(LightLayer.SKY);
            case "world.moonPhase" -> 0;
            case "world.isRaining" -> world != null && world.isRaining();
            case "world.isThundering" -> world != null && world.isThundering();
            case "world.rainStrength" -> world != null ? (double) world.getRainLevel(1.0f) : 0.0;
            case "world.difficulty" -> world != null ? world.getLevelData().getDifficulty().getSerializedName() : "normal";
            case "world.name" -> getWorldName();
            case "world.seed" -> getSeedLabel();
            case "world.spawnX" -> getSpawnPos().getX();
            case "world.spawnZ" -> getSpawnPos().getZ();
            case "world.distanceToSpawn" -> getDistanceToSpawn();

            case "perf.fps" -> client.getFps();
            case "perf.fpsAvg" -> avgInt(fpsWindow);
            case "perf.fpsMin" -> minInt(fpsWindow);
            case "perf.fpsMax" -> maxInt(fpsWindow);
            case "perf.frameTime" -> lastFrameMs;
            case "perf.frameTimeAvg" -> avgDouble(frameMsWindow);
            case "perf.tps" -> 20.0;
            case "perf.mspt" -> 50.0;
            case "perf.chunkUpdates" -> 0;
            case "perf.renderedChunks" -> 0;
            case "perf.entities" -> world != null ? world.getEntityCount() : 0;
            case "perf.blockEntities" -> 0;
            case "perf.particles" -> 0;

            case "combat.attackCooldown" -> player != null ? (double) player.getAttackStrengthScale(0.0f) : 0.0;
            case "combat.attackCooldownMs" -> (int) ((1.0 - (double) (player != null ? player.getAttackStrengthScale(0.0f) : 0.0f)) * 1000.0);
            case "combat.isAttacking" -> client.options != null && client.options.keyAttack.isDown();
            case "combat.lastDamage" -> (double) lastDamage;
            case "combat.lastDamageSource" -> lastDamageSource;
            case "combat.killCount" -> 0;
            case "combat.deathCount" -> 0;
            case "combat.kdr" -> 0.0;
            case "combat.targetName" -> getTargetName();
            case "combat.targetHealth" -> getTargetHealth();
            case "combat.targetMaxHealth" -> getTargetMaxHealth();
            case "combat.targetDistance" -> getTargetDistance();
            case "combat.targetType" -> getTargetType();
            case "combat.isInCombat" -> player != null && player.hurtTime > 0;
            case "combat.combatTimer" -> 0;

            case "inventory.hotbarSlot" -> player != null ? player.getInventory().getSelectedSlot() : 0;
            case "inventory.mainHandItem" -> player != null ? itemId(player.getMainHandItem()) : "minecraft:air";
            case "inventory.mainHandCount" -> player != null ? player.getMainHandItem().getCount() : 0;
            case "inventory.mainHandDurability" -> player != null ? durability(player.getMainHandItem()) : 0;
            case "inventory.mainHandMaxDurability" -> player != null ? player.getMainHandItem().getMaxDamage() : 0;
            case "inventory.mainHandDurabilityPct" -> player != null ? durabilityPct(player.getMainHandItem()) : 0.0;
            case "inventory.offHandItem" -> player != null ? itemId(player.getOffhandItem()) : "minecraft:air";
            case "inventory.offHandCount" -> player != null ? player.getOffhandItem().getCount() : 0;
            case "inventory.offHandDurability" -> player != null ? durability(player.getOffhandItem()) : 0;
            case "inventory.helmetItem" -> player != null ? itemId(player.getItemBySlot(EquipmentSlot.HEAD)) : "minecraft:air";
            case "inventory.helmetDurability" -> player != null ? durability(player.getItemBySlot(EquipmentSlot.HEAD)) : 0;
            case "inventory.helmetDurabilityPct" -> player != null ? durabilityPct(player.getItemBySlot(EquipmentSlot.HEAD)) : 0.0;
            case "inventory.chestplateItem" -> player != null ? itemId(player.getItemBySlot(EquipmentSlot.CHEST)) : "minecraft:air";
            case "inventory.chestplateDurability" -> player != null ? durability(player.getItemBySlot(EquipmentSlot.CHEST)) : 0;
            case "inventory.chestplateDurabilityPct" -> player != null ? durabilityPct(player.getItemBySlot(EquipmentSlot.CHEST)) : 0.0;
            case "inventory.leggingsItem" -> player != null ? itemId(player.getItemBySlot(EquipmentSlot.LEGS)) : "minecraft:air";
            case "inventory.legginsDurability", "inventory.leggingsDurability" -> player != null ? durability(player.getItemBySlot(EquipmentSlot.LEGS)) : 0;
            case "inventory.leggingsDurabilityPct" -> player != null ? durabilityPct(player.getItemBySlot(EquipmentSlot.LEGS)) : 0.0;
            case "inventory.bootsItem" -> player != null ? itemId(player.getItemBySlot(EquipmentSlot.FEET)) : "minecraft:air";
            case "inventory.bootsDurability" -> player != null ? durability(player.getItemBySlot(EquipmentSlot.FEET)) : 0;
            case "inventory.bootsDurabilityPct" -> player != null ? durabilityPct(player.getItemBySlot(EquipmentSlot.FEET)) : 0.0;
            case "inventory.arrowCount" -> countInInventory(Items.ARROW);
            case "inventory.totalSlots" -> 36;
            case "inventory.usedSlots" -> usedSlots();
            case "inventory.emptySlots" -> 36 - usedSlots();
            case "inventory.xpBottleCount" -> countInInventory(Items.EXPERIENCE_BOTTLE);

            case "input.forward" -> client.options != null && client.options.keyUp.isDown();
            case "input.backward" -> client.options != null && client.options.keyDown.isDown();
            case "input.left" -> client.options != null && client.options.keyLeft.isDown();
            case "input.right" -> client.options != null && client.options.keyRight.isDown();
            case "input.jump" -> client.options != null && client.options.keyJump.isDown();
            case "input.sneak" -> client.options != null && client.options.keyShift.isDown();
            case "input.sprint" -> client.options != null && client.options.keySprint.isDown();
            case "input.attack" -> client.options != null && client.options.keyAttack.isDown();
            case "input.use" -> client.options != null && client.options.keyUse.isDown();
            case "input.drop" -> client.options != null && client.options.keyDrop.isDown();
            case "input.inventory" -> client.options != null && client.options.keyInventory.isDown();
            case "input.swap" -> client.options != null && client.options.keySwapOffhand.isDown();
            case "input.hotbar1" -> isHotbarPressed(0);
            case "input.hotbar2" -> isHotbarPressed(1);
            case "input.hotbar3" -> isHotbarPressed(2);
            case "input.hotbar4" -> isHotbarPressed(3);
            case "input.hotbar5" -> isHotbarPressed(4);
            case "input.hotbar6" -> isHotbarPressed(5);
            case "input.hotbar7" -> isHotbarPressed(6);
            case "input.hotbar8" -> isHotbarPressed(7);
            case "input.hotbar9" -> isHotbarPressed(8);
            case "input.cps" -> currentCps(leftClickTimes);
            case "input.rcps" -> currentCps(rightClickTimes);
            case "input.mouseX" -> (int) getScaledMouseX();
            case "input.mouseY" -> (int) getScaledMouseY();
            case "input.mouseDeltaX" -> mouseDeltaX;
            case "input.mouseDeltaY" -> mouseDeltaY;
            case "input.sensitivity" -> getSensitivity();

            case "env.timeOfDay" -> world != null ? (int) (world.getOverworldClockTime() % 24000L) : 0;
            case "env.timeString" -> toMinecraftTimeString(world != null ? (int) (world.getOverworldClockTime() % 24000L) : 0);
            case "env.dayCount" -> world != null ? (int) (world.getOverworldClockTime() / 24000L) : 0;
            case "env.temperature" -> player != null && world != null ? world.getBiome(player.blockPosition()).value().getBaseTemperature() : 0.0;
            case "env.humidity" -> 0.0;
            case "env.canSeeSky" -> player != null && world != null && world.getBrightness(LightLayer.SKY, player.blockPosition()) > 0;
            case "env.isUnderground" -> player != null && world != null && world.getBrightness(LightLayer.SKY, player.blockPosition()) <= 0;
            case "env.nearestVillageDistance" -> -1.0;
            case "env.isInVillage" -> false;
            case "env.nearestPlayerDistance" -> nearestPlayerDistance();
            case "env.nearestPlayerName" -> nearestPlayerName();

            case "game.isPaused" -> client.isPaused();
            case "game.isInGui" -> client.screen != null;
            case "game.isInventoryOpen" -> client.screen instanceof InventoryScreen;
            case "game.isInBed" -> player != null && player.isSleeping();
            case "game.isRiding" -> player != null && player.getVehicle() != null;
            case "game.ridingEntityType" -> player != null && player.getVehicle() != null ? entityType(player.getVehicle()) : "";
            case "game.ridingEntityHealth" -> player != null && player.getVehicle() instanceof LivingEntity living ? (double) living.getHealth() : 0.0;
            case "game.isElytraFlying" -> player != null && player.isFallFlying();
            case "game.elytraHealth" -> getElytraDurability();
            case "game.elytraSpeed" -> playerSpeed;
            case "game.potionEffects" -> getPotionEffectList();
            case "game.potionEffectAmplifiers" -> getPotionAmplifierMap();
            case "game.potionEffectDurations" -> getPotionDurationMap();
            case "game.scoreboardObjective" -> getScoreboardObjective();
            case "game.scoreboardScore" -> getScoreboardScore();
            case "game.bossBarName" -> "";
            case "game.bossBarProgress" -> 0.0;
            case "game.isSpectatingEntity" -> player != null && !player.isAlive();
            case "game.spectatingEntityType" -> client.getCameraEntity() != null ? entityType(client.getCameraEntity()) : "";

            case "server.name" -> getServerName();
            case "server.motd" -> getServerMotd();
            case "server.playerCount" -> getOnlinePlayerCount();
            case "server.maxPlayers" -> getMaxPlayers();
            case "server.ping" -> getPlayerPing();
            case "server.tps" -> 20.0;
            case "server.isLAN" -> client.getCurrentServer() != null && client.getCurrentServer().isLan();
            case "server.isSingleplayer" -> client.isSingleplayer();
            case "server.version" -> client.getLaunchedVersion();

            case "clock.hour" -> LocalDateTime.now().getHour();
            case "clock.minute" -> LocalDateTime.now().getMinute();
            case "clock.second" -> LocalDateTime.now().getSecond();
            case "clock.timeString" -> LocalDateTime.now().format(CLOCK_FORMAT_24);
            case "clock.timeString12" -> LocalDateTime.now().format(CLOCK_FORMAT_12);
            case "clock.sessionTime" -> (int) ((System.currentTimeMillis() - sessionStartMs) / 1000L);
            case "clock.sessionTimeString" -> formatSessionTime((int) ((System.currentTimeMillis() - sessionStartMs) / 1000L));
            case "clock.date" -> LocalDateTime.now().format(DATE_FORMAT);
            case "clock.unixTimestamp" -> System.currentTimeMillis() / 1000L;
            default -> "";
        };
    }

    private static void pruneClickWindow(Deque<Long> queue, long nowMs) {
        while (!queue.isEmpty() && nowMs - queue.peekFirst() > 1000L) {
            queue.removeFirst();
        }
    }

    private int currentCps(Deque<Long> queue) {
        long now = System.currentTimeMillis();
        pruneClickWindow(queue, now);
        return queue.size();
    }

    private String getCardinalDirection(float yaw) {
        double normalized = normalizeYaw(yaw);
        String[] directions = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int index = (int) Math.round(normalized / 45.0) % 8;
        return directions[index];
    }

    private double normalizeYaw(float yaw) {
        double normalized = yaw % 360.0;
        if (normalized < 0.0) {
            normalized += 360.0;
        }
        return normalized;
    }

    private String getBiomeName() {
        if (client.level == null || client.player == null) {
            return "minecraft:plains";
        }

        return client.level.getBiome(client.player.blockPosition())
            .unwrapKey()
            .map(key -> key.identifier().toString())
            .orElse("minecraft:plains");
    }

    private int getLightLevel(LightLayer type) {
        if (client.level == null || client.player == null) {
            return 0;
        }

        return client.level.getBrightness(type, client.player.blockPosition());
    }

    private String getWorldName() {
        if (client.isSingleplayer()) {
            return "singleplayer";
        }

        ServerData data = client.getCurrentServer();
        return data != null ? data.name : "server";
    }

    private String getSeedLabel() {
        if (!client.isSingleplayer()) {
            return "hidden";
        }

        if (client.getSingleplayerServer() == null) {
            return "unknown";
        }

        return String.valueOf(client.getSingleplayerServer().overworld().getSeed());
    }

    private BlockPos getSpawnPos() {
        if (client.level == null) {
            return BlockPos.ZERO;
        }

        return client.level.getRespawnData().pos();
    }

    private double getDistanceToSpawn() {
        if (client.player == null || client.level == null) {
            return 0.0;
        }

        BlockPos spawn = getSpawnPos();
        double dx = client.player.getX() - spawn.getX();
        double dz = client.player.getZ() - spawn.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private int getPlayerPing() {
        if (client.player == null || client.getConnection() == null) {
            return 0;
        }

        PlayerInfo info = client.getConnection().getPlayerInfo(client.player.getUUID());
        return info != null ? info.getLatency() : 0;
    }

    private String getTargetName() {
        if (client.hitResult instanceof EntityHitResult entityHitResult) {
            return entityHitResult.getEntity().getName().getString();
        }

        return "";
    }

    private double getTargetHealth() {
        if (client.hitResult instanceof EntityHitResult entityHitResult && entityHitResult.getEntity() instanceof LivingEntity living) {
            return living.getHealth();
        }

        return 0.0;
    }

    private double getTargetMaxHealth() {
        if (client.hitResult instanceof EntityHitResult entityHitResult && entityHitResult.getEntity() instanceof LivingEntity living) {
            return living.getMaxHealth();
        }

        return 0.0;
    }

    private double getTargetDistance() {
        if (client.player == null || !(client.hitResult instanceof EntityHitResult entityHitResult)) {
            return 0.0;
        }

        return client.player.distanceTo(entityHitResult.getEntity());
    }

    private String getTargetType() {
        if (client.hitResult instanceof EntityHitResult entityHitResult) {
            return entityType(entityHitResult.getEntity());
        }

        return "";
    }

    private String itemId(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private int durability(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem()) {
            return 0;
        }

        return Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
    }

    private double durabilityPct(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem() || stack.getMaxDamage() <= 0) {
            return 0.0;
        }

        return (double) durability(stack) / (double) stack.getMaxDamage();
    }

    private int countInInventory(Item item) {
        if (client.player == null) {
            return 0;
        }

        int count = 0;
        List<ItemStack> nonEquipment = client.player.getInventory().getNonEquipmentItems();
        for (ItemStack stack : nonEquipment) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }

        return count;
    }

    private int usedSlots() {
        if (client.player == null) {
            return 0;
        }

        int used = 0;
        List<ItemStack> nonEquipment = client.player.getInventory().getNonEquipmentItems();
        for (int i = 0; i < 36 && i < nonEquipment.size(); i++) {
            if (!nonEquipment.get(i).isEmpty()) {
                used++;
            }
        }

        return used;
    }

    private boolean isHotbarPressed(int index) {
        if (client.options == null) {
            return false;
        }

        try {
            if (index >= 0 && index < client.options.keyHotbarSlots.length) {
                return client.options.keyHotbarSlots[index].isDown();
            }
        } catch (Throwable ignored) {
            // Keep this robust across minor API adjustments.
        }

        return false;
    }

    private double getScaledMouseX() {
        if (client.getWindow() == null) {
            return 0.0;
        }

        return client.mouseHandler.getScaledXPos(client.getWindow());
    }

    private double getScaledMouseY() {
        if (client.getWindow() == null) {
            return 0.0;
        }

        return client.mouseHandler.getScaledYPos(client.getWindow());
    }

    private double getSensitivity() {
        if (client.options == null) {
            return 0.5;
        }

        try {
            return client.options.sensitivity().get();
        } catch (Throwable ignored) {
            return 0.5;
        }
    }

    private static String toMinecraftTimeString(int ticks) {
        int totalMinutes = (int) (((ticks + 6000L) % 24000L) * 60L / 1000L);
        int hour = (totalMinutes / 60) % 24;
        int minute = totalMinutes % 60;

        String suffix = hour >= 12 ? "PM" : "AM";
        int displayHour = hour % 12;
        if (displayHour == 0) {
            displayHour = 12;
        }

        return String.format(Locale.ROOT, "%d:%02d %s", displayHour, minute, suffix);
    }

    private double nearestPlayerDistance() {
        if (client.player == null || client.level == null) {
            return -1.0;
        }

        double min = Double.MAX_VALUE;
        for (AbstractClientPlayer other : client.level.players()) {
            if (other == client.player) {
                continue;
            }
            min = Math.min(min, client.player.distanceTo(other));
        }

        return min == Double.MAX_VALUE ? -1.0 : min;
    }

    private String nearestPlayerName() {
        if (client.player == null || client.level == null) {
            return "";
        }

        double min = Double.MAX_VALUE;
        String name = "";

        for (AbstractClientPlayer other : client.level.players()) {
            if (other == client.player) {
                continue;
            }

            double distance = client.player.distanceTo(other);
            if (distance < min) {
                min = distance;
                name = other.getGameProfile().name();
            }
        }

        return name;
    }

    private int getElytraDurability() {
        if (client.player == null) {
            return 0;
        }

        ItemStack chest = client.player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA) {
            return 0;
        }

        return durability(chest);
    }

    private List<String> getPotionEffectList() {
        if (client.player == null) {
            return List.of();
        }

        List<String> list = new ArrayList<>();
        for (MobEffectInstance effect : client.player.getActiveEffects()) {
            list.add(BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString());
        }

        return list;
    }

    private String getPotionAmplifierMap() {
        if (client.player == null) {
            return "{}";
        }

        StringBuilder builder = new StringBuilder("{");
        boolean first = true;

        for (MobEffectInstance effect : client.player.getActiveEffects()) {
            if (!first) {
                builder.append(", ");
            }
            builder.append(BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value())).append(":").append(effect.getAmplifier());
            first = false;
        }

        builder.append("}");
        return builder.toString();
    }

    private String getPotionDurationMap() {
        if (client.player == null) {
            return "{}";
        }

        StringBuilder builder = new StringBuilder("{");
        boolean first = true;

        for (MobEffectInstance effect : client.player.getActiveEffects()) {
            if (!first) {
                builder.append(", ");
            }
            builder.append(BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value())).append(":").append(effect.getDuration());
            first = false;
        }

        builder.append("}");
        return builder.toString();
    }

    private String getScoreboardObjective() {
        if (client.level == null || client.player == null || client.level.getScoreboard() == null) {
            return "";
        }

        Objective objective = client.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        return objective != null ? objective.getName() : "";
    }

    private int getScoreboardScore() {
        if (client.level == null || client.player == null || client.level.getScoreboard() == null) {
            return 0;
        }

        var scoreboard = client.level.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) {
            return 0;
        }

        return scoreboard.getOrCreatePlayerScore(client.player, objective).get();
    }

    private String getServerName() {
        if (client.isSingleplayer()) {
            return "singleplayer";
        }

        ServerData data = client.getCurrentServer();
        return data != null ? data.ip : "unknown";
    }

    private String getServerMotd() {
        ServerData data = client.getCurrentServer();
        if (data == null || data.motd == null) {
            return "";
        }

        return data.motd.getString();
    }

    private int getOnlinePlayerCount() {
        if (client.getConnection() == null) {
            return 0;
        }

        return client.getConnection().getOnlinePlayers().size();
    }

    private int getMaxPlayers() {
        ServerData data = client.getCurrentServer();
        if (data == null || data.players == null) {
            return getOnlinePlayerCount();
        }

        return data.players.max();
    }

    private static String formatSessionTime(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;

        if (hours > 0) {
            return String.format(Locale.ROOT, "%dh %02dm", hours, minutes);
        }
        if (minutes > 0) {
            return String.format(Locale.ROOT, "%dm %02ds", minutes, secs);
        }

        return String.format(Locale.ROOT, "%ds", secs);
    }

    private static double avgInt(Deque<Integer> deque) {
        if (deque.isEmpty()) {
            return 0.0;
        }

        long sum = 0;
        for (int value : deque) {
            sum += value;
        }

        return (double) sum / (double) deque.size();
    }

    private static int minInt(Deque<Integer> deque) {
        if (deque.isEmpty()) {
            return 0;
        }

        int min = Integer.MAX_VALUE;
        for (int value : deque) {
            min = Math.min(min, value);
        }

        return min;
    }

    private static int maxInt(Deque<Integer> deque) {
        if (deque.isEmpty()) {
            return 0;
        }

        int max = Integer.MIN_VALUE;
        for (int value : deque) {
            max = Math.max(max, value);
        }

        return max;
    }

    private static double avgDouble(Deque<Double> deque) {
        if (deque.isEmpty()) {
            return 0.0;
        }

        double sum = 0.0;
        for (double value : deque) {
            sum += value;
        }

        return sum / (double) deque.size();
    }

    private static String entityType(Entity entity) {
        return entity == null ? "" : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }
}
