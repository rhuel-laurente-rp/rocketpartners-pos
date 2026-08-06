package com.rocketpartners.onboarding.possystem;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.formdev.flatlaf.FlatLightLaf;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.repository.ItemRepository;
import com.rocketpartners.onboarding.possystem.repository.inmemory.InMemoryItemRepository;
import com.rocketpartners.onboarding.possystem.service.TaxService;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.math.BigDecimal;

/**
 * Entry point for the POS desktop client. Parses CLI args, loads the pricebook, installs the
 * FlatLaf look-and-feel, and shows an empty {@link JFrame} driven by a {@link PosComponent}.
 * Real Swing views land in the {@code display} package later.
 */
public final class Application {

    private static final String PRICEBOOK_RESOURCE = "/pricebook.tsv";
    private static final BigDecimal DEFAULT_TAX_RATE = new BigDecimal("0.07");

    private Application() {}

    public static void main(String[] argv) {
        Args args = new Args();
        JCommander jc = JCommander.newBuilder()
                .addObject(args)
                .programName("runPos")
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

        ItemRepository itemRepository;
        try {
            itemRepository = InMemoryItemRepository.loadFromClasspath(PRICEBOOK_RESOURCE);
        } catch (RuntimeException e) {
            System.err.println("Failed to load pricebook from " + PRICEBOOK_RESOURCE + ": " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(2);
            return;
        }

        TaxService taxService = new TaxService(DEFAULT_TAX_RATE);

        try {
            UIManager.setLookAndFeel(new FlatLightLaf());
        } catch (UnsupportedLookAndFeelException e) {
            System.err.println("FlatLaf unavailable, falling back to system LAF: " + e.getMessage());
        }

        SwingUtilities.invokeLater(() -> {
            PosComponent pos = new PosComponent(
                    itemRepository, taxService, args.storeName, args.laneNumber, args.debug);

            JFrame frame = new JFrame("Rocket POS — " + args.storeName + " lane " + args.laneNumber);
            frame.setPreferredSize(new Dimension(800, 600));
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    pos.shutdown();
                }
            });
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            pos.start();

            if (args.debug) {
                System.err.println("[POS] started: store=" + args.storeName
                        + " lane=" + args.laneNumber
                        + " journal=" + args.journalHost + ":" + args.journalPort
                        + " engine=" + args.discountEngineUrl
                        + " mode=" + args.appMode);
            }
        });
    }

    /** JCommander-parsed CLI arguments. Public for JCommander reflection access. */
    public static final class Args {

        @Parameter(names = "--debug", description = "Enable verbose event tracing to stderr")
        public boolean debug = false;

        @Parameter(names = "--app-mode",
                description = "Application mode: NORMAL or TRAINING (reserved; unused today)")
        public String appMode = "NORMAL";

        @Parameter(names = "--store-name", description = "Store label shown on the window and receipts")
        public String storeName = "Rocket Store";

        @Parameter(names = "--lane-number", description = "Terminal / lane number for this POS")
        public int laneNumber = 1;

        @Parameter(names = "--journal-host", description = "Virtual journal hostname")
        public String journalHost = "localhost";

        @Parameter(names = "--journal-port", description = "Virtual journal TCP port")
        public int journalPort = 12345;

        @Parameter(names = "--discount-engine-url", description = "Discount engine HTTP base URL")
        public String discountEngineUrl = "http://localhost:8080";

        @Parameter(names = {"--help", "-h"}, description = "Print this usage and exit", help = true)
        public boolean help = false;
    }
}
