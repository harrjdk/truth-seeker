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

    @Test
    void aStringBuilderKeptInALocalCollectsEveryAppend() throws Exception {
        String code = generate("fixtures.legacy.ShipmentEvent");
        // appended to again after the branch, through the local variable
        assertTrue(code.contains("assertEquals(\"value\" + \": \" + Type.LOAD + \" by \" + \"value\", result);"), code);
        assertTrue(code.contains("assertEquals(\"value\" + \": \" + Type.LOAD, result);"), code);
    }

    @Test
    void objectsTheMethodBuildsAreCheckedThroughTheirGetters() throws Exception {
        String code = generate("fixtures.legacy.ShipmentEventFactory");
        // constructor arguments come back from the getters; a field no constructor set keeps its default
        assertTrue(code.contains("assertSame(trackingId, result.getTrackingId());"), code);
        assertTrue(code.contains("assertSame(carrier, result.getCarrier());"), code);
        assertTrue(code.contains("assertNull(result.getCarrier());"), code);
        // a bean filled through setters, with a field initialised where it is declared
        assertTrue(code.contains("assertSame(reference, result.getReference());"), code);
        assertTrue(code.contains("assertEquals(1, result.getPriority());") && code.contains("assertEquals(0, result.getPriority());"), code);
        assertTrue(code.contains("assertNull(result.getId());") && code.contains("assertEquals(new ArrayList<>(), result.getStops());"), code);
        assertTrue(!code.contains("TODO: expected `new "), code);
    }

    @Test
    void resultsTheMethodFillsLocallyAreRebuilt() throws Exception {
        String code = generate("fixtures.legacy.CustomerController");
        // ModelAndView: view name and model entries, the name-less one by value
        assertTrue(code.contains("assertEquals(\"customer/show\", result.getViewName());"), code);
        assertTrue(code.contains("assertEquals(\"customer/missing\", result.getViewName());"), code);
        assertTrue(code.contains("assertEquals(id, result.getModel().get(\"id\"));"), code);
        assertTrue(code.contains("result.getModel().get(\"customer\"));"), code);
        assertTrue(code.contains("assertTrue(result.getModel().containsValue("), code);
        // a list filled from a collaborator's results: stubbed with one element, expected back
        assertTrue(code.contains("findByName(any())).thenReturn(new ArrayList<>(List.of(customer"), code);
        assertTrue(code.contains("assertEquals(List.of(customer"), code);
        // a lazily created list on a built object
        assertTrue(code.contains("result.getCustomers());"), code);
        assertTrue(!code.contains("TODO: expected"), code);
    }

    @Test
    void pipelinesOverTheSubjectsCollectionsAreEvaluated() throws Exception {
        String code = generate("fixtures.legacy.Roster");
        // an iterator over one arranged element yields exactly it
        assertTrue(code.contains("result.forEachRemaining(remaining::add);") && code.contains("assertEquals(List.of(member), remaining);"), code);
        // a per-element lambda is only decided on an empty list: the reduction's identity
        assertTrue(code.contains("assertEquals(BigDecimal.ZERO, result);"), code);
        // sorting one element, and the lazily created empty set
        assertTrue(code.contains("assertEquals(List.of(member), result);") && code.contains("assertEquals(List.of(), result);"), code);
        // a private formatter constant re-created from its initializer
        assertTrue(code.contains(".format(DateTimeFormatter.ofPattern(\"yyyy/MM/dd\")), result);"), code);
        assertTrue(!code.contains("TODO: assert"), code);
    }
}
