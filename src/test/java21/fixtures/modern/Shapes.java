package fixtures.modern;

/** Java 21 features: sealed hierarchy, records, pattern-matching switch (typeSwitch/enumSwitch indy). */
public final class Shapes {

    public sealed interface Shape permits Circle, Square, Rect {}

    public record Circle(double r) implements Shape {}

    public record Square(double side) implements Shape {}

    public record Rect(double w, double h) implements Shape {}

    public enum Size { SMALL, LARGE }

    private Shapes() {}

    public static String kind(Shape s) {
        return switch (s) {
            case Circle c -> "round";
            case Square q -> "square";
            case Rect r -> "rect";
        };
    }

    public static int weight(Size size) {
        return switch (size) {
            case SMALL -> 1;
            case LARGE -> 10;
        };
    }
}
