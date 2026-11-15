package fixtures.legacy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Work done inside forEach lambdas, and objects built here and handed on rather than returned. */
public class OrderDesk {

    public interface Purchase {
        List<Line> getLines();
    }

    public interface LineDao {
        void insert(Line line);
    }

    public interface AuditLog {
        void record(Entry entry);
    }

    public static class Line {
        private String sku;

        public String getSku() {
            return sku;
        }

        public void setSku(String sku) {
            this.sku = sku;
        }
    }

    public static class Entry {
        private final String action;
        private final int count;

        public Entry(String action, int count) {
            this.action = action;
            this.count = count;
        }

        public String getAction() {
            return action;
        }

        public int getCount() {
            return count;
        }
    }

    private final LineDao dao;
    private final AuditLog audit;
    private final List<Entry> history = new ArrayList<>();
    private final Map<String, Entry> latest = new HashMap<>();

    public OrderDesk(LineDao dao, AuditLog audit) {
        this.dao = dao;
        this.audit = audit;
    }

    public void saveAll(Purchase order) {
        order.getLines().forEach(line -> {
            line.setSku("SKU-" + line.getSku());
            dao.insert(line);
        });
    }

    public void log(String action, int count) {
        Entry entry = new Entry(action, count + 1);
        audit.record(entry);
        history.add(entry);
        latest.put(action, entry);
    }
}
