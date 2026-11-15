package dev.truthseeker.verify;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProvidedApisTest {

    @Test
    void missingContainerPackagesAreRecognised() {
        Set<String> pkgs = ProvidedApis.missingPackages(List.of(
                "FooTest.java:3: error: package javax.servlet.http does not exist",
                "FooTest.java:4: error: package org.example.util does not exist",
                "FooTest.java:5: error: package jakarta.persistence does not exist"));
        assertEquals(Set.of("javax.servlet.http", "jakarta.persistence"), pkgs);
    }

    @Test
    void versionsCompareNumerically() {
        assertTrue(ProvidedApis.compareVersions("4.0.1", "3.1.0") > 0);
        assertTrue(ProvidedApis.compareVersions("10.0.0", "9.1.0") > 0);
        assertTrue(ProvidedApis.compareVersions("2.5", "2.5.1") < 0);
    }
}
