package de.zeus.power.wallbox;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WallboxControlTest {
    @Test
    void dashboardChangesTakeEffectImmediatelyAndSurviveRestart(@TempDir Path temp) throws Exception {
        Path config = temp.resolve("application.properties");
        String other = "# Standort\r\nbattery.authToken=${BATTERY_AUTH_TOKEN:}\r\n";
        String initial = other + "# BEGIN Zeus wallbox GUI\r\nwallbox.enabled=false\r\nwallbox.mode=ECO\r\nwallbox.smart-min-usoc=20\r\nwallbox.smart-reserve-percent=5\r\n# END Zeus wallbox GUI\r\n";
        Files.writeString(config, initial);
        try (var server = listener()) {
            var runner = new ApplicationContextRunner().withUserConfiguration(WallboxControl.class)
                    .withBean(RestTemplateBuilder.class, () -> new RestTemplateBuilder())
                    .withPropertyValues("wallbox.enabled=true", "wallbox.config-file=" + config, "wallbox.host=127.0.0.1",
                            "wallbox.port=" + server.getLocalPort(), "battery.url=http://127.0.0.1:1");
            runner.run(context -> {
                var control = context.getBean(WallboxControl.class);
                MockMvc mvc = MockMvcBuilders.standaloneSetup(new WallboxController(control)).build();
                assertFalse(control.status().settings().enabled());
                assertThrows(IllegalArgumentException.class, () -> control.update(Map.of("smartMinUsoc", 101)));
                assertThrows(IllegalArgumentException.class, () -> control.update(Map.of("smartReservePercent", 2.5)));
                assertEquals(initial, Files.readString(config));
                mvc.perform(post("/wallbox/settings").contentType("application/json")
                                .content("{\"enabled\":true,\"mode\":\"POWER\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.settings.mode").value("POWER"));
                try (var peer = server.accept()) {
                    expectPauseCommand(peer, 1, false);
                    snapshot(peer, 1, 16);
                    expectWrite(peer, 16, false);
                    org.awaitility.Awaitility.await().until(() -> control.status().sample() != null);
                    assertEquals(16f, control.status().sample().targetAmps());
                    assertEquals(WallboxSettings.Mode.POWER, control.status().activeMode());
                    assertEquals(16f, control.status().lastWrittenTargetAmps());
                    assertNotNull(control.status().lastAccess());
                    mvc.perform(get("/wallbox/status"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.activeMode").value("POWER"))
                            .andExpect(jsonPath("$.lastWrittenTargetAmps").value(16.0));
                    control.update(Map.of("mode", "SMART", "smartMinUsoc", 40, "smartReservePercent", 8));
                    // Socket timeout is shorter than the regular 8 s polling period.
                    snapshot(peer, 1, 16, 16);
                    expectPauseCommand(peer, 1, false);
                    org.awaitility.Awaitility.await().until(() -> control.status().activeMode() == WallboxSettings.Mode.SMART
                            && control.status().sample() != null && control.status().sample().targetAmps() == 0f);
                    mvc.perform(get("/wallbox/status"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.activeMode").value("SMART"))
                            .andExpect(jsonPath("$.lastWrittenTargetAmps").value(16.0))
                            .andExpect(jsonPath("$.boxReportedAppliedAmps").value(16.0));
                    control.update(Map.of("enabled", false));
                    expectPauseCommand(peer, 1, false);
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
    void masterFeatureSwitchPreventsUiFromStartingWallbox(@TempDir Path temp) throws Exception {
        var runner = new ApplicationContextRunner().withUserConfiguration(WallboxControl.class)
                .withBean(RestTemplateBuilder.class, RestTemplateBuilder::new)
                .withPropertyValues("wallbox.config-file=" + temp.resolve("application.properties"),
                        "wallbox.host=localhost", "wallbox.mode=FAST", "battery.url=http://localhost:1");
        runner.run(context -> {
            WallboxControl control = context.getBean(WallboxControl.class);
            assertFalse(control.status().featureEnabled());
            assertFalse(control.status().settings().enabled());
            assertEquals(WallboxSettings.Mode.POWER, control.status().settings().mode());
            assertThrows(IllegalArgumentException.class, () -> control.update(Map.of("enabled", true)));
        });
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
                            "wallbox.read-timeout-ms=500",
                            "wallbox.smart-min-usoc=20", "wallbox.smart-reserve-percent=5",
                            "wallbox.resume-hold-seconds=0", "wallbox.consumption-includes-wallbox=true",
                            "battery.url=http://127.0.0.1:" + battery.getAddress().getPort() + "/api/v2",
                            "battery.authToken=test-token")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        try (Socket peer = server.accept()) {
                            peer.setSoTimeout(4000);
                            expectPauseCommand(peer, 1, false);
                            snapshot(peer, 1, 16);
                            expectWrite(peer, 16, true);
                            snapshot(peer, 1, 6.032f); // Exception 12 refreshes the IR 1028 ceiling.
                            expectWrite(peer, 6.032f, false);
                            snapshot(peer, 1, 6.032f, 6.032f);
                            expectWrite(peer, 6.0f, false); // ECO rounds the 6.032 A box ceiling down to 0.1 A.
                            snapshot(peer, 1, 6.032f, 6.0f);
                            expectPauseCommand(peer, 1, false); // HTTP 503 must pause without HR 8 = 0.
                            assertEquals(3, requests.get());
                        }
                    });
        } finally {
            battery.stop(0);
        }
    }

    @Test
    void minCurrentPauseStrategyHoldsSixAmpsAndManualDisableUsesHr2(@TempDir Path temp) throws Exception {
        var battery = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        battery.createContext("/status", exchange -> {
            byte[] body = "{\"Production_W\":0,\"Consumption_W\":0,\"GridFeedIn_W\":0,\"Pac_total_W\":0,\"USOC\":60,\"BackupBuffer\":5}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        battery.start();
        try (var server = listener()) {
            var runner = new ApplicationContextRunner().withUserConfiguration(WallboxControl.class)
                    .withBean(RestTemplateBuilder.class, RestTemplateBuilder::new)
                    .withPropertyValues("wallbox.enabled=true", "wallbox.mode=ECO", "wallbox.host=localhost",
                            "wallbox.port=" + server.getLocalPort(), "wallbox.pause-strategy=MIN_CURRENT",
                            "wallbox.config-file=" + temp.resolve("application.properties"),
                            "wallbox.poll-interval-ms=8000", "wallbox.resume-hold-seconds=0",
                            "wallbox.consumption-includes-wallbox=false",
                            "battery.url=http://localhost:" + battery.getAddress().getPort());
            runner.run(context -> {
                var control = context.getBean(WallboxControl.class);
                assertNull(context.getStartupFailure());
                try (var peer = server.accept()) {
                    expectPauseCommand(peer, 1, false); // Startup/recovery always pauses safely.
                    snapshot(peer, 1, 16);
                    expectWrite(peer, 6, false); // Low surplus is held at min-current, never HR 8 = 0.
                    org.awaitility.Awaitility.await().until(() -> control.status().sample() != null
                            && Float.valueOf(6).equals(control.status().sample().targetAmps()));
                    assertEquals(6, control.status().lastWrittenTargetAmps());
                    control.update(Map.of("enabled", false));
                    expectPauseCommand(peer, 1, false); // User disable is a real pause for either strategy.
                    assertEquals(-1, peer.getInputStream().read());
                }
            });
        } finally {
            battery.stop(0);
        }
    }

    @Test
    void persistentHr8VsIr4DeviationWarnsWithRawWords(@TempDir Path temp) throws Exception {
        try (var server = listener()) {
            var runner = new ApplicationContextRunner().withUserConfiguration(WallboxControl.class)
                    .withBean(RestTemplateBuilder.class, RestTemplateBuilder::new)
                    .withPropertyValues("wallbox.enabled=true", "wallbox.host=localhost",
                            "wallbox.port=" + server.getLocalPort(), "wallbox.mode=POWER",
                            "wallbox.config-file=" + temp.resolve("application.properties"),
                            "wallbox.poll-interval-ms=300", "battery.url=");
            runner.run(context -> {
                var control = context.getBean(WallboxControl.class);
                try (var peer = server.accept()) {
                    expectPauseCommand(peer, 1, false);
                    snapshot(peer, 1, 16, 0);
                    expectWrite(peer, 16, false);
                    snapshot(peer, 1, 16, 0);
                    expectWrite(peer, 16, false);
                    snapshot(peer, 1, 16, 0);
                    expectWrite(peer, 16, false);
                    assertEquals(16, control.status().lastWrittenTargetAmps());
                    assertNotNull(control.status().sample());
                    assertEquals(0, control.status().sample().charger().appliedAmps());
                    control.update(Map.of("enabled", false));
                    expectPauseCommand(peer, 1, false);
                }
            });
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
                    snapshot(peer, 255, 16, 15.9f);
                    var reading = task.get(4, TimeUnit.SECONDS);
                    assertEquals(4, reading.status());
                    assertEquals(0, reading.phases());
                    assertEquals("3 (L1-L3)", reading.phaseDescription());
                    assertEquals(3, reading.hardwarePhases());
                    assertEquals("Fast", reading.modeDescription());
                    assertEquals(16, reading.installedMaxAmps());
                    assertEquals(16, reading.evMaxAmps());
                    assertEquals(15.9f, reading.appliedAmps(), 0.01f,
                            "IR 4 Float32 must decode big-endian words at FC 04 address 4");
                    assertTrue(reading.enabled());
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
                            expectPauseCommand(peer, 1, false);
                            snapshot(peer, 1, 16);
                            currentWrite(peer, 16); // Charger receives the setpoint; TCP drops before its ACK.
                        }
                        long failedAt;
                        try (var peer = server.accept()) {
                            var pause = pauseCommand(peer, 1);
                            failedAt = System.nanoTime();
                            pause.reply(peer, 0x90, new byte[]{4});
                        }
                        org.awaitility.Awaitility.await().until(() -> control.status().pauseUnconfirmed()
                                && control.status().sample() != null);
                        assertNull(control.status().sample().targetAmps());
                        var lastAccess = control.status().lastAccess();
                        control.update(Map.of("smartMinUsoc", 35)); // Must not bypass recovery.
                        server.setSoTimeout(8000);
                        try (var peer = server.accept()) {
                            assertTrue(System.nanoTime() - failedAt >= TimeUnit.SECONDS.toNanos(7));
                            assertEquals(lastAccess, control.status().lastAccess());
                            expectPauseCommand(peer, 1, false); // Recovery starts with a watchdog pause.
                            snapshot(peer, 1, 12);
                            expectWrite(peer, 12, false); // Recomputed from new readings.
                            org.awaitility.Awaitility.await().until(() -> !control.status().pauseUnconfirmed()
                                    && Float.valueOf(12).equals(control.status().sample().targetAmps()));
                            var stopped = worker.submit(control::stop);
                            expectPauseCommand(peer, 1, false);
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
                     WallboxSettings.Mode.POWER, 8000, 20, 5, 0, true, 2000, 500, 3));
             var worker = Executors.newSingleThreadExecutor()) {
            var write = worker.submit(() -> { client.setCurrent(6); return null; });
            try (var peer = server.accept()) {
                byte[] echo = currentWrite(peer, 6).frame(16, new byte[]{0, 8, 0, 2});
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
        snapshot(socket, unit, installed, 0);
    }

    private static void snapshot(Socket socket, int unit, float installed, float reportedTarget) throws IOException {
        for (int address : new int[]{0, 8, 48, 53, 1023, 1028}) {
            Request req = request(socket, unit, 4);
            assertEquals(address, req.data.getShort());
            ByteBuffer values = switch (address) {
                case 0 -> ByteBuffer.allocate(12).putShort((short) 4).putShort((short) 0).putFloat(16).putFloat(reportedTarget);
                case 8 -> ByteBuffer.allocate(40).putFloat(230).putFloat(230).putFloat(230)
                        .putFloat(1).putFloat(2).putFloat(3).putFloat(.25f).putFloat(.5f).putFloat(.5f).putFloat(1.25f);
                case 48 -> ByteBuffer.allocate(8).putLong(9_000_000_000L);
                case 53 -> ByteBuffer.allocate(2).putShort((short) 1);
                case 1023 -> ByteBuffer.allocate(2).putShort((short) 3);
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

    private static Request pauseCommand(Socket socket, int value) throws IOException {
        Request req = request(socket, 1, 16);
        assertEquals(2, req.data.getShort());
        assertEquals(1, req.data.getShort());
        assertEquals(2, req.data.get());
        assertEquals(value, req.data.getShort());
        assertFalse(req.data.hasRemaining());
        return req;
    }

    private static void expectPauseCommand(Socket socket, int value, boolean reject) throws IOException {
        Request req = pauseCommand(socket, value);
        if (reject) {
            req.reply(socket, 0x90, new byte[]{4});
        } else {
            req.reply(socket, 16, new byte[]{0, 2, 0, 1});
        }
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
