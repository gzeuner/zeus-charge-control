package de.zeus.power.wallbox;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Only replace the GUI-owned block. Keep other properties, placeholders, comments and encoding intact. */
record WallboxConfigFile(Path path) {
    private static final Logger LOG = LoggerFactory.getLogger(WallboxConfigFile.class);
    private static final String BEGIN = "# BEGIN Zeus wallbox GUI";
    private static final String END = "# END Zeus wallbox GUI";
    private static final Pattern BLOCK = Pattern.compile("(?ms)^" + BEGIN + "\\R(.*?)^" + END + "[ \\t]*(?:\\R|$)");

    private String contents() throws IOException {
        return Files.notExists(path) ? "" : Files.readString(path, StandardCharsets.ISO_8859_1);
    }

    record Loaded(WallboxPreferences preferences, WallboxSettings.Mode persistedMode, boolean hasGuiBlock) {}

    Loaded read(WallboxPreferences defaults) throws IOException {
        var block = BLOCK.matcher(contents());
        if (!block.find()) return new Loaded(defaults, null, false);
        Properties values = new Properties();
        values.load(new StringReader(block.group(1)));
        String rawMode = values.getProperty("wallbox.mode");
        WallboxSettings.Mode mode = WallboxSettings.parseMode(rawMode);
        if (mode == null) {
            LOG.warn("Invalid persisted wallbox.mode; falling back to POWER");
            mode = WallboxSettings.Mode.POWER;
        }
        var loaded = new WallboxPreferences(Boolean.parseBoolean(values.getProperty("wallbox.enabled")),
                mode,
                Integer.parseInt(values.getProperty("wallbox.smart-min-usoc")),
                Integer.parseInt(values.getProperty("wallbox.smart-reserve-percent")));
        return new Loaded(loaded, mode, true);
    }

    void save(WallboxPreferences settings) throws IOException {
        String original = contents();
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        String other = BLOCK.matcher(original).replaceAll("");
        if (!other.isEmpty() && !other.endsWith("\n")) other += newline;
        String updated = other + String.join(newline, BEGIN, "wallbox.enabled=" + settings.enabled(),
                "wallbox.mode=" + settings.mode(), "wallbox.smart-min-usoc=" + settings.smartMinUsoc(),
                "wallbox.smart-reserve-percent=" + settings.smartReservePercent(), END, "");
        Path directory = path.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, ".wallbox-", ".tmp");
        try {
            if (Files.exists(path)) Files.copy(path, temp, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(temp, updated, StandardCharsets.ISO_8859_1);
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
