package fixtures.legacy;

import java.util.Optional;

/** Error paths of a typical controller: a lookup that may find nothing, a save that may clash with an existing row. */
public class AccountDesk {

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static class DuplicateKeyException extends RuntimeException {
        public DuplicateKeyException(String message) {
            super(message);
        }
    }

    public interface Accounts {
        Optional<Account> findById(String id);

        void save(Account account);
    }

    public interface Errors {
        void rejectValue(String field, String code);
    }

    private final Accounts accounts;

    public AccountDesk(Accounts accounts) {
        this.accounts = accounts;
    }

    public Account load(String id) {
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("No account " + id));
    }

    public Account loadOrFail(String id) {
        return accounts.findById(id).orElseThrow(IllegalStateException::new);
    }

    public String register(Account account, String name, Errors errors) {
        if (name == null || name.isEmpty()) {
            errors.rejectValue("name", "required");
        }
        try {
            accounts.save(account);
        } catch (DuplicateKeyException ex) {
            errors.rejectValue("name", "required");
            return "form";
        }
        return "done";
    }
}
