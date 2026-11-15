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
    void missingSupertypesNameTheirPackage() {
        Set<String> pkgs = ProvidedApis.missingPackages(List.of(
                "MyItemProcessorGeneratedTest.java:12: error: cannot access javax.batch.api.chunk.ItemProcessor",
                "X.java:3: error: cannot access org.example.Base",
                "  class file for jakarta.enterprise.concurrent.Trigger not found"));
        assertEquals(Set.of("javax.batch.api.chunk", "jakarta.enterprise.concurrent"), pkgs);
    }

    @Test
    void aJarMustHoldThePackage() throws Exception {
        java.nio.file.Path jar = java.nio.file.Files.createTempFile("api", ".jar");
        try (var out = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.zip.ZipEntry("javax/enterprise/inject/Produces.class"));
            out.closeEntry();
        }
        assertTrue(ProvidedApis.holdsPackage(jar, "javax.enterprise.inject"));
        assertTrue(!ProvidedApis.holdsPackage(jar, "javax.enterprise.concurrent"));
        java.nio.file.Files.delete(jar);
    }

    @Test
    void versionsCompareNumerically() {
        assertTrue(ProvidedApis.compareVersions("4.0.1", "3.1.0") > 0);
        assertTrue(ProvidedApis.compareVersions("10.0.0", "9.1.0") > 0);
        assertTrue(ProvidedApis.compareVersions("2.5", "2.5.1") < 0);
    }
}
