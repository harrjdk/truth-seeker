package fixtures.legacy;

import org.springframework.util.Assert;

/** An entity whose constructor validates its arguments, with the rule held by an enum constant. */
public class ShipmentEvent {

    public enum Type {
        LOAD(true), UNLOAD(true), RECEIVE(false), CLAIM(false);

        private final boolean carrierRequired;

        Type(boolean carrierRequired) {
            this.carrierRequired = carrierRequired;
        }

        public boolean requiresCarrier() {
            return carrierRequired;
        }

        public boolean prohibitsCarrier() {
            return !requiresCarrier();
        }
    }

    private final String trackingId;
    private final Type type;
    private final String carrier;

    public ShipmentEvent(String trackingId, Type type) {
        Assert.hasText(trackingId, "tracking id is required");
        Assert.notNull(type, "type is required");
        if (type.requiresCarrier()) {
            throw new IllegalArgumentException("a carrier is required for " + type);
        }
        this.trackingId = trackingId;
        this.type = type;
        this.carrier = null;
    }

    public ShipmentEvent(String trackingId, Type type, String carrier) {
        Assert.hasText(trackingId, "tracking id is required");
        Assert.notNull(type, "type is required");
        if (type.prohibitsCarrier()) {
            throw new IllegalArgumentException("no carrier is allowed for " + type);
        }
        this.trackingId = trackingId;
        this.type = type;
        this.carrier = carrier;
    }

    public String getTrackingId() {
        return trackingId;
    }

    public Type getType() {
        return type;
    }

    public String getCarrier() {
        return carrier;
    }
}
