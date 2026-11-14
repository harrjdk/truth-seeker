package jakarta.ejb;

// Test stub with the real annotation's name; truth-seeker matches annotations by name only.
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface ActivationConfigProperty {
    String propertyName();

    String propertyValue();
}
