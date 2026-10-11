package dev.blockboost;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Packaging and honesty checks on the repository itself.
 *
 * <p>These read source and resource files from the project directory (Gradle runs
 * tests with the project root as working directory). They exist because the two
 * claims this mod makes in its README — "no networking, no telemetry" and "no other
 * mod's code inside" — are things a build should prove mechanically, not things a
 * contributor promises in prose.
 */
public class ModMetadataTest {

    private static Path root() {
        Path p = Path.of("").toAbsolutePath();
        if (!Files.exists(p.resolve("src/main/resources/fabric.mod.json")) && Files.exists(p.getParent())) {
            p = p.getParent();
        }
        return p;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + p, e);
        }
    }

    @Test
    void fabricModJsonDeclaresTheVersionThisBuildTargets() {
        Path file = root().resolve("src/main/resources/fabric.mod.json");
        assertTrue(Files.exists(file), "missing " + file);
        String json = read(file);
        assertTrue(json.contains("\"minecraft\": \"~26.1.2\"") || json.contains("\"~26.1\""),
                "the mod must declare the 26.1.x range it was built against");
        assertTrue(json.contains("\"java\": \">=25\""), "26.1 requires Java 25; the metadata must say so");
        assertTrue(json.contains("blockboost.client.mixins.json"), "the client mixin config must be declared");
        assertTrue(json.contains("\"client\""), "a client entrypoint must be declared");
        assertFalse(json.contains("optifabric"), "we do not know OptiFabric's current behaviour well enough "
                + "to declare a hard conflict from a mod we cannot test");
    }

    @Test
    void everyDeclaredMixinClassExists() {
        Path config = root().resolve("src/client/resources/blockboost.client.mixins.json");
        assertTrue(Files.exists(config), "missing " + config);
        String json = read(config);
        String pkg = json.replaceAll("(?s).*\"package\"\\s*:\\s*\"([^\"]+)\".*", "$1").replace('.', '/');
        List<String> missing = new ArrayList<>();
        for (String name : mixinClassNames(json)) {
            Path java = root().resolve("src/client/java/" + pkg + "/" + name.replace('.', '/') + ".java");
            if (!Files.exists(java)) {
                missing.add(name + " (expected " + java + ")");
            }
        }
        if (!missing.isEmpty()) {
            fail("mixin config lists classes that do not exist: " + missing);
        }
    }

    private static List<String> mixinClassNames(String json) {
        List<String> out = new ArrayList<>();
        int idx = json.indexOf("\"client\"");
        if (idx < 0) {
            return out;
        }
        int open = json.indexOf('[', idx);
        int close = json.indexOf(']', open);
        String body = json.substring(open + 1, Math.max(open + 1, close));
        for (String part : body.split(",")) {
            String trimmed = part.trim().replace("\"", "");
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    @Test
    void noNetworkOrTelemetryCodeAnywhere() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path dir : List.of(root().resolve("src/main/java"), root().resolve("src/client/java"))) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(dir)) {
                stream.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    for (String line : read(p).split("\n")) {
                        String t = line.trim();
                        // Documentation that says "no telemetry" must not fail a test
                        // that is looking for telemetry code.
                        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                            continue;
                        }
                        for (String needle : List.of("import java.net", "Socket(", "HttpURLConnection",
                                "openConnection", "InetAddress", "DatagramSocket", "URL(", "URI.create")) {
                            if (t.contains(needle)) {
                                offenders.add(p.getFileName() + ":" + needle);
                            }
                        }
                    }
                });
            }
        }
        assertTrue(offenders.isEmpty(), "BlockBoost must not contain any network code: " + offenders);
    }

    @Test
    void noOtherModsCodeIsBundled() throws IOException {
        List<String> foreign = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root().resolve("src"))) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                for (String line : read(p).split("\n")) {
                    if (line.startsWith("import ") && (line.contains("caffeinemc") || line.contains("tr7zw")
                            || line.contains("clienthax") || line.contains("jellysquid")
                            || line.contains("earthcomputer") || line.contains("rumin")
                            || line.contains("embeddedt") || line.contains("coderbot"))) {
                        foreign.add(p.getFileName() + ": " + line.trim());
                    }
                }
            });
        }
        assertTrue(foreign.isEmpty(),
                "this mod may read other projects to learn this version's API, but it may not import them: "
                        + foreign);
    }

    @Test
    void licenseFilesAndIconsArePresent() {
        assertTrue(Files.exists(root().resolve("LICENSE")), "MIT license text must ship in the repo root");
        assertTrue(Files.exists(root().resolve("LICENSE.txt")), "case-insensitive checkout convenience");
        assertTrue(Files.exists(root().resolve("NOTICE.md")), "attribution and non-bundling statement");
        Path lang = root().resolve("src/main/resources/assets/blockboost/lang/en_us.json");
        assertTrue(Files.exists(lang), "the mod must ship its own English strings");
        assertTrue(read(lang).contains("blockboost.key."), "keybind names must be translated, not hardcoded");
        assertTrue(Files.exists(root().resolve("src/main/resources/assets/blockboost/icon.png")),
                "Mod Menu shows the icon; it must exist at the path fabric.mod.json claims");
    }
}
