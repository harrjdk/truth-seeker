package fixtures.legacy;

/** A Spring-style Validator: each branch rejects a different field on the Errors it is handed. */
public class SignupValidator {

    public interface Errors {
        void rejectValue(String field, String code);
    }

    public interface Binder {
        void setDisallowedFields(String... fields);
    }

    public interface Signup {
        String getEmail();

        int getAge();
    }

    public void validate(Signup signup, Errors errors) {
        String email = signup.getEmail();
        if (email == null || email.isEmpty()) {
            errors.rejectValue("email", "required");
        }
        if (signup.getAge() < 18) {
            errors.rejectValue("age", "tooYoung");
        }
    }

    public void initBinder(Binder binder) {
        binder.setDisallowedFields("id", "*.id");
    }
}
