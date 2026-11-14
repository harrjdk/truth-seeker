package dev.truthseeker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StateMachineTest {

    @Test
    void serviceDrivenTransitionsOnAnemicEntity() {
        String md = TestSupport.run("state_machine", "packagePrefix", "fixtures.order", "enumType", "OrderStatus");
        assertTrue(md.contains("[*] --> NEW"), md);
        assertTrue(md.contains("NEW --> PAID : OrderService.pay()"), md);
        assertTrue(md.contains("PAID --> SHIPPED : OrderService.ship()"), md);
        assertTrue(md.contains("DELIVERED --> REFUNDED : OrderService.refund()"), md);
        assertTrue(md.contains("Unguarded transitions") && md.contains("OrderService.markDelivered()"), md);
        assertTrue(md.contains("Never entered by analyzed code: ARCHIVED"), md);
        assertTrue(md.contains("`OrderService.ship()` in NEW"), md);
        // validation failure after the state check is not a state rejection
        assertFalse(md.contains("amount must be positive"), md);
    }

    @Test
    void richDomainObjectWithDirectOrdinalSwitch() {
        String md = TestSupport.run("state_machine", "className", "Door", "format", "both");
        assertTrue(md.contains("[*] --> CLOSED"), md);
        assertTrue(md.contains("CLOSED --> LOCKED : Door.lock() [key != null && !key.isEmpty()]"), md);
        assertTrue(md.contains("LOCKED --> CLOSED : Door.unlock()"), md);
        assertFalse(md.contains("OPEN --> LOCKED"), md);
        assertTrue(md.contains("@startuml"), md);
        assertTrue(md.contains("`Door.open()` in LOCKED"), md);
    }

    @Test
    void enumNextMethod() {
        String md = TestSupport.run("state_machine", "className", "TrafficLight");
        assertTrue(md.contains("RED --> GREEN"), md);
        assertTrue(md.contains("YELLOW --> RED"), md);
        assertTrue(md.contains("FLASHING --> FLASHING"), md);
        assertFalse(md.contains("RED --> FLASHING"), md);
    }

    @Test
    void jsonFormat() {
        String json = TestSupport.run("state_machine", "className", "Door", "format", "json");
        assertTrue(json.startsWith("[{\"enum\":\"fixtures.door.Door$State\""), json);
    }
}
