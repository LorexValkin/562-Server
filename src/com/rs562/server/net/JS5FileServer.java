package com.rs562.server.net;

import com.rs562.server.Constants;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.File;
import java.net.Socket;

/**
 * JS5 Update Server — serves cache files to the client.
 *
 * The 562 client connects to the server with type 15 before login.
 * It requests cache files (reference tables, models, maps, etc.)
 * to validate and populate its local cache. Without a working JS5
 * server, the client will show "js5crc" and refuse to proceed.
 *
 * This implementation reads from the local cache directory
 * (same files the client downloads from OpenRS2).
 *
 * Protocol:
 *   1. Client sends 4 bytes (revision int)
 *   2. Server responds 1 byte (0 = OK)
 *   3. Client sends file requests: 1 byte opcode + 3 bytes data
 *   4. Server responds with file data in 512-byte chunks
 */
public class JS5FileServer implements Runnable {

    private final Socket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean connected = true;

    // Cache file handles
    private RandomAccessFile dataFile;
    private RandomAccessFile[] indexFiles;
    private RandomAccessFile metaIndex; // index 255

    public JS5FileServer(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(30000);
            in = socket.getInputStream();
            out = socket.getOutputStream();

            // Step 1: Read revision (4 bytes)
            int revision = readInt();
            if (revision != Constants.REVISION) {
                System.out.println("[JS5] Bad revision: " + revision + " (expected " + Constants.REVISION + ")");
                out.write(6); // response 6 = client out of date
                out.flush();
                close();
                return;
            }

            // Step 2: Respond OK
            out.write(0);
            out.flush();

            // Step 3: Open cache files
            if (!openCache()) {
                System.out.println("[JS5] Failed to open cache files. Make sure cache is at: " + getCacheDir());
                close();
                return;
            }

            System.out.println("[JS5] Connected, serving cache files from: " + getCacheDir());

            // Step 4: Handle file requests
            socket.setSoTimeout(0);
            while (connected) {
                int available = in.available();
                if (available >= 4) {
                    int opcode = in.read() & 0xFF;
                    int indexId = in.read() & 0xFF;
                    int fileId = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);

                    switch (opcode) {
                        case 0: // Normal priority request
                        case 1: // High priority request
                            serveFile(indexId, fileId, opcode == 1);
                            break;
                        case 2: // Client logged in
                        case 3: // Client logged out
                            // Acknowledged, no response needed
                            break;
                        case 4: // Encryption key update
                            // Read 2 more bytes (padding) — total 4 bytes already read
                            // The indexId and fileId we read ARE the key + padding
                            break;
                        default:
                            System.out.println("[JS5] Unknown opcode: " + opcode);
                            break;
                    }
                } else {
                    Thread.sleep(10);
                }
            }
        } catch (Exception e) {
            if (connected) {
                // Only log if it's not a normal disconnect
                String msg = e.getMessage();
                if (msg != null && !msg.contains("Connection reset") && !msg.contains("Socket closed")) {
                    System.out.println("[JS5] Error: " + msg);
                }
            }
        } finally {
            closeCache();
            close();
        }
    }

    /**
     * Serve a single file from the cache.
     * Index 255 = master/meta index (reference table).
     * Other indices = normal cache data.
     */
    private void serveFile(int indexId, int fileId, boolean priority) throws IOException {
        byte[] data;

        if (indexId == 255 && fileId == 255) {
            // Request for the master index (reference table checksum data)
            data = readMasterIndex();
        } else if (indexId == 255) {
            // Request for a specific index's reference table
            data = readCacheFile(255, fileId);
        } else {
            // Normal cache file request
            data = readCacheFile(indexId, fileId);
        }

        if (data == null) {
            // File not found — skip silently, client will handle it
            return;
        }

        // Send the response in 512-byte blocks with the JS5 framing
        writeFileResponse(indexId, fileId, data);
    }

    /**
     * Read the master reference table (idx255 entry for file 255).
     * This is constructed from all the index reference tables.
     */
    private byte[] readMasterIndex() throws IOException {
        if (metaIndex == null) return null;

        // Count how many indices exist
        int indexCount = (int) (metaIndex.length() / 6);

        // Build the master index: 8 bytes per index (4 CRC + 4 revision)
        // We read each index's reference table, but for a minimal implementation
        // we send the raw container data for each index from the meta index
        Buffer buf = new Buffer(indexCount * 8 + 10);
        for (int i = 0; i < indexCount; i++) {
            byte[] idxData = readIndexEntry(metaIndex, i);
            if (idxData != null) {
                // Read the container from the data file
                int fileSize = ((idxData[0] & 0xFF) << 16) | ((idxData[1] & 0xFF) << 8) | (idxData[2] & 0xFF);
                int blockId = ((idxData[3] & 0xFF) << 16) | ((idxData[4] & 0xFF) << 8) | (idxData[5] & 0xFF);

                byte[] container = readFromDataFile(fileSize, blockId, i);
                if (container != null) {
                    // CRC32 of the container
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(container);
                    buf.writeInt((int) crc.getValue());

                    // Version — last 2 bytes of the container if present
                    int version = 0;
                    if (container.length >= 2) {
                        version = ((container[container.length - 2] & 0xFF) << 8) | (container[container.length - 1] & 0xFF);
                    }
                    buf.writeInt(version);
                } else {
                    buf.writeInt(0);
                    buf.writeInt(0);
                }
            } else {
                buf.writeInt(0);
                buf.writeInt(0);
            }
        }

        byte[] result = new byte[buf.getPosition()];
        System.arraycopy(buf.getData(), 0, result, 0, result.length);
        return result;
    }

    /**
     * Read a cache file by index and file ID.
     */
    private byte[] readCacheFile(int indexId, int fileId) throws IOException {
        RandomAccessFile idx;
        if (indexId == 255) {
            idx = metaIndex;
        } else if (indexId < indexFiles.length && indexFiles[indexId] != null) {
            idx = indexFiles[indexId];
        } else {
            return null;
        }

        byte[] entry = readIndexEntry(idx, fileId);
        if (entry == null) return null;

        int fileSize = ((entry[0] & 0xFF) << 16) | ((entry[1] & 0xFF) << 8) | (entry[2] & 0xFF);
        int blockId = ((entry[3] & 0xFF) << 16) | ((entry[4] & 0xFF) << 8) | (entry[5] & 0xFF);

        if (fileSize == 0 || blockId == 0) return null;

        return readFromDataFile(fileSize, blockId, indexId);
    }

    /**
     * Read a 6-byte index entry.
     */
    private byte[] readIndexEntry(RandomAccessFile idx, int fileId) throws IOException {
        long pos = (long) fileId * 6;
        if (pos + 6 > idx.length()) return null;

        byte[] entry = new byte[6];
        synchronized (idx) {
            idx.seek(pos);
            idx.readFully(entry);
        }
        return entry;
    }

    /**
     * Read file data from main_file_cache.dat2 following the block chain.
     */
    private byte[] readFromDataFile(int fileSize, int firstBlock, int indexId) throws IOException {
        byte[] result = new byte[fileSize];
        int remaining = fileSize;
        int offset = 0;
        int blockId = firstBlock;
        int chunk = 0;

        while (remaining > 0) {
            long blockPos = (long) blockId * 520;
            if (blockPos + 520 > dataFile.length()) return null;

            byte[] block = new byte[520];
            synchronized (dataFile) {
                dataFile.seek(blockPos);
                dataFile.readFully(block);
            }

            // Block header: fileId(2) + chunk(2) + nextBlock(3) + indexId(1) = 8 bytes
            int blockFileId = ((block[0] & 0xFF) << 8) | (block[1] & 0xFF);
            int blockChunk = ((block[2] & 0xFF) << 8) | (block[3] & 0xFF);
            int nextBlock = ((block[4] & 0xFF) << 16) | ((block[5] & 0xFF) << 8) | (block[6] & 0xFF);
            int blockIndex = block[7] & 0xFF;

            int dataLen = Math.min(remaining, 512);
            System.arraycopy(block, 8, result, offset, dataLen);

            remaining -= dataLen;
            offset += dataLen;
            blockId = nextBlock;
            chunk++;
        }

        return result;
    }

    /**
     * Write a file response in the JS5 framing format.
     * Data is sent in 512-byte chunks with a header.
     */
    private void writeFileResponse(int indexId, int fileId, byte[] data) throws IOException {
        int compression = data.length > 0 ? data[0] & 0xFF : 0;
        int length = data.length > 5 ? ((data[1] & 0xFF) << 24) | ((data[2] & 0xFF) << 16)
            | ((data[3] & 0xFF) << 8) | (data[4] & 0xFF) : 0;

        // Write the response header
        byte[] header = new byte[8];
        header[0] = (byte) indexId;
        header[1] = (byte) (fileId >> 8);
        header[2] = (byte) fileId;
        header[3] = data.length > 0 ? data[0] : 0; // compression type
        header[4] = data.length > 1 ? data[1] : 0;  // length bytes
        header[5] = data.length > 2 ? data[2] : 0;
        header[6] = data.length > 3 ? data[3] : 0;
        header[7] = data.length > 4 ? data[4] : 0;
        out.write(header);

        // Write data starting from offset 5, in 512-byte chunks
        // After every 512 bytes, write 0xFF separator
        int dataOffset = 5;
        int written = 3; // already wrote 3 bytes of header data beyond the initial 5
        while (dataOffset < data.length) {
            if (written == 512) {
                out.write(0xFF);
                written = 0;
            }
            out.write(data[dataOffset++]);
            written++;
        }
        out.flush();
    }

    // ── Cache File Management ──────────────────────────────────────────

    private String getCacheDir() {
        return System.getProperty("user.home") + "/562cache";
    }

    private boolean openCache() {
        try {
            String dir = getCacheDir();
            File datFile = new File(dir, "main_file_cache.dat2");
            File metaFile = new File(dir, "main_file_cache.idx255");

            if (!datFile.exists() || !metaFile.exists()) {
                System.out.println("[JS5] Cache files not found at: " + dir);
                System.out.println("[JS5] Expected: main_file_cache.dat2 and main_file_cache.idx255");
                return false;
            }

            dataFile = new RandomAccessFile(datFile, "r");
            metaIndex = new RandomAccessFile(metaFile, "r");

            // Open available index files (0-28)
            indexFiles = new RandomAccessFile[29];
            for (int i = 0; i < 29; i++) {
                File idxFile = new File(dir, "main_file_cache.idx" + i);
                if (idxFile.exists()) {
                    indexFiles[i] = new RandomAccessFile(idxFile, "r");
                }
            }

            return true;
        } catch (Exception e) {
            System.err.println("[JS5] Failed to open cache: " + e.getMessage());
            return false;
        }
    }

    private void closeCache() {
        try { if (dataFile != null) dataFile.close(); } catch (Exception ignored) {}
        try { if (metaIndex != null) metaIndex.close(); } catch (Exception ignored) {}
        if (indexFiles != null) {
            for (RandomAccessFile f : indexFiles) {
                try { if (f != null) f.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ── I/O Helpers ────────────────────────────────────────────────────

    private int readInt() throws IOException {
        return ((in.read() & 0xFF) << 24)
             | ((in.read() & 0xFF) << 16)
             | ((in.read() & 0xFF) << 8)
             |  (in.read() & 0xFF);
    }

    public void close() {
        connected = false;
        try { socket.close(); } catch (Exception ignored) {}
    }
}
