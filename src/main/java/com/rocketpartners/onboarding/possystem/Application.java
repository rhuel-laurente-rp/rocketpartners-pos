package com.rocketpartners.onboarding.possystem;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.formdev.flatlaf.FlatLightLaf;
import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.component.BarcodeInputBuffer;
import com.rocketpartners.onboarding.possystem.component.FileJournal;
import com.rocketpartners.onboarding.possystem.component.Journal;
import com.rocketpartners.onboarding.possystem.component.JournalListener;
import com.rocketpartners.onboarding.possystem.component.Journals;
import com.rocketpartners.onboarding.possystem.component.LocalJournal;
import com.rocketpartners.onboarding.possystem.component.RemoteJournal;
import com.rocketpartners.onboarding.possystem.display.CashModeChoiceView;
import com.rocketpartners.onboarding.possystem.display.ChangeQuantityView;
import com.rocketpartners.onboarding.possystem.display.ChangeQuantityViewController;
import com.rocketpartners.onboarding.possystem.display.CustomerView;
import com.rocketpartners.onboarding.possystem.display.CustomerViewController;
import com.rocketpartners.onboarding.possystem.display.ErrorPopupViewController;
import com.rocketpartners.onboarding.possystem.display.PayWithCardView;
import com.rocketpartners.onboarding.possystem.display.PayWithCardViewController;
import com.rocketpartners.onboarding.possystem.display.PayWithCashView;
import com.rocketpartners.onboarding.possystem.display.PayWithCashViewController;
import com.rocketpartners.onboarding.possystem.display.ReceiptView;
import com.rocketpartners.onboarding.possystem.display.ReceiptViewController;
import com.rocketpartners.onboarding.possystem.display.ScannerView;
import com.rocketpartners.onboarding.possystem.display.ScannerViewController;
import com.rocketpartners.onboarding.possystem.display.VoidBasketConfirmView;
import com.rocketpartners.onboarding.possystem.display.VoidBasketConfirmViewController;
import com.rocketpartners.onboarding.possystem.repository.h2.H2ItemRepository;
import com.rocketpartners.onboarding.possystem.service.TaxService;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Entry point for the POS desktop client. Parses CLI args, loads the pricebook, installs the
 * FlatLaf look-and-feel, and boots a {@link PosComponent} driving a {@link CustomerView} /
 * {@link CustomerViewController} pair.
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

        Path dbDir = Paths.get(args.dbDir).toAbsolutePath();
        H2ItemRepository itemRepository;
        try {
            itemRepository = H2ItemRepository.open(dbDir, args.dbName, PRICEBOOK_RESOURCE);
        } catch (RuntimeException e) {
            System.err.println("Failed to open H2 pricebook at " + dbDir + "/" + args.dbName
                    + ": " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(2);
            return;
        }
        System.err.println("[POS] pricebook DB: " + dbDir.resolve(args.dbName)
                + " (" + itemRepository.size() + " items)");

        TaxService taxService = new TaxService(DEFAULT_TAX_RATE);

        try {
            UIManager.setLookAndFeel(new FlatLightLaf());
        } catch (UnsupportedLookAndFeelException e) {
            System.err.println("FlatLaf unavailable, falling back to system LAF: " + e.getMessage());
        }

        // Quick Add renders the whole pricebook now (paged, searchable, sortable in the view),
        // so there is no curated subset to sample — hand the grid every item.
        List<Item> quickAddItems = itemRepository.getAll();

        LocalJournal localJournal = new LocalJournal();
        Path logDir = Paths.get(args.logDir).toAbsolutePath();
        FileJournal fileJournal = new FileJournal(logDir);
        RemoteJournal remoteJournal = new RemoteJournal(args.journalHost, args.journalPort, localJournal);
        Journal journal = new Journals(localJournal, fileJournal, remoteJournal);
        System.err.println("[POS] journal log dir: " + logDir);

        SwingUtilities.invokeLater(() -> {
            PosComponent pos = new PosComponent(
                    itemRepository, taxService, args.storeName, args.laneNumber, args.debug);
            JournalListener journalListener = new JournalListener(journal);

            String title = "Rocket POS — " + args.storeName + " lane " + args.laneNumber;
            CustomerView view = new CustomerView(title, quickAddItems, pos);
            CustomerViewController controller = new CustomerViewController(view);
            // Header journal-status indicator: reflect the RemoteJournal's connection state.
            // The listener fires on the sender thread; CustomerView.setJournalConnected marshals
            // onto the EDT.
            remoteJournal.setConnectionListener(state ->
                    view.setJournalConnected(state == RemoteJournal.ConnectionState.CONNECTED));

            CashModeChoiceView cashChoiceView = new CashModeChoiceView(view, pos);
            PayWithCashView cashView = new PayWithCashView(view, pos);
            PayWithCashViewController cashController =
                    new PayWithCashViewController(cashChoiceView, cashView);

            PayWithCardView cardView = new PayWithCardView(view);
            PayWithCardViewController cardController = new PayWithCardViewController(cardView);

            ChangeQuantityView changeQtyView = new ChangeQuantityView(
                    view, pos, pos.getTransactionService().getMaxLineQuantity());
            ChangeQuantityViewController changeQtyController =
                    new ChangeQuantityViewController(changeQtyView);

            VoidBasketConfirmView voidBasketConfirmView = new VoidBasketConfirmView(view, pos);
            VoidBasketConfirmViewController voidBasketController =
                    new VoidBasketConfirmViewController(voidBasketConfirmView);

            ReceiptView receiptView = new ReceiptView(view, pos);
            ReceiptViewController receiptController =
                    new ReceiptViewController(receiptView, args.storeName, args.laneNumber);

            ScannerView scannerView = new ScannerView(pos);
            // Direct reset from the receipt's Start Next Sale button so the scan bar flips out
            // of the locked mode the moment the modal closes, not later in the
            // RECEIPT_DISMISSED event chain. Belt-and-braces with ScannerViewController's own
            // handler — the event still runs, this just closes the ordering race the user hit.
            receiptView.setOnDismissed(() -> {
                scannerView.setLocked(false);
                scannerView.requestScanFieldFocus();
            });
            view.installScanBar(scannerView);
            BarcodeInputBuffer scanBuffer = new BarcodeInputBuffer(
                    args.scanBurstGapMs,
                    BarcodeInputBuffer.DEFAULT_STALE_TIMEOUT_MS,
                    BarcodeInputBuffer.NO_PREFIX,
                    // CR is included alongside Enter (LF) and Tab: some USB HID scanners emit
                    // CR alone, others emit CR+LF. The buffer's CR+LF swallow makes the pair
                    // resolve as a single scan rather than one scan plus an empty submit.
                    java.util.Set.of(BarcodeInputBuffer.TERMINATOR_ENTER,
                            BarcodeInputBuffer.TERMINATOR_CR,
                            BarcodeInputBuffer.TERMINATOR_TAB));
            ScannerViewController scannerController =
                    new ScannerViewController(scannerView, scanBuffer, args.debug);

            ErrorPopupViewController errorController =
                    new ErrorPopupViewController(view, scannerView.getScanField());

            view.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            view.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    pos.shutdown();
                    itemRepository.close();
                }
            });

            // JournalListener first so it captures the POS_STARTED entry before any
            // controller-initiated startup event. ScannerViewController is registered BEFORE
            // ReceiptViewController so that on TRANSACTION_COMPLETED the scanner reacts
            // BEFORE the modal receipt dialog opens: openDialog() blocks the outer dispatch
            // loop, so any listener sitting after receiptController in registration order
            // would only receive TRANSACTION_COMPLETED after the receipt was dismissed — which
            // would re-lock the scanner and stomp on the unlocked state set by the nested
            // RECEIPT_DISMISSED handler.
            pos.addController(journalListener);
            pos.addController(controller);
            pos.addController(cashController);
            pos.addController(cardController);
            pos.addController(changeQtyController);
            pos.addController(voidBasketController);
            pos.addController(errorController);
            pos.addController(scannerController);
            pos.addController(receiptController);
            pos.start();

            if (args.debug) {
                System.err.println("[POS] started: store=" + args.storeName
                        + " lane=" + args.laneNumber
                        + " journal=" + args.journalHost + ":" + args.journalPort
                        + " engine=" + args.discountEngineUrl
                        + " mode=" + args.appMode
                        + " quickAdd=" + quickAddItems.size() + " items");
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

        @Parameter(names = "--scan-burst-gap-ms",
                description = "Max inter-character gap (ms) inside a scanner burst; input arriving "
                        + "beyond this gap is treated as human typing. Default 50.")
        public long scanBurstGapMs = BarcodeInputBuffer.DEFAULT_BURST_GAP_MS;

        @Parameter(names = "--log-dir",
                description = "Directory to write on-disk JSONL journal files into. "
                        + "Each run appends to journal-YYYY-MM-DD.jsonl.")
        public String logDir = "logs";

        @Parameter(names = "--db-dir",
                description = "Directory to hold the H2 pricebook database file. "
                        + "Created on first run and re-used thereafter.")
        public String dbDir = "data";

        @Parameter(names = "--db-name",
                description = "Base name of the H2 pricebook database (no extension).")
        public String dbName = "pricebook";

        @Parameter(names = {"--help", "-h"}, description = "Print this usage and exit", help = true)
        public boolean help = false;
    }
}
