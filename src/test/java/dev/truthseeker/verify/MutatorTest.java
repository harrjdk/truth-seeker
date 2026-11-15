package dev.truthseeker.verify;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutatorTest {

    private static byte[] bytesOf(String fqcn) throws Exception {
        try (InputStream in = MutatorTest.class.getClassLoader().getResourceAsStream(fqcn.replace('.', '/') + ".class")) {
            return in.readAllBytes();
        }
    }

    /** Loads one mutated class in isolation; linking it runs the JVM's bytecode verifier. */
    private static Class<?> load(Mutator.Mutant m) throws Exception {
        String name = m.className().replace('/', '.');
        ClassLoader loader = new ClassLoader(MutatorTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
                if (n.equals(name)) {
                    synchronized (getClassLoadingLock(n)) {
                        Class<?> c = findLoadedClass(n);
                        if (c == null) c = defineClass(n, m.bytes(), 0, m.bytes().length);
                        return c;
                    }
                }
                return super.loadClass(n, resolve);
            }
        };
        Class<?> c = Class.forName(name, true, loader);
        c.getDeclaredMethods();
        return c;
    }

    @Test
    void everyKindOfChangeIsMadeAndEachMutantVerifies() throws Exception {
        List<Mutator.Mutant> ms = Mutator.mutants(bytesOf("fixtures.rules.ShippingFee"));
        Set<String> kinds = ms.stream().map(m -> m.description().replaceAll(":.*", "")).collect(Collectors.toSet());
        assertTrue(kinds.containsAll(Set.of("negated condition", "changed boundary", "changed arithmetic", "returns the opposite boolean",
                "returns the value + 1")), kinds.toString());
        for (Mutator.Mutant m : ms) {
            assertEquals("fixtures.rules.ShippingFee", load(m).getName(), m.description());
            assertTrue(m.line() > 0, m.description());
        }
        // fee(): weightKg > 10 ? 20 : 5 is mutated at its own line
        assertTrue(ms.stream().anyMatch(m -> m.method().equals("baseFee") && m.description().equals("changed boundary: <= became <")), ms.toString());
    }

    @Test
    void lambdaBodiesBelongToTheirMethodAndCallsCanBeDropped() throws Exception {
        List<Mutator.Mutant> ms = Mutator.mutants(bytesOf("fixtures.legacy.Roster"));
        assertTrue(ms.stream().anyMatch(m -> m.method().startsWith("lambda$") && MutationTesting.sourceMethod(m.method()).equals("covers")), ms.toString());
        for (Mutator.Mutant m : ms) load(m);
        List<Mutator.Mutant> calls = Mutator.mutants(bytesOf("fixtures.legacy.BatchJob")).stream()
                .filter(m -> m.description().startsWith("removed call")).toList();
        assertTrue(calls.stream().anyMatch(m -> m.description().equals("removed call to Handler.handle(..)")), calls.toString());
        // resource cleanup (try-with-resources close()) is not a behaviour worth a mutant
        assertFalse(calls.stream().anyMatch(m -> m.description().contains(".close(")), calls.toString());
        for (Mutator.Mutant m : calls) load(m);
    }
}
