package com.rs562.server.model.player;

import com.rs562.server.Constants;
import com.rs562.server.model.Position;
import com.rs562.server.net.Session;

/**
 * Represents a player in the game world.
 */
public class Player {

    // ── Identity ───────────────────────────────────────────────────────
    private String username;
    private String password;
    private int rights; // 0=normal, 1=mod, 2=admin
    private int index;  // slot in the player array (1-2047)

    // ── Position & Movement ────────────────────────────────────────────
    private Position position;
    private Position lastPosition;
    private Position mapBase; // base chunk for current map view

    private boolean regionChanged;
    private boolean teleporting;

    // ── Appearance ─────────────────────────────────────────────────────
    private int[] appearance = { 0, 10, 18, 26, 33, 36, 42 }; // default male look
    private int[] colors = { 0, 0, 0, 0, 0 };
    private int gender = 0; // 0=male, 1=female
    private boolean appearanceUpdated = true;

    // ── State ──────────────────────────────────────────────────────────
    private boolean active = false;
    private Session session;
    private boolean initialized = false;

    // ── Skills ──────────────────────────────────────────────────────────
    private int[] skillLevels = new int[25];
    private double[] skillXp = new double[25];

    public Player(Session session, String username) {
        this.session = session;
        this.username = username;
        this.password = "";
        this.rights = 2; // admin by default for dev
        this.position = new Position(Constants.SPAWN_X, Constants.SPAWN_Y, Constants.SPAWN_Z);
        this.lastPosition = position.copy();
        this.regionChanged = true;
        this.teleporting = true;

        // Initialize skills to level 1 (except HP = 10)
        for (int i = 0; i < 25; i++) {
            skillLevels[i] = 1;
            skillXp[i] = 0;
        }
        skillLevels[3] = 10; // Hitpoints
        skillXp[3] = 1154;   // Level 10 XP
    }

    /** Called each game tick */
    public void process() {
        // Process walking queue, combat, etc. (to be expanded)
    }

    /** Update the map base position (chunk origin for the client's map view) */
    public void updateMapBase() {
        mapBase = new Position(
            (position.getChunkX() - 6) * 8,
            (position.getChunkY() - 6) * 8,
            position.getZ()
        );
    }

    /** Check if region needs to be rebuilt */
    public boolean needsMapUpdate() {
        if (mapBase == null) return true;
        int localX = position.getX() - mapBase.getX();
        int localY = position.getY() - mapBase.getY();
        // Rebuild when player is within 16 tiles of the map edge
        return localX < 16 || localX >= 88 || localY < 16 || localY >= 88;
    }

    // ── Appearance encoding for player update ──────────────────────────
    public byte[] encodeAppearance() {
        com.rs562.server.net.Buffer buf = new com.rs562.server.net.Buffer(128);
        buf.writeByte(gender);
        buf.writeByte(0); // skull icon
        buf.writeByte(-1); // prayer icon (none)
        // No transform
        for (int i = 0; i < 4; i++) {
            buf.writeByte(0); // head slots: hat, cape, amulet, weapon
        }
        buf.writeShort(0x100 + appearance[2]); // chest (torso)
        buf.writeByte(0); // shield
        buf.writeShort(0x100 + appearance[3]); // arms
        buf.writeShort(0x100 + appearance[5]); // legs
        buf.writeShort(0x100 + appearance[0]); // head (hair)
        buf.writeShort(0x100 + appearance[4]); // hands
        buf.writeShort(0x100 + appearance[6]); // feet
        buf.writeShort(0x100 + appearance[1]); // beard/jaw

        for (int i = 0; i < 5; i++) {
            buf.writeByte(colors[i]); // colors
        }
        // Animation IDs (stand, walk, etc.)
        buf.writeShort(808);  // stand
        buf.writeShort(823);  // stand turn
        buf.writeShort(819);  // walk
        buf.writeShort(820);  // turn 180
        buf.writeShort(821);  // turn 90 cw
        buf.writeShort(822);  // turn 90 ccw
        buf.writeShort(824);  // run

        buf.writeLong(nameToLong(username));
        buf.writeByte(3); // combat level
        buf.writeShort(0); // total skill level (or summoning level for 562+)
        buf.writeByte(0); // hidden (0 = visible)

        byte[] result = new byte[buf.getPosition()];
        System.arraycopy(buf.getData(), 0, result, 0, result.length);
        return result;
    }

    /** Encode username to RS base37 long */
    public static long nameToLong(String name) {
        long encoded = 0L;
        String clean = name.toLowerCase().replace(" ", "_");
        for (int i = 0; i < clean.length() && i < 12; i++) {
            char c = clean.charAt(i);
            encoded *= 37L;
            if (c >= 'a' && c <= 'z') encoded += c - 'a' + 1;
            else if (c >= '0' && c <= '9') encoded += c - '0' + 27;
            else if (c == '_') encoded += 0;
        }
        return encoded;
    }

    // ── Getters / Setters ──────────────────────────────────────────────
    public String getUsername() { return username; }
    public int getRights() { return rights; }
    public void setRights(int rights) { this.rights = rights; }
    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }
    public Position getPosition() { return position; }
    public void setPosition(Position p) { this.position = p; }
    public Position getLastPosition() { return lastPosition; }
    public void setLastPosition(Position p) { this.lastPosition = p; }
    public Position getMapBase() { return mapBase; }
    public boolean isRegionChanged() { return regionChanged; }
    public void setRegionChanged(boolean b) { this.regionChanged = b; }
    public boolean isTeleporting() { return teleporting; }
    public void setTeleporting(boolean b) { this.teleporting = b; }
    public boolean isActive() { return active; }
    public void setActive(boolean b) { this.active = b; }
    public boolean isInitialized() { return initialized; }
    public void setInitialized(boolean b) { this.initialized = b; }
    public Session getSession() { return session; }
    public boolean isAppearanceUpdated() { return appearanceUpdated; }
    public void setAppearanceUpdated(boolean b) { this.appearanceUpdated = b; }
    public int[] getSkillLevels() { return skillLevels; }
    public double[] getSkillXp() { return skillXp; }
}
