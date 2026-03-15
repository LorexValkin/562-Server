# Packet Opcode Tuning Guide

## The Reality of Decompiled Clients

The server I built uses educated guesses for packet opcodes based on:
- The 562 protocol documentation
- Analysis of your client's Login.java, client.java, and PacketParser.java
- Common patterns across 508/530/562 era servers

**However**, your specific client build (the Fractize-based decompile) may use
different opcodes than documented. JODE decompilation + obfuscation makes it
very hard to extract the exact opcode table without running the code.

## How To Find the Correct Opcodes

### Method 1: Packet Sniffer (Recommended)

Add debug logging to the Session's game loop. When the client sends or expects
a packet and the server doesn't recognize it, log the raw bytes:

```java
// In Session.handlePacket():
System.out.println("[PACKET] Received opcode: " + opcode + " (0x" 
    + Integer.toHexString(opcode) + ") size: " + size);
```

### Method 2: Client-Side Logging

Add print statements to your client's packet processing code.
The key places in your client source:

- **Incoming packets**: Look for where `Class128_Sub1.aClass33_4013.method444()`
  is called — that reads the opcode byte from the server
- **Outgoing packets**: Look for `createPacket()` calls in the Stream class —
  those are the opcodes the client sends TO the server

### Method 3: Empirical Testing

1. Start the server
2. Start the client (you'll likely get "Connection lost" at first)
3. Check the server console for what bytes arrived
4. Cross-reference with known 562 protocol docs
5. Adjust opcodes in Session.java and rebuild

## Opcodes That Need Verification

These are the server→client opcodes I used (may need adjustment):

| Purpose | Opcode Used | Confidence | Notes |
|---------|-------------|------------|-------|
| Map Region | 162 | Medium | Varies by client build |
| Player Update | 42 | Medium | Critical — wrong opcode = crash |
| Root Interface | 29 | Medium | Opens the game frame |
| Sub Interface | 155 | Medium | Tab interfaces |
| Skill Update | 39 | Low | May differ significantly |
| Chat Message | 218 | Medium | Server message to player |

## What Happens When an Opcode Is Wrong

- **Server→Client wrong opcode**: Client ignores it or crashes/disconnects.
  You'll see "Connection lost" on the client.
- **Client→Server wrong size**: Server reads wrong number of bytes, causing
  all subsequent packets to be misaligned. The PACKET_SIZES array in
  Session.java needs to match exactly.

## Priority Fix Order

1. **Map Region** — If this opcode is wrong, the client will never render the world
2. **Player Update** — If wrong, you'll be invisible / crash
3. **Root Interface** — If wrong, no game frame appears
4. **Sub Interfaces** — If wrong, tabs are empty but game still works
5. **Messages** — If wrong, no chat but everything else works
