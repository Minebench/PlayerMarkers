package de.braste.SPfB;

/*
 * PlayerMarkers
 * Copyright (c) 2020 Max Lee aka Phoenix616 (mail@moep.tv)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static de.braste.SPfB.PlayerMarkers.replace;

public class PlayerMarkersCommand implements CommandExecutor {
    private final PlayerMarkers plugin;

    public PlayerMarkersCommand(PlayerMarkers plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0) {
            if ("reload".equalsIgnoreCase(args[0]) && sender.hasPermission("playermarkers.command.reload")) {
                plugin.loadConfig();
                sender.sendMessage(ChatColor.YELLOW + "Config reloaded!");
                return true;
            } else if ("link".equalsIgnoreCase(args[0]) && sender.hasPermission("playermarkers.command.link")) {
                Player target;
                if (args.length > 1 && sender.hasPermission("playermarkers.command.link.others")) {
                    target = plugin.getServer().getPlayer(args[1]);
                    if (target == null) {
                        sender.sendMessage(ChatColor.RED + "Player " + ChatColor.YELLOW + args[1] + ChatColor.RED + " not online!");
                        return true;
                    }
                } else if (sender instanceof Player) {
                    target = (Player) sender;
                } else {
                    return false;
                }
                String link = plugin.getMapUrlBase();
                String world = plugin.getMappedWorld(target.getWorld().getName());
                if (world != null) {
                    String renderer = plugin.getConfig().getString("MapRenderers." + target.getWorld().getName());
                    if (renderer != null) {
                        Location loc = target.getLocation();
                        link = plugin.getMapUrlBase() + replace(plugin.getMapUrlFormat(),
                                "x", String.valueOf(loc.getBlockX()),
                                "y", String.valueOf(loc.getBlockY()),
                                "z", String.valueOf(loc.getBlockZ()),
                                "world", loc.getWorld().getName(),
                                "title", world,
                                "renderer", renderer,
                                "environment", loc.getWorld().getEnvironment().name().toLowerCase(),
                                "uuid", loc.getWorld().getUID().toString()
                        );
                    }
                }
                sender.spigot().sendMessage(new ComponentBuilder("Map Link: ").color(ChatColor.GREEN)
                        .append(link).color(ChatColor.YELLOW)
                        .event(new ClickEvent(ClickEvent.Action.OPEN_URL, link.replace(" ", "%20")))
                        .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(ChatColor.BLUE + "Click to open"))).create());
                return true;
            }
        }
        return false;
    }

}
