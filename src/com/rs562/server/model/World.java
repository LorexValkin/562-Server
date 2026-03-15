package com.rs562.server.model;

import com.rs562.server.Constants;
import com.rs562.server.model.player.Player;

/**
 * The game world — holds all entities and processes each tick.
 */
public class World {

    private static final World INSTANCE = new World();
    public static World get() { return INSTANCE; }

    private final Player[] players = new Player[Constants.MAX_PLAYERS];
    private int playerCount = 0;

    private World() {}

    /** Register a player and assign an index (1-based). Returns false if full. */
    public boolean register(Player player) {
        for (int i = 1; i < players.length; i++) {
            if (players[i] == null) {
                players[i] = player;
                player.setIndex(i);
                player.setActive(true);
                playerCount++;
                System.out.println("[WORLD] Registered: " + player.getUsername()
                    + " (index=" + i + ", online=" + playerCount + ")");
                return true;
            }
        }
        return false;
    }

    /** Unregister a player */
    public void unregister(Player player) {
        if (player.getIndex() > 0 && player.getIndex() < players.length) {
            players[player.getIndex()] = null;
            player.setActive(false);
            playerCount--;
            System.out.println("[WORLD] Unregistered: " + player.getUsername()
                + " (online=" + playerCount + ")");
        }
    }

    /** Process one game tick */
    public void tick() {
        // Pre-process: handle walking, teleporting, etc.
        for (int i = 1; i < players.length; i++) {
            if (players[i] != null) {
                players[i].process();
            }
        }
        // Post-process: send updates to clients
        for (int i = 1; i < players.length; i++) {
            Player p = players[i];
            if (p != null && p.isActive()) {
                try {
                    p.getSession().sendPostTick(p);
                } catch (Exception e) {
                    System.err.println("[WORLD] Error updating " + p.getUsername() + ": " + e.getMessage());
                    unregister(p);
                }
            }
        }
        // Reset flags
        for (int i = 1; i < players.length; i++) {
            if (players[i] != null) {
                players[i].setRegionChanged(false);
                players[i].setTeleporting(false);
                players[i].setAppearanceUpdated(false);
                players[i].setLastPosition(players[i].getPosition().copy());
            }
        }
    }

    public Player[] getPlayers() { return players; }
    public int getPlayerCount() { return playerCount; }
}
