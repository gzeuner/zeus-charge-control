package de.zeus.power.kostal;

import java.io.IOException;

interface KostalModbusTransport extends AutoCloseable {
    int[] readHoldingRegisters(int address, int count) throws IOException;
    @Override void close();
}
