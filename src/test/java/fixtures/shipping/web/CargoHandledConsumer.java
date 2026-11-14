package fixtures.shipping.web;

import jakarta.ejb.ActivationConfigProperty;
import jakarta.ejb.MessageDriven;

@MessageDriven(activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "jakarta.jms.Queue"),
        @ActivationConfigProperty(propertyName = "destinationLookup", propertyValue = "java:app/jms/CargoHandledQueue")})
public class CargoHandledConsumer {

    public void onMessage(Object message) {
        // no-op
    }
}
