package dev.truthseeker;

import dev.truthseeker.symbolic.Cond;
import dev.truthseeker.symbolic.PathExplorer;
import dev.truthseeker.testgen.TestGenerator;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Patterns found by running truth-seeker against MyBatis JPetStore. */
class ShopFixturesTest {

    private static PathExplorer.Result explore(String cls, String method) throws Exception {
        var repo = TestSupport.repo();
        var cn = repo.require(cls);
        MethodNode mn = cn.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
        return PathExplorer.explore(repo, cn, mn, PathExplorer.Options.defaults());
    }

    private static String describe(PathExplorer.Result r) {
        return r.paths().stream().map(p -> p.conds().stream().map(Cond::describe).toList() + " -> " + p.outcome().describe())
                .collect(Collectors.joining("\n"));
    }

    private static String generate(String cls) throws Exception {
        var repo = TestSupport.repo();
        return TestGenerator.generate(repo, repo.require(cls), new TestGenerator.Options(List.of(), 64, 24, false));
    }

    @Test
    void methodsOnObjectsBuiltOnThePathAreFollowed() throws Exception {
        PathExplorer.Result r = explore("fixtures.shop.BasketController", "removeItem");
        String all = describe(r);
        // a new Basket is empty: removing from it finds nothing, so only the error outcome follows `basket == null`
        assertTrue(r.paths().stream().filter(p -> p.conds().stream().anyMatch(c -> c.describe().equals("basket == null")))
                .allMatch(p -> p.outcome().describe().equals("return \"error\"")), all);
        PathExplorer.Result u = explore("fixtures.shop.BasketController", "updateQuantities");
        // and its iterator has nothing: one path for a new basket
        assertEquals(1, u.paths().stream().filter(p -> p.conds().stream().anyMatch(c -> c.describe().equals("basket == null"))).count(), describe(u));
    }

    @Test
    void onlyParsersAreAssumedToThrowNumberFormatException() throws Exception {
        PathExplorer.Result r = explore("fixtures.shop.BasketController", "updateQuantities");
        assertTrue(r.paths().stream().noneMatch(p -> p.conds().stream().anyMatch(c -> c.key().startsWith("items.remove()"))), describe(r));
        assertTrue(r.paths().stream().anyMatch(p -> p.conds().stream().anyMatch(c -> c.key().contains("Integer.parseInt") && c.isTrue())), describe(r));
    }


    @Test
    void shopFixturesGenerateWithoutTodos() throws Exception {
        for (String cls : List.of("fixtures.shop.Basket", "fixtures.shop.BasketController", "fixtures.shop.OrderLine", "fixtures.shop.Shelf")) {
            String code = generate(cls);
            assertFalse(code.contains("@Disabled("), cls + ":\n" + code);
        }
        String controller = generate("fixtures.shop.BasketController");
        // different session attributes are different stubs
        assertTrue(controller.contains("getAttribute(eq(\"basket\"))") && controller.contains("getAttribute(eq(\"account\"))"), controller);
        // a collaborator's Iterator is stubbed with a real one over mock elements
        assertTrue(controller.contains("getAllItems()).thenReturn(new ArrayList<>(List.of("), controller);
        // Integer.parseInt(request.getParameter(..)) < 1 is solved through the parameter text
        assertTrue(controller.contains("getParameter(any())).thenReturn(\"0\")")
                || controller.contains("getParameter(ArgumentMatchers.<String>any())).thenReturn(\"0\")"), controller);
        // keywords.split("\s+") with as many tokens as the loop takes
        assertTrue(controller.contains("String keywords = \"a\";") || controller.contains("String keywords = \"a b\";"), controller);
        String basket = generate("fixtures.shop.Basket");
        // lookups in the basket's own map get real entries
        assertTrue(basket.contains(".put("), basket);
        String line = generate("fixtures.shop.OrderLine");
        // built with its no-arg constructor: the other one reads item.getProduct().getId() from a mock
        assertTrue(line.contains("new OrderLine()"), line);
    }
}
