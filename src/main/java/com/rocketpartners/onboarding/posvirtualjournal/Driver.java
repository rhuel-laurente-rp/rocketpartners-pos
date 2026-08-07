package com.rocketpartners.onboarding.posvirtualjournal;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

import java.net.BindException;

/**
 * Entry point for the virtual journal server. Parses CLI args, boots a {@link POSVirtualJournal},
 * and blocks the main thread until interrupted.
 *
 * <p>{@code ./gradlew runJournal} invokes this class. A {@link BindException} at startup (the
 * chosen port is already in use — a stale journal from another terminal is the usual cause) is
 * reported as a single-line message naming the port and exits non-zero, rather than dumping a
 * stack trace at the user.</p>
 */
public final class Driver {

    private Driver() {}

    public static void main(String[] argv) {
        Args args = new Args();
        JCommander jc = JCommander.newBuilder()
                .addObject(args)
                .programName("runJournal")
                .build();
        try {
            jc.parse(argv);
        } catch (ParameterException e) {
            System.err.println(e.getMessage());
            jc.usage();
            System.exit(1);
            return;
        }
        if (args.help) {
            jc.usage();
            System.exit(0);
            return;
        }

        POSVirtualJournal server = new POSVirtualJournal(args.port, new JournalPrinter());
        try {
            server.start();
        } catch (BindException e) {
            System.err.println("[journal] port " + args.port
                    + " is already in use — is another journal running?");
            System.exit(2);
            return;
        } catch (Exception e) {
            System.err.println("[journal] failed to start on port " + args.port + ": " + e.getMessage());
            System.exit(3);
            return;
        }

        Thread shutdownHook = new Thread(server::stop, "journal-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        System.out.println("[journal] listening on port " + server.getBoundPort()
                + " (max clients=" + POSVirtualJournal.MAX_CLIENTS + ")");

        // Park the main thread; the accept loop runs on a daemon. Interrupt (Ctrl-C) fires the
        // shutdown hook and returns.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** JCommander-parsed CLI arguments. Public for JCommander reflection access. */
    public static final class Args {

        @Parameter(names = "--port", description = "TCP port to listen on")
        public int port = 12345;

        @Parameter(names = {"--help", "-h"}, description = "Print this usage and exit", help = true)
        public boolean help = false;
    }
}
