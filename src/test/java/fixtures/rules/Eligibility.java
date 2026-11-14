package fixtures.rules;

/** Classic legacy business rules: compound conditions, else-if ladders, char switches. */
public final class Eligibility {

    private Eligibility() {}

    public static boolean isEligible(int age, boolean banned, boolean vip, String country) {
        return vip || (age >= 18 && !banned && "US".equals(country));
    }

    public static int discountPercent(int quantity, boolean member) {
        if (quantity > 100) {
            return member ? 20 : 15;
        } else if (quantity > 10) {
            return member ? 10 : 5;
        }
        return 0;
    }

    public static String grade(char letter) {
        switch (letter) {
            case 'A':
            case 'B':
                return "pass-high";
            case 'C':
                return "pass";
            default:
                return "fail";
        }
    }

    public static long fee(long balance) {
        if (balance < 0L) {
            throw new IllegalArgumentException("negative balance");
        }
        if (balance > 10_000L) {
            return 0L;
        }
        return 25L;
    }
}
