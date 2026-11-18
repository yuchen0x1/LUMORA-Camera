package com.lumora.camera;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class PresetContractTest {
    @Test
    public void shippedAssetPreservesLegacyIdsAndAddsEightStyles() throws Exception {
        Path relative = Paths.get("src/main/assets/presets.json");
        Path asset = Files.exists(relative) ? relative : Paths.get("app").resolve(relative);
        String json = new String(Files.readAllBytes(asset), StandardCharsets.UTF_8);
        Matcher ids = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        Set<String> actual = new HashSet<>();
        while (ids.find()) assertTrue("Duplicate " + ids.group(1), actual.add(ids.group(1)));
        assertEquals(new HashSet<>(Arrays.asList("portra", "vivid_film", "classic_chrome", "vintage", "cinema",
                "japanese", "soft_portrait", "night_film", "monochrome", "warm_light",
                "cool_tone", "natural", "rainy_mood", "urban_night", "forest_green",
                "seaside_sunset", "flash_street", "low_contrast_portrait",
                "high_contrast_monochrome", "warm_documentary")), actual);
    }
}
