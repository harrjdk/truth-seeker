package fixtures.legacy;

import java.sql.SQLException;

/** JDBC-era DAO: checked exceptions on writes, unchecked on reads. */
public interface AccountDao {
    void save(Account account) throws SQLException;

    Account find(String id);

    void delete(Account account);

    void rename(String id, String name);

    int count();
}
