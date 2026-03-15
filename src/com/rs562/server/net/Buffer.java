package com.rs562.server.net;

/**
 * Binary buffer for reading/writing RS protocol data.
 * Supports standard and RS-specific byte transformations (A, C, S variants).
 */
public class Buffer {

    private byte[] data;
    private int position;
    private int bitPosition;

    public Buffer(int capacity) {
        this.data = new byte[capacity];
        this.position = 0;
    }

    public Buffer(byte[] data) {
        this.data = data;
        this.position = 0;
    }

    // ── Write methods ──────────────────────────────────────────────────

    public void writeByte(int value) {
        data[position++] = (byte) value;
    }

    public void writeByteA(int value) {
        data[position++] = (byte) (value + 128);
    }

    public void writeByteC(int value) {
        data[position++] = (byte) -value;
    }

    public void writeByteS(int value) {
        data[position++] = (byte) (128 - value);
    }

    public void writeShort(int value) {
        data[position++] = (byte) (value >> 8);
        data[position++] = (byte) value;
    }

    public void writeShortA(int value) {
        data[position++] = (byte) (value >> 8);
        data[position++] = (byte) (value + 128);
    }

    public void writeLEShort(int value) {
        data[position++] = (byte) value;
        data[position++] = (byte) (value >> 8);
    }

    public void writeLEShortA(int value) {
        data[position++] = (byte) (value + 128);
        data[position++] = (byte) (value >> 8);
    }

    public void writeMedium(int value) {
        data[position++] = (byte) (value >> 16);
        data[position++] = (byte) (value >> 8);
        data[position++] = (byte) value;
    }

    public void writeInt(int value) {
        data[position++] = (byte) (value >> 24);
        data[position++] = (byte) (value >> 16);
        data[position++] = (byte) (value >> 8);
        data[position++] = (byte) value;
    }

    public void writeIntAlt1(int value) {
        data[position++] = (byte) (value >> 8);
        data[position++] = (byte) value;
        data[position++] = (byte) (value >> 24);
        data[position++] = (byte) (value >> 16);
    }

    public void writeIntAlt2(int value) {
        data[position++] = (byte) (value >> 16);
        data[position++] = (byte) (value >> 24);
        data[position++] = (byte) value;
        data[position++] = (byte) (value >> 8);
    }

    public void writeLong(long value) {
        data[position++] = (byte) (value >> 56);
        data[position++] = (byte) (value >> 48);
        data[position++] = (byte) (value >> 40);
        data[position++] = (byte) (value >> 32);
        data[position++] = (byte) (value >> 24);
        data[position++] = (byte) (value >> 16);
        data[position++] = (byte) (value >> 8);
        data[position++] = (byte) value;
    }

    public void writeString(String value) {
        for (int i = 0; i < value.length(); i++) {
            data[position++] = (byte) value.charAt(i);
        }
        data[position++] = 0; // NUL terminator
    }

    public void writeBytes(byte[] src, int offset, int length) {
        System.arraycopy(src, offset, data, position, length);
        position += length;
    }

    // ── Bit writing ────────────────────────────────────────────────────

    public void initBitAccess() {
        bitPosition = position * 8;
    }

    public void writeBits(int numBits, int value) {
        int bytePos = bitPosition >> 3;
        int bitOffset = 8 - (bitPosition & 7);
        bitPosition += numBits;

        // Ensure capacity
        int needed = (bitPosition + 7) / 8;
        if (needed > data.length) {
            byte[] newData = new byte[needed + 128];
            System.arraycopy(data, 0, newData, 0, data.length);
            data = newData;
        }

        for (; numBits > bitOffset; bitOffset = 8) {
            data[bytePos] &= ~BIT_MASK[bitOffset];
            data[bytePos++] |= (value >> (numBits - bitOffset)) & BIT_MASK[bitOffset];
            numBits -= bitOffset;
        }
        if (numBits == bitOffset) {
            data[bytePos] &= ~BIT_MASK[bitOffset];
            data[bytePos] |= value & BIT_MASK[bitOffset];
        } else {
            data[bytePos] &= ~(BIT_MASK[numBits] << (bitOffset - numBits));
            data[bytePos] |= (value & BIT_MASK[numBits]) << (bitOffset - numBits);
        }
    }

    public void finishBitAccess() {
        position = (bitPosition + 7) / 8;
    }

    // ── Read methods ───────────────────────────────────────────────────

    public int readByte() {
        return data[position++];
    }

    public int readUnsignedByte() {
        return data[position++] & 0xFF;
    }

    public int readUnsignedByteA() {
        return (data[position++] - 128) & 0xFF;
    }

    public int readUnsignedByteC() {
        return -data[position++] & 0xFF;
    }

    public int readUnsignedByteS() {
        return (128 - data[position++]) & 0xFF;
    }

    public int readShort() {
        int value = ((data[position++] & 0xFF) << 8) | (data[position++] & 0xFF);
        if (value > 32767) value -= 65536;
        return value;
    }

    public int readUnsignedShort() {
        return ((data[position++] & 0xFF) << 8) | (data[position++] & 0xFF);
    }

    public int readMedium() {
        return ((data[position++] & 0xFF) << 16)
             | ((data[position++] & 0xFF) << 8)
             |  (data[position++] & 0xFF);
    }

    public int readInt() {
        return ((data[position++] & 0xFF) << 24)
             | ((data[position++] & 0xFF) << 16)
             | ((data[position++] & 0xFF) << 8)
             |  (data[position++] & 0xFF);
    }

    public long readLong() {
        long upper = readInt() & 0xFFFFFFFFL;
        long lower = readInt() & 0xFFFFFFFFL;
        return (upper << 32) | lower;
    }

    public String readString() {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = readUnsignedByte()) != 0) {
            sb.append((char) b);
        }
        return sb.toString();
    }

    public void readBytes(byte[] dest, int offset, int length) {
        System.arraycopy(data, position, dest, offset, length);
        position += length;
    }

    // ── Packet framing ─────────────────────────────────────────────────

    private int packetStart = -1;

    /** Start a fixed-size packet with the given opcode */
    public void createPacket(int opcode) {
        data[position++] = (byte) opcode;
    }

    /** Start a var-byte packet (size written as 1 byte after opcode) */
    public void createVarBytePacket(int opcode) {
        data[position++] = (byte) opcode;
        packetStart = position;
        position++; // placeholder for size
    }

    /** Start a var-short packet (size written as 2 bytes after opcode) */
    public void createVarShortPacket(int opcode) {
        data[position++] = (byte) opcode;
        packetStart = position;
        position += 2; // placeholder for size
    }

    /** End a var-byte packet, writing its size */
    public void endVarBytePacket() {
        data[packetStart] = (byte) (position - packetStart - 1);
    }

    /** End a var-short packet, writing its size */
    public void endVarShortPacket() {
        int size = position - packetStart - 2;
        data[packetStart] = (byte) (size >> 8);
        data[packetStart + 1] = (byte) size;
    }

    // ── Utility ────────────────────────────────────────────────────────

    public int getPosition() { return position; }
    public void setPosition(int pos) { this.position = pos; }
    public byte[] getData() { return data; }
    public int remaining() { return data.length - position; }

    public void ensureCapacity(int needed) {
        if (position + needed > data.length) {
            byte[] newData = new byte[position + needed + 256];
            System.arraycopy(data, 0, newData, 0, position);
            data = newData;
        }
    }

    private static final int[] BIT_MASK = new int[32];
    static {
        for (int i = 0; i < 32; i++) {
            BIT_MASK[i] = (1 << i) - 1;
        }
    }
}
