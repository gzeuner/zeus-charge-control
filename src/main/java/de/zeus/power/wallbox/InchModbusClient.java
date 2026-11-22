package de.zeus.power.wallbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Connector 1. A single polling thread owns this Modbus TCP connection. */
final class InchModbusClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(InchModbusClient.class);
    private static final int TIMEOUT_MS = 600;
    private final WallboxSettings settings;
    private Socket socket;
    private InputStream input;
    private DataOutputStream output;
    private int transaction;
    private int unit;
    private volatile Instant lastAccess;
    private String recovery;

    Instant lastAccess() { return lastAccess; }
    String recovery() { return recovery; }

    InchModbusClient(WallboxSettings settings) {
        this.settings = settings;
        unit = settings.unitId();
    }

    ChargerReadings read() throws IOException {
        recovery = null;
        ByteBuffer connector;
        try {
            connector = readInputs(0, 6);
        } catch (SocketTimeoutException timeout) {
            if (settings.unitId() == 255) {
                throw timeout;
            }
            int previous = unit;
            unit = alternateUnit();
            recovery = "Modbus timeout (unit " + unit + " retry)";
            LOG.warn("Charger timed out. Retrying reads with unit {}", unit);
            try {
                connector = readInputs(0, 6);
            } catch (IOException failedProbe) {
                unit = previous;
                close();
                throw failedProbe;
            }
        }
        ByteBuffer phases = readInputs(8, 20);
        long lifetime = readInputs(48, 4).getLong();
        int mode = readInputs(53, 1).getShort();
        float installed = readInputs(1028, 2).getFloat();
        return new ChargerReadings(connector.getShort(), connector.getShort(), connector.getFloat(),
                connector.getFloat(), phase(phases, 0), phase(phases, 1), phase(phases, 2),
                phases.getFloat(36), lifetime, mode, installed);
    }

    private int alternateUnit() {
        return unit == 255 ? settings.unitId() : 255;
    }

    /** Only a pause may be repeated after an ambiguous write timeout. */
    void confirmPause() throws IOException {
        try {
            setCurrent(0);
        } catch (SocketTimeoutException timeout) {
            if (settings.unitId() == 255) throw timeout;
            int previous = unit;
            unit = alternateUnit();
            try {
                setCurrent(0);
            } catch (IOException failedPause) {
                unit = previous;
                close();
                throw failedPause;
            }
        }
    }

    private static ChargerReadings.Phase phase(ByteBuffer data, int index) {
        int offset = index * Float.BYTES;
        return new ChargerReadings.Phase(data.getFloat(offset), data.getFloat(12 + offset),
                data.getFloat(24 + offset));
    }

    private ByteBuffer readInputs(int address, int words) throws IOException {
        ByteBuffer response = exchange(4, ByteBuffer.allocate(4).putShort((short) address)
                .putShort((short) words).array());
        if (response.remaining() != 1 + words * 2 || Byte.toUnsignedInt(response.get()) != words * 2) {
            throw invalidResponse("FC 04 register count");
        }
        lastAccess = Instant.now();
        return response.slice();
    }

    void setCurrent(float amps) throws IOException {
        if (!Float.isFinite(amps) || (amps != 0 && amps < 6) || amps > 16) {
            throw new IllegalArgumentException("Invalid HR 8 current " + amps);
        }
        LOG.info("Writing charger HR 8 = {} A (unit {})", amps, unit);
        byte[] payload = ByteBuffer.allocate(9).putShort((short) 8).putShort((short) 2)
                .put((byte) 4).putFloat(amps).array();
        ByteBuffer echo = exchange(16, payload);
        if (echo.remaining() != 4 || echo.getShort() != 8 || echo.getShort() != 2) {
            throw invalidResponse("FC 16 address/count");
        }
        lastAccess = Instant.now();
    }

    private ByteBuffer exchange(int function, byte[] payload) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        try {
            if (socket == null) {
                socket = new Socket();
                socket.connect(new InetSocketAddress(settings.host(), settings.port()), TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                input = socket.getInputStream();
                output = new DataOutputStream(socket.getOutputStream());
            }
            transaction = (transaction + 1) & 0xffff;
            output.write(ByteBuffer.allocate(8 + payload.length)
                    .putShort((short) transaction).putShort((short) 0).putShort((short) (2 + payload.length))
                    .put((byte) unit).put((byte) function).put(payload).array());
            ByteBuffer header = ByteBuffer.wrap(readBytes(7, deadline));
            int id = Short.toUnsignedInt(header.getShort());
            int protocol = Short.toUnsignedInt(header.getShort());
            int length = Short.toUnsignedInt(header.getShort());
            int responseUnit = Byte.toUnsignedInt(header.get());
            if (id != transaction || protocol != 0 || responseUnit != unit || length < 3 || length > 254) {
                throw new IOException("Invalid Modbus TCP response header");
            }
            ByteBuffer pdu = ByteBuffer.wrap(readBytes(length - 1, deadline));
            int code = Byte.toUnsignedInt(pdu.get());
            if (code == (function | 0x80) && pdu.remaining() == 1) {
                throw new DeviceException(Byte.toUnsignedInt(pdu.get()));
            }
            if (code != function) {
                throw new IOException("Unexpected Modbus function " + code);
            }
            return pdu.slice();
        } catch (DeviceException ex) {
            throw ex;
        } catch (IOException ex) {
            close();
            throw ex;
        }
    }

    private byte[] readBytes(int length, long deadline) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException("Modbus response deadline exceeded");
            socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) throw new EOFException("Truncated Modbus response");
            offset += count;
        }
        return bytes;
    }

    private IOException invalidResponse(String field) {
        close();
        return new IOException("Invalid Modbus response for " + field);
    }

    static final class DeviceException extends IOException {
        final int code;

        DeviceException(int code) {
            super("Modbus exception " + code);
            this.code = code;
        }
    }

    @Override
    public void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ex) {
                LOG.debug("Closing charger connection", ex);
            } finally {
                socket = null;
            }
        }
    }
}
