package dev.sleepplus;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;

public final class SleepPlus extends JavaPlugin implements Listener, TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private enum Mode { COUNT, PERCENT }

    private Mode mode;
    private int count;
    private int percent;
    private int delay;
    private boolean clearWeather;
    private boolean announce;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        load();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("sleepplus");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        // Check every 5 ticks whether any world has enough sleepers.
        getServer().getScheduler().runTaskTimer(this, this::checkWorlds, 20L, 5L);
    }

    private void load() {
        reloadConfig();
        FileConfiguration c = getConfig();
        try {
            mode = Mode.valueOf(c.getString("required.mode", "COUNT").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Invalid required.mode, using COUNT.");
            mode = Mode.COUNT;
        }
        count = Math.max(1, c.getInt("required.count", 1));
        percent = Math.max(1, Math.min(100, c.getInt("required.percent", 50)));
        delay = Math.max(0, Math.min(100, c.getInt("sleep-delay-ticks", 100)));
        clearWeather = c.getBoolean("clear-weather", true);
        announce = c.getBoolean("announce-sleeping", true);
    }

    private List<Player> eligible(World w) {
        return w.getPlayers().stream()
                .filter(p -> p.getGameMode() != GameMode.SPECTATOR && !p.isSleepingIgnored())
                .toList();
    }

    private int required(int eligible) {
        if (eligible <= 0) return 0;
        int r = mode == Mode.COUNT ? count : (int) Math.ceil(eligible * percent / 100.0);
        return Math.max(1, Math.min(r, eligible));
    }

    private boolean isSkippable(World w) {
        return w.getEnvironment() == World.Environment.NORMAL && (!w.isDayTime() || w.hasStorm());
    }

    private void checkWorlds() {
        for (World w : getServer().getWorlds()) {
            if (!isSkippable(w)) continue;
            List<Player> eligible = eligible(w);
            int req = required(eligible.size());
            if (req == 0) continue;

            long ready = eligible.stream()
                    .filter(p -> p.isSleeping() && p.getSleepTicks() >= delay)
                    .count();
            if (ready >= req) skip(w);
        }
    }

    private void skip(World w) {
        long t = w.getFullTime();
        w.setFullTime(t + 24000L - (t % 24000L));
        if (clearWeather) {
            w.setStorm(false);
            w.setThundering(false);
        }
        Component msg = msg("skipped");
        w.getPlayers().forEach(p -> p.sendMessage(msg));
    }

    @EventHandler
    public void onBedEnter(PlayerBedEnterEvent e) {
        if (!announce || e.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;
        Player player = e.getPlayer();
        World w = player.getWorld();
        // Run next tick so isSleeping() is accurate.
        getServer().getScheduler().runTask(this, () -> {
            List<Player> eligible = eligible(w);
            long sleeping = eligible.stream().filter(Player::isSleeping).count();
            Component m = msg("sleeping",
                    Placeholder.unparsed("player", player.getName()),
                    Placeholder.unparsed("sleeping", String.valueOf(sleeping)),
                    Placeholder.unparsed("required", String.valueOf(required(eligible.size()))));
            w.getPlayers().forEach(p -> p.sendMessage(m));
        });
    }

    private Component msg(String key, TagResolver... resolvers) {
        return MM.deserialize(getConfig().getString("messages." + key, key), resolvers);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("sleepplus.admin")) {
            s.sendMessage(msg("no-permission"));
            return true;
        }
        if (a.length == 0) {
            s.sendMessage(msg("usage"));
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                load();
                s.sendMessage(msg("reloaded"));
            }
            case "status" -> s.sendMessage(Component.text(mode == Mode.COUNT
                    ? "Mode: COUNT, " + count + " player(s) required"
                    : "Mode: PERCENT, " + percent + "% of players required"));
            case "set" -> {
                if (a.length < 3) {
                    s.sendMessage(msg("usage"));
                    return true;
                }
                int v;
                try {
                    v = Integer.parseInt(a[2]);
                } catch (NumberFormatException ex) {
                    s.sendMessage(msg("usage"));
                    return true;
                }
                switch (a[1].toLowerCase(Locale.ROOT)) {
                    case "count" -> {
                        getConfig().set("required.mode", "COUNT");
                        getConfig().set("required.count", Math.max(1, v));
                    }
                    case "percent" -> {
                        getConfig().set("required.mode", "PERCENT");
                        getConfig().set("required.percent", Math.max(1, Math.min(100, v)));
                    }
                    default -> {
                        s.sendMessage(msg("usage"));
                        return true;
                    }
                }
                saveConfig();
                load();
                s.sendMessage(Component.text("Updated. " + (mode == Mode.COUNT
                        ? count + " player(s) now required."
                        : percent + "% of players now required.")));
            }
            default -> s.sendMessage(msg("usage"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (!s.hasPermission("sleepplus.admin")) return List.of();
        if (a.length == 1) return List.of("reload", "status", "set");
        if (a.length == 2 && a[0].equalsIgnoreCase("set")) return List.of("count", "percent");
        return List.of();
    }
}
