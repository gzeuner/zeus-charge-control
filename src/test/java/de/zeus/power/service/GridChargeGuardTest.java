package de.zeus.power.service;

import de.zeus.power.model.BatteryStatusResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class GridChargeGuardTest {
    private final BatteryCommandService commands = mock(BatteryCommandService.class);
    private final long now = System.currentTimeMillis();
    private final long target = now + 3_600_000L;

    private BatteryStatusResponse sample(long timestamp) {
        var status = new BatteryStatusResponse();
        status.setOperatingMode("11");
        status.setFlowGridBattery(true);
        status.setTimestamp(Instant.ofEpochMilli(timestamp).toString());
        return status;
    }

    @Test void observationNeverWrites() {
        var guard = new GridChargeGuard(commands, GridChargeGuard.Mode.OBSERVE, ZoneOffset.UTC);
        guard.tick(now, target, target, sample(now - 15_000));
        guard.tick(now, target, target, sample(now));
        verifyNoInteractions(commands);
    }

    @Test void detectsAndAttemptsToLimitGridChargeWithoutChangingOperatingMode() {
        var guard = new GridChargeGuard(commands, GridChargeGuard.Mode.ACTIVE, ZoneOffset.UTC);
        when(commands.limitAcCharging(30, false)).thenReturn(true);
        var first = sample(now - 15_000); first.setOperatingMode("2");
        var second = sample(now); second.setOperatingMode("2");
        guard.tick(now, target, target, first);
        guard.tick(now, target, target, second);
        verify(commands).limitAcCharging(30, false);
        verifyNoMoreInteractions(commands);
    }

    @Test void confirmedImportRenewsUntilPvSurplus() {
        var guard = new GridChargeGuard(commands, GridChargeGuard.Mode.ACTIVE, ZoneOffset.UTC);
        when(commands.limitAcCharging(30, false)).thenReturn(true);
        var first = sample(now - 15_000);
        guard.tick(now, target, target, first);
        guard.tick(now, target, target, first);
        verifyNoInteractions(commands);
        guard.tick(now, target, target, sample(now));
        verify(commands).limitAcCharging(30, false);
        var held = sample(now + 1000);
        held.setFlowGridBattery(false);
        guard.tick(now + 1000, target, target, held);
        verify(commands).limitAcCharging(30, true);
        held.setProductionW(500);
        guard.tick(now + 2000, target, target, held);
        verifyNoMoreInteractions(commands);
    }
}
