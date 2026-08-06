package com.rocketpartners.onboarding.possystem;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.formdev.flatlaf.FlatLightLaf;
import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.component.BarcodeInputBuffer;
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
import com.rocketpartners.onboarding.possystem.repository.ItemRepository;
import com.rocketpartners.onboarding.possystem.repository.inmemory.InMemoryItemRepository;
import com.rocketpartners.onboarding.possystem.service.TaxService;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Entry point for the POS desktop client. Parses CLI args, loads the pricebook, installs the
 * FlatLaf look-and-feel, and boots a {@link PosComponent} driving a {@link CustomerView} /
 * {@link CustomerViewController} pair.
 */
public final class Application {

    private static final String PRICEBOOK_RESOURCE = "/pricebook.tsv";
    private static final BigDecimal DEFAULT_TAX_RATE = new BigDecimal("0.07");
    private static final int QUICK_ADD_COUNT = 12;

    /**
     * Curated pool of foods and drinks (no tobacco / no lottery / no fuel prepay) drawn from
     * {@code /pricebook.tsv}. {@link #main} shuffles this list at startup and takes the first
     * {@link #QUICK_ADD_COUNT} UPCs to bind to quick-add buttons — a different set each run.
     */
    private static final List<String> QUICK_ADD_UPC_POOL = List.of(
            "049000053418", // COCA COLA CAN
            "049000000450", // DT COKE 20OZ
            "012000001291", // PEPSI 20Z BT
            "049000007640", // SPRT 20Z
            "999999937551", // Medium Polar Pop
            "999995377641", // SLICED PEPPERONI PIZ
            "999999235275", // DONUT WITH HOLE
            "028400323826", // LAYS REGULAR
            "028400324427", // 2.5Z RUFFLES CH
            "999999414977", // BANANA TB
            "194283301180", // MBROOK 1L
            "786162200433", // GLACEAU SMART WATER 20Z
            "034000004805", // REESES PB CUP KING SZ
            "040000000327", // M&M PNUT REG 1.74Z
            "611269818994",  // RED BULL ENERGY DRIN
            "860006114916",
            "999991218931",
            "070847811169",
            "049000000443"
    );

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

        List<Item> quickAddItems = pickQuickAddItems(itemRepository);

        SwingUtilities.invokeLater(() -> {
            PosComponent pos = new PosComponent(
                    itemRepository, taxService, args.storeName, args.laneNumber, args.debug);

            String title = "Rocket POS — " + args.storeName + " lane " + args.laneNumber;
            CustomerView view = new CustomerView(title, quickAddItems, pos);
            CustomerViewController controller = new CustomerViewController(view);

            PayWithCashView cashView = new PayWithCashView(view, pos);
            PayWithCashViewController cashController = new PayWithCashViewController(cashView);

            PayWithCardView cardView = new PayWithCardView(view);
            PayWithCardViewController cardController = new PayWithCardViewController(cardView);

            ChangeQuantityView changeQtyView = new ChangeQuantityView(
                    view, pos, pos.getTransactionService().getMaxLineQuantity());
            ChangeQuantityViewController changeQtyController =
                    new ChangeQuantityViewController(changeQtyView);

            ReceiptView receiptView = new ReceiptView(view, pos);
            ReceiptViewController receiptController =
                    new ReceiptViewController(receiptView, args.storeName, args.laneNumber);

            ScannerView scannerView = new ScannerView(pos);
            view.installScanBar(scannerView);
            BarcodeInputBuffer scanBuffer = new BarcodeInputBuffer(
                    args.scanBurstGapMs,
                    BarcodeInputBuffer.DEFAULT_STALE_TIMEOUT_MS,
                    BarcodeInputBuffer.NO_PREFIX,
                    java.util.Set.of(BarcodeInputBuffer.TERMINATOR_ENTER,
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
                }
            });

            pos.addController(controller);
            pos.addController(cashController);
            pos.addController(cardController);
            pos.addController(changeQtyController);
            pos.addController(receiptController);
            pos.addController(errorController);
            pos.addController(scannerController);
            pos.start();

            if (args.debug) {
                System.err.println("[POS] started: store=" + args.storeName
                        + " lane=" + args.laneNumber
                        + " journal=" + args.journalHost + ":" + args.journalPort
                        + " engine=" + args.discountEngineUrl
                        + " mode=" + args.appMode
                        + " quickAdd=" + quickAddItems.stream()
                                .map(Item::getUpc).toList());
            }
        });
    }

    /**
     * Shuffles {@link #QUICK_ADD_UPC_POOL} and resolves the first {@link #QUICK_ADD_COUNT}
     * UPCs against the pricebook. UPCs that fail to resolve are skipped (the pricebook could
     * legitimately have been swapped in), and shuffling continues until either enough items
     * are gathered or the pool is exhausted.
     */
    private static List<Item> pickQuickAddItems(ItemRepository repo) {
        List<String> upcs = new ArrayList<>(QUICK_ADD_UPC_POOL);
        Collections.shuffle(upcs, new Random());
        List<Item> picked = new ArrayList<>();
        for (String upc : upcs) {
            if (picked.size() >= QUICK_ADD_COUNT) break;
            Optional<Item> item = repo.findByUpc(upc);
            item.ifPresent(picked::add);
        }
        return picked;
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

        @Parameter(names = {"--help", "-h"}, description = "Print this usage and exit", help = true)
        public boolean help = false;
    }
}
