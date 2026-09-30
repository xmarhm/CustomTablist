package fr.maghrebsmp.customtablist;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CustomTablistPlugin extends JavaPlugin implements Listener {

    private static final String TEAM_PREFIX = "ctab_";
    private static final Pattern HEX_COLOR = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private final List<Rank> ranks = new ArrayList<>();
    private final List<Team> levelTeams = new ArrayList<>();
    private final Set<String> ownedTeamNames = new HashSet<>();
    private final Map<String, Layout> worldLayouts = new HashMap<>();
    private final Map<UUID, Shown> shown = new HashMap<>();
    private final List<Permission> registeredPermissions = new ArrayList<>();
    private Scoreboard scoreboard;
    private Method listOrderMethod;
    private String serverName;
    private String tabNameFormat;
    private String chatNameFormat;
    private volatile String chatFormat;
    private Layout defaultLayout;
    private int levelCount;
    private boolean refreshScheduled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("tablist") != null) {
            getCommand("tablist").setExecutor(new TablistCommand(this));
        }
        scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        findListOrderMethod();
        rebuildRankTeams();
        refreshAll();
        getLogger().info("CustomTablist enabled for " + Bukkit.getOnlinePlayers().size() + " player(s).");
    }

    @Override
    public void onDisable() {
        removePluginTeams();
        unregisterRankPermissions();
        shown.clear();
    }

    private void loadSettings() {
        serverName = getConfig().getString("settings.server-name", "Server");
        tabNameFormat = getConfig().getString("settings.tab-name", "%prefix%%player%");
        chatNameFormat = getConfig().getString("settings.chat-name", "%prefix%%player%");
        chatFormat = toChatFormat(getConfig().getString("settings.chat-format", "%name% &8» &f%message%"));
        defaultLayout = new Layout(
                readLines("settings.header", List.of("&6&l%server-name%")),
                readLines("settings.footer", List.of("&7Players: &f%online%"))
        );

        worldLayouts.clear();
        ConfigurationSection worlds = getConfig().getConfigurationSection("worlds");
        if (worlds != null) {
            for (String world : worlds.getKeys(false)) {
                String path = "worlds." + world;
                worldLayouts.put(world, new Layout(
                        readLines(path + ".header", defaultLayout.header()),
                        readLines(path + ".footer", defaultLayout.footer())
                ));
            }
        }

        ranks.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("ranks");
        if (section != null) {
            List<String> ids = new ArrayList<>(section.getKeys(false));
            ids.sort(Comparator.comparingInt(id -> section.getInt(id + ".priority", 100)));
            int level = -1;
            int previousPriority = 0;
            for (String id : ids) {
                int priority = section.getInt(id + ".priority", 100);
                if (level < 0 || priority != previousPriority) {
                    level++;
                    previousPriority = priority;
                }
                ranks.add(new Rank(
                        id,
                        section.getString(id + ".permission", ""),
                        priority,
                        section.getString(id + ".prefix", "&7"),
                        level
                ));
            }
        }
        if (ranks.isEmpty()) {
            ranks.add(new Rank("member", "", 100, "&7", 0));
        }
        levelCount = ranks.get(ranks.size() - 1).level() + 1;

        unregisterRankPermissions();
        registerRankPermissions();
    }

    private void registerRankPermissions() {
        for (Rank rank : ranks) {
            if (rank.permission().isBlank() || Bukkit.getPluginManager().getPermission(rank.permission()) != null) {
                continue;
            }
            Permission permission = new Permission(rank.permission(), PermissionDefault.FALSE);
            Bukkit.getPluginManager().addPermission(permission);
            registeredPermissions.add(permission);
        }
    }

    private void unregisterRankPermissions() {
        for (Permission permission : registeredPermissions) {
            Bukkit.getPluginManager().removePermission(permission);
        }
        registeredPermissions.clear();
    }

    private void findListOrderMethod() {
        try {
            listOrderMethod = Player.class.getMethod("setPlayerListOrder", int.class);
            getLogger().info("Native tablist ordering detected.");
        } catch (NoSuchMethodException ignored) {
            listOrderMethod = null;
        }
    }

    private void rebuildRankTeams() {
        removePluginTeams();
        levelTeams.clear();
        if (scoreboard == null || listOrderMethod != null) {
            return;
        }
        for (int level = 0; level < levelCount; level++) {
            String teamName = TEAM_PREFIX + String.format(Locale.ROOT, "%03d", level);
            Team team = scoreboard.getTeam(teamName);
            if (team == null) {
                team = scoreboard.registerNewTeam(teamName);
            }
            team.setPrefix("");
            team.setCanSeeFriendlyInvisibles(false);
            ownedTeamNames.add(teamName);
            levelTeams.add(team);
        }
    }

    private void removePluginTeams() {
        if (scoreboard == null) {
            return;
        }
        for (String teamName : new HashSet<>(ownedTeamNames)) {
            Team team = scoreboard.getTeam(teamName);
            if (team != null) {
                team.unregister();
            }
        }
        ownedTeamNames.clear();
    }

    private void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayer(player);
        }
    }

    private void updatePlayer(Player player) {
        Rank rank = findRank(player);
        Layout layout = worldLayouts.getOrDefault(player.getWorld().getName(), defaultLayout);
        Shown state = shown.computeIfAbsent(player.getUniqueId(), id -> new Shown());

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("server-name", serverName);
        placeholders.put("player", player.getName());
        placeholders.put("world", player.getWorld().getName());
        placeholders.put("online", Integer.toString(Bukkit.getOnlinePlayers().size()));
        placeholders.put("max-players", Integer.toString(Bukkit.getMaxPlayers()));
        placeholders.put("prefix", rank.prefix());
        placeholders.put("rank", rank.id());

        String header = formatLines(layout.header(), placeholders);
        String footer = formatLines(layout.footer(), placeholders);
        if (!header.equals(state.header) || !footer.equals(state.footer)) {
            player.setPlayerListHeaderFooter(header, footer);
            state.header = header;
            state.footer = footer;
        }

        String listName = colorize(replace(tabNameFormat, placeholders));
        if (!listName.equals(state.listName)) {
            player.setPlayerListName(listName);
            state.listName = listName;
        }

        String displayName = chatNameFormat.isBlank()
                ? null
                : colorize(replace(chatNameFormat, placeholders)) + ChatColor.RESET;
        if (!Objects.equals(displayName, state.displayName)) {
            player.setDisplayName(displayName);
            state.displayName = displayName;
        }

        if (listOrderMethod != null) {
            int order = levelCount - rank.level();
            if (order != state.order) {
                invokeListOrder(player, order);
                state.order = order;
            }
        } else {
            putInRankTeam(player, rank);
        }
    }

    private void putInRankTeam(Player player, Rank rank) {
        if (rank.level() >= levelTeams.size()) {
            return;
        }
        Team destination = levelTeams.get(rank.level());
        Team current = scoreboard.getEntryTeam(player.getName());
        if (current != null && !ownedTeamNames.contains(current.getName())) {
            return;
        }
        if (current != null && current != destination) {
            current.removeEntry(player.getName());
        }
        if (!destination.hasEntry(player.getName())) {
            destination.addEntry(player.getName());
        }
    }

    private void removeFromRankTeam(Player player) {
        if (scoreboard == null) {
            return;
        }
        Team current = scoreboard.getEntryTeam(player.getName());
        if (current != null && ownedTeamNames.contains(current.getName())) {
            current.removeEntry(player.getName());
        }
    }

    private void invokeListOrder(Player player, int order) {
        try {
            listOrderMethod.invoke(player, order);
        } catch (ReflectiveOperationException exception) {
            getLogger().warning("Could not set the list order of " + player.getName() + ": " + exception.getMessage());
        }
    }

    private Rank findRank(Player player) {
        for (Rank rank : ranks) {
            if (rank.permission().isBlank() || player.hasPermission(rank.permission())) {
                return rank;
            }
        }
        return ranks.get(ranks.size() - 1);
    }

    private String toChatFormat(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return colorize(configured)
                .replace("%", "%%")
                .replace("%%name%%", "%1$s")
                .replace("%%message%%", "%2$s");
    }

    private List<String> readLines(String path, List<String> fallback) {
        if (getConfig().isList(path)) {
            return getConfig().getStringList(path);
        }
        String single = getConfig().getString(path);
        return single == null ? fallback : List.of(single);
    }

    private String formatLines(List<String> lines, Map<String, String> placeholders) {
        return lines.stream()
                .map(line -> colorize(replace(line, placeholders)))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String replace(String text, Map<String, String> placeholders) {
        String result = text;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            result = result.replace("%" + placeholder.getKey() + "%", placeholder.getValue());
        }
        return result;
    }

    private String colorize(String text) {
        Matcher matcher = HEX_COLOR.matcher(text);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char digit : hex.toCharArray()) {
                replacement.append('§').append(digit);
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(output);
        return ChatColor.translateAlternateColorCodes('&', output.toString());
    }

    private void scheduleRefresh() {
        if (refreshScheduled) {
            return;
        }
        refreshScheduled = true;
        Bukkit.getScheduler().runTask(this, () -> {
            refreshScheduled = false;
            refreshAll();
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        updatePlayer(event.getPlayer());
        scheduleRefresh();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        shown.remove(event.getPlayer().getUniqueId());
        removeFromRankTeam(event.getPlayer());
        scheduleRefresh();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        updatePlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        String format = chatFormat;
        if (format != null) {
            event.setFormat(format);
        }
    }

    public void reloadTablist() {
        reloadConfig();
        loadSettings();
        rebuildRankTeams();
        refreshAll();
    }

    private record Rank(String id, String permission, int priority, String prefix, int level) {
    }

    private record Layout(List<String> header, List<String> footer) {
    }

    private static final class Shown {
        private String header;
        private String footer;
        private String listName;
        private String displayName;
        private int order = -1;
    }
}
