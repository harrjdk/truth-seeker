package fixtures.order;

public interface Notifier {
    void send(String customerId, String message);
}
