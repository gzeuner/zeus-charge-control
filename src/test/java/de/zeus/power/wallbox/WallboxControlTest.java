package de.zeus.power.wallbox;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WallboxControlTest {
    @Test
    void dashboardChangesTakeEffectImmediatelyAndSurviveRestart(@TempDir Path temp) throws Exception {
        Path config = temp.resolve("application.properties");
        String other = "# Standort\r\nbattery.authToken=${BATTERY_AUTH_TOKEN:}\r\n";
        Files.writeString(config, other);
        try (var server = listener()) {
            var runner = new ApplicationContextRunner().withUserConfiguration(WallboxControl.class)
                    .withBean(RestTemplateBuilder.class, () -> new RestTemplateBuilder())
                    .withPropertyValues("wallbox.config-file=" + config, "wallbox.host=127.0.0.1",
                            "wallbox.port=" + server.getLocalPort(), "battery.url=http://127.0.0.1:1");
            runner.run(context -> {
                var control = context.getBean(WallboxControl.class);
                assertFalse(control.status().settings().enabled());
                assertThrows(IllegalArgumentException.class, () -> control.update(Map.of("smartMinUsoc", 101)));
                assertThrows(IllegalArgumentException.class, () -> control.update(Map.of("smartReservePercent", 2.5)));
                assertEquals(other, Files.readString(config));
                control.update(Map.of("enabled", true, "mode", "POWER"));
                try (var peer = server.accept()) {
                    expectWrite(peer, 0, false);
                    snapshot(peer, 1, 16);
                    expectWrite(peer, 16, false);
                    org.awaitility.Awaitility.await().until(() -> control.status().sample() != null);
                    assertEquals(16f, control.status().sample().targetAmps());
                    assertNotNull(control.status().lastAccess());
                    control.update(Map.of("mode", "SMART", "smartMinUsoc", 40, "smartReservePercent", 8));
                    // Socket timeout is shorter than the regular 8 s polling period.
                    snapshot(peer, 1, 16);
                    expectWrite(peer, 0, false);
                    control.update(Map.of("enabled", false));
                    expectWrite(peer, 0, false);
                    assertEquals(-1, peer.getInputStream().read());
                }
                assertTrue(Files.readString(config).startsWith(other));
            });
            runner.run(context -> {
                var status = context.getBean(WallboxControl.class).status();
                assertEquals(new WallboxPreferences(false, WallboxSettings.Mode.SMART, 40, 8), status.settings());
                assertNull(status.lastAccess());
            });
        }
    }

    @Test
    void enabledSpringServicePollsAndRenewsOnlyHr8WithFreshData() throws Exception {
        var requests = new AtomicInteger();
        var battery = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        battery.createContext("/api/v2/status", exchange -> {
            try (exchange) {
                if (!exchange.getRequestMethod().equals("GET")
                        || !"test-token".equals(exchange.getRequestHeaders().getFirst("Auth-Token"))) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                int request = requests.incrementAndGet();
                var body = """
                        {"Production_W":11040,"Consumption_W":0,"Pac_total_W":0,
                         "GridFeedIn_W":11040,"USOC":82,"BackupBuffer":"5"}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(request <= 2 ? 200 : 503, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        battery.start();
        try (var server = listener()) {
            new ApplicationContextRunner().withUserConfiguration(WallboxControl.class)
                    .withBean(RestTemplateBuilder.class, () -> new RestTemplateBuilder())
                    .withPropertyValues("wallbox.enabled=true", "wallbox.host=127.0.0.1",
                            "wallbox.port=" + server.getLocalPort(), "wallbox.unit-id=1",
                            "wallbox.mode=eco", "wallbox.poll-interval-ms=500",
                            "wallbox.smart-min-usoc=20", "wallbox.smart-reserve-percent=5",
                            "wallbox.resume-hold-seconds=0", "wallbox.consumption-includes-wallbox=true",
                            "battery.url=http://127.0.0.1:" + battery.getAddress().getPort() + "/api/v2",
                            "battery.authToken=test-token")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        try (Socket peer = server.accept()) {
                            peer.setSoTimeout(4000);
                            expectWrite(peer, 0, false);
                            snapshot(peer, 1, 16);
                            expectWrite(peer, 16, true);
                            snapshot(peer, 1, 12); // Exception 12 triggers a fresh limit read and retry.
                            expectWrite(peer, 12, false);
                            snapshot(peer, 1, 12);
                            expectWrite(peer, 12, false); // IR 4 is still zero during EV detection.
                            snapshot(peer, 1, 12);
                            expectWrite(peer, 0, false);  // HTTP 503 must clear the previous target.
                            assertEquals(3, requests.get());
                        }
                    });
        } finally {
            battery.stop(0);
        }
    }

    @Test
    void timeoutChangesUnitAndDropOpensANewConnection() throws Exception {
        try (var server = listener();
             var client = new InchModbusClient(new WallboxSettings("127.0.0.1", server.getLocalPort(), 1,
                     WallboxSettings.Mode.ECO, 8000, 20, 5, 0, true));
             var worker = Executors.newSingleThreadExecutor()) {
            var task = worker.submit(client::read);
            try (var ignoredUnit = server.accept()) {
                request(ignoredUnit, 1, 4); // Keep the first socket silent.
                try (var peer = server.accept()) {
                    snapshot(peer, 255, 16);
                    var reading = task.get(4, TimeUnit.SECONDS);
                    assertEquals(4, reading.status());
                    assertEquals(16, reading.installedMaxAmps());
                    assertEquals(16, reading.evMaxAmps());
                    assertFalse(reading.enabled());
                    assertEquals(1.25f, reading.powerKw());
                    assertEquals(0.5f, reading.l2().kw());
                    assertEquals(3.0f, reading.l3().amps());
                    assertEquals(9_000_000_000L, reading.lifetimeMwh());
                }
            }
            assertThrows(IOException.class, client::read);
            var recovered = worker.submit(client::read);
            try (var peer = server.accept()) {
                snapshot(peer, 255, 11);
                assertEquals(11, recovered.get(4, TimeUnit.SECONDS).installedMaxAmps());
            }
        }
    }

    private static ServerSocket listener() throws IOException {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(5000);
        return server;
    }

    @Test
    void missingWriteAckAndRejectedPauseLeaveWatchdogQuietUntilConfirmedRecovery(@TempDir Path temp)
            throws Exception {
        try (var server = listener(); var worker = Executors.newSingleThreadExecutor()) {
            new ApplicationContextRunner().withUserConfiguration(WallboxControl.class)
                    .withBean(RestTemplateBuilder.class, RestTemplateBuilder::new)
                    .withPropertyValues("wallbox.config-file=" + temp.resolve("application.properties"),
                            "wallbox.host=127.0.0.1", "wallbox.port=" + server.getLocalPort(),
                            "wallbox.enabled=true", "wallbox.mode=POWER", "wallbox.poll-interval-ms=300",
                            "battery.url=http://127.0.0.1:1")
                    .run(context -> {
                        var control = context.getBean(WallboxControl.class);
                        try (var peer = server.accept()) {
                            expectWrite(peer, 0, false);
                            snapshot(peer, 1, 16);
                            currentWrite(peer, 16); // Charger receives the setpoint; TCP drops before its ACK.
                        }
                        long failedAt;
                        try (var peer = server.accept()) {
                            var pause = currentWrite(peer, 0);
                            failedAt = System.nanoTime();
                            pause.reply(peer, 0x90, new byte[]{4});
                        }
                        org.awaitility.Awaitility.await().until(() -> control.status().pauseUnconfirmed()
                                && control.status().sample() != null);
                        assertNull(control.status().sample().targetAmps());
                        var lastAccess = control.status().lastAccess();
                        control.update(Map.of("smartMinUsoc", 35)); // Must not bypass recovery.
                        server.setSoTimeout(8000);
                        assertThrows(SocketTimeoutException.class, server::accept);
                        assertEquals(lastAccess, control.status().lastAccess());
                        org.awaitility.Awaitility.await().atMost(4, TimeUnit.SECONDS)
                                .until(() -> control.status().watchdogPossible());
                        try (var peer = server.accept()) {
                            expectWrite(peer, 0, false); // First recovery access is a pause, even in POWER.
                            assertTrue(System.nanoTime() - failedAt >= TimeUnit.SECONDS.toNanos(11));
                            snapshot(peer, 1, 12);
                            expectWrite(peer, 12, false); // Recomputed from new readings.
                            org.awaitility.Awaitility.await().until(() -> !control.status().pauseUnconfirmed()
                                    && Float.valueOf(12).equals(control.status().sample().targetAmps()));
                            var stopped = worker.submit(control::stop);
                            expectWrite(peer, 0, false);
                            stopped.get(3, TimeUnit.SECONDS);
                            assertEquals(-1, peer.getInputStream().read());
                        }
                    });
        }
    }

    private record Request(short transaction, int unit, ByteBuffer data) {
        byte[] frame(int function, byte[] payload) {
            return ByteBuffer.allocate(8 + payload.length)
                    .putShort(transaction).putShort((short) 0).putShort((short) (payload.length + 2))
                    .put((byte) unit).put((byte) function).put(payload).array();
        }

        void reply(Socket socket, int function, byte[] payload) throws IOException {
            socket.getOutputStream().write(frame(function, payload));
        }
    }

    private static Request request(Socket socket, int unit, int function) throws IOException {
        socket.setSoTimeout(5000);
        var in = new DataInputStream(socket.getInputStream());
        short transaction = in.readShort();
        assertEquals(0, in.readUnsignedShort());
        int length = in.readUnsignedShort();
        assertEquals(unit, in.readUnsignedByte());
        assertEquals(function, in.readUnsignedByte());
        return new Request(transaction, unit, ByteBuffer.wrap(in.readNBytes(length - 2)));
    }

    @Test
    void lateButOtherwiseValidWriteEchoDoesNotCountAsAnAcknowledgement() throws Exception {
        try (var server = listener();
             var client = new InchModbusClient(new WallboxSettings("127.0.0.1", server.getLocalPort(), 1,
                     WallboxSettings.Mode.POWER, 8000, 20, 5, 0, true));
             var worker = Executors.newSingleThreadExecutor()) {
            var write = worker.submit(() -> { client.setCurrent(0); return null; });
            try (var peer = server.accept()) {
                byte[] echo = currentWrite(peer, 0).frame(16, new byte[]{0, 8, 0, 2});
                var output = peer.getOutputStream();
                output.write(echo, 0, 4);
                Thread.sleep(350);
                output.write(echo, 4, 4);
                Thread.sleep(350);
                try {
                    output.write(echo, 8, 4);
                } catch (java.net.SocketException expectedAfterTimeout) {
                    // The client closes its socket at the overall response deadline.
                }
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> write.get(2, TimeUnit.SECONDS));
                assertInstanceOf(SocketTimeoutException.class, failure.getCause());
                assertNull(client.lastAccess());
            }
        }
    }

    private static void snapshot(Socket socket, int unit, float installed) throws IOException {
        for (int address : new int[]{0, 8, 48, 53, 1028}) {
            Request req = request(socket, unit, 4);
            assertEquals(address, req.data.getShort());
            ByteBuffer values = switch (address) {
                case 0 -> ByteBuffer.allocate(12).putShort((short) 4).putShort((short) 0).putFloat(16).putFloat(0);
                case 8 -> ByteBuffer.allocate(40).putFloat(230).putFloat(230).putFloat(230)
                        .putFloat(1).putFloat(2).putFloat(3).putFloat(.25f).putFloat(.5f).putFloat(.5f).putFloat(1.25f);
                case 48 -> ByteBuffer.allocate(8).putLong(9_000_000_000L);
                case 53 -> ByteBuffer.allocate(2).putShort((short) 1);
                case 1028 -> ByteBuffer.allocate(4).putFloat(installed);
                default -> throw new AssertionError(address);
            };
            assertEquals(values.capacity() / 2, req.data.getShort());
            req.reply(socket, 4, ByteBuffer.allocate(1 + values.capacity())
                    .put((byte) values.capacity()).put(values.array()).array());
        }
    }

    private static Request currentWrite(Socket socket, float amps) throws IOException {
        Request req = request(socket, 1, 16);
        assertEquals(8, req.data.getShort());
        assertEquals(2, req.data.getShort());
        assertEquals(4, req.data.get());
        assertEquals(amps, req.data.getFloat());
        assertFalse(req.data.hasRemaining());
        return req;
    }

    private static void expectWrite(Socket socket, float amps, boolean reject) throws IOException {
        Request req = currentWrite(socket, amps);
        if (reject) {
            req.reply(socket, 0x90, new byte[]{12});
        } else {
            req.reply(socket, 16, new byte[]{0, 8, 0, 2});
        }
    }
}
