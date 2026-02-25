package main;

import analysis.GameAnalyzer;

import javax.swing.*;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.util.List;

public class Main {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Chess");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.getContentPane().setBackground(Theme.BG);
            frame.setLayout(new BorderLayout());

            Board board = new Board();
            board.setBorder(BorderFactory.createEmptyBorder());

            // ── Difficulty combo ──────────────────────────────────────────────
            int[] eloValues = {800,1000,1200,1400,1600,1800,2000,2200,2400,2600,2800};
            JComboBox<String> skillSelect = new JComboBox<>();
            for (int elo : eloValues) skillSelect.addItem("Elo " + elo);
            skillSelect.setSelectedIndex(0);
            board.setEngineElo(eloValues[0]);
            skillSelect.addActionListener(e -> board.setEngineElo(eloValues[skillSelect.getSelectedIndex()]));
            styleCombo(skillSelect);

            // ── Time control combo ────────────────────────────────────────────
            int[] timeMinutes = {1,3,5,10,15};
            JComboBox<String> timeSelect = new JComboBox<>();
            for (int m : timeMinutes) timeSelect.addItem(m + " min");
            timeSelect.setSelectedIndex(2);
            board.setTimeControlMinutes(timeMinutes[2]);
            timeSelect.addActionListener(e -> board.setTimeControlMinutes(timeMinutes[timeSelect.getSelectedIndex()]));
            styleCombo(timeSelect);

            // ── Info bar labels ───────────────────────────────────────────────
            JLabel statusLabel = new JLabel("Press Play to begin.");
            statusLabel.setForeground(Theme.TEXT_PRIMARY);
            statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 17f));
            statusLabel.setHorizontalAlignment(SwingConstants.CENTER);

            JLabel evalLabel = new JLabel("Material even");
            evalLabel.setForeground(Theme.ACCENT_PRIMARY);
            evalLabel.setFont(evalLabel.getFont().deriveFont(Font.BOLD, 13f));
            final String[] liveEval = {"Material even"};

            JLabel whiteCaptureLabel = new JLabel("White captures: -");
            whiteCaptureLabel.setForeground(Theme.TEXT_SECONDARY);
            whiteCaptureLabel.setFont(whiteCaptureLabel.getFont().deriveFont(Font.PLAIN, 13f));
            JLabel blackCaptureLabel = new JLabel("Black captures: -");
            blackCaptureLabel.setForeground(Theme.TEXT_SECONDARY);
            blackCaptureLabel.setFont(blackCaptureLabel.getFont().deriveFont(Font.PLAIN, 13f));

            ClockLabel whiteClockLabel = new ClockLabel("White", "05:00", true);
            ClockLabel blackClockLabel = new ClockLabel("Black", "05:00", false);

            // ── Toggle / radio ────────────────────────────────────────────────
            JCheckBox botToggle = new JCheckBox("Play vs Stockfish", true);
            styleToggle(botToggle);
            botToggle.addActionListener(e -> board.setHumanVsHuman(!botToggle.isSelected()));

            JRadioButton playAsWhite = new JRadioButton("Play as White");
            JRadioButton playAsBlack = new JRadioButton("Play as Black");
            styleToggle(playAsWhite); styleToggle(playAsBlack);
            playAsWhite.setSelected(true);
            board.setEnginePlaysWhite(false);
            playAsWhite.addActionListener(e -> board.setEnginePlaysWhite(false));
            playAsBlack.addActionListener(e -> board.setEnginePlaysWhite(true));
            ButtonGroup sideGroup = new ButtonGroup();
            sideGroup.add(playAsWhite); sideGroup.add(playAsBlack);

            // ── Buttons ───────────────────────────────────────────────────────
            JButton playButton = createPrimaryButton("Play");
            playButton.addActionListener(e -> {
                board.startNewGame();
                playButton.setText("Playing...");
                playButton.setEnabled(false);
            });

            JButton copyPgn = createSecondaryButton("Copy PGN");
            copyPgn.addActionListener(e -> Toolkit.getDefaultToolkit()
                .getSystemClipboard().setContents(
                    new StringSelection(board.getPgn(board.getLastResultTag())), null));

            JButton analyzeButton = createPrimaryButton("Analyze");
            JProgressBar analyzeProgress = new JProgressBar();
            analyzeProgress.setIndeterminate(true);
            analyzeProgress.setVisible(false);
            analyzeProgress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
            analyzeProgress.setForeground(Theme.ACCENT_PRIMARY);
            analyzeProgress.setBackground(Theme.INSET);
            analyzeProgress.setBorder(BorderFactory.createEmptyBorder(4,0,0,0));

            JButton analysisPrevButton = createGhostButton("◀  Prev");
            analysisPrevButton.setEnabled(false);
            analysisPrevButton.addActionListener(e -> board.stepAnalysis(-1));
            JButton analysisNextButton = createGhostButton("Next  ▶");
            analysisNextButton.setEnabled(false);
            analysisNextButton.addActionListener(e -> board.stepAnalysis(1));
            JButton exitReviewButton = createSecondaryButton("Exit");
            exitReviewButton.setEnabled(false);
            exitReviewButton.addActionListener(e -> board.exitAnalysisReview());

            // ── Text areas ────────────────────────────────────────────────────
            JTextArea moveArea = new JTextArea(6, 20);
            moveArea.setEditable(false);
            moveArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            moveArea.setLineWrap(true); moveArea.setWrapStyleWord(true);
            styleTextArea(moveArea);

            JTextArea analysisReportArea = new JTextArea(8, 20);
            analysisReportArea.setEditable(false);
            analysisReportArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            analysisReportArea.setText("Press Analyze to run a Stockfish-powered review.");
            analysisReportArea.setLineWrap(true); analysisReportArea.setWrapStyleWord(true);
            styleTextArea(analysisReportArea);

            JTextArea analysisDetailArea = new JTextArea(6, 20);
            analysisDetailArea.setEditable(false);
            analysisDetailArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            analysisDetailArea.setText("Press Analyze to see best-move feedback.");
            analysisDetailArea.setLineWrap(true); analysisDetailArea.setWrapStyleWord(true);
            styleTextArea(analysisDetailArea);

            // ── Analysis labels ───────────────────────────────────────────────
            JLabel analysisSummaryLabel = new JLabel("<html>Run analysis to enable review.</html>");
            analysisSummaryLabel.setForeground(Theme.TEXT_PRIMARY);
            analysisSummaryLabel.setFont(analysisSummaryLabel.getFont().deriveFont(Font.BOLD, 13f));

            JLabel analysisMoveLabel = new JLabel("<html>No analysis loaded.</html>");
            analysisMoveLabel.setForeground(Theme.ACCENT_PRIMARY);
            analysisMoveLabel.setFont(analysisMoveLabel.getFont().deriveFont(Font.BOLD, 12f));

            // ── Board callbacks ───────────────────────────────────────────────
            board.setStatusConsumer(msg -> SwingUtilities.invokeLater(() -> statusLabel.setText(msg)));
            board.setGameEndConsumer(msg -> SwingUtilities.invokeLater(() -> {
                playButton.setText("Play Again"); playButton.setEnabled(true);
            }));
            board.setEvaluationConsumer(msg -> SwingUtilities.invokeLater(() -> {
                liveEval[0] = msg;
                if (!board.isAnalysisMode()) evalLabel.setText(msg);
            }));
            board.setCaptureConsumer((w, b) -> SwingUtilities.invokeLater(() -> {
                whiteCaptureLabel.setText("White captures: " + w);
                blackCaptureLabel.setText("Black captures: " + b);
            }));
            board.setMoveLogConsumer(list -> SwingUtilities.invokeLater(() ->
                moveArea.setText(String.join("\n", list))));
            board.setClockConsumer((wt, bt) -> SwingUtilities.invokeLater(() -> {
                whiteClockLabel.setTime(wt); blackClockLabel.setTime(bt);
            }));
            board.setAnalysisFrameConsumer(af -> SwingUtilities.invokeLater(() -> {
                if (af.summary == null) {
                    analysisSummaryLabel.setText("<html>Run analysis to enable review.</html>");
                    analysisMoveLabel.setText("<html>No analysis loaded.</html>");
                    analysisDetailArea.setText("Press Analyze to see best-move feedback.");
                    analysisPrevButton.setEnabled(false);
                    analysisNextButton.setEnabled(false);
                    exitReviewButton.setEnabled(false);
                    evalLabel.setText(liveEval[0]);
                    return;
                }
                analysisSummaryLabel.setText(formatAnalysisSummary(af.summary));
                if (af.plyIndex < 0) {
                    analysisMoveLabel.setText("<html>Start position</html>");
                    analysisDetailArea.setText("Use ▶ to step through the reviewed game.");
                    evalLabel.setText("Review ready — step through moves");
                } else {
                    String mover = af.entry != null && af.entry.isWhite ? "White" : "Black";
                    String tag = af.entry != null ? af.entry.qualityTag : null;
                    String lbl = String.format("Move %d (%s): %s", af.moveNumber(), mover, af.san);
                    if (tag != null) lbl += " — " + formatQualityTag(tag);
                    analysisMoveLabel.setText("<html>" + lbl + "</html>");
                    analysisDetailArea.setText(formatAnalysisDetail(af));
                    if (af.entry != null) evalLabel.setText(buildEvalHeadline(af.entry));
                }
                analysisPrevButton.setEnabled(af.plyIndex >= 0);
                analysisNextButton.setEnabled(af.plyIndex < af.totalPlies - 1);
                exitReviewButton.setEnabled(true);
            }));

            analyzeButton.addActionListener(e -> {
                analyzeButton.setEnabled(false); analyzeButton.setText("Analyzing…");
                analyzeProgress.setVisible(true);
                analysisReportArea.setText("Analyzing game...");
                board.analyzeGame(lines -> {
                    analysisReportArea.setText(String.join("\n", lines));
                    analyzeButton.setText("Analyze"); analyzeButton.setEnabled(true);
                    analyzeProgress.setVisible(false);
                });
            });

            // ── Scroll panes for text areas ───────────────────────────────────
            JScrollPane detailScroll = wrapScroll(analysisDetailArea);
            detailScroll.setPreferredSize(new Dimension(10, 190));
            JScrollPane reportScroll = wrapScroll(analysisReportArea);
            reportScroll.setPreferredSize(new Dimension(10, 150));
            JScrollPane movesScroll  = wrapScroll(moveArea);
            movesScroll.setPreferredSize(new Dimension(10, 130));

            // ── Setup form ────────────────────────────────────────────────────
            JPanel setupForm = new JPanel(new GridBagLayout());
            setupForm.setOpaque(false);
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 2, 5, 2);
            gbc.fill = GridBagConstraints.HORIZONTAL;

            JLabel diffLbl = styledFormLabel("Difficulty");
            gbc.gridx=0; gbc.gridy=0; gbc.weightx=0; gbc.anchor=GridBagConstraints.WEST;
            setupForm.add(diffLbl, gbc);
            gbc.gridx=1; gbc.weightx=1.0;
            setupForm.add(skillSelect, gbc);

            JLabel timeLbl = styledFormLabel("Time Control");
            gbc.gridx=0; gbc.gridy=1; gbc.weightx=0;
            setupForm.add(timeLbl, gbc);
            gbc.gridx=1; gbc.weightx=1.0;
            setupForm.add(timeSelect, gbc);

            gbc.gridx=0; gbc.gridy=2; gbc.gridwidth=2;
            setupForm.add(botToggle, gbc);

            JPanel sideChoice = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            sideChoice.setOpaque(false);
            sideChoice.add(playAsWhite); sideChoice.add(playAsBlack);
            gbc.gridy=3;
            setupForm.add(sideChoice, gbc);

            // ── Controls panel ────────────────────────────────────────────────
            Dimension btnFull = new Dimension(Integer.MAX_VALUE, 38);
            playButton.setAlignmentX(Component.LEFT_ALIGNMENT);    playButton.setMaximumSize(btnFull);
            analyzeButton.setAlignmentX(Component.LEFT_ALIGNMENT); analyzeButton.setMaximumSize(btnFull);
            copyPgn.setAlignmentX(Component.LEFT_ALIGNMENT);       copyPgn.setMaximumSize(btnFull);
            analyzeProgress.setAlignmentX(Component.LEFT_ALIGNMENT);

            JPanel controlsPanel = new JPanel();
            controlsPanel.setOpaque(false);
            controlsPanel.setLayout(new BoxLayout(controlsPanel, BoxLayout.Y_AXIS));
            controlsPanel.add(playButton);
            controlsPanel.add(Box.createVerticalStrut(10));
            controlsPanel.add(analyzeButton);
            controlsPanel.add(analyzeProgress);
            controlsPanel.add(Box.createVerticalStrut(10));
            controlsPanel.add(copyPgn);

            // ── Analysis review panel ─────────────────────────────────────────
            JPanel navRow = new JPanel(new GridLayout(1, 3, 6, 0));
            navRow.setOpaque(false);
            navRow.add(analysisPrevButton);
            navRow.add(analysisNextButton);
            navRow.add(exitReviewButton);

            JSeparator sep = new JSeparator();
            sep.setForeground(Theme.DIVIDER); sep.setBackground(Theme.DIVIDER);
            sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));

            JPanel analysisContent = new JPanel();
            analysisContent.setOpaque(false);
            analysisContent.setLayout(new BoxLayout(analysisContent, BoxLayout.Y_AXIS));
            for (JComponent c : new JComponent[]{analysisSummaryLabel, analysisMoveLabel,
                detailScroll, sep, navRow}) {
                c.setAlignmentX(Component.LEFT_ALIGNMENT);
                c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getMaximumSize().height));
            }
            analysisContent.add(analysisSummaryLabel);
            analysisContent.add(Box.createVerticalStrut(6));
            analysisContent.add(analysisMoveLabel);
            analysisContent.add(Box.createVerticalStrut(10));
            analysisContent.add(detailScroll);
            analysisContent.add(Box.createVerticalStrut(10));
            analysisContent.add(sep);
            analysisContent.add(Box.createVerticalStrut(8));
            analysisContent.add(navRow);

            // ── History tabs ──────────────────────────────────────────────────
            JTabbedPane logTabs = new JTabbedPane();
            logTabs.addTab("Moves", movesScroll);
            logTabs.addTab("Analysis Log", reportScroll);
            logTabs.setForeground(Theme.TEXT_SECONDARY);
            logTabs.setBackground(Theme.PANEL);
            logTabs.setOpaque(false);
            logTabs.setFont(logTabs.getFont().deriveFont(Font.PLAIN, 12f));
            logTabs.setBorder(BorderFactory.createEmptyBorder());
            logTabs.setUI(new FlatTabbedPaneUI());

            // ── Side column ───────────────────────────────────────────────────
            JPanel sideColumn = new JPanel();
            sideColumn.setBackground(Theme.BG);
            sideColumn.setLayout(new BoxLayout(sideColumn, BoxLayout.Y_AXIS));
            sideColumn.setBorder(BorderFactory.createEmptyBorder(14, 10, 20, 10));

            for (Object[] spec : new Object[][]{
                {"Game Setup",    setupForm},
                {"Controls",      controlsPanel},
                {"Engine Review", analysisContent},
                {"History",       logTabs}
            }) {
                JPanel card = createCard((String)spec[0], (JComponent)spec[1]);
                card.setAlignmentX(Component.LEFT_ALIGNMENT);
                card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
                sideColumn.add(card);
                sideColumn.add(Box.createVerticalStrut(10));
            }
            sideColumn.add(Box.createVerticalGlue());

            // ── Info bar ──────────────────────────────────────────────────────
            JPanel captureBlock = new JPanel();
            captureBlock.setOpaque(false);
            captureBlock.setLayout(new BoxLayout(captureBlock, BoxLayout.Y_AXIS));
            captureBlock.add(whiteCaptureLabel);
            captureBlock.add(blackCaptureLabel);

            JPanel infoLeft = new JPanel();
            infoLeft.setOpaque(false);
            infoLeft.setLayout(new BoxLayout(infoLeft, BoxLayout.Y_AXIS));
            infoLeft.add(evalLabel);
            infoLeft.add(Box.createVerticalStrut(4));
            infoLeft.add(captureBlock);

            JPanel clockRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            clockRow.setOpaque(false);
            clockRow.add(whiteClockLabel);
            clockRow.add(blackClockLabel);

            JPanel infoBar = new JPanel(new BorderLayout(12, 0));
            infoBar.setBackground(Theme.PANEL);
            infoBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER),
                BorderFactory.createEmptyBorder(12, 16, 12, 16)
            ));
            infoBar.add(infoLeft,   BorderLayout.WEST);
            infoBar.add(statusLabel, BorderLayout.CENTER);
            infoBar.add(clockRow,   BorderLayout.EAST);

            // ── Board area ────────────────────────────────────────────────────
            JPanel boardFrame = new JPanel(new BorderLayout());
            boardFrame.setBackground(new Color(53, 45, 36));
            boardFrame.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(90, 78, 64), 2),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)
            ));
            boardFrame.add(board, BorderLayout.CENTER);

            // Center the board without wasting horizontal space
            JPanel boardArea = new JPanel(new GridBagLayout());
            boardArea.setBackground(Theme.BG);
            boardArea.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
            boardArea.add(boardFrame, new GridBagConstraints());

            JPanel centerColumn = new JPanel(new BorderLayout());
            centerColumn.setBackground(Theme.BG);
            centerColumn.add(infoBar,   BorderLayout.NORTH);
            centerColumn.add(boardArea, BorderLayout.CENTER);

            // ── Side scroll pane — invisible zero-width scrollbar ─────────────
            JScrollPane sideScroll = new JScrollPane(sideColumn);
            sideScroll.setBorder(BorderFactory.createEmptyBorder());
            sideScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            sideScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
            sideScroll.getViewport().setBackground(Theme.BG);
            sideScroll.getVerticalScrollBar().setUnitIncrement(20);
            sideScroll.getVerticalScrollBar().setPreferredSize(new Dimension(0, 0));
            sideScroll.setPreferredSize(new Dimension(300, 0));

            // ── Root ──────────────────────────────────────────────────────────
            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(Theme.BG);
            root.add(centerColumn, BorderLayout.CENTER);
            root.add(sideScroll,   BorderLayout.EAST);

            frame.setContentPane(root);
            frame.pack();
            frame.setMinimumSize(new Dimension(1100, 780));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    // ── Formatting helpers ────────────────────────────────────────────────────

    private static String formatAnalysisSummary(GameAnalyzer.Summary s) {
        return String.format(
            "<html>W <b>%.1f%%</b> &nbsp; B <b>%.1f%%</b><br>" +
                "<font color='#9e9e9e'>Blunders %d &nbsp; Mistakes %d &nbsp; Inaccuracies %d</font></html>",
            s.whiteAccuracy, s.blackAccuracy, s.blunders, s.mistakes, s.inaccuracies);
    }

    private static String formatAnalysisDetail(Board.AnalysisFrame f) {
        if (f.entry == null) return "Use ▶ to step through the reviewed moves.";
        GameAnalyzer.Entry e = f.entry;
        return "Played: " + f.san + "\n"
            + "Tag: " + formatQualityTag(e.qualityTag) + "\n"
            + String.format("Eval: %.2f → %.2f (Δ %.2f)\n", e.evalBefore, e.evalAfter, e.evalAfter - e.evalBefore)
            + String.format("Loss vs best: %.2f pawns\n", Math.max(0, e.loss))
            + "Best: " + (e.bestMove == null ? "n/a" : friendlyMove(e.bestMove)) + "\n"
            + (e.bestLine.isEmpty() ? "" : "Line: " + String.join(" ", e.bestLine.subList(0, Math.min(8, e.bestLine.size()))) + "\n")
            + "\n" + buildMoveFeedback(e);
    }

    private static String formatQualityTag(String tag) {
        if (tag == null || tag.isBlank()) return "Unknown";
        switch (tag) {
            case "Brilliant":      return "💎 Brilliant";
            case "Great":          return "✨ Great";
            case "Best":           return "✔ Best";
            case "Excellent":      return "👍 Excellent";
            case "Good":           return "🙂 Good";
            case "Inaccuracy":     return "?! Inaccuracy";
            case "Mistake":        return "?? Mistake";
            case "Severe Mistake": return "☡ Severe Mistake";
            case "Blunder":        return "☠ Blunder";
            case "Mate":           return "♛ Forced Mate";
            default:               return tag;
        }
    }

    private static String buildEvalHeadline(GameAnalyzer.Entry e) {
        if (e == null) return "Review ready — step through moves";
        double loss = Math.max(0, e.loss), gain = e.loss < 0 ? -e.loss : 0;
        String tag = formatQualityTag(e.qualityTag);
        if (loss >= 0.1) {
            String s = tag + String.format(" — lost %.2f pawns", loss);
            if (e.bestMove != null) s += "; better was " + friendlyMove(e.bestMove);
            return s;
        }
        if (gain >= 0.1) return String.format("%s — improved %.2f pawns", tag, gain);
        return tag + " — keeps the evaluation steady";
    }

    private static String buildMoveFeedback(GameAnalyzer.Entry e) {
        double loss = Math.max(0, e.loss), gain = e.loss < 0 ? -e.loss : 0;
        if (loss >= 3.0) return String.format("Huge swing: drops %.2f pawns. Stockfish preferred %s.", loss, friendlyMove(e.bestMove));
        if (loss >= 1.5) return String.format("Serious mistake (%.2f pawn loss). Consider %s instead.", loss, friendlyMove(e.bestMove));
        if (loss >= 0.5) return String.format("Inaccuracy: worsens by %.2f pawns. Try %s.", loss, friendlyMove(e.bestMove));
        if (gain >= 1.0) return String.format("Crushing find! You gain %.2f pawns over best play.", gain);
        if (gain >= 0.2) return String.format("Nice improvement (+%.2f pawns).", gain);
        return "Solid move that keeps the evaluation close to the engine line.";
    }

    private static String friendlyMove(String uci) {
        if (uci == null || uci.length() < 4) return "n/a";
        String promo = uci.length() > 4 ? "=" + Character.toUpperCase(uci.charAt(4)) : "";
        return uci.substring(0, 2) + "→" + uci.substring(2, 4) + promo;
    }

    // ── UI factory helpers ────────────────────────────────────────────────────

    private static JPanel createCard(String title, JComponent content) {
        CardPanel card = new CardPanel();
        card.setLayout(new BorderLayout());
        card.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        if (title != null && !title.isBlank()) {
            JLabel h = new JLabel(title);
            h.setForeground(Theme.TEXT_PRIMARY);
            h.setFont(h.getFont().deriveFont(Font.BOLD, 14f));
            h.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
            card.add(h, BorderLayout.NORTH);
        }
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private static JLabel styledFormLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Theme.TEXT_SECONDARY);
        l.setFont(l.getFont().deriveFont(Font.PLAIN, 13f));
        return l;
    }

    private static JScrollPane wrapScroll(JComponent c) {
        JScrollPane scroll = new JScrollPane(c);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBorder(null);
        scroll.getViewport().setBackground(Theme.INSET);
        scroll.setBackground(Theme.INSET);
        scroll.setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        JScrollBar vb = scroll.getVerticalScrollBar();
        vb.setPreferredSize(new Dimension(5, 0));
        vb.setOpaque(false);
        vb.setUI(new BasicScrollBarUI() {
            @Override protected void configureScrollBarColors() { thumbColor = new Color(100,100,100); trackColor = Theme.INSET; }
            @Override protected JButton createDecreaseButton(int o) { return zeroBtn(); }
            @Override protected JButton createIncreaseButton(int o) { return zeroBtn(); }
            private JButton zeroBtn() { JButton b = new JButton(); b.setPreferredSize(new Dimension(0,0)); return b; }
            @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { g.setColor(Theme.INSET); g.fillRect(r.x,r.y,r.width,r.height); }
            @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
                if (r.isEmpty()) return;
                Graphics2D g2 = (Graphics2D)g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(120,120,120,200));
                g2.fillRoundRect(r.x+1, r.y+2, r.width-2, r.height-4, 5, 5);
                g2.dispose();
            }
        });
        return scroll;
    }

    private static void styleTextArea(JTextArea a) {
        a.setBackground(Theme.INSET); a.setForeground(Theme.TEXT_PRIMARY);
        a.setCaretColor(Theme.TEXT_PRIMARY);
        a.setSelectionColor(new Color(93,156,255,100));
        a.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.FIELD_BORDER, 1, true),
            BorderFactory.createEmptyBorder(7, 9, 7, 9)));
    }

    private static void styleToggle(AbstractButton b) {
        b.setOpaque(false); b.setForeground(Theme.TEXT_PRIMARY);
        b.setFont(b.getFont().deriveFont(Font.PLAIN, 13f));
    }

    private static void styleCombo(JComboBox<?> combo) {
        combo.setUI(new BasicComboBoxUI() {
            @Override protected JButton createArrowButton() {
                JButton btn = new JButton("▾") {
                    @Override protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D)g.create();
                        g2.setColor(Theme.FIELD_BG); g2.fillRect(0,0,getWidth(),getHeight());
                        g2.setColor(Theme.TEXT_SECONDARY);
                        g2.setFont(getFont().deriveFont(Font.PLAIN, 11f));
                        FontMetrics fm = g2.getFontMetrics(); String t = "▾";
                        g2.drawString(t, (getWidth()-fm.stringWidth(t))/2, (getHeight()+fm.getAscent()-fm.getDescent())/2);
                        g2.dispose();
                    }
                };
                btn.setBorder(BorderFactory.createEmptyBorder(0,5,0,5));
                btn.setFocusPainted(false); btn.setContentAreaFilled(false); btn.setOpaque(false);
                return btn;
            }
            @Override public void paintCurrentValueBackground(Graphics g, Rectangle b, boolean f) {
                g.setColor(Theme.FIELD_BG); g.fillRect(b.x,b.y,b.width,b.height);
            }
            @Override public void paintCurrentValue(Graphics g, Rectangle b, boolean f) {
                Object sel = comboBox.getSelectedItem(); if (sel == null) return;
                Graphics2D g2 = (Graphics2D)g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(Theme.TEXT_PRIMARY); g2.setFont(comboBox.getFont().deriveFont(Font.PLAIN, 13f));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(sel.toString(), b.x+4, b.y+(b.height+fm.getAscent()-fm.getDescent())/2);
                g2.dispose();
            }
        });
        combo.setOpaque(true); combo.setBackground(Theme.FIELD_BG); combo.setForeground(Theme.TEXT_PRIMARY);
        combo.setFont(combo.getFont().deriveFont(Font.PLAIN, 13f));
        combo.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.FIELD_BORDER, 1, true),
            BorderFactory.createEmptyBorder(2, 7, 2, 3)));
        combo.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object val, int idx, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, val, idx, sel, focus);
                setBackground(sel ? Theme.ACCENT_PRIMARY.darker() : Theme.FIELD_BG);
                setForeground(sel ? Color.WHITE : Theme.TEXT_PRIMARY);
                setBorder(BorderFactory.createEmptyBorder(4,10,4,10));
                setFont(getFont().deriveFont(Font.PLAIN, 13f));
                return this;
            }
        });
    }

    private static JButton createPrimaryButton(String text) {
        return new StyledButton(text, Theme.ACCENT_PRIMARY, Theme.ACCENT_PRIMARY.brighter(), Theme.ACCENT_PRIMARY.darker());
    }
    private static JButton createSecondaryButton(String text) {
        StyledButton b = new StyledButton(text, Theme.BUTTON_BG, Theme.BUTTON_BG.brighter(), Theme.BUTTON_BG.darker());
        b.setFont(b.getFont().deriveFont(Font.PLAIN, 13f)); return b;
    }
    private static JButton createGhostButton(String text) {
        StyledButton b = new StyledButton(text, Theme.GHOST_BG, Theme.GHOST_BG.brighter(), Theme.GHOST_BG.darker());
        b.setForeground(Theme.TEXT_SECONDARY); b.setFont(b.getFont().deriveFont(Font.PLAIN, 12f));
        b.setBorder(BorderFactory.createEmptyBorder(6,10,6,10)); return b;
    }

    // ── Inner classes ─────────────────────────────────────────────────────────

    private static final class CardPanel extends JPanel {
        CardPanel() { setOpaque(false); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D)g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc=16, ins=3, w=Math.max(0,getWidth()-ins*2-1), h=Math.max(0,getHeight()-ins*2-1);
            g2.setColor(new Color(0,0,0,50)); g2.fillRoundRect(ins+5,ins+5,w,h,arc+4,arc+4);
            g2.setPaint(new GradientPaint(ins,ins,Theme.PANEL_LIGHT,ins,ins+h,Theme.PANEL));
            g2.fillRoundRect(ins,ins,w,h,arc,arc);
            g2.setColor(new Color(255,255,255,18)); g2.drawRoundRect(ins,ins,w,h,arc,arc);
            g2.dispose(); super.paintComponent(g);
        }
    }

    private static final class StyledButton extends JButton {
        private final Color base, hover, pressed;
        StyledButton(String text, Color base, Color hover, Color pressed) {
            super(text); this.base=base; this.hover=hover; this.pressed=pressed;
            setFocusPainted(false); setContentAreaFilled(false); setOpaque(false);
            setForeground(Theme.TEXT_PRIMARY); setFont(getFont().deriveFont(Font.BOLD, 13f));
            setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D)g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w=getWidth(), h=getHeight();
            if (!isEnabled()) { g2.setColor(Theme.BUTTON_DISABLED); g2.fillRoundRect(0,0,w,h,14,14); }
            else if (getModel().isPressed()) { g2.setColor(pressed); g2.fillRoundRect(0,0,w,h,14,14); }
            else if (getModel().isRollover()) { g2.setPaint(new GradientPaint(0,0,hover.brighter(),0,h,hover)); g2.fillRoundRect(0,0,w,h,14,14); }
            else { g2.setPaint(new GradientPaint(0,0,base,0,h,base.darker())); g2.fillRoundRect(0,0,w,h,14,14);
                g2.setColor(new Color(255,255,255,35)); g2.drawRoundRect(0,0,w-1,h-1,14,14); }
            g2.dispose(); super.paintComponent(g);
        }
        @Override public Color getForeground() { return !isEnabled() ? Theme.TEXT_SECONDARY : super.getForeground(); }
    }

    private static final class FlatTabbedPaneUI extends BasicTabbedPaneUI {
        @Override protected void installDefaults() {
            super.installDefaults();
            tabInsets = new Insets(5,10,5,10); tabAreaInsets = new Insets(4,6,0,6);
        }
        @Override protected void paintTabBackground(Graphics g, int tp, int ti, int x, int y, int w, int h, boolean sel) {
            Graphics2D g2=(Graphics2D)g.create(); g2.setColor(sel?Theme.INSET:Theme.GHOST_BG);
            g2.fillRoundRect(x,y+2,w,h-4,12,12); g2.dispose();
        }
        @Override protected void paintTabBorder(Graphics g,int a,int b,int x,int y,int w,int h,boolean s){}
        @Override protected void paintFocusIndicator(Graphics g,int a,Rectangle[] b,int c,Rectangle d,Rectangle e,boolean f){}
        @Override protected void paintContentBorder(Graphics g,int a,int b){}
        @Override protected void paintText(Graphics g,int tp,Font font,FontMetrics fm,int ti,String title,Rectangle tr,boolean sel){
            g.setFont(font.deriveFont(sel?Font.BOLD:Font.PLAIN,12f));
            g.setColor(sel?Theme.TEXT_PRIMARY:Theme.TEXT_SECONDARY);
            g.drawString(title,tr.x,tr.y+fm.getAscent());
        }
    }

    private static final class Theme {
        static final Color BG            = new Color(30,30,30);
        static final Color PANEL         = new Color(45,45,45);
        static final Color PANEL_LIGHT   = new Color(55,55,55);
        static final Color INSET         = new Color(32,32,32);
        static final Color FIELD_BG      = new Color(52,52,52);
        static final Color FIELD_BORDER  = new Color(74,74,74);
        static final Color TEXT_PRIMARY  = new Color(232,232,232);
        static final Color TEXT_SECONDARY= new Color(158,158,158);
        static final Color ACCENT_PRIMARY= new Color(93,156,255);
        static final Color BUTTON_BG     = new Color(63,63,63);
        static final Color BUTTON_DISABLED=new Color(55,55,55);
        static final Color GHOST_BG      = new Color(50,50,50);
        static final Color DIVIDER       = new Color(60,60,60);
        static final Color CLOCK_WHITE_BG= new Color(240,236,220);
        static final Color CLOCK_BLACK_BG= new Color(42,42,42);
        static final Color CLOCK_WHITE_FG= new Color(30,30,30);
        static final Color CLOCK_BLACK_FG= new Color(220,220,220);
    }

    private static final class ClockLabel extends JPanel {
        private final JLabel timeLabel;
        private final boolean isWhite;
        ClockLabel(String side, String time, boolean isWhite) {
            this.isWhite = isWhite;
            setOpaque(false); setLayout(new BorderLayout());
            JLabel sideLabel = new JLabel(side.toUpperCase());
            sideLabel.setFont(sideLabel.getFont().deriveFont(Font.BOLD, 9f));
            sideLabel.setForeground(isWhite ? new Color(90,75,50) : Theme.TEXT_SECONDARY);
            sideLabel.setHorizontalAlignment(SwingConstants.CENTER);
            timeLabel = new JLabel(time);
            timeLabel.setFont(timeLabel.getFont().deriveFont(Font.BOLD, 15f));
            timeLabel.setForeground(isWhite ? Theme.CLOCK_WHITE_FG : Theme.CLOCK_BLACK_FG);
            timeLabel.setHorizontalAlignment(SwingConstants.CENTER);
            JPanel inner = new JPanel();
            inner.setOpaque(false);
            inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
            inner.setBorder(BorderFactory.createEmptyBorder(4,11,4,11));
            sideLabel.setAlignmentX(CENTER_ALIGNMENT); timeLabel.setAlignmentX(CENTER_ALIGNMENT);
            inner.add(sideLabel); inner.add(Box.createVerticalStrut(1)); inner.add(timeLabel);
            add(inner, BorderLayout.CENTER);
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D)g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isWhite ? Theme.CLOCK_WHITE_BG : Theme.CLOCK_BLACK_BG);
            g2.fillRoundRect(0,0,getWidth(),getHeight(),20,20);
            g2.setColor(isWhite ? new Color(180,160,120,100) : new Color(255,255,255,25));
            g2.drawRoundRect(0,0,getWidth()-1,getHeight()-1,20,20);
            g2.dispose(); super.paintComponent(g);
        }
        void setTime(String t) { timeLabel.setText(t); }
    }
}