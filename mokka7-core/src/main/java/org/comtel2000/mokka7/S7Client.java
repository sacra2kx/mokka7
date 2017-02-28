/*
 * PROJECT Mokka7 (fork of Moka7)
 *
 * Copyright (C) 2013, 2016 Davide Nardella All rights reserved.
 * Copyright (C) 2017 J.Zimmermann All rights reserved.
 *
 * SNAP7 is free software: you can redistribute it and/or modify it under the terms of the Lesser
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or under EPL Eclipse Public License 1.0.
 *
 * This means that you have to chose in advance which take before you import the library into your
 * project.
 *
 * SNAP7 is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE whatever license you
 * decide to adopt.
 */
package org.comtel2000.mokka7;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Dave Nardella
 * @author comtel2000
 */
public class S7Client implements Client, ReturnCode {

    private static final Logger logger = LoggerFactory.getLogger(S7Client.class);

    private static final int DEFAULT_PDU_SIZE_REQUESTED = 480;

    private static final int SIZE_RD = 31;
    private static final int SIZE_WR = 35;

    /** Max number of vars (multiread/write) */
    public static final int MAX_VARS = 20;

    /** Result transport size */
    public static final byte TS_RESBIT = 0x03;
    public static final byte TS_RESBYTE = 0x04;
    public static final byte TS_RESINT = 0x05;
    public static final byte TS_RESREAL = 0x07;
    public static final byte TS_RESOCTET = 0x09;

    /** default ISO tcp port */
    private static final int ISO_TCP = 102;

    /** TPKT+COTP Header Size */
    private static final int ISO_HEADER_SIZE = 7;

    private static final int MAX_PDU_SIZE = DEFAULT_PDU_SIZE_REQUESTED + ISO_HEADER_SIZE;

    private static final int MinPduSize = 16;

    /** TPKT + ISO COTP Header (Connection Oriented Transport Protocol) */
    private static final byte[] TPKT_ISO = { // 7 bytes
            0x03, 0x00, 0x00, 0x1f, // Telegram Length (Data Size + 31 or 35)
            0x02, (byte) 0xf0, (byte) 0x80 // COTP (see above for info)
    };

    /** Telegrams ISO Connection Request telegram (contains also ISO Header and COTP Header) */
    private static final byte ISO_CR[] = {
            // TPKT (RFC1006 Header)
            (byte) 0x03, // RFC 1006 ID (3)
            (byte) 0x00, // Reserved, always 0
            (byte) 0x00, // High part of packet lenght (entire frame, payload and TPDU included)
            (byte) 0x16, // Low part of packet lenght (entire frame, payload and TPDU included)
            // COTP (ISO 8073 Header)
            (byte) 0x11, // PDU Size length
            (byte) 0xE0, // CR - Connection Request ID
            (byte) 0x00, // Dst Reference HI
            (byte) 0x00, // Dst Reference LO
            (byte) 0x00, // Src Reference HI
            (byte) 0x01, // Src Reference LO
            (byte) 0x00, // Class + Options Flags
            (byte) 0xC0, // PDU Max length ID
            (byte) 0x01, // PDU Max length HI
            (byte) 0x0A, // PDU Max length LO
            (byte) 0xC1, // Src TSAP Identifier
            (byte) 0x02, // Src TSAP length (2 bytes)
            (byte) 0x01, // Src TSAP HI (will be overwritten)
            (byte) 0x00, // Src TSAP LO (will be overwritten)
            (byte) 0xC2, // Dst TSAP Identifier
            (byte) 0x02, // Dst TSAP length (2 bytes)
            (byte) 0x01, // Dst TSAP HI (will be overwritten)
            (byte) 0x02 // Dst TSAP LO (will be overwritten)
    };

    /** S7 get Block Info Request Header (contains also ISO Header and COTP Header) */
    private static final byte S7_BI[] = { (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x25, (byte) 0x02, (byte) 0xf0, (byte) 0x80, (byte) 0x32, (byte) 0x07,
            (byte) 0x00, (byte) 0x00, (byte) 0x05, (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x0c, (byte) 0x00, (byte) 0x01, (byte) 0x12,
            (byte) 0x04, (byte) 0x11, (byte) 0x43, (byte) 0x03, (byte) 0x00, (byte) 0xff, (byte) 0x09, (byte) 0x00, (byte) 0x08, (byte) 0x30, (byte) 0x41,
            // Block Type
            (byte) 0x30, (byte) 0x30, (byte) 0x30, (byte) 0x30, (byte) 0x30,
            // ASCII Block Number
            (byte) 0x41 };

    /** S7 Clear Session Password */
    private static final byte S7_CLR_PWD[] = { (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x1d, (byte) 0x02, (byte) 0xf0, (byte) 0x80, (byte) 0x32,
            (byte) 0x07, (byte) 0x00, (byte) 0x00, (byte) 0x29, (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x04, (byte) 0x00, (byte) 0x01,
            (byte) 0x12, (byte) 0x04, (byte) 0x11, (byte) 0x45, (byte) 0x02, (byte) 0x00, (byte) 0x0a, (byte) 0x00, (byte) 0x00, (byte) 0x00 };

    /** S7 COLD start request */
    private static final byte S7_COLD_START[] = { (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x27, (byte) 0x02, (byte) 0xf0, (byte) 0x80, (byte) 0x32,
            (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x0f, (byte) 0x00, (byte) 0x00, (byte) 0x16, (byte) 0x00, (byte) 0x00, (byte) 0x28, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xfd, (byte) 0x00, (byte) 0x02, (byte) 0x43, (byte) 0x20, (byte) 0x09,
            (byte) 0x50, (byte) 0x5f, (byte) 0x50, (byte) 0x52, (byte) 0x4f, (byte) 0x47, (byte) 0x52, (byte) 0x41, (byte) 0x4d };

    /** get Date/Time request */
    private static final byte S7_GET_DT[] = { (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x1d, (byte) 0x02, (byte) 0xf0, (byte) 0x80, (byte) 0x32,
            (byte) 0x07, (byte) 0x00, (byte) 0x00, (byte) 0x38, (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x04, (byte) 0x00, (byte) 0x01,
            (byte) 0x12, (byte) 0x04, (byte) 0x11, (byte) 0x47, (byte) 0x01, (byte) 0x00, (byte) 0x0a, (byte) 0x00, (byte) 0x00, (byte) 0x00 };

    /** S7 get PLC Status */
    private static final byte S7_GET_STAT[] = { (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x21, (byte) 0x02, (byte) 0xf0, (byte) 0x80, (byte) 0x32,
            (byte) 0x07, (byte) 0x00, (byte) 0x00, (byte) 0x2c, (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x01,
            (byte) 0x12, (byte) 0x04, (byte) 0x11, (byte) 0x44, (byte) 0x01, (byte) 0x00, (byte) 0xff, (byte) 0x09, (byte) 0x00, (byte) 0x04, (byte) 0x04,
            (byte) 0x24, (byte) 0x00, (byte) 0x00 };

    /** S7 HOT start request */
    private static final byte S7_HOT_START[] = { (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x25, (byte) 0x02, (byte) 0xf0, (byte) 0x80, (byte) 0x32,
            (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x0c, (byte) 0x00, (byte) 0x00, (byte) 0x14, (byte) 0x00, (byte) 0x00, (byte) 0x28, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xfd, (byte) 0x00, (byte) 0x00, (byte) 0x09, (byte) 0x50, (byte) 0x5f,
            (byte) 0x50, (byte) 0x52, (byte) 0x4f, (byte) 0x47, (byte) 0x52, (byte) 0x41, (byte) 0x4d };

    /** S7 PDU Negotiation Telegram (contains also ISO Header and COTP Header) */
    private static final byte S7_PN[] = { (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x19, (byte) 0x02, (byte) 0xf0,

            (byte) 0x80, // TPKT + COTP (see above for info)

            (byte) 0x32, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x04, (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x00, (byte) 0xf0,
            (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x01, (byte) 0x00,

            (byte) 0x1e // PDU length Requested = HI-LO 480 bytes
    };

    /** S7 Read/Write Request Header (contains also ISO Header and COTP Header) */
    private static final byte S7_RW[] = { // 31-35 bytes
            (byte) 0x03, (byte) 0x00, (byte) 0x00,

            (byte) 0x1f, // Telegram length (Data Size + 31 or 35)
            (byte) 0x02, (byte) 0xf0, (byte) 0x80, // COTP (see above for info)
            (byte) 0x32, // S7 Protocol ID
            (byte) 0x01, // Job Type
            (byte) 0x00, (byte) 0x00, // Redundancy identification
            (byte) 0x05, (byte) 0x00, // PDU Reference
            (byte) 0x00, (byte) 0x0e, // Parameters length
            (byte) 0x00, (byte) 0x00, // Data length = Size(bytes) + 4
            (byte) 0x04, // Function 4 Read Var, 5 Write Var
            (byte) 0x01, // Items count
            (byte) 0x12, // Var spec.
            (byte) 0x0a, // length of remaining bytes
            (byte) 0x10, // Syntax ID
            DataType.S7WLByte.getValue(), // Transport Size
            (byte) 0x00, (byte) 0x00, // Num Elements
            (byte) 0x00, (byte) 0x00, // DB Number (if any, else 0)
            (byte) 0x84, // area Type
            (byte) 0x00, (byte) 0x00, (byte) 0x00, // area offset
            // WR area
            (byte) 0x00, // Reserved
            (byte) 0x04, // Transport size
            (byte) 0x00, (byte) 0x00, // Data length * 8 (if not timer or counter)
    };

    /** get Date/Time command */
    private static final byte S7_SET_DT[] = { (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x27, (byte) 0x02, (byte) 0xf0, (byte) 0x80, (byte) 0x32,
            (byte) 0x07, (byte) 0x00, (byte) 0x00, (byte) 0x89, (byte) 0x03, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x0e, (byte) 0x00, (byte) 0x01,
            (byte) 0x12, (byte) 0x04, (byte) 0x11, (byte) 0x47, (byte) 0x02, (byte) 0x00, (byte) 0xff, (byte) 0x09, (byte) 0x00, (byte) 0x0a, (byte) 0x00,

            (byte) 0x19, // Hi part of Year
            (byte) 0x13, // Lo part of Year
            (byte) 0x12, // Month
            (byte) 0x06, // Day
            (byte) 0x17, // Hour
            (byte) 0x37, // Min
            (byte) 0x13, // Sec
            (byte) 0x00, (byte) 0x01 // ms + Day of week
    };

    /** S7 Set Session Password */
    private static final byte S7_SET_PWD[] = { (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x25, (byte) 0x02, (byte) 0xf0, (byte) 0x80, (byte) 0x32,
            (byte) 0x07, (byte) 0x00, (byte) 0x00, (byte) 0x27, (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x0c, (byte) 0x00, (byte) 0x01,
            (byte) 0x12, (byte) 0x04, (byte) 0x11, (byte) 0x45, (byte) 0x01, (byte) 0x00, (byte) 0xff, (byte) 0x09, (byte) 0x00, (byte) 0x08,
            // 8 Char Encoded Password
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };

    /** S7 STOP request */
    private static final byte S7_STOP[] = { (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x21, (byte) 0x02, (byte) 0xf0, (byte) 0x80, (byte) 0x32, (byte) 0x01,
            (byte) 0x00, (byte) 0x00, (byte) 0x0e, (byte) 0x00, (byte) 0x00, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x29, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x09, (byte) 0x50, (byte) 0x5f, (byte) 0x50, (byte) 0x52, (byte) 0x4f, (byte) 0x47, (byte) 0x52,
            (byte) 0x41, (byte) 0x4d };
    /** SZL First telegram request */
    private static final byte S7_SZL_FIRST[] = { (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x21, (byte) 0x02, (byte) 0xf0, (byte) 0x80, (byte) 0x32,
            (byte) 0x07, (byte) 0x00, (byte) 0x00, (byte) 0x05, (byte) 0x00, // Sequence out
            (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x01, (byte) 0x12, (byte) 0x04, (byte) 0x11, (byte) 0x44, (byte) 0x01,
            (byte) 0x00, (byte) 0xff, (byte) 0x09, (byte) 0x00, (byte) 0x04, (byte) 0x00, (byte) 0x00, // ID
                                                                                                       // (29)
            (byte) 0x00, (byte) 0x00 // Index (31)
    };
    /** SZL Next telegram request */
    private static final byte S7_SZL_NEXT[] = { (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x21, (byte) 0x02, (byte) 0xf0, (byte) 0x80, (byte) 0x32,
            (byte) 0x07, (byte) 0x00, (byte) 0x00, (byte) 0x06, (byte) 0x00, (byte) 0x00, (byte) 0x0c, (byte) 0x00, (byte) 0x04, (byte) 0x00, (byte) 0x01,
            (byte) 0x12, (byte) 0x08, (byte) 0x12, (byte) 0x44, (byte) 0x01, (byte) 0x01, // Sequence
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x0a, (byte) 0x00, (byte) 0x00, (byte) 0x00 };

    // S7 Variable MultiRead Header
    private static final byte[] S7_MRD_HEADER = { 0x03, 0x00, 0x00, 0x1f, // Telegram length
            0x02, (byte) 0xf0, (byte) 0x80, // COTP (see above for info)
            0x32, // S7 Protocol ID
            0x01, // Job Type
            0x00, 0x00, // Redundancy identification
            0x05, 0x00, // PDU Reference
            0x00, 0x0e, // Parameters length
            0x00, 0x00, // Data length = Size(bytes) + 4
            0x04, // Function 4 Read Var, 5 Write Var
            0x01 // Items count (idx 18)
    };

    // S7 Variable MultiRead Item
    private static final byte[] S7_MRD_ITEM = { 0x12, // Var spec.
            0x0a, // length of remaining bytes
            0x10, // Syntax ID
            DataType.S7WLByte.getValue(), // Transport Size idx=3
            0x00, 0x00, // Num Elements
            0x00, 0x00, // DB Number (if any, else 0)
            (byte) 0x84, // area Type
            0x00, 0x00, 0x00 // area offset
    };

    // S7 Variable MultiWrite Header
    private static final byte[] S7_MWR_HEADER = { 0x03, 0x00, 0x00, 0x1f, // Telegram length
            0x02, (byte) 0xf0, (byte) 0x80, // COTP (see above for info)
            0x32, // S7 Protocol ID
            0x01, // Job Type
            0x00, 0x00, // Redundancy identification
            0x05, 0x00, // PDU Reference
            0x00, 0x0e, // Parameters length (idx 13)
            0x00, 0x00, // Data length = Size(bytes) + 4 (idx 15)
            0x05, // Function 5 Write Var
            0x01 // Items count (idx 18)
    };

    // S7 Variable MultiWrite Item (Param)
    private static final byte[] S7_MWR_PARAM = { 0x12, // Var spec.
            0x0a, // length of remaining bytes
            0x10, // Syntax ID
            DataType.S7WLByte.getValue(), // Transport Size idx=3
            0x00, 0x00, // Num Elements
            0x00, 0x00, // DB Number (if any, else 0)
            (byte) 0x84, // area Type
            0x00, 0x00, 0x00, // area offset
    };

    private int pduLength = 0;


    public boolean connected = false;
    private ConnectionType connType = ConnectionType.PG;

    private Socket tcpSocket;
    private DataInputStream inStream = null;
    private DataOutputStream outStream = null;

    private String ipAddress;

    public int lastError = 0;

    private byte lastPDUType;
    private byte localTSAP_HI;
    private byte localTSAP_LO;
    private byte remoteTSAP_HI;
    private byte remoteTSAP_LO;

    private final byte[] pdu = new byte[2048];

    public int recvTimeout = 2000;

    public S7Client() {
        Arrays.fill(buffer, (byte) 0);
    }

    public int clearSessionPassword() {
        lastError = 0;
        if (sendPacket(S7_CLR_PWD)) {
            int length = recvIsoPacket();
            if (length > 30) {
                if (S7.getWordAt(pdu, 27) != 0) {
                    lastError = S7_FUNCTION_ERROR;
                }
            } else {
                lastError = ISO_INVALID_PDU;
            }
        }
        return lastError;
    }

    public int connect() {
        logger.debug("try to connect to {}", ipAddress);
        lastError = 0;
        if (!connected) {
            // First stage : TCP Connection
            if (openTcpConnect()) {
                // Second stage : ISOTCP (ISO 8073) Connection
                if (openIsoConnect()) {
                    // Third stage : S7 PDU negotiation
                    updateNegotiatePduLength();
                }
            }
        }
        connected = (lastError == 0);

        // In case the connection is not completely established (TCP connection + ISO connection +
        // PDU negotiation)
        // we close the socket and its IO streams to revert the object back to pre-Connect() state
        if (!connected) {
            if (tcpSocket != null) {
                try {
                    tcpSocket.close();
                } catch (IOException ex) {
                }
            }
            if (inStream != null) {
                try {
                    inStream.close();
                } catch (IOException ex) {
                }
            }
            if (outStream != null) {
                try {
                    outStream.close();
                } catch (IOException ex) {
                }
            }
            pduLength = 0;
        }

        return lastError;
    }

    public int connect(String address, int rack, int slot) {
        int remoteTSAP = (connType.getValue() << 8) + (rack * 0x20) + slot;
        setConnectionParams(address, 0x0100, remoteTSAP);
        return connect();
    }


    public int dbGet(int db, byte[] buffer) {
        S7BlockInfo block = getAgBlockInfo(S7.Block_DB, db);
        // Query the DB length
        if (block != null) {
            int sizeToRead = block.mc7Size;
            // Checks the room
            if (sizeToRead <= buffer.length) {
                if (readArea(AreaType.S7AreaDB, db, 0, sizeToRead, DataType.S7WLByte, buffer) == 0) {
                    return sizeToRead;
                }
            } else {
                lastError = S7_BUFFER_TOO_SMALL;
            }
        }
        return -1;
    }

    public void disconnect() {
        if (connected) {
            try {
                outStream.close();
                inStream.close();
                tcpSocket.close();
                pduLength = 0;
            } catch (IOException ex) {
            }
            connected = false;
        }
    }

    public S7BlockInfo getAgBlockInfo(int blockType, int blockNumber) {
        lastError = 0;
        // Block Type
        S7_BI[30] = (byte) blockType;
        // Block Number
        S7_BI[31] = (byte) ((blockNumber / 10000) + 0x30);
        blockNumber = blockNumber % 10000;
        S7_BI[32] = (byte) ((blockNumber / 1000) + 0x30);
        blockNumber = blockNumber % 1000;
        S7_BI[33] = (byte) ((blockNumber / 100) + 0x30);
        blockNumber = blockNumber % 100;
        S7_BI[34] = (byte) ((blockNumber / 10) + 0x30);
        blockNumber = blockNumber % 10;
        S7_BI[35] = (byte) ((blockNumber) + 0x30);

        if (sendPacket(S7_BI)) {
            int length = recvIsoPacket();
            if (length > 32) {
                if ((S7.getWordAt(pdu, 27) == 0) && (pdu[29] == (byte) 0xff)) {
                    return S7BlockInfo.of(pdu, 42);
                }
                lastError = S7_FUNCTION_ERROR;

            } else {
                lastError = ISO_INVALID_PDU;
            }
        }
        return null;
    }

    public S7CpInfo getCpInfo() {
        S7Szl szl = readSzl(0x0131, 0x0001, 1024);
        if (szl != null) {
            return S7CpInfo.of(szl.data, 0);
        }
        return null;
    }

    public S7CpuInfo getCpuInfo() {
        S7Szl szl = readSzl(0x001C, 0x0000, 1024);
        if (szl != null) {
            return S7CpuInfo.of(szl.data, 0);
        }
        return null;
    }

    public S7OrderCode getOrderCode() {
        S7Szl szl = readSzl(0x0011, 0x0000, 1024);
        if (szl != null) {
            return S7OrderCode.of(szl.data, 0, szl.dataSize);
        }
        return null;
    }

    public LocalDateTime getPlcDateTime() {
        lastError = 0;
        if (sendPacket(S7_GET_DT)) {
            int length = recvIsoPacket();
            if (length > 30) {
                if ((S7.getWordAt(pdu, 27) == 0) && (pdu[29] == (byte) 0xff)) {
                    return S7.getlDateTimeAt(pdu, 34);
                }
                lastError = S7_FUNCTION_ERROR;
            } else {
                lastError = ISO_INVALID_PDU;
            }
        }
        return null;
    }

    public PlcCpuStatus getPlcStatus() {

        lastError = 0;
        if (sendPacket(S7_GET_STAT)) {
            int length = recvIsoPacket();
            // the minimum expected
            if (length > 30) {
                if (S7.getWordAt(pdu, 27) == 0) {

                    return PlcCpuStatus.valueOf(pdu[44]);

                } else {
                    lastError = S7_FUNCTION_ERROR;
                }
            } else {
                lastError = ISO_INVALID_PDU;
            }
        }
        return PlcCpuStatus.UNKNOWN;
    }

    public S7Protection getProtection() {
        S7Szl szl = readSzl(0x0232, 0x0004, 256);
        if (szl != null) {
            return S7Protection.of(szl.data);
        }
        return null;
    }

    private boolean openIsoConnect() {
        ISO_CR[16] = localTSAP_HI;
        ISO_CR[17] = localTSAP_LO;
        ISO_CR[20] = remoteTSAP_HI;
        ISO_CR[21] = remoteTSAP_LO;
        // Sends the connection request telegram
        if (sendPacket(ISO_CR)) {
            // gets the reply (if any)
            int length = recvIsoPacket();
            if (lastError == 0) {
                if (length == 22) {
                    if (lastPDUType != (byte) 0xD0) {
                        lastError = ISO_CONNECTION_FAILED;
                    }
                } else {
                    lastError = ISO_INVALID_PDU;
                }
            }
        }
        return lastError == 0;
    }

    private boolean updateNegotiatePduLength() {
        // Set PDU Size Requested
        S7.setWordAt(S7_PN, 23, DEFAULT_PDU_SIZE_REQUESTED);
        // Sends the connection request telegram
        if (sendPacket(S7_PN)) {
            int length = recvIsoPacket();
            if (lastError == 0) {
                // check S7 Error: 20 = size of Negotiate Answer
                if ((length == 27) && (pdu[17] == 0) && (pdu[18] == 0)) {
                    // get PDU Size Negotiated
                    pduLength = S7.getWordAt(pdu, 25);
                    logger.debug("PDU negotiated length: {} bytes", pduLength);
                    if (pduLength > 0) {
                        return true;
                    }
                    lastError = ISO_NEGOTIATING_PDU;
                } else {
                    lastError = ISO_NEGOTIATING_PDU;
                }
            }
        }
        return false;
    }

    public int getPduLength() {
        return pduLength;
    }

    public int setPlcColdStart() {
        lastError = 0;
        if (sendPacket(S7_COLD_START)) {
            int length = recvIsoPacket();
            if (length > 18) {
                if (S7.getWordAt(pdu, 17) != 0) {
                    lastError = S7_FUNCTION_ERROR;
                }
            } else {
                lastError = ISO_INVALID_PDU;
            }
        }
        return lastError;
    }

    public int setPlcHotStart() {
        lastError = 0;
        if (sendPacket(S7_HOT_START)) {
            int length = recvIsoPacket();
            if (length > 18) {
                if (S7.getWordAt(pdu, 17) != 0) {
                    lastError = S7_FUNCTION_ERROR;
                }
            } else {
                lastError = ISO_INVALID_PDU;
            }
        }
        return lastError;
    }

    public int setPlcStop() {
        lastError = 0;
        if (sendPacket(S7_STOP)) {
            int length = recvIsoPacket();
            if (length > 18) {
                if (S7.getWordAt(pdu, 17) != 0) {
                    lastError = S7_FUNCTION_ERROR;
                }
            } else {
                lastError = ISO_INVALID_PDU;
            }
        }
        return lastError;
    }

    public int readMultiVars(S7DataItem[] items, int itemsCount) {
        int offset;
        int length;
        int itemSize;
        byte[] s7Item = new byte[12];
        byte[] s7ItemRead = new byte[1024];

        lastError = 0;

        // Checks items
        if (itemsCount > MAX_VARS) {
            return ERR_CLI_TOO_MANY_ITEMS;
        }

        // Fills Header
        System.arraycopy(S7_MRD_HEADER, 0, pdu, 0, S7_MRD_HEADER.length);
        S7.setWordAt(pdu, 13, (itemsCount * s7Item.length + 2));
        pdu[18] = (byte) itemsCount;
        offset = 19;
        for (int c = 0; c < itemsCount; c++) {
            System.arraycopy(S7_MRD_ITEM, 0, s7Item, 0, s7Item.length);
            s7Item[3] = items[c].type.getValue();
            S7.setWordAt(s7Item, 4, items[c].amount);
            if (items[c].area == AreaType.S7AreaDB) {
                S7.setWordAt(s7Item, 6, items[c].db);
            }
            s7Item[8] = items[c].area.getValue();

            // address into the PLC
            int address = items[c].start;
            s7Item[11] = (byte) (address & 0xff);
            address = address >> 8;
            s7Item[10] = (byte) (address & 0xff);
            address = address >> 8;
            s7Item[9] = (byte) (address & 0xff);

            System.arraycopy(s7Item, 0, pdu, offset, s7Item.length);
            offset += s7Item.length;
        }

        if (offset > pduLength) {
            return ERR_CLI_SIZE_OVER_PDU;
        }

        S7.setWordAt(pdu, 2, offset); // Whole size

        if (!sendPacket(pdu, offset)) {
            return lastError;
        }
        // get Answer
        length = recvIsoPacket();
        if (lastError != 0) {
            return lastError;
        }
        // Check ISO length
        if (length < 22) {
            return lastError = ERR_ISO_INVALID_PDU; // PDU too Small
        }
        // Check Global Operation Result
        if ((lastError = ReturnCode.getCpuError(S7.getWordAt(pdu, 17))) != 0) {
            return lastError;
        }
        // get true itemsCount
        int itemsRead = S7.getByteAt(pdu, 20);
        if ((itemsRead != itemsCount) || (itemsRead > MAX_VARS)) {
            return lastError = ERR_CLI_INVALID_PLC_ANSWER;
        }
        // get Data
        offset = 21;
        for (int c = 0; c < itemsCount; c++) {
            // get the Item
            System.arraycopy(pdu, offset, s7ItemRead, 0, length - offset);
            if (s7ItemRead[0] == (byte) 0xff) {
                itemSize = S7.getWordAt(s7ItemRead, 2);
                if ((s7ItemRead[1] != TS_RESOCTET) && (s7ItemRead[1] != TS_RESREAL) && (s7ItemRead[1] != TS_RESBIT)) {
                    itemSize = itemSize >> 3;
                }
                System.err.println(items[c].data.length + " " + itemSize);
                System.arraycopy(s7ItemRead, 4, items[c].data, 0, Math.min(items[c].data.length, itemSize));
                // Marshal.Copy(s7ItemRead, 4, items[c].pData, itemSize);
                items[c].result = 0;
                if (itemSize % 2 != 0) {
                    itemSize++; // Odd size are rounded
                }
                offset = offset + 4 + itemSize;
            } else {
                items[c].result = ReturnCode.getCpuError(s7ItemRead[0]);
                offset += 4; // Skip the Item header
            }
        }

        return lastError;
    }

    public int writeMultiVars(S7DataItem[] items, int itemsCount) {
        int offset;
        int parLength;
        int dataLength;
        int itemDataSize;
        byte[] s7ParItem = new byte[S7_MWR_PARAM.length];
        byte[] dataItem = new byte[1024];

        lastError = 0;

        // Checks items
        if (itemsCount > MAX_VARS) {
            return ERR_CLI_TOO_MANY_ITEMS;
        }
        // Fills Header
        System.arraycopy(S7_MWR_HEADER, 0, pdu, 0, S7_MWR_HEADER.length);
        parLength = itemsCount * S7_MWR_PARAM.length + 2;
        S7.setWordAt(pdu, 13, parLength);
        pdu[18] = (byte) itemsCount;
        // Fills Params
        offset = S7_MWR_HEADER.length;
        for (int c = 0; c < itemsCount; c++) {
            System.arraycopy(S7_MWR_PARAM, 0, s7ParItem, 0, S7_MWR_PARAM.length);
            s7ParItem[3] = items[c].type.getValue();
            s7ParItem[8] = items[c].area.getValue();
            S7.setWordAt(s7ParItem, 4, items[c].amount);
            S7.setWordAt(s7ParItem, 6, items[c].db);
            // address into the PLC
            int address = items[c].start;
            s7ParItem[11] = (byte) (address & 0xff);
            address = address >> 8;
            s7ParItem[10] = (byte) (address & 0xff);
            address = address >> 8;
            s7ParItem[9] = (byte) (address & 0xff);
            System.arraycopy(s7ParItem, 0, pdu, offset, s7ParItem.length);
            offset += S7_MWR_PARAM.length;
        }
        // Fills Data
        dataLength = 0;
        for (int c = 0; c < itemsCount; c++) {
            dataItem[0] = 0x00;
            switch (items[c].type) {
                case S7WLBit:
                    dataItem[1] = TS_RESBIT;
                    break;
                case S7WLCounter:
                case S7WLTimer:
                    dataItem[1] = TS_RESOCTET;
                    break;
                default:
                    dataItem[1] = TS_RESBYTE; // byte/word/dword etc.
                    break;
            };
            if ((items[c].type == DataType.S7WLTimer) || (items[c].type == DataType.S7WLCounter)) {
                itemDataSize = items[c].amount * 2;
            } else {
                itemDataSize = items[c].amount;
            }

            if ((dataItem[1] != TS_RESOCTET) && (dataItem[1] != TS_RESBIT)) {
                S7.setWordAt(dataItem, 2, (itemDataSize * 8));
            } else {
                S7.setWordAt(dataItem, 2, itemDataSize);
            }

            System.arraycopy(items[c].data, 0, dataItem, 4, itemDataSize);
            // Marshal.Copy(items[c].pData, S7DataItem, 4, itemDataSize);
            if (itemDataSize % 2 != 0) {
                dataItem[itemDataSize + 4] = 0x00;
                itemDataSize++;
            }
            System.arraycopy(dataItem, 0, pdu, offset, itemDataSize + 4);
            offset = offset + itemDataSize + 4;
            dataLength = dataLength + itemDataSize + 4;
        }

        // Checks the size
        if (offset > pduLength) {
            return ERR_CLI_SIZE_OVER_PDU;
        }

        S7.setWordAt(pdu, 2, offset); // Whole size
        S7.setWordAt(pdu, 15, dataLength); // Whole size
        sendPacket(pdu, offset);

        recvIsoPacket();
        if (lastError == 0) {
            // Check Global Operation Result
            lastError = ReturnCode.getCpuError(S7.getWordAt(pdu, 17));
            if (lastError != 0) {
                return lastError;
            }
            // get true itemsCount
            int ItemsWritten = S7.getByteAt(pdu, 20);
            if ((ItemsWritten != itemsCount) || (ItemsWritten > MAX_VARS)) {
                lastError = ERR_CLI_INVALID_PLC_ANSWER;
                return lastError;
            }

            for (int c = 0; c < itemsCount; c++) {
                if (pdu[c + 21] == 0xff) {
                    items[c].result = 0;
                } else {
                    items[c].result = ReturnCode.getCpuError(pdu[c + 21]);
                }
            }

        }
        return lastError;
    }

    @Override
    public int readArea(final AreaType area, final int db, final int start, final int amount, final DataType type, final byte[] buffer) {
        int address;
        int numElements, maxElements, totElements;
        int SizeRequested;
        int length;
        int offset = 0;
        int wordSize = 1;
        int _start = start;
        int _amount = amount;

        lastError = 0;

        DataType _type;
        switch (area) {
            case S7AreaCT:
                _type = DataType.S7WLCounter;
                break;
            case S7AreaTM:
                _type = DataType.S7WLTimer;
                break;
            default:
                _type = type;
                break;
        }

        // Calc Word size
        wordSize = DataType.getByteLength(_type);
        if (wordSize == 0) {
            return ERR_CLI_INVALID_WORD_LEN;
        }

        if (_type == DataType.S7WLBit) {
            _amount = 1; // Only 1 bit can be transferred at time
        } else {
            if ((_type != DataType.S7WLCounter) && (_type != DataType.S7WLTimer)) {
                _amount = _amount * wordSize;
                wordSize = 1;
                _type = DataType.S7WLByte;
            }
        }

        maxElements = (pduLength - 18) / wordSize; // 18 = Reply telegram header
        totElements = _amount;

        while ((totElements > 0) && (lastError == 0)) {
            numElements = totElements;
            if (numElements > maxElements) {
                numElements = maxElements;
            }

            SizeRequested = numElements * wordSize;

            // Setup the telegram
            System.arraycopy(S7_RW, 0, pdu, 0, SIZE_RD);
            // Set DB Number
            pdu[27] = area.getValue();
            // Set area
            if (area == AreaType.S7AreaDB) {
                S7.setWordAt(pdu, 25, db);
            }

            // Adjusts start and word length
            if ((_type == DataType.S7WLBit) || (_type == DataType.S7WLCounter) || (_type == DataType.S7WLTimer)) {
                address = _start;
                pdu[22] = _type.getValue();
            } else {
                address = _start << 3;
            }

            // Num elements
            S7.setWordAt(pdu, 23, numElements);

            // address into the PLC (only 3 bytes)
            pdu[30] = (byte) (address & 0xff);
            address = address >> 8;
            pdu[29] = (byte) (address & 0xff);
            address = address >> 8;
            pdu[28] = (byte) (address & 0xff);

            sendPacket(pdu, SIZE_RD);
            if (lastError == 0) {
                length = recvIsoPacket();
                if (lastError == 0) {
                    if (length < 25) {
                        lastError = ERR_ISO_INVALID_DATA_SIZE;
                    } else {
                        if (pdu[21] != (byte) 0xff) {
                            lastError = ReturnCode.getCpuError(pdu[21]);
                        } else {
                            System.arraycopy(pdu, 25, buffer, offset, SizeRequested);
                            offset += SizeRequested;
                        }
                    }
                }
            }
            totElements -= numElements;
            _start += numElements * wordSize;
        }
        return lastError;
    }

    @Deprecated
    public int readArea(AreaType area, int db, int start, int amount, byte[] Data) {
        int address;
        int numElements;
        int maxElements;
        int totElements;
        int SizeRequested;
        int length;
        int offset = 0;
        int wordSize = 1;

        lastError = 0;

        // If we are addressing Timers or counters the element size is 2
        if ((area == AreaType.S7AreaCT) || (area == AreaType.S7AreaTM)) {
            wordSize = 2;
        }

        maxElements = (pduLength - 18) / wordSize; // 18 = Reply telegram header
        totElements = amount;

        while ((totElements > 0) && (lastError == 0)) {
            numElements = totElements;
            if (numElements > maxElements) {
                numElements = maxElements;
            }

            SizeRequested = numElements * wordSize;

            // Setup the telegram
            System.arraycopy(S7_RW, 0, pdu, 0, SIZE_RD);
            // Set DB Number
            pdu[27] = area.getValue();
            // Set area
            if (area == AreaType.S7AreaDB) {
                S7.setWordAt(pdu, 25, db);
            }

            // Adjusts start and word length
            if ((area == AreaType.S7AreaCT) || (area == AreaType.S7AreaTM)) {
                address = start;
                if (area == AreaType.S7AreaCT) {
                    pdu[22] = DataType.S7WLCounter.getValue();
                } else {
                    pdu[22] = DataType.S7WLTimer.getValue();
                }
            } else {
                address = start << 3;
            }

            // Num elements
            S7.setWordAt(pdu, 23, numElements);

            // address into the PLC (only 3 bytes)
            pdu[30] = (byte) (address & 0xff);
            address = address >> 8;
            pdu[29] = (byte) (address & 0xff);
            address = address >> 8;
            pdu[28] = (byte) (address & 0xff);

            sendPacket(pdu, SIZE_RD);
            if (lastError == 0) {
                length = recvIsoPacket();
                if (lastError == 0) {
                    if (length >= 25) {
                        if ((length - 25 == SizeRequested) && (pdu[21] == (byte) 0xff)) {
                            System.arraycopy(pdu, 25, Data, offset, SizeRequested);
                            offset += SizeRequested;
                        } else {
                            lastError = S7_DATA_READ;
                        }
                    } else {
                        lastError = ISO_INVALID_PDU;
                    }
                }
            }

            totElements -= numElements;
            start += numElements * wordSize;
        }
        return lastError;
    }

    public S7Szl readSzl(int id, int index, int bufferSize) {
        final S7Szl szl = new S7Szl(bufferSize);
        int length;
        int dataSZL;
        int offset = 0;
        boolean done = false;
        boolean first = true;
        byte seq_in = 0x00;
        int seq_out = 0x0000;

        lastError = 0;
        szl.dataSize = 0;
        do {
            if (first) {
                S7.setWordAt(S7_SZL_FIRST, 11, ++seq_out);
                S7.setWordAt(S7_SZL_FIRST, 29, id);
                S7.setWordAt(S7_SZL_FIRST, 31, index);
                sendPacket(S7_SZL_FIRST);
            } else {
                S7.setWordAt(S7_SZL_NEXT, 11, ++seq_out);
                pdu[24] = seq_in;
                sendPacket(S7_SZL_NEXT);
            }
            if (lastError != 0) {
                return null;
            }

            length = recvIsoPacket();
            if (lastError == 0) {
                if (first) {
                    if (length > 32) // the minimum expected
                    {
                        if ((S7.getWordAt(pdu, 27) == 0) && (pdu[29] == (byte) 0xff)) {
                            // gets amount of this slice
                            dataSZL = S7.getWordAt(pdu, 31) - 8; // Skips extra params (ID, Index
                                                                 // ...)
                            done = pdu[26] == 0x00;
                            seq_in = pdu[24]; // Slice sequence

                            szl.lenthdr = S7.getWordAt(pdu, 37);
                            szl.n_dr = S7.getWordAt(pdu, 39);
                            szl.copy(pdu, 41, offset, dataSZL);
                            offset += dataSZL;
                            szl.dataSize += dataSZL;
                        } else {
                            lastError = S7_FUNCTION_ERROR;
                        }
                    } else {
                        lastError = ISO_INVALID_PDU;
                    }
                } else {
                    if (length > 32) // the minimum expected
                    {
                        if ((S7.getWordAt(pdu, 27) == 0) && (pdu[29] == (byte) 0xff)) {
                            // gets amount of this slice
                            dataSZL = S7.getWordAt(pdu, 31);
                            done = pdu[26] == 0x00;
                            seq_in = pdu[24]; // Slice sequence
                            szl.copy(pdu, 37, offset, dataSZL);
                            offset += dataSZL;
                            szl.dataSize += dataSZL;
                        } else {
                            lastError = S7_FUNCTION_ERROR;
                        }
                    } else {
                        lastError = ISO_INVALID_PDU;
                    }
                }
            }
            first = false;
        } while (!done && (lastError == 0));

        return lastError == 0 ? szl : null;
    }

    private int recvIsoPacket() {
        boolean done = false;
        int size = 0;
        while ((lastError == 0) && !done) {
            // get TPKT (4 bytes)
            recvPacket(pdu, 0, 4);
            if (lastError == 0) {
                size = S7.getWordAt(pdu, 2);
                // Check 0 bytes Data Packet (only TPKT+COTP = 7 bytes)
                if (size == ISO_HEADER_SIZE) {
                    recvPacket(pdu, 4, 3); // Skip remaining 3 bytes and Done is still false
                } else {
                    if ((size > MAX_PDU_SIZE) || (size < MinPduSize)) {
                        lastError = ISO_INVALID_PDU;
                    } else {
                        done = true; // a valid length !=7 && >16 && <247
                    }
                }
            }
        }
        if (lastError == 0) {
            // Skip remaining 3 COTP bytes
            recvPacket(pdu, 4, 3);
            // Stores PDU Type, we need it
            lastPDUType = pdu[5];
            // Receives the S7 Payload
            recvPacket(pdu, 7, size - ISO_HEADER_SIZE);
        }

        return lastError == 0 ? size : 0;
    }

    private int recvPacket(byte[] buffer, int start, int size) {
        int bytesRead = 0;
        lastError = waitForData(size, recvTimeout);
        if (lastError == 0) {
            try {
                bytesRead = inStream.read(buffer, start, size);
            } catch (IOException ex) {
                lastError = TCP_DATA_RECV;
            }
            if (bytesRead == 0) {
                lastError = TCP_CONNECTION_RESET;
            }
        }
        return lastError;
    }

    private boolean sendPacket(byte[] buffer) {
        return sendPacket(buffer, buffer.length);
    }

    private boolean sendPacket(byte[] buffer, int Len) {
        lastError = 0;
        try {
            outStream.write(buffer, 0, Len);
            outStream.flush();
            return true;
        } catch (IOException ex) {
            lastError = TCP_DATA_SEND;
        }
        return false;
    }

    public void setConnectionParams(String address, int localTSAP, int remoteTSAP) {
        int locTSAP = localTSAP & 0x0000FFFF;
        int remTSAP = remoteTSAP & 0x0000FFFF;
        ipAddress = Objects.requireNonNull(address);
        localTSAP_HI = (byte) (locTSAP >> 8);
        localTSAP_LO = (byte) (locTSAP & 0x00FF);
        remoteTSAP_HI = (byte) (remTSAP >> 8);
        remoteTSAP_LO = (byte) (remTSAP & 0x00FF);
    }

    public void setConnectionType(ConnectionType type) {
        connType = Objects.requireNonNull(type);
    }

    public int setPlcDateTime(LocalDateTime dateTime) {
        int length;

        lastError = 0;
        S7.setDateTimeAt(S7_SET_DT, 31, dateTime);
        if (sendPacket(S7_SET_DT)) {
            length = recvIsoPacket();
            // the minimum expected
            if (length > 30) {
                if (S7.getWordAt(pdu, 27) != 0) {
                    lastError = S7_FUNCTION_ERROR;
                }
            } else {
                lastError = ISO_INVALID_PDU;
            }
        }

        return lastError;
    }

    public int setPlcSystemDateTime() {
        return setPlcDateTime(LocalDateTime.now());
    }

    public int setSessionPassword(String password) {
        byte[] pwd = { 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20 };
        int length;

        lastError = 0;
        // Adjusts the Password length to 8
        if (password.length() > 8) {
            password = password.substring(0, 8);
        } else {
            while (password.length() < 8) {
                password = password + " ";
            }
        }

        try {
            pwd = password.getBytes("UTF-8");
        } catch (UnsupportedEncodingException ex) {
            lastError = S7_INVALID_PARAMS;
        }
        if (lastError == 0) {
            // Encodes the password
            pwd[0] = (byte) (pwd[0] ^ 0x55);
            pwd[1] = (byte) (pwd[1] ^ 0x55);
            for (int c = 2; c < 8; c++) {
                pwd[c] = (byte) (pwd[c] ^ 0x55 ^ pwd[c - 2]);
            }
            System.arraycopy(pwd, 0, S7_SET_PWD, 29, 8);
            if (sendPacket(S7_SET_PWD)) {
                length = recvIsoPacket();
                if (length > 32) // the minimum expected
                {
                    if (S7.getWordAt(pdu, 27) != 0) {
                        lastError = S7_FUNCTION_ERROR;
                    }
                } else {
                    lastError = ISO_INVALID_PDU;
                }
            }
        }
        return lastError;
    }

    private boolean openTcpConnect() {
        lastError = 0;
        try {
            tcpSocket = new Socket();
            tcpSocket.connect(new InetSocketAddress(ipAddress, ISO_TCP), 5000);
            tcpSocket.setTcpNoDelay(true);
            inStream = new DataInputStream(tcpSocket.getInputStream());
            outStream = new DataOutputStream(tcpSocket.getOutputStream());
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            lastError = TCP_CONNECTION_FAILED;
        }
        return lastError == 0;
    }

    private int waitForData(int Size, int Timeout) {
        int cnt = 0;
        lastError = 0;
        int SizeAvail;
        boolean Expired = false;
        try {
            SizeAvail = inStream.available();
            while ((SizeAvail < Size) && (!Expired) && (lastError == 0)) {

                cnt++;
                try {
                    Thread.sleep(1);
                } catch (InterruptedException ex) {
                    lastError = TCP_DATA_RECV_TOUT;
                }
                SizeAvail = inStream.available();
                Expired = cnt > Timeout;
                // If timeout we clean the buffer
                if (Expired && (SizeAvail > 0) && (lastError == 0)) {
                    inStream.read(pdu, 0, SizeAvail);
                }
            }
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            lastError = TCP_DATA_RECV_TOUT;
        }
        if (cnt >= Timeout) {
            lastError = TCP_DATA_RECV_TOUT;
        }
        return lastError;
    }

    @Override
    public int writeArea(final AreaType area, final int db, final int start, final int amount, final DataType type, final byte[] buffer) {
        int address;
        int numElements, maxElements, totElements;
        int dataSize, isoSize, length;
        int offset = 0;
        int _start = start;
        int _amount = amount;

        lastError = 0;

        DataType _type;
        switch (area) {
            case S7AreaCT:
                _type = DataType.S7WLCounter;
                break;
            case S7AreaTM:
                _type = DataType.S7WLTimer;
                break;
            default:
                _type = type;
                break;
        }

        // Calc Word size
        int wordSize = DataType.getByteLength(_type);
        if (wordSize == 0) {
            return ERR_CLI_INVALID_WORD_LEN;
        }

        if (_type == DataType.S7WLBit) {
            _amount = 1;
        } else {
            if ((_type != DataType.S7WLCounter) && (_type != DataType.S7WLTimer)) {
                _amount = _amount * wordSize;
                wordSize = 1;
                _type = DataType.S7WLByte;
            }
        }

        maxElements = (pduLength - 35) / wordSize; // 35 = Reply telegram header
        totElements = _amount;
        while ((totElements > 0) && (lastError == 0)) {
            numElements = totElements;
            if (numElements > maxElements) {
                numElements = maxElements;
            }

            dataSize = numElements * wordSize;
            isoSize = SIZE_WR + dataSize;

            // setup the telegram
            System.arraycopy(S7_RW, 0, pdu, 0, SIZE_WR);
            // Whole telegram Size
            S7.setWordAt(pdu, 2, isoSize);
            // Data length
            length = dataSize + 4;
            S7.setWordAt(pdu, 15, length);
            // Function
            pdu[17] = (byte) 0x05;
            // set DB Number
            pdu[27] = area.getValue();
            if (area == AreaType.S7AreaDB) {
                S7.setWordAt(pdu, 25, db);
            }


            // Adjusts start and word length
            if ((_type == DataType.S7WLBit) || (_type == DataType.S7WLCounter) || (_type == DataType.S7WLTimer)) {
                address = _start;
                length = dataSize;
                pdu[22] = type.getValue();
            } else {
                address = _start << 3;
                length = dataSize << 3;
            }

            // Num elements
            S7.setWordAt(pdu, 23, numElements);
            // address into the PLC
            pdu[30] = (byte) (address & 0xff);
            address = address >> 8;
            pdu[29] = (byte) (address & 0xff);
            address = address >> 8;
            pdu[28] = (byte) (address & 0xff);

            // Transport Size
            switch (type) {
                case S7WLBit:
                    pdu[32] = TS_RESBIT;
                    break;
                case S7WLCounter:
                case S7WLTimer:
                    pdu[32] = TS_RESOCTET;
                    break;
                default:
                    pdu[32] = TS_RESBYTE; // byte/word/dword etc.
                    break;
            };
            // length
            S7.setWordAt(pdu, 33, length);

            // Copies the Data
            System.arraycopy(buffer, offset, pdu, 35, dataSize);

            if (sendPacket(pdu, isoSize)) {
                length = recvIsoPacket();
                if (lastError == 0) {
                    if (length == 22) {
                        if (pdu[21] != (byte) 0xff) {
                            lastError = ReturnCode.getCpuError(pdu[21]);
                        }
                    } else {
                        lastError = ERR_ISO_INVALID_PDU;
                    }
                }
            }
            offset += dataSize;
            totElements -= numElements;
            _start += numElements * wordSize;
        }
        return lastError;
    }

    @Deprecated
    public int writeArea(AreaType area, int db, int start, int amount, byte[] data) {
        int address;
        int numElements;
        int maxElements;
        int totElements;
        int dataSize;
        int isoSize;
        int length;
        int offset = 0;
        int wordSize = 1;

        lastError = 0;

        // If we are addressing Timers or counters the element size is 2
        if ((area == AreaType.S7AreaCT) || (area == AreaType.S7AreaTM)) {
            wordSize = 2;
        }

        maxElements = (pduLength - 35) / wordSize; // 18 = Reply telegram header
        totElements = amount;

        while ((totElements > 0) && (lastError == 0)) {
            numElements = totElements;
            if (numElements > maxElements) {
                numElements = maxElements;
            }

            dataSize = numElements * wordSize;
            isoSize = SIZE_WR + dataSize;

            // setup the telegram
            System.arraycopy(S7_RW, 0, pdu, 0, SIZE_WR);
            // Whole telegram Size
            S7.setWordAt(pdu, 2, isoSize);
            // Data length
            length = dataSize + 4;
            S7.setWordAt(pdu, 15, length);
            // Function
            pdu[17] = (byte) 0x05;
            // set DB Number
            pdu[27] = area.getValue();
            if (area == AreaType.S7AreaDB) {
                S7.setWordAt(pdu, 25, db);
            }

            // Adjusts start and word length
            if ((area == AreaType.S7AreaCT) || (area == AreaType.S7AreaTM)) {
                address = start;
                length = dataSize;
                if (area == AreaType.S7AreaCT) {
                    pdu[22] = DataType.S7WLCounter.getValue();
                } else {
                    pdu[22] = DataType.S7WLTimer.getValue();
                }
            } else {
                address = start << 3;
                length = dataSize << 3;
            }
            // Num elements
            S7.setWordAt(pdu, 23, numElements);
            // address into the PLC
            pdu[30] = (byte) (address & 0xff);
            address = address >> 8;
            pdu[29] = (byte) (address & 0xff);
            address = address >> 8;
            pdu[28] = (byte) (address & 0xff);
            // length
            S7.setWordAt(pdu, 33, length);

            // Copies the Data
            System.arraycopy(data, offset, pdu, 35, dataSize);

            if (sendPacket(pdu, isoSize)) {
                length = recvIsoPacket();
                if (lastError == 0) {
                    if (length == 22) {
                        if ((S7.getWordAt(pdu, 17) != 0) || (pdu[21] != (byte) 0xff)) {
                            lastError = S7_DATA_WRITE;
                        }
                    } else {
                        lastError = ISO_INVALID_PDU;
                    }
                }
            }

            offset += dataSize;
            totElements -= numElements;
            start += numElements * wordSize;
        }
        return lastError;
    }

    public int getIsoExchangeBuffer(byte[] buffer) {
        int size = 0;
        lastError = 0;
        System.arraycopy(TPKT_ISO, 0, pdu, 0, TPKT_ISO.length);
        S7.setWordAt(pdu, 2, (size + TPKT_ISO.length));
        try {
            System.arraycopy(buffer, 0, pdu, TPKT_ISO.length, size);
        } catch (Exception e) {
            return ERR_ISO_INVALID_PDU;
        }
        if (sendPacket(pdu, TPKT_ISO.length + size)) {
            int length = recvIsoPacket();
            if (lastError == 0) {
                System.arraycopy(pdu, TPKT_ISO.length, buffer, 0, length - TPKT_ISO.length);
                size = length - TPKT_ISO.length;
            }
        }
        return lastError == 0 ? size : 0;
    }

    public int getLastError() {
        return lastError;
    }
}
