package dev.truthseeker;

import dev.truthseeker.archive.ClassRepository;
import dev.truthseeker.symbolic.Cond;
import dev.truthseeker.symbolic.Path;
import dev.truthseeker.symbolic.PathExplorer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class TruthTableTest {

    private static PathExplorer.Result explore(String cls, String method) throws Exception {
        ClassRepository repo = TestSupport.repo();
        ClassNode cn = repo.require(cls);
        return PathExplorer.explore(repo, cn, ClassRepository.findMethods(cn, method, null).get(0), PathExplorer.Options.defaults());
    }

    @Test
    void compoundBooleanConditionsBecomeColumns() throws Exception {
        PathExplorer.Result r = explore("fixtures.rules.Eligibility", "isEligible");
        List<String> keys = r.paths().stream().flatMap(p -> p.conds().stream()).map(Cond::key).distinct().toList();
        assertEquals(List.of("vip", "age >= 18", "banned", "\"US\".equals(country)"), keys);
        assertEquals(5, r.paths().size());
        // exactly two paths return true: vip, or the full conjunction
        long trues = r.paths().stream().filter(p -> p.outcome().describe().equals("return true")).count();
        assertEquals(2, trues);
    }

    @Test
    void elseIfLadderProducesOnePathPerReturn() throws Exception {
        PathExplorer.Result r = explore("fixtures.rules.Eligibility", "discountPercent");
        assertEquals(List.of("return 20", "return 15", "return 10", "return 5", "return 0"),
                r.paths().stream().map(p -> p.outcome().describe()).toList());
    }

    @Test
    void charSwitchGroupsSharedCases() throws Exception {
        PathExplorer.Result r = explore("fixtures.rules.Eligibility", "grade");
        List<String> labels = r.paths().stream().map(p -> p.conds().get(0).value()).toList();
        assertEquals(List.of("'A' | 'B'", "'C'", "default"), labels);
    }

    @Test
    void localsAreShownByNameAndQueriesAreNotSideEffects() throws Exception {
        PathExplorer.Result r = explore("fixtures.order.OrderService", "pay");
        List<String> keys = r.paths().stream().flatMap(p -> p.conds().stream()).map(Cond::key).distinct().toList();
        assertEquals(List.of("found.isPresent()", "order.getStatus() != OrderStatus.NEW", "amount.compareTo(BigDecimal.ZERO) <= 0", "charged"), keys);
        Path happy = r.paths().stream().filter(p -> p.cond("charged").map(Cond::isTrue).orElse(false)).findFirst().orElseThrow();
        String effects = happy.effects().stream().map(e -> e.text()).collect(Collectors.joining("; "));
        assertTrue(effects.contains("order.setStatus(OrderStatus.PAID)"), effects);
        assertFalse(effects.contains("isPresent"), effects);
    }

    @Test
    void enumSwitchViaSwitchMapIsDecoded() throws Exception {
        PathExplorer.Result r = explore("fixtures.order.OrderService", "ship");
        assertEquals(List.of("OrderStatus.PAID", "OrderStatus.NEW", "default"),
                r.paths().stream().map(p -> p.conds().get(0).value()).toList());
    }

    @Test
    void loopConditionsAreReevaluatedPerIteration() throws Exception {
        PathExplorer.Result r = explore("fixtures.clinic.Owner", "getPet(String)");
        // first pet does not match, loop runs again and ends: must still return null
        boolean exitsAfterOneMiss = r.paths().stream().anyMatch(p -> p.outcome().describe().equals("return null")
                && p.conds().stream().anyMatch(c -> c.key().contains("hasNext() [iteration 2]") && !c.isTrue())
                && p.conds().stream().anyMatch(c -> c.key().contains("hasNext()") && !c.key().contains("iteration") && c.isTrue()));
        assertTrue(exitsAfterOneMiss, () -> r.paths().stream().map(p -> p.conds() + " -> " + p.outcome().describe()).collect(Collectors.joining("\n")));
        // parameters stay pinned across iterations: no re-decided "[iteration" column for them
        PathExplorer.Result byId = explore("fixtures.clinic.Owner", "getPet(Integer)");
        assertTrue(byId.paths().size() >= 4, "paths: " + byId.paths().size());
    }

    @Test
    void gettersOnCastParameterAreNotSideEffects() throws Exception {
        PathExplorer.Result r = explore("fixtures.clinic.PetValidator", "validate");
        assertTrue(r.paths().stream().allMatch(p -> p.effects().stream().noneMatch(e -> e.text().contains("getName"))));
    }

    @Test
    void markdownContainsExpandedTableAndFormula() {
        String md = TestSupport.run("truth_table", "className", "Eligibility", "method", "isEligible", "expand", true);
        assertTrue(md.contains("**Expanded truth table** (all 2^4 combinations)"), md);
        assertTrue(md.contains("|F|T|F|T|`return true`|P1|"), md);
        assertTrue(md.contains("|| vip"), md);
    }

    @Test
    void jsonFormat() {
        String json = TestSupport.run("truth_table", "className", "Eligibility", "method", "fee", "format", "json");
        assertTrue(json.contains("\"conditions\":[\"balance < 0L\",\"balance > 10000L\"]"), json);
    }

    @Test
    void java21PatternSwitches() throws Exception {
        Assumptions.assumeTrue(TestSupport.hasJava21Fixtures(), "Java 21 fixtures not compiled");
        PathExplorer.Result kind = explore("fixtures.modern.Shapes", "kind");
        assertEquals(List.of("instanceof Circle", "instanceof Square", "instanceof Rect"),
                kind.paths().stream().map(p -> p.conds().get(0).value()).toList());
        assertTrue(kind.notes().stream().anyMatch(n -> n.contains("exhaustive switch")));
        PathExplorer.Result weight = explore("fixtures.modern.Shapes", "weight");
        assertEquals(List.of("return 1", "return 10"), weight.paths().stream().map(p -> p.outcome().describe()).toList());
    }

    @Test
    void selfCallsAreInlined() throws Exception {
        PathExplorer.Result r = explore("fixtures.rules.ShippingFee", "fee");
        String all = r.paths().stream().map(p -> p.conds() + " -> " + p.outcome().describe()).collect(Collectors.joining(" ;; "));
        // the private helper's guard, its ternary and the getter's field all become decisions of fee(..)
        assertTrue(r.paths().stream().anyMatch(p -> p.outcome().kind() == Path.OutcomeKind.THROW
                && p.conds().stream().anyMatch(c -> c.key().equals("weightKg <= 0"))), all);
        assertTrue(r.paths().stream().anyMatch(p -> p.outcome().describe().equals("return 40")), all);
        assertTrue(r.paths().stream().anyMatch(p -> p.outcome().describe().equals("return 5")), all);
        assertTrue(r.paths().stream().anyMatch(p -> p.conds().stream().anyMatch(c -> c.key().equals("this.expressAvailable")
                || c.key().contains("this.expressAvailable"))), all);
        assertTrue(all.indexOf("baseFee") < 0 && all.indexOf("surcharge") < 0, all);
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("inlined")), r.notes().toString());

        String local = TestSupport.run("truth_table", "className", "ShippingFee", "method", "fee", "inlineSelfCalls", false);
        assertTrue(local.contains("this.baseFee(weightKg)"), local);
    }

    @Test
    void selfCallLoopDecidesCallerOutcome() throws Exception {
        PathExplorer.Result r = explore("fixtures.clinic.Owner", "addVisit");
        String all = r.paths().stream().map(p -> p.conds() + " -> " + p.effects() + " -> " + p.outcome().describe()).collect(Collectors.joining(" ;; "));
        // the matching pet is found inside getPet(Integer) and receives the visit
        assertTrue(r.paths().stream().anyMatch(p -> p.outcome().kind() == Path.OutcomeKind.RETURN_VOID
                && p.conds().stream().anyMatch(c -> c.key().contains("hasNext()") && c.isTrue())
                && p.effects().stream().anyMatch(e -> e.text().contains("addVisit(description)"))), all);
        assertFalse(all.contains("this.getPet("), all);
        // Objects.requireNonNull(pet, ..) after the loop: no pet found → NPE, and no path dereferences null
        assertTrue(r.paths().stream().anyMatch(p -> p.outcome().describe().contains("Invalid Pet identifier!")), all);
        assertFalse(all.contains("null.addVisit"), all);
    }

    @Test
    void preconditionHelpersBranchToTheirException() throws Exception {
        PathExplorer.Result r = explore("fixtures.rules.ShippingFee", "quote");
        List<String> outcomes = r.paths().stream().map(p -> p.outcome().describe()).toList();
        assertTrue(outcomes.contains("throw new IllegalArgumentException(\"zone is required\")"), outcomes.toString());
        assertTrue(outcomes.contains("throw new IllegalArgumentException(\"too heavy\")"), outcomes.toString());
        assertTrue(outcomes.contains("throw new IllegalArgumentException(\"weight must be positive\")"), outcomes.toString());
        assertTrue(outcomes.contains("return 5") && outcomes.contains("return 20"), outcomes.toString());
        assertTrue(r.paths().stream().anyMatch(p -> p.conds().stream().anyMatch(c -> c.key().equals("StringUtils.hasText(zone)"))),
                r.paths().stream().map(p -> p.conds().toString()).collect(Collectors.joining(" ;; ")));
    }

    @Test
    void exceptionsInsideTryBlocksReachTheirHandlers() throws Exception {
        PathExplorer.Result open = explore("fixtures.legacy.AccountService", "open");
        String all = open.paths().stream().map(p -> p.conds() + " -> " + p.outcome().describe()).collect(Collectors.joining(" ;; "));
        // checked exception declared by the DAO, wrapped and rethrown by the handler
        assertTrue(open.paths().stream().anyMatch(p -> p.outcome().describe().startsWith("throw new IllegalStateException")
                && p.conds().stream().anyMatch(c -> c.key().equals("this.dao.save(account) throws SQLException"))), all);
        // the JDK parser guard: failing input goes to the handler, the happy path records that it did not throw
        assertTrue(open.paths().stream().anyMatch(p -> p.outcome().describe().equals("return \"invalid-amount\"")), all);
        assertTrue(open.paths().stream().anyMatch(p -> p.outcome().describe().equals("return \"ok\"")
                && p.conds().stream().anyMatch(c -> c.describe().equals("Integer.parseInt(initialDeposit) does not throw NumberFormatException"))), all);

        PathExplorer.Result close = explore("fixtures.legacy.AccountService", "close");
        List<String> outcomes = close.paths().stream().map(p -> p.outcome().describe()).toList();
        // a throw inside the inlined private helper is caught by the caller's handler: no path escapes with it
        assertTrue(outcomes.stream().noneMatch(o -> o.startsWith("throw")), outcomes.toString());
        assertTrue(close.paths().stream().anyMatch(p -> p.outcome().describe().equals("return false")
                && p.conds().stream().anyMatch(c -> c.key().equals("account.getBalance() != 0") && c.isTrue())), outcomes.toString());
        assertTrue(close.paths().stream().anyMatch(p -> p.conds().stream().anyMatch(c -> c.key().equals("this.dao.find(id) throws IllegalStateException"))), outcomes.toString());
    }

    @Test
    void constructorValidationAndEnumRulesAreFollowed() throws Exception {
        PathExplorer.Result r = explore("fixtures.legacy.ShipmentEventFactory", "create");
        String all = r.paths().stream().map(p -> p.conds().stream().map(c -> c.describe()).toList() + " -> " + p.outcome().describe())
                .collect(Collectors.joining("\n"));
        // the enum rule is evaluated per constant: LOAD/UNLOAD require a carrier, RECEIVE/CLAIM prohibit one
        assertTrue(r.paths().stream().anyMatch(p -> p.outcome().describe().startsWith("return new ShipmentEvent(trackingId, type)")
                && p.conds().stream().anyMatch(c -> c.describe().equals("type in {Type.RECEIVE, Type.CLAIM}"))), all);
        assertTrue(r.paths().stream().anyMatch(p -> p.outcome().describe().startsWith("return new ShipmentEvent(trackingId, type, carrier)")
                && p.conds().stream().anyMatch(c -> c.describe().equals("type in {Type.LOAD, Type.UNLOAD}"))), all);
        // the constructor's guards and its throw reach the factory's catch (Exception) handler
        long wrapped = r.paths().stream().filter(p -> p.outcome().describe().startsWith("throw new InvalidEventException")).count();
        assertTrue(wrapped >= 6, all);
        // the constructor's own field writes are not effects of the factory
        assertTrue(r.paths().stream().allMatch(p -> p.effects().isEmpty()), all);
        // no generic "constructor throws RuntimeException" guess once the body is followed
        assertTrue(r.paths().stream().noneMatch(p -> p.conds().stream().anyMatch(c -> c.kind() == dev.truthseeker.symbolic.Cond.Kind.THROWS)), all);
    }
}
