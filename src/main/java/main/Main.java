package main;

import analysis.GameAnalyzer;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.FlowLayout;
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

            int[] eloValues = {800, 1000, 1200, 1400, 1600, 1800, 2000, 2200, 2400, 2600, 2800};
            JComboBox<String> skillSelect = new JComboBox<>();
            for (int elo : eloValues) {
                skillSelect.addItem("Elo " + elo);
            }
            int defaultIndex = 0; // 800
            skillSelect.setSelectedIndex(defaultIndex);
            board.setEngineElo(eloValues[defaultIndex]);
            skillSelect.addActionListener(e ->
                board.setEngineElo(eloValues[skillSelect.getSelectedIndex()])
            );
            styleCombo(skillSelect);

            int[] timeMinutes = {1, 3, 5, 10, 15};
            JComboBox<String> timeSelect = new JComboBox<>();
            for (int mins : timeMinutes) {
                timeSelect.addItem(mins + " min");
            }
            int defaultTimeIndex = 2; // 5 minutes
            timeSelect.setSelectedIndex(defaultTimeIndex);
            board.setTimeControlMinutes(timeMinutes[defaultTimeIndex]);
            timeSelect.addActionListener(e ->
                board.setTimeControlMinutes(timeMinutes[timeSelect.getSelectedIndex()])
            );
            styleCombo(timeSelect);

            JLabel statusLabel = new JLabel("Press Play to begin.");
            statusLabel.setForeground(Theme.TEXT_PRIMARY);
            statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 17f));

            JLabel evalLabel = new JLabel("Material even");
            evalLabel.setForeground(Theme.TEXT_PRIMARY);
            evalLabel.setFont(evalLabel.getFont().deriveFont(Font.PLAIN, 15f));
            final String[] liveEvalMessage = {"Material even"};

            JLabel whiteCaptureLabel = new JLabel("White captures: -");
            whiteCaptureLabel.setForeground(Theme.TEXT_SECONDARY);
            whiteCaptureLabel.setFont(whiteCaptureLabel.getFont().deriveFont(Font.PLAIN, 14f));
            JLabel blackCaptureLabel = new JLabel("Black captures: -");
            blackCaptureLabel.setForeground(Theme.TEXT_SECONDARY);
            blackCaptureLabel.setFont(blackCaptureLabel.getFont().deriveFont(Font.PLAIN, 14f));
            JLabel whiteClockLabel = new JLabel("White 05:00");
            whiteClockLabel.setForeground(Theme.TEXT_PRIMARY);
            whiteClockLabel.setFont(whiteClockLabel.getFont().deriveFont(Font.BOLD, 16f));
            JLabel blackClockLabel = new JLabel("Black 05:00");
            blackClockLabel.setForeground(Theme.TEXT_PRIMARY);
            blackClockLabel.setFont(blackClockLabel.getFont().deriveFont(Font.BOLD, 16f));

            JCheckBox botToggle = new JCheckBox("Play vs Stockfish", true);
            styleToggle(botToggle);
            botToggle.addActionListener(e -> {
                boolean playBot = botToggle.isSelected();
                board.setHumanVsHuman(!playBot);
            });

            JButton playButton = createPrimaryButton("Play");
            playButton.addActionListener(e -> {
                board.startNewGame();
                playButton.setText("Playing...");
                playButton.setEnabled(false);
            });

            board.setStatusConsumer(message ->
                SwingUtilities.invokeLater(() -> statusLabel.setText(message))
            );
            board.setGameEndConsumer(message ->
                SwingUtilities.invokeLater(() -> {
                    playButton.setText("Play Again");
                    playButton.setEnabled(true);
                })
            );
            board.setEvaluationConsumer(message ->
                SwingUtilities.invokeLater(() -> {
                    liveEvalMessage[0] = message;
                    if(!board.isAnalysisMode()){
                        evalLabel.setText(message);
                    }
                })
            );
            JTextArea moveArea = new JTextArea(6, 40);
            moveArea.setEditable(false);
            moveArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            styleTextArea(moveArea);

            JTextArea analysisReportArea = new JTextArea(10, 40);
            analysisReportArea.setEditable(false);
            analysisReportArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            analysisReportArea.setText("Press Analyze to run a Stockfish-powered review.");
            styleTextArea(analysisReportArea);

            JTextArea analysisDetailArea = new JTextArea(4, 30);
            analysisDetailArea.setEditable(false);
            analysisDetailArea.setLineWrap(true);
            analysisDetailArea.setWrapStyleWord(true);
            analysisDetailArea.setText("Press Analyze to see best-move feedback.");
            styleTextArea(analysisDetailArea);

            JLabel analysisSummaryLabel = new JLabel("Run analysis to enable review.");
            analysisSummaryLabel.setForeground(Theme.TEXT_PRIMARY);
            JLabel analysisMoveLabel = new JLabel("No analysis loaded.");
            analysisMoveLabel.setForeground(Theme.TEXT_SECONDARY);

            JButton analysisPrevButton = createGhostButton("◀");
            analysisPrevButton.setEnabled(false);
            analysisPrevButton.addActionListener(e -> board.stepAnalysis(-1));
            JButton analysisNextButton = createGhostButton("▶");
            analysisNextButton.setEnabled(false);
            analysisNextButton.addActionListener(e -> board.stepAnalysis(1));
            JButton exitReviewButton = createSecondaryButton("Exit Review");
            exitReviewButton.setEnabled(false);
            exitReviewButton.addActionListener(e -> board.exitAnalysisReview());

            board.setCaptureConsumer((white, black) ->
                SwingUtilities.invokeLater(() -> {
                    whiteCaptureLabel.setText("White captures: " + white);
                    blackCaptureLabel.setText("Black captures: " + black);
                })
            );
            board.setMoveLogConsumer(list ->
                SwingUtilities.invokeLater(() -> moveArea.setText(String.join("\n", list)))
            );
            board.setClockConsumer((whiteTime, blackTime) ->
                SwingUtilities.invokeLater(() -> {
                    whiteClockLabel.setText("White " + whiteTime);
                    blackClockLabel.setText("Black " + blackTime);
                })
            );

            board.setAnalysisFrameConsumer(analysisFrame ->
                SwingUtilities.invokeLater(() -> {
                    if(analysisFrame.summary == null){
                        analysisSummaryLabel.setText("Run analysis to enable review.");
                        analysisMoveLabel.setText("No analysis loaded.");
                        analysisDetailArea.setText("Press Analyze to see best-move feedback.");
                        analysisPrevButton.setEnabled(false);
                        analysisNextButton.setEnabled(false);
                        exitReviewButton.setEnabled(false);
                        evalLabel.setText(liveEvalMessage[0]);
                        return;
                    }
                    analysisSummaryLabel.setText(formatAnalysisSummary(analysisFrame.summary));
                    if(analysisFrame.plyIndex < 0){
                        analysisMoveLabel.setText("Start position");
                        analysisDetailArea.setText("Use ▶ to step through the reviewed game.");
                        evalLabel.setText("Review ready — step through moves");
                    } else {
                        String mover = analysisFrame.entry != null && analysisFrame.entry.isWhite ? "White" : "Black";
                        String tag = analysisFrame.entry != null ? analysisFrame.entry.qualityTag : null;
                        String label = String.format("Move %d (%s): %s", analysisFrame.moveNumber(), mover, analysisFrame.san);
                        if(tag != null){
                            label += " — " + formatQualityTag(tag);
                        }
                        analysisMoveLabel.setText(label);
                        analysisDetailArea.setText(formatAnalysisDetail(analysisFrame));
                        if(analysisFrame.entry != null){
                            evalLabel.setText(buildEvalHeadline(analysisFrame.entry));
                        }
                    }
                    analysisPrevButton.setEnabled(analysisFrame.plyIndex >= 0);
                    analysisNextButton.setEnabled(analysisFrame.plyIndex < analysisFrame.totalPlies - 1);
                    exitReviewButton.setEnabled(true);
                })
            );

            statusLabel.setHorizontalAlignment(SwingConstants.CENTER);

            JRadioButton playAsWhite = new JRadioButton("Play as White");
            JRadioButton playAsBlack = new JRadioButton("Play as Black");
            styleToggle(playAsWhite);
            styleToggle(playAsBlack);
            playAsWhite.setSelected(true);
            board.setEnginePlaysWhite(false);
            playAsWhite.addActionListener(e -> board.setEnginePlaysWhite(false));
            playAsBlack.addActionListener(e -> board.setEnginePlaysWhite(true));
            ButtonGroup sideGroup = new ButtonGroup();
            sideGroup.add(playAsWhite);
            sideGroup.add(playAsBlack);

            JButton copyPgn = createSecondaryButton("Copy PGN");
            copyPgn.addActionListener(e -> {
                String pgn = board.getPgn(board.getLastResultTag());
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(pgn), null);
            });
            JButton analyzeButton = createPrimaryButton("Analyze");
            analyzeButton.addActionListener(e -> {
                analyzeButton.setEnabled(false);
                analysisReportArea.setText("Analyzing game...");
                board.analyzeGame(lines -> {
                    analysisReportArea.setText(String.join("\n", lines));
                    analyzeButton.setEnabled(true);
                });
            });
            JScrollPane detailScroll = wrapScroll(analysisDetailArea);
            detailScroll.setPreferredSize(new Dimension(260, 150));
            JScrollPane reportScroll = wrapScroll(analysisReportArea);
            JScrollPane movesScroll = wrapScroll(moveArea);

            JPanel analysisControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            analysisControls.setOpaque(false);
            JLabel navLabel = new JLabel("Navigate");
            navLabel.setForeground(Theme.TEXT_SECONDARY);
            analysisControls.add(navLabel);
            analysisControls.add(analysisPrevButton);
            analysisControls.add(analysisNextButton);
            analysisControls.add(exitReviewButton);
            analysisControls.setAlignmentX(Component.LEFT_ALIGNMENT);

            JPanel setupForm = new JPanel(new GridBagLayout());
            setupForm.setOpaque(false);
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(6, 4, 6, 4);
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;

            JLabel difficultyLabel = new JLabel("Difficulty");
            difficultyLabel.setForeground(Theme.TEXT_SECONDARY);
            gbc.gridx = 0; gbc.gridy = 0;
            setupForm.add(difficultyLabel, gbc);
            gbc.gridx = 1;
            setupForm.add(skillSelect, gbc);

            JLabel timeLabel = new JLabel("Time Control");
            timeLabel.setForeground(Theme.TEXT_SECONDARY);
            gbc.gridx = 0; gbc.gridy = 1;
            setupForm.add(timeLabel, gbc);
            gbc.gridx = 1;
            setupForm.add(timeSelect, gbc);

            gbc.gridx = 0; gbc.gridy = 2;
            gbc.gridwidth = 2;
            setupForm.add(botToggle, gbc);

            JPanel sideChoice = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            sideChoice.setOpaque(false);
            sideChoice.add(playAsWhite);
            sideChoice.add(playAsBlack);
            gbc.gridy = 3;
            setupForm.add(sideChoice, gbc);

            JPanel controlsPanel = new JPanel();
            controlsPanel.setOpaque(false);
            controlsPanel.setLayout(new BoxLayout(controlsPanel, BoxLayout.Y_AXIS));
            playButton.setAlignmentX(Component.LEFT_ALIGNMENT);
            analyzeButton.setAlignmentX(Component.LEFT_ALIGNMENT);
            copyPgn.setAlignmentX(Component.LEFT_ALIGNMENT);
            controlsPanel.add(playButton);
            controlsPanel.add(Box.createVerticalStrut(10));
            controlsPanel.add(analyzeButton);
            controlsPanel.add(Box.createVerticalStrut(6));
            controlsPanel.add(copyPgn);

            JPanel analysisContent = new JPanel();
            analysisContent.setOpaque(false);
            analysisContent.setLayout(new BoxLayout(analysisContent, BoxLayout.Y_AXIS));
            analysisSummaryLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            analysisMoveLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            detailScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            analysisControls.setAlignmentX(Component.LEFT_ALIGNMENT);
            analysisContent.add(analysisSummaryLabel);
            analysisContent.add(Box.createVerticalStrut(4));
            analysisContent.add(analysisMoveLabel);
            analysisContent.add(Box.createVerticalStrut(10));
            analysisContent.add(detailScroll);
            analysisContent.add(Box.createVerticalStrut(10));
            analysisContent.add(analysisControls);

            JTabbedPane logTabs = new JTabbedPane();
            logTabs.addTab("Moves", movesScroll);
            logTabs.addTab("Analysis Log", reportScroll);
            logTabs.setForeground(Theme.TEXT_SECONDARY);
            logTabs.setBackground(Theme.PANEL);
            logTabs.setBorder(BorderFactory.createEmptyBorder());

            JPanel sideColumn = new JPanel();
            sideColumn.setBackground(Theme.BG);
            sideColumn.setLayout(new BoxLayout(sideColumn, BoxLayout.Y_AXIS));
            sideColumn.setBorder(BorderFactory.createEmptyBorder(20, 16, 20, 24));
            sideColumn.setPreferredSize(new Dimension(320, 0));
            sideColumn.add(createCard("Game Setup", setupForm));
            sideColumn.add(Box.createVerticalStrut(12));
            sideColumn.add(createCard("Controls", controlsPanel));
            sideColumn.add(Box.createVerticalStrut(12));
            sideColumn.add(createCard("Engine Review", analysisContent));
            sideColumn.add(Box.createVerticalStrut(12));
            sideColumn.add(createCard("History", logTabs));
            sideColumn.add(Box.createVerticalGlue());

            JPanel infoLeft = new JPanel();
            infoLeft.setOpaque(false);
            infoLeft.setLayout(new BoxLayout(infoLeft, BoxLayout.Y_AXIS));
            infoLeft.add(evalLabel);
            infoLeft.add(Box.createVerticalStrut(6));
            infoLeft.add(whiteCaptureLabel);
            infoLeft.add(blackCaptureLabel);

            JPanel infoCenter = new JPanel(new GridBagLayout());
            infoCenter.setOpaque(false);
            infoCenter.add(statusLabel);

            JPanel infoRight = new JPanel();
            infoRight.setOpaque(false);
            infoRight.setLayout(new BoxLayout(infoRight, BoxLayout.Y_AXIS));
            infoRight.add(whiteClockLabel);
            infoRight.add(Box.createVerticalStrut(6));
            infoRight.add(blackClockLabel);

            JPanel infoBar = new JPanel(new BorderLayout(18, 0));
            infoBar.setBackground(Theme.PANEL);
            infoBar.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
            infoBar.add(infoLeft, BorderLayout.WEST);
            infoBar.add(infoCenter, BorderLayout.CENTER);
            infoBar.add(infoRight, BorderLayout.EAST);

            JPanel boardFrame = new JPanel(new BorderLayout());
            boardFrame.setBackground(new Color(53, 45, 36));
            boardFrame.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(90, 78, 64), 2),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)
            ));
            boardFrame.add(board, BorderLayout.CENTER);

            JPanel boardArea = new JPanel(new GridBagLayout());
            boardArea.setBackground(Theme.BG);
            boardArea.setBorder(BorderFactory.createEmptyBorder(20, 24, 24, 24));
            GridBagConstraints boardConstraints = new GridBagConstraints();
            boardConstraints.gridx = 0;
            boardConstraints.gridy = 0;
            boardArea.add(boardFrame, boardConstraints);

            JPanel centerColumn = new JPanel(new BorderLayout());
            centerColumn.setBackground(Theme.BG);
            centerColumn.add(infoBar, BorderLayout.NORTH);
            centerColumn.add(boardArea, BorderLayout.CENTER);

            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(Theme.BG);
            root.add(centerColumn, BorderLayout.CENTER);
            root.add(sideColumn, BorderLayout.EAST);

            frame.setContentPane(root);

            frame.pack();
            frame.setMinimumSize(new Dimension(1120, 840));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    private static String formatAnalysisSummary(GameAnalyzer.Summary summary){
        return String.format(
            "Accuracy  W %.1f%%  |  B %.1f%%   —   Blunders %d, Mistakes %d, Inaccuracies %d",
            summary.whiteAccuracy,
            summary.blackAccuracy,
            summary.blunders,
            summary.mistakes,
            summary.inaccuracies
        );
    }

    private static String formatAnalysisDetail(Board.AnalysisFrame frame){
        if(frame.entry == null){
            return "Use ▶ to step through the reviewed moves.";
        }
        GameAnalyzer.Entry entry = frame.entry;
        StringBuilder sb = new StringBuilder();
        sb.append("Played: ").append(frame.san).append('\n');
        sb.append("Evaluation tag: ").append(formatQualityTag(entry.qualityTag)).append('\n');
        sb.append(String.format("Eval: %.2f → %.2f (Δ %.2f)\n", entry.evalBefore, entry.evalAfter, entry.evalAfter - entry.evalBefore));
        sb.append(String.format("Loss vs best: %.2f pawns\n", Math.max(0, entry.loss)));
        sb.append("Best move: ").append(entry.bestMove == null ? "n/a" : friendlyMove(entry.bestMove)).append('\n');
        if(!entry.bestLine.isEmpty()){
            sb.append("Suggested line: ").append(String.join(" ", limitPv(entry.bestLine, 8))).append('\n');
        }
        sb.append('\n').append(buildMoveFeedback(entry)).append('\n');
        return sb.toString();
    }

    private static String formatQualityTag(String tag){
        if(tag == null || tag.isBlank()){
            return "Unknown";
        }
        switch (tag){
            case "Brilliant":
                return "💎 Brilliant";
            case "Great":
                return "✨ Great";
            case "Best":
                return "✔ Best";
            case "Excellent":
                return "👍 Excellent";
            case "Good":
                return "🙂 Good";
            case "Inaccuracy":
                return "?! Inaccuracy";
            case "Mistake":
                return "?? Mistake";
            case "Blunder":
                return "☠ Blunder";
            case "Mate":
                return "♛ Forced Mate";
            default:
                return tag;
        }
    }

    private static List<String> limitPv(List<String> pv, int max){
        if(pv.size() <= max){
            return pv;
        }
        return pv.subList(0, max);
    }

    private static String buildEvalHeadline(GameAnalyzer.Entry entry){
        if(entry == null){
            return "Review ready — step through moves";
        }
        double loss = Math.max(0, entry.loss);
        double gain = entry.loss < 0 ? -entry.loss : 0;
        String tag = formatQualityTag(entry.qualityTag);
        if(loss >= 0.1){
            StringBuilder sb = new StringBuilder(tag);
            sb.append(String.format(" — lost %.2f pawns", loss));
            if(entry.bestMove != null){
                sb.append("; better was ").append(friendlyMove(entry.bestMove));
            }
            return sb.toString();
        }
        if(gain >= 0.1){
            return String.format("%s — improved %.2f pawns", tag, gain);
        }
        return tag + " — keeps the evaluation steady";
    }

    private static String buildMoveFeedback(GameAnalyzer.Entry entry){
        double loss = Math.max(0, entry.loss);
        double gain = entry.loss < 0 ? -entry.loss : 0;
        if(loss >= 3.0){
            return String.format("Huge swing: the evaluation drops %.2f pawns. Stockfish preferred %s to stay in the game.",
                loss, friendlyMove(entry.bestMove));
        }
        if(loss >= 1.5){
            return String.format("This move is a serious mistake (%.2f pawn loss). Consider %s instead.",
                loss, friendlyMove(entry.bestMove));
        }
        if(loss >= 0.5){
            return String.format("Inaccuracy: position worsens by %.2f pawns. Try %s to keep balance.",
                loss, friendlyMove(entry.bestMove));
        }
        if(gain >= 1.0){
            return String.format("Crushing find! You gain %.2f pawns over best play.", gain);
        }
        if(gain >= 0.2){
            return String.format("Nice improvement (+%.2f pawns) and still following top engine ideas.", gain);
        }
        return "Solid move that keeps the evaluation close to the engine line.";
    }

    private static String friendlyMove(String uci){
        if(uci == null || uci.length() < 4){
            return "n/a";
        }
        String from = uci.substring(0, 2);
        String to = uci.substring(2, 4);
        String promo = uci.length() > 4 ? "=" + Character.toUpperCase(uci.charAt(4)) : "";
        return from + "→" + to + promo;
    }

    private static JPanel createCard(String title, JComponent content){
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Theme.PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(58, 58, 58)),
            BorderFactory.createEmptyBorder(12, 14, 12, 14)
        ));
        if(title != null && !title.isBlank()){
            JLabel header = new JLabel(title);
            header.setForeground(Theme.TEXT_PRIMARY);
            header.setFont(header.getFont().deriveFont(Font.BOLD, 14f));
            header.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
            card.add(header, BorderLayout.NORTH);
        }
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private static JScrollPane wrapScroll(JComponent component){
        JScrollPane scroll = new JScrollPane(component);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Theme.INSET);
        scroll.setBackground(Theme.INSET);
        return scroll;
    }

    private static void styleTextArea(JTextArea area){
        area.setBackground(Theme.INSET);
        area.setForeground(Theme.TEXT_PRIMARY);
        area.setCaretColor(Theme.TEXT_PRIMARY);
        area.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
    }

    private static void styleToggle(AbstractButton button){
        button.setOpaque(false);
        button.setForeground(Theme.TEXT_PRIMARY);
        button.setFont(button.getFont().deriveFont(Font.PLAIN, 13f));
    }

    private static void styleCombo(JComboBox<?> combo){
        combo.setBackground(Theme.INSET);
        combo.setForeground(Theme.TEXT_PRIMARY);
        combo.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        combo.setMaximumSize(new Dimension(Integer.MAX_VALUE, combo.getPreferredSize().height));
    }

    private static JButton createPrimaryButton(String text){
        return new StyledButton(text, Theme.ACCENT_PRIMARY, Theme.ACCENT_PRIMARY.brighter(), Theme.ACCENT_PRIMARY.darker());
    }

    private static JButton createSecondaryButton(String text){
        StyledButton button = new StyledButton(text, Theme.BUTTON_BG, Theme.BUTTON_BG.brighter(), Theme.BUTTON_BG.darker());
        button.setFont(button.getFont().deriveFont(Font.PLAIN, 13f));
        return button;
    }

    private static JButton createGhostButton(String text){
        StyledButton button = new StyledButton(text, Theme.GHOST_BG, Theme.GHOST_BG.brighter(), Theme.GHOST_BG.darker());
        button.setForeground(Theme.TEXT_SECONDARY);
        button.setFont(button.getFont().deriveFont(Font.PLAIN, 12f));
        button.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
        return button;
    }

    private static final class StyledButton extends JButton {
        private final Color base;
        private final Color hover;
        private final Color pressed;

        private StyledButton(String text, Color base, Color hover, Color pressed) {
            super(text);
            this.base = base;
            this.hover = hover;
            this.pressed = pressed;
            setFocusPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setForeground(Theme.TEXT_PRIMARY);
            setFont(getFont().deriveFont(Font.BOLD, 13f));
            setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = base;
            if(!isEnabled()){
                fill = Theme.BUTTON_DISABLED;
            } else if(getModel().isPressed()){
                fill = pressed;
            } else if(getModel().isRollover()){
                fill = hover;
            }
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static final class Theme {
        private static final Color BG = new Color(30, 30, 30);
        private static final Color PANEL = new Color(42, 42, 42);
        private static final Color INSET = new Color(26, 26, 26);
        private static final Color TEXT_PRIMARY = new Color(224, 224, 224);
        private static final Color TEXT_SECONDARY = new Color(170, 170, 170);
        private static final Color ACCENT_PRIMARY = new Color(108, 172, 255);
        private static final Color BUTTON_BG = new Color(58, 58, 58);
        private static final Color BUTTON_DISABLED = new Color(65, 65, 65);
        private static final Color GHOST_BG = new Color(48, 48, 48);
    }

}
