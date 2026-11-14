package fixtures.order;

import java.math.BigDecimal;

/** Anemic JPA-style entity: all behaviour lives in OrderService (very common in legacy Spring/EJB apps). */
public class Order {
    private Long id;
    private String customerId;
    private BigDecimal total;
    private OrderStatus status = OrderStatus.NEW;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
}
