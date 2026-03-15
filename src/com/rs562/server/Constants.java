package com.rs562.server;

/**
 * 562 Server Constants
 * Central configuration - modify these to tune your server.
 */
public final class Constants {

    // ── Network ────────────────────────────────────────────────────────
    public static final int PORT = 43594;
    public static final int REVISION = 562;

    // ── Game Engine ────────────────────────────────────────────────────
    /** Game tick interval in milliseconds (600ms = standard RS tick) */
    public static final int TICK_RATE = 600;

    /** Maximum concurrent players */
    public static final int MAX_PLAYERS = 2048;

    /** Maximum concurrent NPCs */
    public static final int MAX_NPCS = 32768;

    // ── Default Spawn ──────────────────────────────────────────────────
    /** Default spawn location (Lumbridge) */
    public static final int SPAWN_X = 3222;
    public static final int SPAWN_Y = 3218;
    public static final int SPAWN_Z = 0;

    // ── Map ────────────────────────────────────────────────────────────
    /** Default map size (region chunks) */
    public static final int MAP_SIZES_0 = 104;
    public static final int MAP_SIZES_1 = 104;

    // ── Paths ──────────────────────────────────────────────────────────
    public static final String SAVE_PATH = "data/saves/";

    private Constants() {}
}
