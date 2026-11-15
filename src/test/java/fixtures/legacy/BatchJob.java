package fixtures.legacy;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** Java EE 7 sample-shaped code: listeners, polling loops, string switches and lifecycle-initialised collaborators. */
public class BatchJob {

    public interface Output {
        PrintWriter getWriter() throws IOException;
    }

    public interface Executor {
        Future<String> submit(String task);
    }

    public interface Handler {
        void handle(String message);
    }

    public static class Record {
        private final int id;

        public Record(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }
    }

    private Integer id;
    private Handler handler;
    private final Executor executor;

    public BatchJob(Executor executor) {
        this.executor = executor;
    }

    /** Container lifecycle: the handler arrives after construction. */
    public void initialize(Handler handler) {
        this.handler = handler;
    }

    public void notifyDone(String message) {
        handler.handle(message);
    }

    public String language(String code) {
        if (code == null) {
            return null;
        }
        switch (code) {
            case "en":
                return "English";
            case "de":
                return "German";
            default:
                return "unknown";
        }
    }

    public boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    public String await(Output out) throws IOException, ExecutionException, InterruptedException {
        try (PrintWriter w = out.getWriter()) {
            w.println("start");
            Future<String> f = executor.submit("job");
            while (!f.isDone()) {
                Thread.sleep(1);
            }
            w.println("done");
            return f.get();
        }
    }

    public String onSkip(Exception e) {
        return "skipped: " + e.getMessage();
    }

    public Object process(Object item) {
        return new Record(((Record) item).getId() + 1);
    }

    public String render(String text) throws IOException {
        StringWriter target = new StringWriter();
        try (BufferedWriter writer = new BufferedWriter(target)) {
            writer.write(text);
        }
        return target.toString();
    }

    public Class<?>[] supportedTypes() {
        return new Class<?>[] {String.class, Integer.class};
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}
