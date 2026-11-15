package fixtures.legacy;

import java.sql.SQLException;
import java.util.List;

/** Legacy try/catch shapes: a parse guard, a wrapped checked exception, and a throw inside a helper caught by its caller. */
public class AccountService {
    private final AccountDao dao;

    public AccountService(AccountDao dao) {
        this.dao = dao;
    }

    public String open(String id, String initialDeposit) {
        int amount;
        try {
            amount = Integer.parseInt(initialDeposit);
        } catch (NumberFormatException e) {
            return "invalid-amount";
        }
        Account account = new Account(id, amount);
        try {
            dao.save(account);
        } catch (SQLException e) {
            throw new IllegalStateException("could not save " + id, e);
        }
        return "ok";
    }

    public boolean close(String id) {
        try {
            Account account = dao.find(id);
            if (account == null) {
                return false;
            }
            remove(account);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private void remove(Account account) {
        if (account.getBalance() != 0) {
            throw new IllegalStateException("balance must be zero");
        }
        dao.delete(account);
    }

    /** The handler inspects the caught exception before deciding (Spring's duplicate-key idiom). */
    public String rename(String id, String name) {
        try {
            dao.rename(id, name);
            return "ok";
        } catch (IllegalStateException e) {
            String message = e.getMessage();
            if (message != null && message.toLowerCase().contains("duplicate")) {
                return "duplicate";
            }
            throw e;
        }
    }

    /** Pagination guard: page beyond the last page (at least one) is rejected. */
    public int page(int page) {
        int total = dao.count();
        if (page > Math.max(total, 1)) {
            return -1;
        }
        return page;
    }

    public String firstId(List<Account> accounts) {
        if (accounts.isEmpty()) {
            return null;
        }
        return accounts.get(0).getId();
    }
}
