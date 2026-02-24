package main;

import javax.swing.*;
import java.awt.*;

public class Main {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Chess");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.getContentPane().setBackground(new Color(43, 43, 43));
            frame.setLayout(new BorderLayout());

            Board board = new Board();

            String[] skillLabels = {"Skill 0", "Skill 5", "Skill 10", "Skill 15", "Skill 20"};
            int[] skillValues = {0, 5, 10, 15, 20};
            JComboBox<String> skillSelect = new JComboBox<>(skillLabels);
            skillSelect.setSelectedIndex(2);
            skillSelect.addActionListener(e ->
                board.setEngineSkillLevel(skillValues[skillSelect.getSelectedIndex()])
            );

            JLabel statusLabel = new JLabel("Press Play to begin.");
            statusLabel.setForeground(Color.WHITE);

            JLabel evalLabel = new JLabel("Material even");
            evalLabel.setForeground(new Color(200, 200, 200));

            JLabel whiteCaptureLabel = new JLabel("White captures: -");
            whiteCaptureLabel.setForeground(new Color(200, 200, 200));
            JLabel blackCaptureLabel = new JLabel("Black captures: -");
            blackCaptureLabel.setForeground(new Color(200, 200, 200));

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
                SwingUtilities.invokeLater(() -> evalLabel.setText(message))
            );
            board.setCaptureConsumer((white, black) ->
                SwingUtilities.invokeLater(() -> {
                    whiteCaptureLabel.setText("White captures: " + white);
                    blackCaptureLabel.setText("Black captures: " + black);
                })
            );

            JPanel topPanel = new JPanel(new BorderLayout());
            topPanel.setBackground(new Color(33, 33, 33));
            topPanel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            JPanel leftPanel = new JPanel();
            leftPanel.setBackground(new Color(33, 33, 33));
            leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));
            leftPanel.add(evalLabel);
            leftPanel.add(Box.createVerticalStrut(4));
            leftPanel.add(whiteCaptureLabel);
            leftPanel.add(blackCaptureLabel);
            topPanel.add(leftPanel, BorderLayout.WEST);

            statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
            JPanel centerPanel = new JPanel(new GridBagLayout());
            centerPanel.setBackground(new Color(33, 33, 33));
            centerPanel.add(statusLabel);
            topPanel.add(centerPanel, BorderLayout.CENTER);

            JPanel rightPanel = new JPanel();
            rightPanel.setBackground(new Color(33, 33, 33));
            rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));
            JLabel difficultyLabel = new JLabel("Difficulty");
            difficultyLabel.setForeground(Color.WHITE);
            rightPanel.add(difficultyLabel);
            rightPanel.add(skillSelect);
            rightPanel.add(Box.createVerticalStrut(6));
            rightPanel.add(playButton);
            topPanel.add(rightPanel, BorderLayout.EAST);

            frame.add(topPanel, BorderLayout.NORTH);
            frame.add(board, BorderLayout.CENTER);

            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

}
