package fixtures.legacy;

import java.io.IOException;
import java.io.InputStream;
import java.util.AbstractList;
import java.util.Properties;

/** Cleanup in finally blocks, and exceptions from inherited methods that are not followed. */
public class Cleanup {

    public interface Lock {
        void lock();

        void unlock();
    }

    public interface Store {
        void save(String value);
    }

    private Lock lock;
    private Store store;

    public void setLock(Lock lock) {
        this.lock = lock;
    }

    public void setStore(Store store) {
        this.store = store;
    }

    /** The lock is released whether or not the save throws. */
    public void write(String value) {
        lock.lock();
        try {
            store.save(value);
        } finally {
            lock.unlock();
        }
    }

    /** synchronized: its compiler-made handler only releases the monitor. */
    public synchronized void writeQuietly(String value) {
        store.save(value);
    }

    /** A legacy configuration class: Properties.load(..) may throw IOException. */
    public static class Settings extends Properties {
        private Store store;

        public void setStore(Store store) {
            this.store = store;
        }

        public boolean reload(InputStream in) {
            try {
                load(in);
                return true;
            } catch (IOException e) {
                store.save("defaults");
                return false;
            } finally {
                store.save("reloaded");
            }
        }
    }

    /** A read-only list: AbstractList.set(..) always throws UnsupportedOperationException. */
    public static class Rows extends AbstractList<String> {
        private Store store;

        public void setStore(Store store) {
            this.store = store;
        }

        @Override
        public String get(int index) {
            return "row" + index;
        }

        @Override
        public int size() {
            return 1;
        }

        public String rename(int index, String name) {
            try {
                return set(index, name);
            } catch (UnsupportedOperationException e) {
                store.save("read-only");
                return null;
            }
        }
    }
}
