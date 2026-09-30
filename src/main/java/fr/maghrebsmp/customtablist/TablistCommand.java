package fr.maghrebsmp.customtablist;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class TablistCommand implements CommandExecutor {
    private final CustomTablistPlugin plugin;

    public TablistCommand(CustomTablistPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("customtablist.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " reload");
            return true;
        }
        plugin.reloadTablist();
        sender.sendMessage(ChatColor.GREEN + "Tablist configuration reloaded.");
        return true;
    }
}
