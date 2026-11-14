package fixtures.order;

import java.math.BigDecimal;

public interface PaymentGateway {
    boolean charge(String customerId, BigDecimal amount);

    void refund(String customerId, BigDecimal amount);
}
