package fixtures.legacy;

import java.util.Optional;

/** A hand-written equals, and an update that only touches the password when one was entered. */
public class Profile {

    public interface Form {
        String getPassword();

        String getNickname();
    }

    public interface Store {
        void saveDetails(Form form);

        void savePassword(String password);
    }

    private final Store store;
    private String login;

    public Profile(Store store) {
        this.store = store;
    }

    public void update(Form form) {
        store.saveDetails(form);
        Optional.ofNullable(form.getPassword()).filter(p -> !p.isEmpty()).ifPresent(p -> store.savePassword(p));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null) {
            return false;
        }
        if (!(o instanceof Profile)) {
            return false;
        }
        Profile that = (Profile) o;
        return login != null && login.equals(that.login);
    }

    @Override
    public int hashCode() {
        return login == null ? 0 : login.hashCode();
    }
}
