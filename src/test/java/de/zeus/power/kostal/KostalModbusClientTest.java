package de.zeus.power.kostal;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class KostalModbusClientTest {
    @Test void readsHoldingRegistersAndKeepsOneTcpConnection() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            var peer = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = server.accept()) {
                    var in = new DataInputStream(socket.getInputStream());
                    var out = new DataOutputStream(socket.getOutputStream());
                    for (int address : new int[]{172, 1066}) {
                        int tx = readRequest(in, address);
                        out.writeShort(tx); out.writeShort(0); out.writeShort(7);
                        out.writeByte(71); out.writeByte(3); out.writeByte(4);
                        out.writeShort(address); out.writeShort(0); out.flush();
                    }
                    return true;
                } catch (IOException ex) { throw new RuntimeException(ex); }
            });
            try (KostalModbusClient client = client(server)) {
                assertArrayEquals(new int[]{172, 0}, client.readHoldingRegisters(172, 2));
                assertArrayEquals(new int[]{1066, 0}, client.readHoldingRegisters(1066, 2));
            }
            assertTrue(peer.get(2, TimeUnit.SECONDS));
        }
    }

    @Test void reportsModbusExceptionCodeAndRejectsWrongTransactionAndUnitIds() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            var peer = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    var in = new DataInputStream(socket.getInputStream());
                    var out = new DataOutputStream(socket.getOutputStream());
                    int tx = readRequest(in, 172);
                    out.writeShort(tx); out.writeShort(0); out.writeShort(3);
                    out.writeByte(71); out.writeByte(0x83); out.writeByte(6); out.flush();
                } catch (IOException ex) { throw new RuntimeException(ex); }
            });
            try (KostalModbusClient client = client(server)) {
                var error = assertThrows(KostalModbusClient.ModbusExceptionResponse.class,
                        () -> client.readHoldingRegisters(172, 2));
                assertEquals(6, error.code());
            }
            peer.get(2, TimeUnit.SECONDS);
        }

        assertInvalidReply((tx, out) -> {
            out.writeShort(tx + 1); out.writeShort(0); out.writeShort(7);
            out.writeByte(71); out.writeByte(3); out.writeByte(4); out.writeInt(0);
        }, "transaction ID");
        assertInvalidReply((tx, out) -> {
            out.writeShort(tx); out.writeShort(0); out.writeShort(7);
            out.writeByte(72); out.writeByte(3); out.writeByte(4); out.writeInt(0);
        }, "unit ID");
    }

    @Test void rejectsIncompleteRegisterPayload() throws Exception {
        assertInvalidReply((tx, out) -> {
            out.writeShort(tx); out.writeShort(0); out.writeShort(7);
            out.writeByte(71); out.writeByte(3); out.writeByte(4); out.writeByte(0);
        }, "Incomplete");
    }

    private void assertInvalidReply(ReplyWriter reply, String expected) throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            var peer = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    var in = new DataInputStream(socket.getInputStream());
                    var out = new DataOutputStream(socket.getOutputStream());
                    int tx = readRequest(in, 172);
                    reply.write(tx, out); out.flush();
                } catch (IOException ex) { throw new RuntimeException(ex); }
            });
            try (KostalModbusClient client = client(server)) {
                IOException error = assertThrows(IOException.class, () -> client.readHoldingRegisters(172, 2));
                assertTrue(error.getMessage().contains(expected), error.getMessage());
            }
            peer.get(2, TimeUnit.SECONDS);
        }
    }

    private int readRequest(DataInputStream in, int expectedAddress) throws IOException {
        int tx = in.readUnsignedShort();
        assertEquals(0, in.readUnsignedShort());
        assertEquals(6, in.readUnsignedShort());
        assertEquals(71, in.readUnsignedByte());
        assertEquals(3, in.readUnsignedByte());
        assertEquals(expectedAddress, in.readUnsignedShort());
        assertEquals(2, in.readUnsignedShort());
        return tx;
    }

    private KostalModbusClient client(ServerSocket server) {
        return new KostalModbusClient("127.0.0.1", server.getLocalPort(), 71, 500, 500);
    }

    @FunctionalInterface interface ReplyWriter { void write(int tx, DataOutputStream out) throws IOException; }
}
