package de.braste.SPfB;

import org.bukkit.Location;
import java.io.Serializable;

public class SimpleLocation implements Serializable {
    private static final long serialVersionUID = -1249619403579340650L;
    public String worldName;
    public int x, y, z;

    public SimpleLocation(Location loc) {
        worldName = loc.getWorld().getName();
        x = loc.getBlockX();
        y = loc.getBlockY();
        z = loc.getBlockZ();
    }
}
