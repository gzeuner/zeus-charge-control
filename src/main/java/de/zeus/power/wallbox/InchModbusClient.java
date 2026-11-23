package de.zeus.power.wallbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** A persistent, single-owner Modbus TCP connection to Sonnen / Etrel INCH. */
final class InchModbusClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(InchModbusClient.class);
    private static final int SLAVE_DEVICE_BUSY = 6;
    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000;

    enum ConnectionState { CONNECTED, RECONNECTING, ERROR }

    private final WallboxSettings settings;
    private Socket socket;
    private InputStream input;
    private DataOutputStream output;
    private int transaction;
    private int unit;
    private int consecutiveErrors;
    private int backoffLevel;
    private long reconnectAfterNanos;
    private volatile Instant lastAccess;
    private volatile Instant lastSuccessfulRead;
    private volatile ConnectionState connectionState = ConnectionState.RECONNECTING;
    private volatile String lastError;
    private volatile String recovery;

    InchModbusClient(WallboxSettings settings) {
        this.settings = settings;
        unit = settings.unitId();
    }

    Instant lastAccess() { return lastAccess; }
    Instant lastSuccessfulRead() { return lastSuccessfulRead; }
    ConnectionState connectionState() { return connectionState; }
    String lastError() { return lastError; }
    String recovery() { return recovery; }

    ChargerReadings read() throws IOException {
        recovery = null;
        try {
            ChargerReadings readings = readSnapshot();
            Instant now = Instant.now();
            lastSuccessfulRead = now;
            lastAccess = now;
            recordSuccess();
            return readings;
        } catch (IOException failure) {
            recordFailure(failure);
            throw failure;
        }
    }

    private ChargerReadings readSnapshot() throws IOException {
        ByteBuffer connector;
        try {
            connector = readInputs(0, 6);
        } catch (SocketTimeoutException timeout) {
            if (settings.unitId() == 255) throw timeout;
            int previous = unit;
            unit = alternateUnit();
            recovery = "Read timeout on unit " + previous + "; retrying unit " + unit;
            LOG.warn("Modbus {} on unit {}; retrying documented reads with unit {}",
                    timeout.getClass().getSimpleName(), previous, unit);
            try {
                connector = readInputs(0, 6);
            } catch (IOException retryFailure) {
                unit = previous;
                closeSocket();
                throw retryFailure;
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
            try {
                writeCurrent(0);
            } catch (SocketTimeoutException timeout) {
                if (settings.unitId() == 255) throw timeout;
                int previous = unit;
                unit = alternateUnit();
                LOG.warn("Modbus {} while pausing unit {}; retrying zero-amp pause with unit {}",
                        timeout.getClass().getSimpleName(), previous, unit);
                try {
                    writeCurrent(0);
                } catch (IOException retryFailure) {
                    unit = previous;
                    closeSocket();
                    throw retryFailure;
                }
            }
            lastAccess = Instant.now();
            recordSuccess();
        } catch (IOException failure) {
            recordFailure(failure);
            throw failure;
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
            throw invalidResponse("FC 04 register count at " + address);
        }
        lastAccess = Instant.now();
        return response.slice();
    }

    void setCurrent(float amps) throws IOException {
        if (!Float.isFinite(amps) || (amps != 0 && amps < 6) || amps > 16) {
            throw new IllegalArgumentException("Invalid HR 8 current " + amps);
        }
        try {
            writeCurrent(amps);
            lastAccess = Instant.now();
            recordSuccess();
        } catch (IOException failure) {
            recordFailure(failure);
            throw failure;
        }
    }

    private void writeCurrent(float amps) throws IOException {
        LOG.info("Writing charger HR 8 = {} A (unit {})", amps, unit);
        byte[] payload = ByteBuffer.allocate(9).putShort((short) 8).putShort((short) 2)
                .put((byte) 4).putFloat(amps).array();
        ByteBuffer echo = exchange(16, payload);
        if (echo.remaining() != 4 || echo.getShort() != 8 || echo.getShort() != 2) {
            throw invalidResponse("FC 16 address/count for HR 8");
        }
    }

    private ByteBuffer exchange(int function, byte[] payload) throws IOException {
        try {
            ensureConnected();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.readTimeoutMs());
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
            if (code != function) throw new IOException("Unexpected Modbus function " + code);
            return pdu.slice();
        } catch (DeviceException ex) {
            throw ex;
        } catch (IOException ex) {
            closeSocket();
            throw ex;
        }
    }

    private void ensureConnected() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) return;
        long waitNanos = reconnectAfterNanos - System.nanoTime();
        if (waitNanos > 0) {
            connectionState = ConnectionState.ERROR;
            throw new ReconnectBackoffException(TimeUnit.NANOSECONDS.toMillis(waitNanos));
        }
        connectionState = ConnectionState.RECONNECTING;
        Socket candidate = new Socket();
        try {
            candidate.connect(new InetSocketAddress(settings.host(), settings.port()), settings.connectTimeoutMs());
            candidate.setTcpNoDelay(true);
            candidate.setSoTimeout(settings.readTimeoutMs());
            socket = candidate;
            input = candidate.getInputStream();
            output = new DataOutputStream(candidate.getOutputStream());
            LOG.info("Modbus TCP connected to {}:{} (unit {})", settings.host(), settings.port(), unit);
        } catch (IOException failure) {
            try {
                candidate.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
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
        closeSocket();
        return new IOException("Invalid Modbus response for " + field);
    }

    private void recordSuccess() {
        consecutiveErrors = 0;
        backoffLevel = 0;
        reconnectAfterNanos = 0;
        lastError = null;
        connectionState = ConnectionState.CONNECTED;
    }

    private void recordFailure(IOException failure) {
        if (failure instanceof ReconnectBackoffException) return;

        String message = failure.getMessage();
        String type = failureType(failure);
        lastError = type + (message == null || message.isBlank() ? "" : ": " + message);
        if (failure instanceof DeviceException device && device.code() != SLAVE_DEVICE_BUSY) {
            consecutiveErrors = 0;
            connectionState = ConnectionState.ERROR;
            LOG.error("Modbus {} (slave exception {})", type, device.code());
            return;
        }

        consecutiveErrors++;
        if (consecutiveErrors >= settings.maxErrorsBeforeReconnect()) {
            closeSocket();
            long backoffMs = Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS << Math.min(backoffLevel, 5));
            backoffLevel++;
            reconnectAfterNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoffMs);
            consecutiveErrors = 0;
            connectionState = ConnectionState.ERROR;
            LOG.warn("Modbus {} after {} consecutive failures; reconnect in {} ms: {}",
                    type, settings.maxErrorsBeforeReconnect(), backoffMs,
                    failure.getMessage());
        } else {
            connectionState = ConnectionState.RECONNECTING;
            LOG.warn("Modbus {} ({}/{} consecutive errors): {}", type,
                    consecutiveErrors, settings.maxErrorsBeforeReconnect(), failure.getMessage());
        }
    }

    private static String failureType(IOException failure) {
        if (failure instanceof DeviceException device && device.code() == SLAVE_DEVICE_BUSY) {
            return "SlaveDeviceBusy";
        }
        return failure.getClass().getSimpleName();
    }

    @Override
    public void close() {
        closeSocket();
    }

    private void closeSocket() {
        Socket current = socket;
        socket = null;
        input = null;
        output = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ex) {
                LOG.debug("Closing Modbus TCP connection: {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
            }
        }
    }

    static final class DeviceException extends IOException {
        private final int code;

        DeviceException(int code) {
            super("Modbus exception " + code);
            this.code = code;
        }

        int code() { return code; }
    }

    private static final class ReconnectBackoffException extends IOException {
        ReconnectBackoffException(long waitMs) {
            super("Reconnect backoff active (" + Math.max(1, waitMs) + " ms remaining)");
        }
    }
}
