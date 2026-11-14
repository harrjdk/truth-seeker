package dev.truthseeker.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Location of truth-seeker's extraction caches (override with -Dtruthseeker.cache=...). */
final class Cache {
    private Cache() {}

    static Path root() throws IOException {
        String override = System.getProperty("truthseeker.cache");
        Path p = override != null ? Path.of(override) : Path.of(System.getProperty("java.io.tmpdir"), "truth-seeker");
        Files.createDirectories(p);
        return p;
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
