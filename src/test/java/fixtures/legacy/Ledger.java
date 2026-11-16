package fixtures.legacy;

import java.util.ArrayList;
import java.util.List;

/** Running totals and counters kept in fields, and entries numbered by the list they go into. */
public class Ledger {

    public static class Entry {
        private final int number;
        private final int amount;
        private final boolean credit;
        private int fee;

        public Entry(int number, int amount, boolean credit) {
            this.number = number;
            this.amount = amount;
            this.credit = credit;
            computeFee();
        }

        private void computeFee() {
            fee = amount > 100 ? amount / 100 : 0;
        }

        public int getNumber() { return number; }

        public int getAmount() { return amount; }

        public boolean isCredit() { return credit; }

        public int getFee() { return fee; }
    }

    private final List<Entry> entries = new ArrayList<>();
    private long balance;
    private int postings;
    private boolean frozen;

    public void post(int amount, boolean credit) {
        entries.add(new Entry(entries.size() + 1, amount, credit));
        balance = balance + amount;
        postings++;
    }

    public void freeze(boolean frozen) {
        this.frozen = frozen;
    }

    public long getBalance() {
        return balance;
    }
}
