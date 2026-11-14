package jakarta.ejb;

@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface MessageDriven {
    ActivationConfigProperty[] activationConfig() default {};

    String mappedName() default "";
}
