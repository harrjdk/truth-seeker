package dev.truthseeker;

import dev.truthseeker.archive.ClassRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class InventoryToolsTest {

    @Test
    void scanFindsStereotypes() {
        String md = TestSupport.run("scan_archive");
        assertTrue(md.contains("Spring @Service: 1"), md);
        assertTrue(md.contains("Spring @RestController: 1"), md);
        assertTrue(md.contains("`fixtures.order.OrderService` | Spring @Service"), md);
    }

    @Test
    void endpointsCombineClassAndMethodMappings() {
        String md = TestSupport.run("list_endpoints", "packagePrefix", "fixtures");
        assertTrue(md.contains("| HTTP (Spring MVC) | POST | `/orders/{id}/pay` | `OrderController.pay()`"), md);
        assertTrue(md.contains("| HTTP (Spring MVC) | GET | `/orders/{id}/retries`"), md);
    }

    @Test
    void describeShowsInjectionStyles() {
        String md = TestSupport.run("describe_class", "className", "OrderService");
        assertTrue(md.contains("| `orderRepository` | `OrderRepository` | @Autowired |"), md);
        assertTrue(md.contains("| `notifier` | `Notifier` | constructor |"), md);
        assertTrue(md.contains("`boolean pay(long, BigDecimal)`"), md);
    }

    @Test
    void callGraphExpandsInterfaceToImplementation() {
        String md = TestSupport.run("call_graph", "className", "OrderController", "method", "pay", "depth", 2);
        assertTrue(md.contains("OrderService.pay(long,BigDecimal)"), md);
        assertTrue(md.contains("Order.setStatus(OrderStatus)"), md);
        String in = TestSupport.run("call_graph", "className", "Order", "method", "setStatus", "direction", "in");
        assertTrue(in.contains("OrderService.ship(Order) → Order.setStatus(OrderStatus)"), in);
    }

    @Test
    void disassembleSingleMethod() {
        String md = TestSupport.run("disassemble", "className", "Eligibility", "method", "fee");
        assertTrue(md.contains("LCMP"), md);
        assertFalse(md.contains("isEligible"), md);
    }

    @Test
    void readsNestedWarWithDescriptorsAndLibraries(@TempDir Path tmp) throws Exception {
        byte[] eligibility = Files.readAllBytes(TestSupport.fixtures().resolve("fixtures/rules/Eligibility.class"));
        byte[] notifier = Files.readAllBytes(TestSupport.fixtures().resolve("fixtures/order/Notifier.class"));
        ByteArrayOutputStream libJar = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(libJar)) {
            put(z, "fixtures/order/Notifier.class", notifier);
        }
        String webXml = """
                <web-app>
                  <servlet><servlet-name>legacy</servlet-name><servlet-class>com.acme.web.LegacyServlet</servlet-class></servlet>
                  <servlet-mapping><servlet-name>legacy</servlet-name><url-pattern>/legacy/*</url-pattern></servlet-mapping>
                  <!-- <servlet-mapping><servlet-name>legacy</servlet-name><url-pattern>/commented-out</url-pattern></servlet-mapping> -->
                </web-app>""";
        String struts = """
                <struts-config><action-mappings>
                  <action path="/checkout" type="com.acme.web.CheckoutAction" name="cartForm"/>
                </action-mappings></struts-config>""";
        Path war = tmp.resolve("app.war");
        try (OutputStream out = Files.newOutputStream(war); ZipOutputStream z = new ZipOutputStream(out)) {
            put(z, "WEB-INF/classes/fixtures/rules/Eligibility.class", eligibility);
            put(z, "WEB-INF/lib/notify.jar", libJar.toByteArray());
            put(z, "WEB-INF/web.xml", webXml.getBytes());
            put(z, "WEB-INF/struts-config.xml", struts.getBytes());
        }
        ClassRepository repo = ClassRepository.open(war.toString());
        assertFalse(repo.entry("fixtures/rules/Eligibility").orElseThrow().library());
        assertTrue(repo.entry("fixtures/order/Notifier").orElseThrow().library());
        assertEquals("app.war!/WEB-INF/lib/notify.jar!/fixtures/order/Notifier.class", repo.entry("fixtures/order/Notifier").orElseThrow().origin());

        Tools.ToolDef endpoints = Tools.all().stream().filter(t -> t.name().equals("list_endpoints")).findFirst().orElseThrow();
        String md = endpoints.handler().apply(Map.of("path", war.toString()));
        assertTrue(md.contains("`/legacy/*` | `legacy → LegacyServlet`"), md);
        assertTrue(md.contains("`/checkout` | `CheckoutAction`"), md);
        assertFalse(md.contains("commented-out"), md);
    }

    @Test
    void springBootLauncherClassesAreNotApplicationCode(@TempDir Path tmp) throws Exception {
        byte[] app = Files.readAllBytes(TestSupport.fixtures().resolve("fixtures/rules/Eligibility.class"));
        byte[] launcher = Files.readAllBytes(TestSupport.fixtures().resolve("fixtures/door/Door.class"));
        Path jar = tmp.resolve("app.jar");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream z = new ZipOutputStream(out)) {
            put(z, "BOOT-INF/classes/fixtures/rules/Eligibility.class", app);
            put(z, "org/springframework/boot/loader/launch/JarLauncher.class", launcher); // stand-in bytes
        }
        ClassRepository repo = ClassRepository.open(jar.toString());
        assertFalse(repo.entry("fixtures/rules/Eligibility").orElseThrow().library());
        assertTrue(repo.entry("fixtures/door/Door").orElseThrow().library());
    }

    @Test
    void jakartaEndpointsShowQueuesAndApplicationPath() {
        String md = TestSupport.run("list_endpoints", "packagePrefix", "fixtures.shipping");
        assertTrue(md.contains("| JMS (EJB MDB) | consume | `java:app/jms/CargoHandledQueue (Queue)` | `CargoHandledConsumer.onMessage()`"), md);
        assertTrue(md.contains("| HTTP (JAX-RS) | GET | `/rest/tracking/{id}` | `TrackingResource.track()`"), md);
    }

    @Test
    void noStateMachinesMessage() {
        String md = TestSupport.run("state_machine", "packagePrefix", "fixtures.clinic");
        assertTrue(md.contains("No state machines inferred"), md);
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }
}
