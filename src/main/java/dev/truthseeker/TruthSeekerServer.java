package dev.truthseeker;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point. Default: MCP server over stdio. With {@code --cli <tool> key=value ...}: run one tool
 * and print its output (handy for scripting and for checking results without an MCP client).
 */
public final class TruthSeekerServer {

    public static final String VERSION = "0.1.0";

    public static void main(String[] args) throws Exception {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", System.getProperty("truthseeker.log", "warn"));
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
        if (args.length > 0 && (args[0].equals("--cli") || args[0].equals("cli"))) {
            System.exit(cli(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("-h"))) {
            usage(System.out);
            return;
        }
        serveStdio();
    }

    private static void serveStdio() throws InterruptedException {
        // Protect the protocol channel: anything accidentally printed to System.out goes to stderr instead.
        PrintStream protocolOut = System.out;
        System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        CountDownLatch closed = new CountDownLatch(1);
        InputStream in = new EofSignallingInputStream(System.in, closed::countDown);

        McpJsonMapper mapper = McpJsonDefaults.getMapper();
        StdioServerTransportProvider transport = new StdioServerTransportProvider(mapper, in, protocolOut);
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        for (Tools.ToolDef def : Tools.all()) {
            McpSchema.Tool tool = McpSchema.Tool.builder()
                    .name(def.name())
                    .title(def.title())
                    .description(def.description())
                    .inputSchema(mapper, def.schema())
                    .annotations(new McpSchema.ToolAnnotations(def.title(), !def.name().equals("generate_unit_tests"), false, true, false, null))
                    .build();
            specs.add(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(tool)
                    .callHandler((exchange, request) -> call(def, request.arguments()))
                    .build());
        }
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("truth-seeker", VERSION)
                .instructions(Tools.instructions())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(specs)
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
        closed.await();
        server.closeGracefully();
    }

    static McpSchema.CallToolResult call(Tools.ToolDef def, Map<String, Object> args) {
        try {
            String out = def.handler().apply(args == null ? Map.of() : args);
            return McpSchema.CallToolResult.builder().addTextContent(out).isError(false).build();
        } catch (IllegalArgumentException e) {
            return McpSchema.CallToolResult.builder().addTextContent("Error: " + e.getMessage()).isError(true).build();
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Internal error in " + def.name() + ": " + e + "\nPlease report with the class/method that triggered it.")
                    .isError(true).build();
        }
    }

    private static int cli(String[] args) {
        if (args.length == 0) {
            usage(System.err);
            return 2;
        }
        Optional<Tools.ToolDef> def = Tools.all().stream().filter(t -> t.name().equals(args[0])).findFirst();
        if (def.isEmpty()) {
            System.err.println("Unknown tool '" + args[0] + "'");
            usage(System.err);
            return 2;
        }
        Map<String, Object> a = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq <= 0) {
                System.err.println("Arguments must be key=value, got: " + args[i]);
                return 2;
            }
            String k = args[i].substring(0, eq), v = args[i].substring(eq + 1);
            if (k.equals("methods")) a.put(k, List.of(v.split(",")));
            else a.put(k, v);
        }
        McpSchema.CallToolResult r = call(def.get(), a);
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        for (McpSchema.Content c : r.content()) if (c instanceof McpSchema.TextContent t) out.println(t.text());
        return Boolean.TRUE.equals(r.isError()) ? 1 : 0;
    }

    private static void usage(PrintStream ps) {
        ps.println("truth-seeker " + VERSION + " - MCP server for Java bytecode analysis");
        ps.println("  java -jar truth-seeker.jar                      run as an MCP server on stdio");
        ps.println("  java -jar truth-seeker.jar --cli <tool> k=v ... run one tool, print the result");
        ps.println("Tools:");
        for (Tools.ToolDef t : Tools.all()) ps.println("  " + t.name());
    }

    /** Lets the process exit when the MCP client closes stdin. */
    private static final class EofSignallingInputStream extends InputStream {
        private final InputStream in;
        private final Runnable onEof;

        EofSignallingInputStream(InputStream in, Runnable onEof) {
            this.in = in;
            this.onEof = onEof;
        }

        @Override
        public int read() throws java.io.IOException {
            int b = in.read();
            if (b < 0) onEof.run();
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws java.io.IOException {
            int n = in.read(buf, off, len);
            if (n < 0) onEof.run();
            return n;
        }

        @Override
        public int available() throws java.io.IOException {
            return in.available();
        }

        @Override
        public void close() throws java.io.IOException {
            in.close();
        }
    }
}
