package com.rs562.server.engine;

import com.rs562.server.Constants;
import com.rs562.server.model.World;

/**
 * Game engine — runs the world tick loop at 600ms intervals.
 * Each tick processes all player movement, combat, and sends updates.
 */
public class GameEngine implements Runnable {

    private volatile boolean running = true;
    private int tickCount = 0;

    @Override
    public void run() {
        System.out.println("[ENGINE] Game engine started (" + Constants.TICK_RATE + "ms ticks)");

        while (running) {
            long start = System.currentTimeMillis();

            try {
                World.get().tick();
                tickCount++;

                // Periodic status log (every 100 ticks = ~60 seconds)
                if (tickCount % 100 == 0) {
                    System.out.println("[ENGINE] Tick " + tickCount
                        + " | Players: " + World.get().getPlayerCount());
                }
            } catch (Exception e) {
                System.err.println("[ENGINE] Error in tick " + tickCount + ": " + e.getMessage());
                e.printStackTrace();
            }

            // Sleep for the remainder of the tick
            long elapsed = System.currentTimeMillis() - start;
            long sleep = Constants.TICK_RATE - elapsed;
            if (sleep > 0) {
                try { Thread.sleep(sleep); } catch (InterruptedException ignored) {}
            } else if (elapsed > Constants.TICK_RATE * 2) {
                System.err.println("[ENGINE] WARNING: Tick " + tickCount
                    + " took " + elapsed + "ms (>" + Constants.TICK_RATE + "ms)");
            }
        }

        System.out.println("[ENGINE] Game engine stopped");
    }

    public void stop() {
        running = false;
    }

    public int getTickCount() {
        return tickCount;
    }
}
