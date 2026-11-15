package fixtures.legacy;

public class Account {
    private final String id;
    private final int balance;

    public Account(String id, int balance) {
        this.id = id;
        this.balance = balance;
    }

    public String getId() { return id; }

    public int getBalance() { return balance; }
}
