package fixtures.rules;

import org.springframework.util.Assert;

/** Decisions hidden behind calls on the class's own methods: a private helper, a static helper and a getter. */
public class ShippingFee {
    private boolean expressAvailable = true;

    public boolean isExpressAvailable() {
        return expressAvailable;
    }

    public void setExpressAvailable(boolean expressAvailable) {
        this.expressAvailable = expressAvailable;
    }

    public int fee(int weightKg, boolean express) {
        int base = baseFee(weightKg);
        if (express && isExpressAvailable()) {
            return surcharge(base);
        }
        return base;
    }

    private int baseFee(int weightKg) {
        if (weightKg <= 0) {
            throw new IllegalArgumentException("weight must be positive");
        }
        return weightKg > 10 ? 20 : 5;
    }

    private static int surcharge(int base) {
        return base * 2;
    }

    /** Spring-style preconditions in front of a self-call two levels deep (quote → fee → baseFee). */
    public int quote(String zone, int weightKg) {
        Assert.hasText(zone, "zone is required");
        Assert.isTrue(weightKg < 1000, "too heavy");
        return fee(weightKg, false);
    }
}
