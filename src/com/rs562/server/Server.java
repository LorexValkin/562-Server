package com.rs562.server;

import com.rs562.server.engine.GameEngine;
import com.rs562.server.net.Session;

import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 562 Dev Server
 * Custom-built server for Chase Foster's educational game design project.
 *
 * Architecture:
 *   - One thread per client connection (Session)
 *   - One game engine thread running the 600ms tick loop
 *   - One main thread accepting new connections
 *
 * For a dev/single-player environment this is clean and simple.
 * For multi-player, you'd want to move to NIO with Netty.
 */
public class Server {

    private static GameEngine engine;

    public static void main(String[] args) {
        System.out.println("================================================");
        System.out.println("  562 Dev Server");
        System.out.println("  Educational Game Design Project");
        System.out.println("================================================");
        System.out.println("  Port:     " + Constants.PORT);
        System.out.println("  Revision: " + Constants.REVISION);
        System.out.println("  Spawn:    " + Constants.SPAWN_X + ", " + Constants.SPAWN_Y);
        System.out.println("  Max:      " + Constants.MAX_PLAYERS + " players");
        System.out.println("================================================");

        // Ensure save directory exists
        new File(Constants.SAVE_PATH).mkdirs();

        // Start game engine
        engine = new GameEngine();
        Thread engineThread = new Thread(engine, "GameEngine");
        engineThread.setDaemon(true);
        engineThread.start();

        // Start accepting connections
        try (ServerSocket serverSocket = new ServerSocket(Constants.PORT)) {
            System.out.println("[SERVER] Listening on port " + Constants.PORT);
            System.out.println("[SERVER] Ready for connections!");
            System.out.println();

            while (true) {
                Socket client = serverSocket.accept();
                String addr = client.getRemoteSocketAddress().toString();
                System.out.println("[NET] New connection from " + addr);

                Session session = new Session(client);
                Thread sessionThread = new Thread(session, "Session-" + addr);
                sessionThread.setDaemon(true);
                sessionThread.start();
            }
        } catch (Exception e) {
            System.err.println("[SERVER] Fatal error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
