package kr.ep1.tag;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class TagPlugin extends JavaPlugin implements Listener {

    private static final Set<Material> ORES = Set.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);

    private static final Map<Material, Material> SMELT = Map.of(
            Material.RAW_IRON, Material.IRON_INGOT,
            Material.RAW_COPPER, Material.COPPER_INGOT,
            Material.RAW_GOLD, Material.GOLD_INGOT);

    private final Map<UUID, Integer> scores = new LinkedHashMap<>();
    private final Map<UUID, Long> frozenUntil = new HashMap<>();
    private final Set<UUID> freezePending = new HashSet<>();
    // 리스폰 보상: true면 레벨 + 책장/마법부여대, false면 레벨만 (인벤토리 유지된 경우)
    private final Map<UUID, Boolean> respawnReward = new HashMap<>();
    private final Random random = new Random();

    private boolean running;
    private boolean shrunk;
    private int remaining; // 남은 시간(초)
    private World world;
    private Location center;
    private UUID tagger;
    private BukkitTask startTask;
    private BukkitTask loop;
    private BukkitRunnable trackTask;
    private BossBar bossBar;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("시작").setExecutor(this);
        getCommand("중지").setExecutor(this);
    }

    @Override
    public void onDisable() {
        if (running) cleanup();
    }

    // ───────────────────────── 명령어 ─────────────────────────

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (c.getName().equals("시작")) {
            start(s);
        } else {
            if (!running) { s.sendMessage("진행 중인 게임이 없습니다."); return true; }
            cleanup();
            Bukkit.broadcast(Component.text("[술래잡기] 게임이 중지되었습니다.", NamedTextColor.RED));
        }
        return true;
    }

    // ───────────────────────── 게임 시작 ─────────────────────────

    private void start(CommandSender s) {
        if (running) { s.sendMessage("이미 게임이 진행 중입니다."); return; }

        world = Bukkit.getWorlds().get(0); // 오버월드
        List<Player> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SPECTATOR) players.add(p);
        }
        if (players.size() < 2) { s.sendMessage("최소 2명이 필요합니다."); return; }

        scores.clear();
        frozenUntil.clear();
        freezePending.clear();
        respawnReward.clear();
        tagger = null;
        shrunk = false;
        for (Player p : players) scores.put(p.getUniqueId(), 0);

        world.setDifficulty(Difficulty.EASY);
        center = (s instanceof Player sp && sp.getWorld().equals(world))
                ? sp.getLocation().getBlock().getLocation().add(0.5, 0, 0.5)
                : world.getSpawnLocation().getBlock().getLocation().add(0.5, 0, 0.5);
        WorldBorder border = world.getWorldBorder();
        border.setCenter(center);
        border.setSize(getConfig().getDouble("border-size", 600));

        running = true;

        // 3 → 2 → 1 → (술래 선정 + 제목) → 3초 준비 → 배치 + 역할 메시지 + 타이머 시작
        startTask = new BukkitRunnable() {
            int step = 0;
            @Override public void run() {
                if (!running) { cancel(); return; }
                switch (step) {
                    case 0, 1, 2 -> showTitleAll(String.valueOf(3 - step), NamedTextColor.YELLOW);
                    case 3 -> {
                        List<Player> online = onlineParticipants();
                        if (online.size() < 2) { cleanup(); return; }
                        Player first = online.get(random.nextInt(online.size()));
                        becomeTagger(first, false, false);
                        showTitleAll("술래가 정해졌습니다!", NamedTextColor.RED);
                    }
                    case 6 -> {
                        placePlayers();
                        for (Player p : onlineParticipants()) {
                            sendRole(p, p.getUniqueId().equals(tagger));
                            // 시작 보상: 레벨 + 책장/마법부여대
                            p.giveExpLevels(getConfig().getInt("respawn-levels", 10000));
                            giveKit(p);
                        }
                        Player t = taggerPlayer();
                        if (t != null) applyTaggerState(t);
                        beginTimer();
                        cancel();
                        startTask = null;
                    }
                    default -> { }
                }
                step++;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void beginTimer() {
        remaining = getConfig().getInt("total-minutes", 100) * 60;
        bossBar = BossBar.bossBar(timeText(), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        for (Player p : onlineParticipants()) p.showBossBar(bossBar);
        loop = new BukkitRunnable() {
            @Override public void run() { tick(); }
        }.runTaskTimer(this, 20L, 20L);
    }

    private void placePlayers() {
        double half = getConfig().getDouble("border-size", 600) / 2.0 - 5;
        List<double[]> corners = new ArrayList<>(List.of(
                new double[]{-1, -1}, new double[]{1, -1}, new double[]{-1, 1}, new double[]{1, 1}));
        Collections.shuffle(corners, random);
        List<double[]> chosen = corners.subList(0, 3); // 4개 중 랜덤 3개
        for (Player p : onlineParticipants()) {
            double[] k = chosen.get(random.nextInt(chosen.size()));
            p.teleport(surface(center.getX() + k[0] * half, center.getZ() + k[1] * half));
        }
    }

    private Location surface(double x, double z) {
        int y = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
        return new Location(world, x, y + 1, z);
    }

    // ───────────────────────── 진행 / 종료 ─────────────────────────

    private void tick() {
        remaining--;

        int shrinkAt = getConfig().getInt("shrink-at-remaining-minutes", 15) * 60;
        if (!shrunk && remaining <= shrinkAt) shrinkBorder();

        bossBar.name(timeText());
        int total = getConfig().getInt("total-minutes", 100) * 60;
        bossBar.progress(Math.max(0f, Math.min(1f, remaining / (float) total)));

        if (remaining <= 0) finish();
    }

    private void shrinkBorder() {
        shrunk = true;
        double size = getConfig().getDouble("shrink-size", 150);
        WorldBorder border = world.getWorldBorder();
        border.setSize(size);

        double half = size / 2.0 - 2;
        for (Player p : onlineParticipants()) {
            Location l = p.getLocation();
            if (!l.getWorld().equals(world) || !border.isInside(l)) {
                double sx = l.getX() >= center.getX() ? 1 : -1;
                double sz = l.getZ() >= center.getZ() ? 1 : -1;
                p.teleport(surface(center.getX() + sx * half, center.getZ() + sz * half));
            }
        }

        showTitleAll("자기장이 축소되었습니다", NamedTextColor.RED);
        Bukkit.broadcast(Component.text("[술래잡기] 자기장이 축소되었습니다", NamedTextColor.RED));

        Player t = taggerPlayer();
        if (t != null) applyStrength(t);
    }

    private void finish() {
        int min = Collections.min(scores.values());
        List<String> losers = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : scores.entrySet()) {
            if (e.getValue() == min) losers.add(nameOf(e.getKey()));
        }
        List<Map.Entry<UUID, Integer>> list = new ArrayList<>(scores.entrySet());
        list.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));

        Bukkit.broadcast(Component.text("[술래잡기] ===== 게임 종료 =====", NamedTextColor.GOLD));
        for (Map.Entry<UUID, Integer> en : list) {
            Bukkit.broadcast(Component.text("[술래잡기] " + nameOf(en.getKey()) + " : " + en.getValue(), NamedTextColor.WHITE));
        }
        Bukkit.broadcast(Component.text("[술래잡기] 패배: " + String.join(", ", losers) + " (" + min + "점)", NamedTextColor.RED));
        cleanup();
    }

    private void cleanup() {
        if (startTask != null) startTask.cancel();
        startTask = null;
        if (loop != null) loop.cancel();
        loop = null;
        stopTracking();
        if (bossBar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bossBar);
            bossBar = null;
        }
        for (UUID id : scores.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) clearTaggerState(p);
        }
        frozenUntil.clear();
        freezePending.clear();
        respawnReward.clear();
        tagger = null;
        running = false;
        if (world != null) world.getWorldBorder().reset();
    }

    // ───────────────────────── 술래 처리 ─────────────────────────

    /** @param freeze 실명/이동 제한 적용 여부, @param apply 지금 바로 효과를 적용할지(사망 직후엔 리스폰 때 적용) */
    private void becomeTagger(Player p, boolean freeze, boolean apply) {
        UUID id = p.getUniqueId();
        tagger = id;
        scores.merge(id, -1, Integer::sum);
        if (freeze) freezePending.add(id);
        if (running && bossBar != null) { // 게임 중 교체일 때만 메시지 (최초 선정은 배치 때 메시지)
            sendRole(p, true);
        }
        if (apply) applyTaggerState(p);
    }

    private void becomeRunner(Player p) {
        clearTaggerState(p);
        if (bossBar != null) sendRole(p, false);
    }

    private void applyTaggerState(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
                PotionEffect.INFINITE_DURATION, 0, false, false));
        if (shrunk) applyStrength(p);
        if (freezePending.remove(p.getUniqueId())) {
            int sec = getConfig().getInt("freeze-seconds", 60);
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, sec * 20, 0, false, false));
            frozenUntil.put(p.getUniqueId(), System.currentTimeMillis() + sec * 1000L);
        }
    }

    private void applyStrength(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,
                PotionEffect.INFINITE_DURATION, 0, false, false));
    }

    private void clearTaggerState(Player p) {
        p.removePotionEffect(PotionEffectType.RESISTANCE);
        p.removePotionEffect(PotionEffectType.STRENGTH);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        frozenUntil.remove(p.getUniqueId());
        freezePending.remove(p.getUniqueId());
    }

    private void sendRole(Player p, boolean isTagger) {
        p.sendMessage(Component.text("당신의 역할은 '" + (isTagger ? "술래" : "러너") + "' 입니다.",
                isTagger ? NamedTextColor.RED : NamedTextColor.GREEN));
    }

    private Player taggerPlayer() {
        return tagger == null ? null : Bukkit.getPlayer(tagger);
    }

    // ───────────────────────── 사망 / 인벤토리 ─────────────────────────

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (!running || bossBar == null) return;
        Player dead = e.getEntity();
        UUID id = dead.getUniqueId();
        if (!scores.containsKey(id)) return;

        boolean deadIsTagger = id.equals(tagger);
        Player killer = dead.getKiller();
        boolean killedByTagger = !deadIsTagger && killer != null && killer.getUniqueId().equals(tagger);

        if (deadIsTagger || killedByTagger) {
            e.setKeepInventory(true); // 술래 사망 / 술래에게 죽은 러너 → 인벤토리 유지
            e.getDrops().clear();
        } else {
            e.setKeepInventory(false); // 러너 자연사 → 아이템 드롭
        }

        // 인벤토리를 잃는 죽음(러너 자연사)만 책장/마법부여대까지 지급, 유지되면 레벨만
        respawnReward.put(id, !e.getKeepInventory());

        if (killedByTagger) {
            becomeRunner(killer);           // 기존 술래 → 러너
            becomeTagger(dead, true, false); // 죽은 러너 → 새 술래 (효과는 리스폰 때 적용)
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!running || bossBar == null) return;
        Player p = e.getPlayer();
        Boolean withItems = respawnReward.remove(p.getUniqueId());
        boolean wasTagger = p.getUniqueId().equals(tagger);
        if (!wasTagger && withItems == null) return;
        Bukkit.getScheduler().runTask(this, () -> {
            if (p.getUniqueId().equals(tagger)) applyTaggerState(p);
            if (withItems != null) {
                p.giveExpLevels(getConfig().getInt("respawn-levels", 10000));
                if (withItems) giveKit(p);
            }
        });
    }

    private void giveKit(Player p) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(
                new ItemStack(Material.BOOKSHELF, getConfig().getInt("respawn-bookshelves", 64)),
                new ItemStack(Material.ENCHANTING_TABLE, 1));
        for (ItemStack it : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), it);
    }

    // 이동 불가 (시선 회전은 허용, 낙하는 허용)
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!running) return;
        Long until = frozenUntil.get(e.getPlayer().getUniqueId());
        if (until == null) return;
        if (System.currentTimeMillis() >= until) {
            frozenUntil.remove(e.getPlayer().getUniqueId());
            return;
        }
        Location f = e.getFrom();
        Location t = e.getTo();
        if (t == null) return;
        if (f.getX() != t.getX() || f.getZ() != t.getZ() || t.getY() > f.getY()) {
            Location n = f.clone();
            n.setYaw(t.getYaw());
            n.setPitch(t.getPitch());
            e.setTo(n);
        }
    }

    // 네더/엔드 진입 차단
    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (!running) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(Component.text("술래잡기 중에는 다른 차원으로 갈 수 없습니다.", NamedTextColor.GRAY));
    }

    // 술래가 나가면 다른 참가자에게 술래 넘김
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (!running || !e.getPlayer().getUniqueId().equals(tagger)) return;
        clearTaggerState(e.getPlayer());
        tagger = null;
        List<Player> others = new ArrayList<>();
        for (Player p : onlineParticipants()) if (!p.equals(e.getPlayer())) others.add(p);
        if (others.isEmpty()) { cleanup(); return; }
        becomeTagger(others.get(random.nextInt(others.size())), false, true);
    }

    // ───────────────────────── 다이아 추적 ─────────────────────────

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!running || bossBar == null || e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!p.getUniqueId().equals(tagger)) return;
        if (p.getInventory().getItemInMainHand().getType() != Material.DIAMOND) return;
        if (trackTask != null) return; // 이미 추적 중

        trackTask = new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (!running || !p.isOnline() || ticks >= 100 || !p.getUniqueId().equals(tagger)) {
                    stopTracking();
                    return;
                }
                Player target = nearestRunner(p);
                if (target != null) {
                    Vector dir = target.getLocation().toVector().subtract(p.getLocation().toVector());
                    if (dir.lengthSquared() > 0.0001) {
                        dir.normalize();
                        Location eye = p.getEyeLocation();
                        for (int i = 2; i <= 6; i++) { // 술래에게만 보이는 파티클
                            p.spawnParticle(Particle.END_ROD, eye.clone().add(dir.clone().multiply(i)), 1, 0, 0, 0, 0);
                        }
                    }
                }
                ticks += 2;
            }
        };
        trackTask.runTaskTimer(this, 0L, 2L);
    }

    private void stopTracking() {
        if (trackTask != null) {
            try { trackTask.cancel(); } catch (IllegalStateException ignored) { }
            trackTask = null;
        }
    }

    private Player nearestRunner(Player from) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : onlineParticipants()) {
            if (p.getUniqueId().equals(tagger) || p.isDead()) continue;
            if (!p.getWorld().equals(from.getWorld())) continue;
            double d = p.getLocation().distanceSquared(from.getLocation());
            if (d < bestDist) { bestDist = d; best = p; }
        }
        return best;
    }

    // ───────────────────────── 광물 2배 + 자동 제련 ─────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOreDrop(BlockDropItemEvent e) {
        if (!running || !e.getBlock().getWorld().equals(world)) return;
        if (!ORES.contains(e.getBlockState().getType())) return;

        for (Item item : e.getItems()) {
            ItemStack stack = item.getItemStack().clone();
            Material type = stack.getType();
            if (ORES.contains(type)) continue; // 섬세한 손길로 캔 광석 블록은 그대로

            Material smelted = SMELT.get(type);
            if (smelted != null) stack.setType(smelted);

            int total = stack.getAmount() * 2; // 행운 반영된 최종 드롭의 2배
            int max = stack.getMaxStackSize();
            stack.setAmount(Math.min(total, max));
            item.setItemStack(stack);

            int extra = total - max;
            while (extra > 0) {
                ItemStack more = stack.clone();
                more.setAmount(Math.min(extra, max));
                e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.5, 0.5), more);
                extra -= more.getAmount();
            }
        }
    }

    // ───────────────────────── 유틸 ─────────────────────────

    private List<Player> onlineParticipants() {
        List<Player> list = new ArrayList<>();
        for (UUID id : scores.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) list.add(p);
        }
        return list;
    }

    private Component timeText() {
        return Component.text(String.format("술래잡기 | 남은 시간 %02d:%02d", remaining / 60, remaining % 60));
    }

    private void showTitleAll(String text, NamedTextColor color) {
        Title title = Title.title(Component.text(text, color), Component.empty(),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(800), Duration.ofMillis(100)));
        for (Player p : onlineParticipants()) p.showTitle(title);
    }

    private String nameOf(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n != null ? n : id.toString().substring(0, 8);
    }
}
