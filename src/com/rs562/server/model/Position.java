package com.rs562.server.model;

/**
 * Represents a position in the game world.
 * Handles tile coordinates and region/chunk math.
 */
public class Position {

    private int x;
    private int y;
    private int z; // height plane (0-3)

    public Position(int x, int y) {
        this(x, y, 0);
    }

    public Position(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Position copy() {
        return new Position(x, y, z);
    }

    // ── Region math ────────────────────────────────────────────────────
    /** Region ID (64x64 tile blocks) */
    public int getRegionId() {
        return (getRegionX() << 8) | getRegionY();
    }

    /** Region X coordinate */
    public int getRegionX() {
        return x >> 6;
    }

    /** Region Y coordinate */
    public int getRegionY() {
        return y >> 6;
    }

    /** Chunk X (8-tile blocks, used for map base) */
    public int getChunkX() {
        return x >> 3;
    }

    /** Chunk Y (8-tile blocks, used for map base) */
    public int getChunkY() {
        return y >> 3;
    }

    /** Local X within the current map view (relative to map base) */
    public int getLocalX(Position mapBase) {
        return x - (mapBase.getChunkX() - 6) * 8;
    }

    /** Local Y within the current map view */
    public int getLocalY(Position mapBase) {
        return y - (mapBase.getChunkY() - 6) * 8;
    }

    /** Local X within region (0-63) */
    public int getRegionLocalX() {
        return x & 0x3F;
    }

    /** Local Y within region (0-63) */
    public int getRegionLocalY() {
        return y & 0x3F;
    }

    /** Distance to another position (Chebyshev / game-tiles) */
    public int distanceTo(Position other) {
        int dx = Math.abs(x - other.x);
        int dy = Math.abs(y - other.y);
        return Math.max(dx, dy);
    }

    /** Check if within distance of another position */
    public boolean isWithinDistance(Position other, int distance) {
        return distanceTo(other) <= distance && z == other.z;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Position)) return false;
        Position o = (Position) obj;
        return x == o.x && y == o.y && z == o.z;
    }

    @Override
    public int hashCode() {
        return (z << 28) | (x << 14) | y;
    }

    @Override
    public String toString() {
        return "Position[" + x + ", " + y + ", " + z + "]";
    }

    // ── Getters/Setters ────────────────────────────────────────────────
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }

    public void setX(int x) { this.x = x; }
    public void setY(int y) { this.y = y; }
    public void setZ(int z) { this.z = z; }

    public void set(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
