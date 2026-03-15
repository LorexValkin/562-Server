package com.rs562.server.net;

import com.rs562.server.Constants;
import com.rs562.server.model.Position;
import com.rs562.server.model.World;
import com.rs562.server.model.player.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Manages a single client connection: login handshake and game packet I/O.
 *
 * The 562 login protocol (with RSA/ISAAC disabled) works as:
 *   1. Client connects, sends opcode 14 + name hash byte
 *   2. Server responds: status 0 + 8 bytes (server session key)
 *   3. Client sends RSA block (opcode 10, seeds, username, password, revision)
 *      wrapped in outer block (revision 562, machine info, CRC checksums)
 *   4. Server responds: status 2 (success) + 14 session bytes + N remaining bytes
 *   5. Client enters game; server must send map region + player update packets
 */
public class Session implements Runnable {

    private final Socket socket;
    private InputStream in;
    private OutputStream out;
    private Player player;
    private volatile boolean connected = true;

    public Session(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(30000);
            in = socket.getInputStream();
            out = socket.getOutputStream();

            // Step 1: Read connection type
            int connectionType = in.read();
            if (connectionType == 14) {
                handleGameLogin();
            } else if (connectionType == 15) {
                // JS5 update server — hand off to dedicated handler
                new JS5FileServer(socket).run();
                return; // JS5FileServer handles its own cleanup
            } else {
                System.out.println("[NET] Unknown connection type: " + connectionType);
                close();
            }
        } catch (Exception e) {
            if (connected) {
                System.out.println("[NET] Connection error: " + e.getMessage());
            }
        } finally {
            if (player != null && player.isActive()) {
                World.get().unregister(player);
            }
            close();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  LOGIN PROTOCOL
    // ════════════════════════════════════════════════════════════════════

    private void handleGameLogin() throws IOException {
        // Read the name hash byte (1 byte, ignored)
        in.read();

        // Send response: status 0 + 8 bytes server key
        byte[] response = new byte[9];
        response[0] = 0; // status OK
        // Bytes 1-8 = server session key (can be random, ISAAC is disabled)
        long serverKey = (long) (Math.random() * Long.MAX_VALUE);
        for (int i = 0; i < 8; i++) {
            response[1 + i] = (byte) (serverKey >> (56 - i * 8));
        }
        out.write(response);
        out.flush();

        // Read login block
        int loginType = in.read(); // 16 = normal login, 18 = reconnect
        if (loginType != 16 && loginType != 18) {
            sendLoginResponse(10); // bad session
            return;
        }

        int blockSize = readShort();
        byte[] block = new byte[blockSize];
        readFully(block);

        Buffer buf = new Buffer(block);

        // Outer block
        int revision = buf.readInt();
        if (revision != Constants.REVISION) {
            System.out.println("[LOGIN] Bad revision: " + revision + " (expected " + Constants.REVISION + ")");
            sendLoginResponse(6); // updated
            return;
        }

        int displayMode = buf.readUnsignedByte();
        int screenSizeId = buf.readUnsignedByte();
        int screenWidth = buf.readUnsignedShort();
        int screenHeight = buf.readUnsignedShort();
        int aaMode = buf.readUnsignedByte();

        // Machine info block (variable, skip past it)
        // The client writes machine info via Class110_Sub3.method947
        // which writes an opcode byte, then various system properties.
        // We just need to skip to the username/password.
        // Machine info starts with a length-prefixed block
        skipMachineInfo(buf);

        // Skip the settings string
        buf.readString();

        // Skip two ints (machine info hashes)
        buf.readInt();
        buf.readInt();

        // Skip a short
        buf.readUnsignedShort();

        // Skip 29 CRC checksums (29 x 4 bytes = 116 bytes)
        for (int i = 0; i < 29; i++) {
            buf.readInt();
        }

        // RSA block (not encrypted since DISABLE_RSA = true)
        // Starts with opcode 10 check
        int rsaOpcode = buf.readUnsignedByte();
        if (rsaOpcode != 10) {
            System.out.println("[LOGIN] Bad RSA opcode: " + rsaOpcode);
            sendLoginResponse(10);
            return;
        }

        // ISAAC seeds (4 ints, ignored since DISABLE_ISAAC = true)
        for (int i = 0; i < 4; i++) {
            buf.readInt();
        }

        // Username hash (long, ignored — we read the string below)
        buf.readLong();

        // Username and password strings
        String username = buf.readString();
        String password = buf.readString(); // "ostava" in this client

        // Cache revision
        int cacheRevision = buf.readInt();

        System.out.println("[LOGIN] Login request: " + username + " (rev=" + revision
            + ", cache=" + cacheRevision + ", display=" + displayMode + ")");

        // Create and register the player
        player = new Player(this, username);

        if (!World.get().register(player)) {
            sendLoginResponse(7); // world full
            return;
        }

        // Send success response
        sendLoginResponse(2);
        sendSessionData();

        System.out.println("[LOGIN] " + username + " logged in successfully");

        // Initialize the player (send game frame, map, etc.)
        initializePlayer();

        // Enter game read loop
        gameLoop();
    }

    private void skipMachineInfo(Buffer buf) {
        // Machine info is written by Class110_Sub3.method947
        // It writes a block starting with an opcode byte, then various data.
        // The exact length varies, but we can parse through it.
        // Format: byte opcode, then system properties
        int opcode = buf.readUnsignedByte();
        if (opcode == 6) { // standard machine info block
            buf.readUnsignedByte(); // os type
            buf.readUnsignedByte(); // 64bit flag
            buf.readUnsignedByte(); // os version
            buf.readUnsignedByte(); // java vendor
            buf.readUnsignedByte(); // java major
            buf.readUnsignedByte(); // java minor
            buf.readUnsignedByte(); // java patch
            buf.readUnsignedByte(); // ??? 
            buf.readUnsignedShort(); // max memory
            buf.readUnsignedByte(); // available processors
            buf.readMedium(); // ??? 
            buf.readUnsignedShort(); // ???
            buf.readString(); // ???
            buf.readString(); // ???
            buf.readString(); // ???
            buf.readString(); // ???
            buf.readUnsignedByte(); // ???
            buf.readUnsignedShort(); // ???
        }
        // If opcode is something else, we may need to handle differently
        // For now this covers the standard case
    }

    private void sendLoginResponse(int code) throws IOException {
        out.write(code);
        out.flush();
    }

    private void sendSessionData() throws IOException {
        // After response code 2, the client reads 14 bytes + N remaining bytes.
        // 14-byte session header:
        byte[] session = new byte[14];
        int pos = 0;
        session[pos++] = (byte) player.getRights();  // rights (0/1/2)
        session[pos++] = 0;                           // ???
        session[pos++] = 1;                           // member (1=yes)
        session[pos++] = 0;                           // ???
        session[pos++] = 0;                           // ???
        session[pos++] = 0;                           // ???
        session[pos++] = 0;                           // ???
        session[pos++] = 0; session[pos++] = 0;       // short: ???
        session[pos++] = 0;                           // ???
        session[pos++] = 1;                           // HD enabled
        // Player index (int, but only first byte matters for method1793 which reads 1 byte)
        session[pos++] = (byte) player.getIndex();
        // Remaining bytes count (short) — 0 means no additional login data
        session[pos++] = 0;
        session[pos++] = 0;
        out.write(session);
        out.flush();
    }

    // ════════════════════════════════════════════════════════════════════
    //  GAME PACKETS — OUTGOING
    // ════════════════════════════════════════════════════════════════════

    /** Send all post-login initialization packets */
    private void initializePlayer() throws IOException {
        player.updateMapBase();
        sendMapRegion();
        sendPlayerUpdate();
        sendGameFrame();
        sendTabs();
        sendSkills();
        sendMessage("Welcome to 562 Dev Server.");
        sendMessage("Type ::commands for available commands.");
        player.setInitialized(true);
        player.setRegionChanged(false);
        player.setTeleporting(false);
        player.setAppearanceUpdated(false);
    }

    /** Called each tick by World to send updates */
    public void sendPostTick(Player p) throws IOException {
        if (!connected) return;
        if (p.isRegionChanged() || p.needsMapUpdate()) {
            p.updateMapBase();
            sendMapRegion();
        }
        sendPlayerUpdate();
    }

    /** Send map region packet — tells client which region to render */
    private void sendMapRegion() throws IOException {
        // 562 map region packet opcode = varies by source
        // The packet sends chunk coordinates for the client's map base
        // Opcode for buildMapRegion in 562: typically 162 or similar
        // Since we can determine this from the client's PacketParser, we use
        // a known 562 opcode for map region.
        Buffer buf = new Buffer(256);
        buf.createVarShortPacket(162); // map region opcode

        int chunkX = player.getPosition().getChunkX();
        int chunkY = player.getPosition().getChunkY();
        boolean forceReload = player.isTeleporting() || !player.isInitialized();

        buf.writeByteA(player.getPosition().getZ());
        buf.writeShortA(chunkX);
        buf.writeLEShort(chunkY);
        buf.writeByteC(forceReload ? 1 : 0);

        // Write region keys (XTEA keys, all zeros since we don't encrypt map data)
        int regionBaseX = (chunkX - 6) / 8;
        int regionBaseY = (chunkY - 6) / 8;
        int regionEndX = (chunkX + 6) / 8;
        int regionEndY = (chunkY + 6) / 8;

        for (int rx = regionBaseX; rx <= regionEndX; rx++) {
            for (int ry = regionBaseY; ry <= regionEndY; ry++) {
                // 4 ints per region (XTEA key = 0,0,0,0)
                buf.writeInt(0);
                buf.writeInt(0);
                buf.writeInt(0);
                buf.writeInt(0);
            }
        }

        buf.endVarShortPacket();
        write(buf);
    }

    /** Send player update — the main player rendering packet */
    private void sendPlayerUpdate() throws IOException {
        Buffer buf = new Buffer(4096);
        Buffer blockBuf = new Buffer(2048); // appearance/update blocks

        buf.createVarShortPacket(42); // player update opcode

        buf.initBitAccess();

        // Local player update
        if (player.isTeleporting() || !player.isInitialized()) {
            // Teleport: flag update, type 3
            buf.writeBits(1, 1); // update required
            buf.writeBits(2, 3); // update type 3 = teleport
            buf.writeBits(2, player.getPosition().getZ());
            buf.writeBits(1, 1); // discard walking queue
            buf.writeBits(1, 1); // has update blocks
            buf.writeBits(7, player.getPosition().getLocalY(player.getMapBase()));
            buf.writeBits(7, player.getPosition().getLocalX(player.getMapBase()));
        } else {
            // No movement
            if (player.isAppearanceUpdated()) {
                buf.writeBits(1, 1); // update required
                buf.writeBits(2, 0); // type 0 = no movement, but has update blocks
            } else {
                buf.writeBits(1, 0); // no update needed
            }
        }

        // Other players — write 0 (none visible for now)
        buf.writeBits(8, 0); // local player count = 0
        buf.writeBits(11, 0); // new players to add = 0

        buf.finishBitAccess();

        // Appearance update block (for our player)
        if (player.isTeleporting() || player.isAppearanceUpdated() || !player.isInitialized()) {
            byte[] appearance = player.encodeAppearance();
            blockBuf.writeByte(0x4);  // appearance update flag
            blockBuf.writeByteC(appearance.length);
            blockBuf.writeBytes(appearance, 0, appearance.length);
        }

        // Append update blocks
        if (blockBuf.getPosition() > 0) {
            buf.writeBytes(blockBuf.getData(), 0, blockBuf.getPosition());
        }

        buf.endVarShortPacket();
        write(buf);
    }

    /** Send the main game frame interface (gameframe) */
    private void sendGameFrame() throws IOException {
        // Opens the root game interface
        // 562 uses interface IDs for the game frame
        // Opcode for root interface: 29
        sendInterface(29, 548); // 548 = fixed game frame
    }

    private void sendInterface(int opcode, int interfaceId) throws IOException {
        Buffer buf = new Buffer(16);
        buf.createPacket(29); // root interface opcode
        buf.writeShort(interfaceId);
        buf.writeByte(0); // window mode
        write(buf);
    }

    /** Send tab interfaces (inventory, stats, equipment, etc.) */
    private void sendTabs() throws IOException {
        // Each tab needs an interface opened on a specific child of the game frame.
        // These are sendInterfaceOnChild packets
        // 562 tab interface IDs:
        int[][] tabs = {
            { 137, 1 },    // combat tab
            { 320, 68 },   // stats
            { 274, 69 },   // quest list
            { 149, 70 },   // inventory
            { 387, 71 },   // equipment
            { 271, 72 },   // prayer
            { 192, 73 },   // magic (standard)
            { 662, 74 },   // summoning (562+)
            { 550, 75 },   // friends
            { 551, 76 },   // ignore
            { 589, 77 },   // clan
            { 261, 78 },   // settings
            { 464, 79 },   // emotes
            { 187, 80 },   // music
            { 182, 81 },   // logout
        };

        for (int[] tab : tabs) {
            sendTabInterface(tab[0], tab[1]);
        }
    }

    private void sendTabInterface(int interfaceId, int childId) throws IOException {
        // Send sub-interface to a child of the root interface
        Buffer buf = new Buffer(16);
        buf.createPacket(155); // interface-on-child opcode
        buf.writeInt((548 << 16) | childId); // parent hash
        buf.writeShort(interfaceId);
        buf.writeByte(0); // clickthrough
        write(buf);
    }

    /** Send a skill level update */
    private void sendSkills() throws IOException {
        int[] levels = player.getSkillLevels();
        double[] xp = player.getSkillXp();
        for (int i = 0; i < 25; i++) {
            sendSkill(i, levels[i], (int) xp[i]);
        }
    }

    private void sendSkill(int skillId, int level, int xp) throws IOException {
        Buffer buf = new Buffer(16);
        buf.createPacket(39); // skill update opcode
        buf.writeByte(skillId);
        buf.writeInt(xp);
        buf.writeByte(level);
        write(buf);
    }

    /** Send a chat message to the player */
    public void sendMessage(String msg) throws IOException {
        Buffer buf = new Buffer(msg.length() + 8);
        buf.createVarBytePacket(218); // message opcode
        buf.writeString(msg);
        buf.endVarBytePacket();
        write(buf);
    }

    // ════════════════════════════════════════════════════════════════════
    //  GAME LOOP — INCOMING PACKETS
    // ════════════════════════════════════════════════════════════════════

    private void gameLoop() {
        try {
            socket.setSoTimeout(0); // remove timeout for game mode
            while (connected) {
                int available = in.available();
                if (available > 0) {
                    int opcode = in.read() & 0xFF;
                    handlePacket(opcode);
                } else {
                    Thread.sleep(10);
                }
            }
        } catch (Exception e) {
            if (connected) {
                System.out.println("[NET] " + (player != null ? player.getUsername() : "?")
                    + " disconnected: " + e.getMessage());
            }
        }
    }

    private void handlePacket(int opcode) throws IOException {
        // Read packet based on known sizes. For now, just consume bytes
        // to prevent the buffer from filling up.
        // Full packet size table would go here.
        int size = getPacketSize(opcode);
        if (size == -1) {
            // Var-byte: read 1 byte for size
            size = in.read() & 0xFF;
        } else if (size == -2) {
            // Var-short: read 2 bytes for size
            size = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        }
        byte[] data = new byte[size];
        if (size > 0) {
            readFully(data);
        }

        // Handle known packets
        switch (opcode) {
            case 14: // Walk (click on minimap)
            case 78: // Walk (click on screen)
            case 39: // Walk (to object/npc)
                handleWalk(new Buffer(data));
                break;
            case 4: // Public chat
                handleChat(new Buffer(data));
                break;
            case 44: // Command
                handleCommand(new Buffer(data));
                break;
            default:
                // Silently consume unknown packets
                break;
        }
    }

    private void handleWalk(Buffer buf) {
        // Basic walk packet: reads destination tile coordinates
        // For now, just teleport to the target position
        try {
            // Walk packets vary in format, but the first bytes typically contain
            // the destination coordinates. This is a simplified handler.
            if (buf.remaining() >= 4) {
                // Parse will vary by exact opcode — placeholder for expansion
            }
        } catch (Exception e) {
            // Silently handle malformed walk packets during dev
        }
    }

    private void handleChat(Buffer buf) {
        // Public chat message — would broadcast to nearby players
    }

    private void handleCommand(Buffer buf) {
        try {
            String command = new String(buf.getData(), 0, buf.getData().length).trim();
            processCommand(command);
        } catch (Exception e) {
            // Malformed command packet
        }
    }

    private void processCommand(String raw) throws IOException {
        String[] parts = raw.split(" ");
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "tele":
            case "teleport":
                if (parts.length >= 3) {
                    int x = Integer.parseInt(parts[1]);
                    int y = Integer.parseInt(parts[2]);
                    int z = parts.length >= 4 ? Integer.parseInt(parts[3]) : 0;
                    player.getPosition().set(x, y, z);
                    player.setTeleporting(true);
                    player.setRegionChanged(true);
                    sendMessage("Teleported to " + x + ", " + y + ", " + z);
                } else {
                    sendMessage("Usage: ::tele x y [z]");
                }
                break;

            case "pos":
            case "mypos":
                Position p = player.getPosition();
                sendMessage("Position: " + p.getX() + ", " + p.getY() + ", " + p.getZ()
                    + " | Region: " + p.getRegionId()
                    + " | Chunk: " + p.getChunkX() + "," + p.getChunkY());
                break;

            case "commands":
                sendMessage("Commands: ::tele x y [z], ::pos, ::item id [amount]");
                break;

            default:
                sendMessage("Unknown command: " + cmd);
                break;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  UPDATE SERVER (cache serving — stub)
    // ════════════════════════════════════════════════════════════════════

    private void handleUpdateServer() throws IOException {
        // The update server serves cache files to the client on request.
        // For now, the client loads cache from local disk, so this is a stub.
        // A full implementation would read the client's request and serve
        // the appropriate cache file from the 562 cache.
        System.out.println("[UPDATE] Update server request (not implemented - client uses local cache)");
        close();
    }

    // ════════════════════════════════════════════════════════════════════
    //  I/O HELPERS
    // ════════════════════════════════════════════════════════════════════

    private synchronized void write(Buffer buf) throws IOException {
        if (!connected) return;
        out.write(buf.getData(), 0, buf.getPosition());
        out.flush();
    }

    private int readShort() throws IOException {
        return ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
    }

    private void readFully(byte[] dest) throws IOException {
        int offset = 0;
        while (offset < dest.length) {
            int read = in.read(dest, offset, dest.length - offset);
            if (read == -1) throw new IOException("End of stream");
            offset += read;
        }
    }

    public void close() {
        connected = false;
        try { socket.close(); } catch (Exception ignored) {}
    }

    // ════════════════════════════════════════════════════════════════════
    //  PACKET SIZE TABLE
    // ════════════════════════════════════════════════════════════════════
    // -1 = var byte, -2 = var short, 0+ = fixed size
    // This table needs to match your client's packet sizes.
    // These are common 562 client→server packet sizes.
    private static final int[] PACKET_SIZES = new int[256];
    static {
        // Initialize all to 0 (unknown/fixed-0)
        for (int i = 0; i < 256; i++) PACKET_SIZES[i] = 0;
        // Known 562 packet sizes (client → server)
        // These may need adjustment based on your specific client build.
        PACKET_SIZES[4] = -1;   // public chat
        PACKET_SIZES[14] = -1;  // walk minimap
        PACKET_SIZES[39] = -1;  // walk to entity
        PACKET_SIZES[44] = -1;  // command
        PACKET_SIZES[78] = -1;  // walk screen click
        // Add more as needed. When a packet arrives with wrong size,
        // the server logs it and you can add the correct entry.
    }

    private int getPacketSize(int opcode) {
        if (opcode < 0 || opcode >= 256) return 0;
        return PACKET_SIZES[opcode];
    }
}
