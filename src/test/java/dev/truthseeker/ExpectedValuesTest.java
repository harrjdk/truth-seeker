package dev.truthseeker;

import dev.truthseeker.testgen.TestGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Expected values rendered in the test's own terms instead of a bare assertNotNull. */
class ExpectedValuesTest {

    private static String generate(String cls) throws Exception {
        var repo = TestSupport.repo();
        return TestGenerator.generate(repo, repo.require(cls), new TestGenerator.Options(List.of(), 64, 24, false));
    }

    @Test
    void concatenationsAndHashesUseTheValuesTheTestSets() throws Exception {
        String code = generate("fixtures.legacy.Deadline");
        // the field is set to a known date, then the format is rebuilt from it
        assertTrue(code.contains("assertEquals(\"Deadline \" + LocalDate.of(2024, 1, 15) + \" (\" + LocalDate.of(2024, 1, 15).getDayOfWeek() + \")\", result);"), code);
        // javac's varargs array is spread back into the call
        assertTrue(code.contains("assertEquals(Objects.hash(LocalDate.of(2024, 1, 15), \"deadline\"), result);"), code);
    }

    @Test
    void stubbedCollaboratorValuesFlowIntoTheExpectedString() throws Exception {
        String code = generate("fixtures.legacy.CustomerSearch");
        // "redirect:/customers/" + only.getId(), with getId() stubbed on the element the iterator returns
        assertTrue(code.contains("assertEquals(\"redirect:/customers/\" + 1, result);"), code);
        assertTrue(!code.contains("TODO: expected `\"redirect:/customers/\""), code);
    }
}
