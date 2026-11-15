package fixtures.legacy;

/** Builds events from raw input; any validation failure in the entity becomes a checked exception. */
public class ShipmentEventFactory {

    public static class InvalidEventException extends Exception {
        public InvalidEventException(Throwable cause) {
            super(cause);
        }
    }

    public ShipmentEvent create(String trackingId, String carrier, ShipmentEvent.Type type) throws InvalidEventException {
        try {
            if (carrier == null) {
                return new ShipmentEvent(trackingId, type);
            }
            return new ShipmentEvent(trackingId, type, carrier);
        } catch (Exception e) {
            throw new InvalidEventException(e);
        }
    }

    /** Fills a new bean through its setters and returns it. */
    public Waybill draft(String reference, boolean express) {
        Waybill waybill = new Waybill();
        waybill.setReference(reference);
        if (express) {
            waybill.setPriority(1);
        }
        return waybill;
    }
}
