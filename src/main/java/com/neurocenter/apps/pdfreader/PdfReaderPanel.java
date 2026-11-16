package com.neurocenter.apps.pdfreader;

import com.neurocenter.api.AppContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextPane;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lector de PDF con tres vistas:
 *  - Lectura: el texto se recompone y se muestra con Bionic Reading.
 *  - Original: la página tal cual está en el PDF.
 *  - Original + Bionic: conserva la página y refuerza visualmente el inicio de las palabras.
 * Recuerda los ajustes de lectura y la página de cada documento, pero no reabre PDFs automáticamente.
 */
final class PdfReaderPanel extends JPanel {

    private record ReadingTheme(String name, Color background, Color foreground) {
        @Override public String toString() { return name; }
    }

    private static final ReadingTheme[] THEMES = {
            new ReadingTheme("Claro", new Color(0xFFFFFF), new Color(0x1F1F1F)),
            new ReadingTheme("Crema", new Color(0xFBF3DF), new Color(0x2E2A22)),
            new ReadingTheme("Sepia", new Color(0xF1E4C8), new Color(0x3B2F1E)),
            new ReadingTheme("Oscuro", new Color(0x1E1E1E), new Color(0xE0E0E0)),
            new ReadingTheme("Azul noche", new Color(0x14202E), new Color(0xD8E2EE)),
    };

    /** Fracción de cada palabra que se pone en negrita según el control "Intensidad" (1 a 5). */
    private static final double[] RATIOS = { 0.25, 0.35, 0.5, 0.6, 0.7 };

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_BIONIC = "bionic";
    private static final String CARD_ORIGINAL = "original";
    private static final String CARD_HYBRID = "hybrid";
    private static final String MODE_BIONIC = "bionic";
    private static final String MODE_ORIGINAL = "original";
    private static final String MODE_HYBRID = "hybrid";

    private final AppContext ctx;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pdf-reader-worker");
        t.setDaemon(true);
        return t;
    });

    // estado del documento (se toca solo desde el hilo de Swing; PDFBox solo desde "worker")
    private PDDocument document;
    private Path currentFile;
    private int pageCount;
    private int pageIndex;
    private volatile int ticket;
    private boolean disposed;
    private boolean updatingPage;
    private double zoom;
    private String viewMode;
    private boolean textReady;
    private List<String> paragraphs = List.of();
    private int lastSide = -1;

    // componentes
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final JLabel imageLabel = new JLabel();
    private final JScrollPane imageScroll = new JScrollPane(imageLabel);
    private final JLabel hybridImageLabel = new JLabel();
    private final JScrollPane hybridImageScroll = new JScrollPane(hybridImageLabel);
    private final JTextPane textPane = new JTextPane();
    private final JScrollPane textScroll = new JScrollPane(textPane);
    private final JPanel settingsSide = new JPanel(new BorderLayout());
    private final SpinnerNumberModel pageModel = new SpinnerNumberModel(1, 1, 1, 1);
    private final JSpinner pageSpinner = new JSpinner(pageModel);
    private final JLabel pageTotal = new JLabel("/ –");
    private final JLabel zoomLabel = new JLabel();
    private final JButton prevBtn = new JButton("‹");
    private final JButton nextBtn = new JButton("›");
    private final JToggleButton bionicBtn = new JToggleButton("Lectura");
    private final JToggleButton originalBtn = new JToggleButton("Original");
    private final JToggleButton hybridBtn = new JToggleButton("Original + Bionic");
    private final JToggleButton settingsToggle = new JToggleButton("Ajustes de lectura");
    private final JPanel zoomBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JComboBox<String> fontBox;
    private final JSpinner sizeSpin;
    private final JSpinner spacingSpin;
    private final JSlider strengthSlider;
    private final JSlider widthSlider;
    private final JComboBox<ReadingTheme> themeBox = new JComboBox<>(THEMES);

    PdfReaderPanel(AppContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;

        // --- ajustes guardados
        List<String> families = new ArrayList<>(List.of("SansSerif", "Serif"));
        families.addAll(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        fontBox = new JComboBox<>(families.toArray(new String[0]));
        String wantedFont = ctx.getSetting("font", "Verdana");
        fontBox.setSelectedItem(families.contains(wantedFont) ? wantedFont : "SansSerif");
        sizeSpin = new JSpinner(new SpinnerNumberModel(intPref("size", 18), 10, 60, 1));
        spacingSpin = new JSpinner(new SpinnerNumberModel(doublePref("spacing", 1.6), 1.0, 3.0, 0.1));
        spacingSpin.setEditor(new JSpinner.NumberEditor(spacingSpin, "0.0"));
        strengthSlider = new JSlider(1, 5, clamp(intPref("strength", 3), 1, 5));
        strengthSlider.setSnapToTicks(true);
        strengthSlider.setMajorTickSpacing(1);
        strengthSlider.setPaintTicks(true);
        widthSlider = new JSlider(400, 1200, clamp(intPref("width", 760), 400, 1200));
        themeBox.setSelectedIndex(clamp(intPref("theme", 1), 0, THEMES.length - 1));
        zoom = Math.max(0.4, Math.min(3.0, doublePref("zoom", 1.0)));
        viewMode = ctx.getSetting("mode", MODE_BIONIC);
        if (!MODE_BIONIC.equals(viewMode) && !MODE_ORIGINAL.equals(viewMode)
                && !MODE_HYBRID.equals(viewMode)) viewMode = MODE_BIONIC;
        settingsToggle.setSelected(!"false".equals(ctx.getSetting("settingsOpen", "true")));

        // --- interfaz
        add(buildTopBar(), BorderLayout.NORTH);
        content.add(buildEmptyCard(), CARD_EMPTY);
        content.add(buildBionicCard(), CARD_BIONIC);
        content.add(buildOriginalCard(), CARD_ORIGINAL);
        content.add(buildHybridCard(), CARD_HYBRID);
        add(content, BorderLayout.CENTER);
        cards.show(content, CARD_EMPTY);

        updateZoomLabel();
        applyBackground();
        setNavEnabled(false);
        applyModeVisibility();
        wireReadingControls();
        installKeys();

    }

    // ------------------------------------------------------------------ construcción de la interfaz

    private JComponent buildTopBar() {
        JButton open = new JButton("Abrir PDF…");
        styleToolbarButton(open);
        open.addActionListener(e -> chooseFile());

        styleToolbarButton(prevBtn);
        styleToolbarButton(nextBtn);
        styleToolbarButton(bionicBtn);
        styleToolbarButton(originalBtn);
        styleToolbarButton(hybridBtn);
        styleToolbarButton(settingsToggle);
        styleToolbarToggle(bionicBtn);
        styleToolbarToggle(originalBtn);
        styleToolbarToggle(hybridBtn);
        styleToolbarToggle(settingsToggle);

        prevBtn.setToolTipText("Página anterior (Alt + ←)");
        nextBtn.setToolTipText("Página siguiente (Alt + →)");
        prevBtn.addActionListener(e -> goToPage(pageIndex - 1));
        nextBtn.addActionListener(e -> goToPage(pageIndex + 1));
        pageSpinner.setPreferredSize(new Dimension(72, pageSpinner.getPreferredSize().height));
        pageSpinner.putClientProperty("JComponent.roundRect", true);
        pageSpinner.addChangeListener(e -> {
            if (!updatingPage) goToPage(pageModel.getNumber().intValue() - 1);
        });

        ButtonGroup modes = new ButtonGroup();
        modes.add(bionicBtn);
        modes.add(originalBtn);
        modes.add(hybridBtn);
        bionicBtn.setToolTipText("Texto recompuesto con Bionic Reading");
        originalBtn.setToolTipText("La página tal como es en el PDF");
        hybridBtn.setToolTipText("Conserva la página original y refuerza visualmente el inicio de las palabras");
        bionicBtn.setSelected(MODE_BIONIC.equals(viewMode));
        originalBtn.setSelected(MODE_ORIGINAL.equals(viewMode));
        hybridBtn.setSelected(MODE_HYBRID.equals(viewMode));
        bionicBtn.addActionListener(e -> setViewMode(MODE_BIONIC));
        originalBtn.addActionListener(e -> setViewMode(MODE_ORIGINAL));
        hybridBtn.addActionListener(e -> setViewMode(MODE_HYBRID));

        JButton zoomOut = new JButton("−");
        JButton zoomIn = new JButton("+");
        styleToolbarButton(zoomOut);
        styleToolbarButton(zoomIn);
        zoomOut.setToolTipText("Reducir");
        zoomIn.setToolTipText("Ampliar");
        zoomOut.addActionListener(e -> changeZoom(-0.15));
        zoomIn.addActionListener(e -> changeZoom(0.15));
        zoomBox.add(zoomOut);
        zoomBox.add(zoomLabel);
        zoomBox.add(zoomIn);

        settingsToggle.addActionListener(e -> {
            settingsSide.setVisible(settingsToggle.isSelected());
            ctx.putSetting("settingsOpen", String.valueOf(settingsToggle.isSelected()));
        });

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        bar.add(open);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(prevBtn);
        bar.add(pageSpinner);
        bar.add(pageTotal);
        bar.add(nextBtn);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(bionicBtn);
        bar.add(originalBtn);
        bar.add(hybridBtn);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(zoomBox);
        bar.add(settingsToggle);

        JPanel north = new JPanel(new BorderLayout());
        north.add(bar, BorderLayout.CENTER);
        north.add(new JSeparator(), BorderLayout.SOUTH);
        return north;
    }

    /** Botones planos integrados visualmente en la barra de herramientas de FlatLaf. */
    private static void styleToolbarButton(AbstractButton button) {
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setFocusable(false);
        button.setOpaque(false);
        button.setBorderPainted(false);
    }

    /** Los botones de modo son pestañas, no botones de acción grises. */
    private static void styleToolbarToggle(JToggleButton button) {
        button.putClientProperty("JToggleButton.buttonType", "toolBarButton");
        button.setFocusable(false);
        button.setOpaque(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
    }

    private JComponent buildEmptyCard() {
        JLabel msg = new JLabel("<html><div style='text-align:center'><span style='font-size:18pt'><b>Abre un PDF para empezar</b></span>"
                + "<br><br>En la vista de lectura, el principio de cada palabra va en negrita<br>"
                + "para que resulte más fácil seguir el texto.</div></html>", SwingConstants.CENTER);
        JButton open = new JButton("Abrir PDF…");
        styleToolbarButton(open);
        open.addActionListener(e -> chooseFile());
        msg.setAlignmentX(Component.CENTER_ALIGNMENT);
        open.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.add(msg);
        box.add(Box.createVerticalStrut(20));
        box.add(open);

        JPanel center = new JPanel(new GridBagLayout());
        center.add(box);
        return center;
    }

    private JComponent buildOriginalCard() {
        imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
        imageLabel.setVerticalAlignment(SwingConstants.TOP);
        imageLabel.setBorder(new EmptyBorder(16, 16, 16, 16));
        imageScroll.setBorder(null);
        imageScroll.getVerticalScrollBar().setUnitIncrement(24);
        imageScroll.getHorizontalScrollBar().setUnitIncrement(24);
        return imageScroll;
    }

    private JComponent buildHybridCard() {
        hybridImageLabel.setHorizontalAlignment(SwingConstants.CENTER);
        hybridImageLabel.setVerticalAlignment(SwingConstants.TOP);
        hybridImageLabel.setBorder(new EmptyBorder(16, 16, 16, 16));
        hybridImageScroll.setBorder(null);
        hybridImageScroll.getVerticalScrollBar().setUnitIncrement(24);
        hybridImageScroll.getHorizontalScrollBar().setUnitIncrement(24);
        return hybridImageScroll;
    }

    private JComponent buildBionicCard() {
        textPane.setEditable(false);
        textScroll.setBorder(null);
        textScroll.getVerticalScrollBar().setUnitIncrement(24);
        textScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { applyTextWidth(); }
        });

        // Panel de ajustes a la derecha
        JPanel settings = new JPanel(new GridBagLayout());
        settings.setBorder(new EmptyBorder(18, 16, 16, 16));
        int row = 0;
        addSetting(settings, row++, "Fuente", fontBox);
        addSetting(settings, row++, "Tamaño", sizeSpin);
        addSetting(settings, row++, "Interlineado", spacingSpin);
        addSetting(settings, row++, "Intensidad de la negrita", strengthSlider);
        addSetting(settings, row++, "Ancho del texto", widthSlider);
        addSetting(settings, row++, "Fondo", themeBox);
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridy = row * 2;
        filler.weighty = 1;
        settings.add(Box.createVerticalGlue(), filler);

        settingsSide.add(new JSeparator(SwingConstants.VERTICAL), BorderLayout.WEST);
        settingsSide.add(settings, BorderLayout.CENTER);
        settingsSide.setPreferredSize(new Dimension(260, 10));

        JPanel card = new JPanel(new BorderLayout());
        card.add(textScroll, BorderLayout.CENTER);
        card.add(settingsSide, BorderLayout.EAST);
        return card;
    }

    private static void addSetting(JPanel panel, int row, String label, JComponent field) {
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.gridy = row * 2;
        l.weightx = 1;
        l.anchor = GridBagConstraints.WEST;
        l.fill = GridBagConstraints.HORIZONTAL;
        l.insets = new Insets(row == 0 ? 0 : 16, 0, 4, 0);
        panel.add(new JLabel(label), l);

        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 0;
        f.gridy = row * 2 + 1;
        f.weightx = 1;
        f.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, f);
    }

    private void wireReadingControls() {
        fontBox.addActionListener(e -> readingPrefsChanged());
        sizeSpin.addChangeListener(e -> readingPrefsChanged());
        spacingSpin.addChangeListener(e -> readingPrefsChanged());
        strengthSlider.addChangeListener(e -> {
            if (!strengthSlider.getValueIsAdjusting()) readingPrefsChanged();
        });
        widthSlider.addChangeListener(e -> {
            applyTextWidth();
            if (!widthSlider.getValueIsAdjusting()) savePrefs();
        });
        themeBox.addActionListener(e -> {
            applyBackground();
            readingPrefsChanged();
        });
    }

    private void installKeys() {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK), "nextPage");
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK), "prevPage");
        getActionMap().put("nextPage", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { goToPage(pageIndex + 1); }
        });
        getActionMap().put("prevPage", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { goToPage(pageIndex - 1); }
        });
    }

    // ------------------------------------------------------------------ abrir documentos

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser(ctx.getSetting("lastDir", System.getProperty("user.home")));
        chooser.setDialogTitle("Abrir PDF");
        chooser.setFileFilter(new FileNameExtensionFilter("Documentos PDF (*.pdf)", "pdf"));
        if (chooser.showOpenDialog(ctx.window()) == JFileChooser.APPROVE_OPTION) {
            openFile(chooser.getSelectedFile().toPath(), false);
        }
    }

    private void openFile(Path path, boolean silent) {
        if (worker.isShutdown()) return;
        ctx.setStatus("Abriendo " + path.getFileName() + "…");
        worker.submit(() -> {
            try {
                PDDocument loaded = Loader.loadPDF(path.toFile());
                SwingUtilities.invokeLater(() -> onLoaded(path, loaded));
            } catch (Throwable ex) {
                SwingUtilities.invokeLater(() -> onLoadFailed(path, ex, silent));
            }
        });
    }

    private void onLoaded(Path path, PDDocument loaded) {
        if (disposed || loaded.getNumberOfPages() == 0) {
            closeAsync(loaded);
            if (!disposed) {
                ctx.setStatus(" ");
                JOptionPane.showMessageDialog(ctx.window(), "Este PDF no tiene páginas.", "Lector PDF",
                        JOptionPane.WARNING_MESSAGE);
            }
            return;
        }
        PDDocument previous = document;
        document = loaded;
        currentFile = path;
        pageCount = loaded.getNumberOfPages();
        if (previous != null) closeAsync(previous);

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) ctx.putSetting("lastDir", parent.toString());

        updatingPage = true;
        pageModel.setValue(1);
        pageModel.setMaximum(Integer.valueOf(pageCount));
        updatingPage = false;
        pageTotal.setText("/ " + pageCount);
        setNavEnabled(true);
        cards.show(content, cardForMode());

        int start = intPrefRaw(ctx.getSetting("page|" + path, "1"), 1);
        goToPage(start - 1); // sigue por donde lo dejaste
    }

    private void onLoadFailed(Path path, Throwable ex, boolean silent) {
        ctx.setStatus(" ");
        if (silent || disposed) return;
        String detail = ex.getClass().getSimpleName().contains("InvalidPassword")
                ? "El PDF está protegido con contraseña (todavía no se admite)."
                : String.valueOf(ex.getMessage());
        JOptionPane.showMessageDialog(ctx.window(), "No se ha podido abrir «" + path.getFileName() + "».\n\n" + detail,
                "Lector PDF", JOptionPane.ERROR_MESSAGE);
    }

    private void closeAsync(PDDocument doc) {
        Runnable close = () -> {
            try {
                doc.close();
            } catch (IOException ignored) {
                // nada que hacer
            }
        };
        if (worker.isShutdown()) close.run(); else worker.submit(close);
    }

    // ------------------------------------------------------------------ navegación y carga de páginas

    private void goToPage(int index) {
        if (document == null) return;
        pageIndex = Math.max(0, Math.min(pageCount - 1, index));
        updatingPage = true;
        pageModel.setValue(pageIndex + 1);
        updatingPage = false;
        prevBtn.setEnabled(pageIndex > 0);
        nextBtn.setEnabled(pageIndex < pageCount - 1);
        ctx.putSetting("page|" + currentFile, String.valueOf(pageIndex + 1));
        ctx.setStatus(currentFile.getFileName() + " — página " + (pageIndex + 1) + " de " + pageCount);
        loadCurrentPage();
    }

    private void loadCurrentPage() {
        if (document == null || worker.isShutdown()) return;
        final PDDocument doc = document;
        final int index = pageIndex;
        final String mode = viewMode;
        final float dpi = (float) (96.0 * zoom);
        final double ratio = RATIOS[strengthSlider.getValue() - 1];
        final int myTicket = ++ticket;
        if (MODE_BIONIC.equals(mode)) textReady = false;

        worker.submit(() -> {
            if (myTicket != ticket) return; // ya hay una petición más reciente
            try {
                if (MODE_BIONIC.equals(mode)) {
                    List<String> result = extractParagraphs(doc, index);
                    SwingUtilities.invokeLater(() -> {
                        if (myTicket == ticket) showText(result);
                    });
                } else if (MODE_HYBRID.equals(mode)) {
                    BufferedImage image = BionicPageRenderer.render(doc, index, dpi, ratio);
                    SwingUtilities.invokeLater(() -> {
                        if (myTicket == ticket) showHybridImage(image);
                    });
                } else {
                    BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(index, dpi);
                    SwingUtilities.invokeLater(() -> {
                        if (myTicket == ticket) showImage(image);
                    });
                }
            } catch (Throwable ex) {
                SwingUtilities.invokeLater(() -> {
                    if (myTicket == ticket) ctx.setStatus("No se pudo leer la página: " + ex.getMessage());
                });
            }
        });
    }

    private static List<String> extractParagraphs(PDDocument doc, int pageIndex) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        return BionicText.paragraphs(stripper.getText(doc));
    }

    private void showImage(BufferedImage image) {
        imageLabel.setIcon(new ImageIcon(image));
        SwingUtilities.invokeLater(() -> imageScroll.getVerticalScrollBar().setValue(0));
    }

    private void showHybridImage(BufferedImage image) {
        hybridImageLabel.setIcon(new ImageIcon(image));
        SwingUtilities.invokeLater(() -> hybridImageScroll.getVerticalScrollBar().setValue(0));
    }

    private void showText(List<String> result) {
        paragraphs = result;
        textReady = true;
        renderBionic();
    }

    // ------------------------------------------------------------------ vista de lectura

    private void renderBionic() {
        ReadingTheme theme = currentTheme();
        int size = ((Number) sizeSpin.getValue()).intValue();
        double spacing = ((Number) spacingSpin.getValue()).doubleValue();
        double ratio = RATIOS[strengthSlider.getValue() - 1];

        SimpleAttributeSet normal = new SimpleAttributeSet();
        StyleConstants.setFontFamily(normal, String.valueOf(fontBox.getSelectedItem()));
        StyleConstants.setFontSize(normal, size);
        StyleConstants.setForeground(normal, theme.foreground());
        StyleConstants.setBold(normal, false);
        SimpleAttributeSet strong = new SimpleAttributeSet(normal);
        StyleConstants.setBold(strong, true);

        DefaultStyledDocument doc = new DefaultStyledDocument();
        try {
            if (paragraphs.isEmpty()) {
                doc.insertString(0, "Esta página no tiene texto seleccionable (puede ser una imagen escaneada).\n"
                        + "Cambia a la vista Original o Original + Bionic para verla.", normal);
            } else {
                for (String p : paragraphs) {
                    BionicText.append(doc, p, ratio, normal, strong);
                    doc.insertString(doc.getLength(), "\n", normal);
                }
            }
            SimpleAttributeSet paragraph = new SimpleAttributeSet();
            StyleConstants.setLineSpacing(paragraph, (float) (spacing - 1.0));
            StyleConstants.setSpaceBelow(paragraph, size * 0.7f);
            doc.setParagraphAttributes(0, doc.getLength(), paragraph, false);
        } catch (BadLocationException ex) {
            throw new IllegalStateException(ex);
        }
        textPane.setDocument(doc);
        textPane.setCaretPosition(0);
        applyTextWidth();
    }

    /** Limita el ancho de la columna de texto (las líneas muy largas cansan) y la centra. */
    private void applyTextWidth() {
        int viewport = textScroll.getViewport().getWidth();
        if (viewport <= 0) return;
        int textWidth = Math.min(widthSlider.getValue(), Math.max(240, viewport - 48));
        int side = Math.max(24, (viewport - textWidth) / 2);
        if (side != lastSide) {
            lastSide = side;
            textPane.setBorder(new EmptyBorder(32, side, 56, side));
        }
    }

    private void applyBackground() {
        ReadingTheme theme = currentTheme();
        textPane.setBackground(theme.background());
        textPane.setForeground(theme.foreground());
        textScroll.getViewport().setBackground(theme.background());
    }

    private ReadingTheme currentTheme() {
        return themeBox.getItemAt(Math.max(0, themeBox.getSelectedIndex()));
    }

    private void readingPrefsChanged() {
        savePrefs();
        if (MODE_BIONIC.equals(viewMode) && textReady) {
            renderBionic();
        } else if (MODE_HYBRID.equals(viewMode) && document != null) {
            loadCurrentPage();
        }
    }

    private void savePrefs() {
        ctx.putSetting("font", String.valueOf(fontBox.getSelectedItem()));
        ctx.putSetting("size", String.valueOf(((Number) sizeSpin.getValue()).intValue()));
        ctx.putSetting("spacing", String.format(Locale.ROOT, "%.1f", ((Number) spacingSpin.getValue()).doubleValue()));
        ctx.putSetting("strength", String.valueOf(strengthSlider.getValue()));
        ctx.putSetting("width", String.valueOf(widthSlider.getValue()));
        ctx.putSetting("theme", String.valueOf(Math.max(0, themeBox.getSelectedIndex())));
    }

    // ------------------------------------------------------------------ modos y zoom

    private String cardForMode() {
        if (MODE_BIONIC.equals(viewMode)) return CARD_BIONIC;
        if (MODE_HYBRID.equals(viewMode)) return CARD_HYBRID;
        return CARD_ORIGINAL;
    }

    private void setViewMode(String mode) {
        viewMode = mode;
        ctx.putSetting("mode", mode);
        bionicBtn.setSelected(MODE_BIONIC.equals(mode));
        originalBtn.setSelected(MODE_ORIGINAL.equals(mode));
        hybridBtn.setSelected(MODE_HYBRID.equals(mode));
        applyModeVisibility();
        if (document != null) {
            cards.show(content, cardForMode());
            loadCurrentPage();
        }
    }

    private void applyModeVisibility() {
        boolean reading = MODE_BIONIC.equals(viewMode);
        zoomBox.setVisible(!reading);
        settingsToggle.setVisible(reading);
        settingsSide.setVisible(reading && settingsToggle.isSelected());
    }

    private void changeZoom(double delta) {
        zoom = Math.max(0.4, Math.min(3.0, zoom + delta));
        ctx.putSetting("zoom", String.format(Locale.ROOT, "%.2f", zoom));
        updateZoomLabel();
        if (document != null && !MODE_BIONIC.equals(viewMode)) loadCurrentPage();
    }

    private void updateZoomLabel() {
        zoomLabel.setText(Math.round(zoom * 100) + " %");
    }

    private void setNavEnabled(boolean enabled) {
        prevBtn.setEnabled(enabled && pageIndex > 0);
        nextBtn.setEnabled(enabled && pageIndex < pageCount - 1);
        pageSpinner.setEnabled(enabled);
    }

    // ------------------------------------------------------------------ ciclo de vida

    void dispose() {
        disposed = true;
        ticket++;
        PDDocument doc = document;
        document = null;
        if (doc != null) closeAsync(doc);
        worker.shutdown();
    }

    // ------------------------------------------------------------------ ajustes

    private int intPref(String key, int def) {
        return intPrefRaw(ctx.getSetting(key, String.valueOf(def)), def);
    }

    private static int intPrefRaw(String value, int def) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private double doublePref(String key, double def) {
        try {
            return Double.parseDouble(ctx.getSetting(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
