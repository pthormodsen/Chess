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
        return buildReport(summary, sanMoves, null);
    }

    /** @param gameEndMessage how the game actually ended (e.g. "White won on time"), or null if unfinished. */
    public static List<String> buildReport(GameAnalyzer.Summary summary, List<String> sanMoves, String gameEndMessage) {
        List<String> lines = new ArrayList<>();
        lines.add("Game Review powered by Stockfish");
        if (gameEndMessage != null) {
            lines.add("Result: " + gameEndMessage);
        }
        lines.add("Accuracy: White " + fmt(summary.whiteAccuracy) + "% | Black " + fmt(summary.blackAccuracy) + "%");
        lines.add("White: " + countsLine(summary.white));
        lines.add("Black: " + countsLine(summary.black));
        lines.add("Avg loss: " + fmt(summary.avgLoss) + " pawns | Max loss: " + fmt(summary.maxLoss));
        lines.add("---");

        List<GameAnalyzer.Entry> entries = summary.entries;
        for (int i = 0; i < entries.size(); i++) {
            GameAnalyzer.Entry entry = entries.get(i);
            String moveLabel = buildMoveLabel(i, entry, sanMoves);
            StringBuilder sb = new StringBuilder();
            sb.append(moveLabel).append(" → ").append(labelFor(entry.qualityTag));
            if (!Double.isNaN(entry.winLoss)) {
                sb.append(" (-").append(String.format("%.1f", entry.winLoss)).append("% win)");
            } else {
                sb.append(" (loss ").append(fmt(entry.loss)).append(")");
            }
            String best = entry.bestMoveSan != null ? entry.bestMoveSan : entry.bestMove;
            sb.append(" | Best: ").append(best == null ? "n/a" : best);
            List<String> line = entry.bestLineSan.isEmpty() ? entry.bestLine : entry.bestLineSan;
            if (!line.isEmpty()) {
                sb.append(" | Line: ").append(String.join(" ", limitPv(line, 6)));
            }
            if (entry.explanation != null) {
                sb.append(" | ").append(entry.explanation);
            }
            lines.add(sb.toString());
        }
        if (entries.isEmpty()) {
            lines.add("No moves to analyze.");
        }
        return lines;
    }

    private static String countsLine(GameAnalyzer.SideSummary side) {
        List<String> parts = new ArrayList<>();
        side.counts.forEach((tag, count) -> {
            if (count > 0) {
                parts.add(tag + " " + count);
            }
        });
        return parts.isEmpty() ? "no moves" : String.join(", ", parts);
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
            case "Book":
                return "📖 Book";
            case "Inaccuracy":
                return "?! Inaccuracy";
            case "Mistake":
                return "? Mistake";
            case "Miss":
                return "✖ Miss";
            case "Severe Mistake":
                return "☡ Severe mistake";
            case "Blunder":
                return "?? Blunder";
            case "Mate":
                return "♛ Forced Mate";
            default:
                return tag;
        }
    }
}
