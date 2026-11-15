package fixtures.shop;

import java.math.BigDecimal;

/** LineItem-shaped: a no-arg constructor and one that reads through its argument's getters. */
public class OrderLine {
    private int lineNumber;
    private String productId;
    private BigDecimal unitPrice;
    private Product product;

    public OrderLine() {
    }

    public OrderLine(int lineNumber, BasketItem item) {
        this.lineNumber = lineNumber;
        this.productId = item.getProduct().getId();
        this.unitPrice = item.getProduct().getPrice();
        this.product = item.getProduct();
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getProductId() {
        return productId;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public Product getProduct() {
        return product;
    }
}
