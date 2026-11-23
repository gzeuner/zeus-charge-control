package de.zeus.power.kostal;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Small Modbus/TCP read-only transport. Each request uses function 03 only. */
final class KostalModbusClient implements KostalModbusTransport {
    private final String host;
    private final int port, unitId, connectTimeoutMs, readTimeoutMs;
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private int transactionId;

    KostalModbusClient(String host, int port, int unitId, int connectTimeoutMs, int readTimeoutMs) {
        this.host = host; this.port = port; this.unitId = unitId;
        this.connectTimeoutMs = connectTimeoutMs; this.readTimeoutMs = readTimeoutMs;
    }

    @Override public synchronized int[] readHoldingRegisters(int address, int count) throws IOException {
        connect();
        int tx = ++transactionId & 0xffff;
        output.writeShort(tx); output.writeShort(0); output.writeShort(6);
        output.writeByte(unitId); output.writeByte(3); output.writeShort(address); output.writeShort(count); output.flush();
        int responseTx = input.readUnsignedShort();
        int protocol = input.readUnsignedShort();
        int length = input.readUnsignedShort();
        if (protocol != 0 || length < 3 || length > 255) throw protocolError("Invalid Modbus/TCP header");
        byte[] pdu = input.readNBytes(length);
        if (pdu.length != length) throw protocolError("Incomplete Modbus/TCP response");
        if (responseTx != tx) throw protocolError("Unexpected Modbus transaction ID");
        int responseUnit = Byte.toUnsignedInt(pdu[0]);
        int function = Byte.toUnsignedInt(pdu[1]);
        if (responseUnit != unitId) throw protocolError("Unexpected Modbus unit ID");
        if (function == 0x83) {
            throw new ModbusExceptionResponse(Byte.toUnsignedInt(pdu[2]));
        }
        int byteCount = Byte.toUnsignedInt(pdu[2]);
        if (function != 3 || byteCount != count * 2 || length != byteCount + 3) throw protocolError("Invalid Modbus register response length");
        int[] values = new int[count];
        for (int i = 0; i < count; i++) values[i] = (Byte.toUnsignedInt(pdu[3 + i * 2]) << 8) | Byte.toUnsignedInt(pdu[4 + i * 2]);
        return values;
    }

    private IOException protocolError(String message) {
        close();
        return new IOException(message);
    }

    private void connect() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) return;
        close();
        Socket next = new Socket();
        next.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        next.setSoTimeout(readTimeoutMs);
        socket = next; input = new DataInputStream(new BufferedInputStream(next.getInputStream()));
        output = new DataOutputStream(new BufferedOutputStream(next.getOutputStream()));
    }

    @Override public synchronized void close() {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        socket = null; input = null; output = null;
    }

    static final class ModbusExceptionResponse extends IOException {
        private final int code;
        ModbusExceptionResponse(int code) { super("Modbus exception response code " + code); this.code = code; }
        int code() { return code; }
    }
}
