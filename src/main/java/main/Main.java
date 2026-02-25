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
            frame.getContentPane().setBackground(new Color(43, 43, 43));
            frame.setLayout(new BorderLayout());

            Board board = new Board();
            board.setBorder(BorderFactory.createEmptyBorder());

            int[] eloValues = {800, 1000, 1200, 1400, 1600, 1800, 2000, 2200, 2400, 2600, 2800};
            JComboBox<String> skillSelect = new JComboBox<>();
            for (int elo : eloValues) {
                skillSelect.addItem("Elo " + elo);
            }
            int defaultIndex = 2; // 1200
            skillSelect.setSelectedIndex(defaultIndex);
            board.setEngineElo(eloValues[defaultIndex]);
            skillSelect.addActionListener(e ->
                board.setEngineElo(eloValues[skillSelect.getSelectedIndex()])
            );

            JLabel statusLabel = new JLabel("Press Play to begin.");
            statusLabel.setForeground(Color.WHITE);
            statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 17f));

            JLabel evalLabel = new JLabel("Material even");
            evalLabel.setForeground(new Color(200, 200, 200));
            evalLabel.setFont(evalLabel.getFont().deriveFont(Font.PLAIN, 15f));
            final String[] liveEvalMessage = {"Material even"};

            JLabel whiteCaptureLabel = new JLabel("White captures: -");
            whiteCaptureLabel.setForeground(new Color(200, 200, 200));
            whiteCaptureLabel.setFont(whiteCaptureLabel.getFont().deriveFont(Font.PLAIN, 14f));
            JLabel blackCaptureLabel = new JLabel("Black captures: -");
            blackCaptureLabel.setForeground(new Color(200, 200, 200));
            blackCaptureLabel.setFont(blackCaptureLabel.getFont().deriveFont(Font.PLAIN, 14f));
            JLabel whiteClockLabel = new JLabel("White 05:00");
            whiteClockLabel.setForeground(new Color(200, 200, 200));
            whiteClockLabel.setFont(whiteClockLabel.getFont().deriveFont(Font.BOLD, 16f));
            JLabel blackClockLabel = new JLabel("Black 05:00");
            blackClockLabel.setForeground(new Color(200, 200, 200));
            blackClockLabel.setFont(blackClockLabel.getFont().deriveFont(Font.BOLD, 16f));

            JCheckBox botToggle = new JCheckBox("Play vs Stockfish", true);
            botToggle.setOpaque(false);
            botToggle.setForeground(Color.WHITE);
            botToggle.addActionListener(e -> {
                boolean playBot = botToggle.isSelected();
                board.setHumanVsHuman(!playBot);
            });

            JButton playButton = new JButton("Play");
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
            JTextArea analysisReportArea = new JTextArea(10, 40);
            analysisReportArea.setEditable(false);
            analysisReportArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            analysisReportArea.setText("Press Analyze to run a Stockfish-powered review.");
            JTextArea analysisDetailArea = new JTextArea(4, 30);
            analysisDetailArea.setEditable(false);
            analysisDetailArea.setLineWrap(true);
            analysisDetailArea.setWrapStyleWord(true);
            analysisDetailArea.setText("Press Analyze to see best-move feedback.");

            JLabel analysisSummaryLabel = new JLabel("Run analysis to enable review.");
            JLabel analysisMoveLabel = new JLabel("No analysis loaded.");

            JButton analysisPrevButton = new JButton("◀");
            analysisPrevButton.setEnabled(false);
            analysisPrevButton.addActionListener(e -> board.stepAnalysis(-1));
            JButton analysisNextButton = new JButton("▶");
            analysisNextButton.setEnabled(false);
            analysisNextButton.addActionListener(e -> board.stepAnalysis(1));
            JButton exitReviewButton = new JButton("Exit Review");
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

            JPanel topPanel = new JPanel(new BorderLayout());
            topPanel.setBackground(new Color(26, 26, 26));
            topPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(50, 50, 50)),
                BorderFactory.createEmptyBorder(10, 18, 10, 18)
            ));
            JPanel leftPanel = new JPanel();
            leftPanel.setBackground(new Color(26, 26, 26));
            leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));
            leftPanel.add(evalLabel);
            leftPanel.add(Box.createVerticalStrut(4));
            leftPanel.add(whiteCaptureLabel);
            leftPanel.add(blackCaptureLabel);
            leftPanel.add(Box.createVerticalStrut(4));
            leftPanel.add(whiteClockLabel);
            leftPanel.add(blackClockLabel);
            topPanel.add(leftPanel, BorderLayout.WEST);

            statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
            JPanel centerPanel = new JPanel(new GridBagLayout());
            centerPanel.setBackground(new Color(26, 26, 26));
            centerPanel.add(statusLabel);
            topPanel.add(centerPanel, BorderLayout.CENTER);

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

            JRadioButton playAsWhite = new JRadioButton("Play as White");
            JRadioButton playAsBlack = new JRadioButton("Play as Black");
            playAsWhite.setOpaque(false);
            playAsBlack.setOpaque(false);
            playAsWhite.setForeground(Color.WHITE);
            playAsBlack.setForeground(Color.WHITE);
            playAsWhite.setSelected(true);
            board.setEnginePlaysWhite(false);
            playAsWhite.addActionListener(e -> board.setEnginePlaysWhite(false));
            playAsBlack.addActionListener(e -> board.setEnginePlaysWhite(true));
            ButtonGroup sideGroup = new ButtonGroup();
            sideGroup.add(playAsWhite);
            sideGroup.add(playAsBlack);

            JPanel rightPanel = new JPanel();
            rightPanel.setBackground(new Color(26, 26, 26));
            rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));
            JLabel difficultyLabel = new JLabel("Difficulty");
            difficultyLabel.setForeground(Color.WHITE);
            rightPanel.add(difficultyLabel);
            rightPanel.add(skillSelect);
            JLabel timeLabel = new JLabel("Time Control");
            timeLabel.setForeground(Color.WHITE);
            rightPanel.add(timeLabel);
            rightPanel.add(timeSelect);
            rightPanel.add(botToggle);
            rightPanel.add(Box.createVerticalStrut(8));
            rightPanel.add(playAsWhite);
            rightPanel.add(playAsBlack);
            rightPanel.add(Box.createVerticalStrut(6));
            rightPanel.add(playButton);
            topPanel.add(rightPanel, BorderLayout.EAST);

            JButton copyPgn = new JButton("Copy PGN");
            copyPgn.setMargin(new Insets(2, 6, 2, 6));
            copyPgn.addActionListener(e -> {
                String pgn = board.getPgn(board.getLastResultTag());
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(pgn), null);
            });
            JButton analyzeButton = new JButton("Analyze");
            analyzeButton.setMargin(new Insets(2, 6, 2, 6));
            analyzeButton.addActionListener(e -> {
                analyzeButton.setEnabled(false);
                analysisReportArea.setText("Analyzing game...");
                board.analyzeGame(lines -> {
                    analysisReportArea.setText(String.join("\n", lines));
                    analyzeButton.setEnabled(true);
                });
            });
            JPanel logPanel = new JPanel(new BorderLayout());
            logPanel.setBackground(new Color(24, 24, 24));
            logPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            logPanel.setPreferredSize(new Dimension(260, 0));
            JPanel analysisControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
            analysisControls.add(new JLabel("Navigate:"));
            analysisControls.add(analysisPrevButton);
            analysisControls.add(analysisNextButton);
            analysisControls.add(exitReviewButton);

            JScrollPane detailScroll = new JScrollPane(analysisDetailArea);
            detailScroll.setBorder(BorderFactory.createTitledBorder("Move feedback"));
            detailScroll.setPreferredSize(new Dimension(240, 120));

            JScrollPane reportScroll = new JScrollPane(analysisReportArea);
            reportScroll.setBorder(BorderFactory.createTitledBorder("Full review log"));

            JPanel analysisTop = new JPanel();
            analysisTop.setLayout(new BoxLayout(analysisTop, BoxLayout.Y_AXIS));
            analysisTop.add(analysisSummaryLabel);
            analysisTop.add(Box.createVerticalStrut(4));
            analysisTop.add(analysisMoveLabel);
            analysisTop.add(Box.createVerticalStrut(4));
            analysisTop.add(detailScroll);
            analysisTop.add(Box.createVerticalStrut(4));
            analysisTop.add(analysisControls);

            JPanel analysisTab = new JPanel(new BorderLayout());
            analysisTab.add(analysisTop, BorderLayout.NORTH);
            analysisTab.add(reportScroll, BorderLayout.CENTER);

            JPanel logHeader = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
            logHeader.add(copyPgn);
            logHeader.add(analyzeButton);
            logPanel.add(logHeader, BorderLayout.NORTH);
            JTabbedPane logTabs = new JTabbedPane();
            logTabs.addTab("Moves", new JScrollPane(moveArea));
            logTabs.addTab("Analysis", analysisTab);
            logPanel.add(logTabs, BorderLayout.CENTER);

            JPanel boardFrame = new JPanel(new BorderLayout());
            boardFrame.setBackground(new Color(58, 44, 32));
            boardFrame.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(109, 89, 72), 2, true),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)
            ));
            boardFrame.add(board, BorderLayout.CENTER);

            JPanel boardWrapper = new JPanel(new BorderLayout());
            boardWrapper.setBackground(new Color(17, 17, 17));
            boardWrapper.setBorder(BorderFactory.createEmptyBorder(6, 10, 10, 10));
            boardWrapper.add(boardFrame, BorderLayout.CENTER);

            frame.add(topPanel, BorderLayout.NORTH);
            frame.add(boardWrapper, BorderLayout.CENTER);
            frame.add(logPanel, BorderLayout.EAST);

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

}
