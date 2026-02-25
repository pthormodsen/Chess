package analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper that converts engine review data into a human-readable report similar
 * to chess.com game review summaries.
 */
public final class GameReviewFormatter {

    private GameReviewFormatter() {}

    public static List<String> buildReport(GameAnalyzer.Summary summary, List<String> sanMoves) {
        List<String> lines = new ArrayList<>();
        lines.add("Game Review powered by Stockfish");
        lines.add("Accuracy: White " + fmt(summary.whiteAccuracy) + "% | Black " + fmt(summary.blackAccuracy) + "%");
        lines.add("Blunders: " + summary.blunders + ", Mistakes: " + summary.mistakes + ", Inaccuracies: " + summary.inaccuracies);
        lines.add("Avg loss: " + fmt(summary.avgLoss) + " pawns | Max loss: " + fmt(summary.maxLoss));
        lines.add("---");

        List<GameAnalyzer.Entry> entries = summary.entries;
        for (int i = 0; i < entries.size(); i++) {
            GameAnalyzer.Entry entry = entries.get(i);
            String moveLabel = buildMoveLabel(i, entry, sanMoves);
            StringBuilder sb = new StringBuilder();
            sb.append(moveLabel).append(" → ").append(labelFor(entry.qualityTag));
            if (!entry.severity.startsWith("Mate")) {
                sb.append(" (loss ").append(fmt(entry.loss)).append(")");
            }
            sb.append(" | Best: ").append(entry.bestMove == null ? "n/a" : entry.bestMove);
            if (!entry.bestLine.isEmpty()) {
                sb.append(" | Line: ").append(String.join(" ", limitPv(entry.bestLine, 6)));
            }
            lines.add(sb.toString());
        }
        if (entries.isEmpty()) {
            lines.add("No moves to analyze.");
        }
        return lines;
    }

    private static String buildMoveLabel(int plyIndex, GameAnalyzer.Entry entry, List<String> sanMoves) {
        int moveNumber = (plyIndex / 2) + 1;
        String san = (plyIndex < sanMoves.size()) ? sanMoves.get(plyIndex) : entry.playedMove;
        return (entry.isWhite ? moveNumber + ". " : moveNumber + "... ") + (san == null ? entry.playedMove : san);
    }

    private static List<String> limitPv(List<String> pv, int max) {
        if (pv.size() <= max) {
            return pv;
        }
        return new ArrayList<>(pv.subList(0, max));
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }

    private static String labelFor(String tag){
        if(tag == null || tag.isBlank()){
            return "• Unknown";
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
            case "Severe Mistake":
                return "☡ Severe mistake";
            case "Blunder":
                return "☠ Blunder (lost ≥7 pawns)";
            case "Mate":
                return "♛ Forced Mate";
            default:
                return tag;
        }
    }
}
