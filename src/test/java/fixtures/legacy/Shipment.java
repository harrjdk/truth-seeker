package fixtures.legacy;

/** Fields set from a collaborator's answer, a static factory, a trimmed argument, and a flag the constructor already set. */
public class Shipment {

    public interface Status {
        Status advance(String event);
    }

    public static final class Label {
        private final String text;

        private Label(String text) {
            this.text = text;
        }

        public static Label of(String code) {
            return new Label("LBL-" + code);
        }

        public String getText() {
            return text;
        }
    }

    private Status status;
    private Label label;
    private String reference;
    private boolean insured = true;

    public Shipment(Status status) {
        this.status = status;
    }

    public void record(String event) {
        this.status = status.advance(event);
    }

    public void relabel(String code) {
        this.label = Label.of(code);
    }

    public void setReference(String reference) {
        this.reference = reference.trim();
    }

    public void setInsured(boolean insured) {
        this.insured = insured;
    }
}
