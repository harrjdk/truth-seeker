package fixtures.order;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

/** Service-layer state transitions over an anemic entity, the way a lot of 2008-2015 Spring code looks. */
@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentGateway paymentGateway;

    private final Notifier notifier;

    private int maxRetries = 3;

    public OrderService(Notifier notifier) {
        this.notifier = notifier;
    }

    public boolean pay(long orderId, BigDecimal amount) {
        Optional<Order> found = orderRepository.findById(orderId);
        if (!found.isPresent()) {
            throw new IllegalArgumentException("No order " + orderId);
        }
        Order order = found.get();
        if (order.getStatus() != OrderStatus.NEW) {
            throw new IllegalStateException("Order " + orderId + " is " + order.getStatus());
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        boolean charged = paymentGateway.charge(order.getCustomerId(), amount);
        if (charged) {
            order.setStatus(OrderStatus.PAID);
            orderRepository.save(order);
            notifier.send(order.getCustomerId(), "Payment received");
        }
        return charged;
    }

    public void ship(Order order) {
        switch (order.getStatus()) {
            case PAID:
                order.setStatus(OrderStatus.SHIPPED);
                orderRepository.save(order);
                break;
            case NEW:
                throw new IllegalStateException("not paid yet");
            default:
                throw new IllegalStateException("cannot ship from " + order.getStatus());
        }
    }

    /** Bug-shaped on purpose: no guard, so any order (even CANCELLED) can be marked delivered. */
    public void markDelivered(Order order) {
        order.setStatus(OrderStatus.DELIVERED);
        orderRepository.save(order);
    }

    public void cancel(Order order) {
        if (order.getStatus() == OrderStatus.SHIPPED || order.getStatus() == OrderStatus.DELIVERED) {
            throw new IllegalStateException("too late to cancel");
        }
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
    }

    public void refund(Order order) {
        if (order.getStatus() == OrderStatus.PAID || order.getStatus() == OrderStatus.DELIVERED) {
            paymentGateway.refund(order.getCustomerId(), order.getTotal());
            order.setStatus(OrderStatus.REFUNDED);
            orderRepository.save(order);
        }
    }

    public int retriesLeft(int attempts) {
        if (attempts >= maxRetries) {
            return 0;
        }
        return maxRetries - attempts;
    }
}
