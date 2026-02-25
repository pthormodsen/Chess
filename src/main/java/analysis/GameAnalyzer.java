package analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import uci.StockfishClient;

public class GameAnalyzer {

    public static final class Entry {
        public final int plyIndex;
        public final boolean isWhite;
        public final String playedMove;
        public final String bestMove;
        public final List<String> bestLine;
        public final double evalBefore;
        public final double evalAfter;
        public final double loss;
        public final String severity;
        public final String qualityTag;

        public Entry(int plyIndex, boolean isWhite, String playedMove, String bestMove,
                     List<String> bestLine, double evalBefore, double evalAfter,
                     double loss, String severity, String qualityTag) {
            this.plyIndex = plyIndex;
            this.isWhite = isWhite;
            this.playedMove = playedMove;
            this.bestMove = bestMove;
            this.bestLine = bestLine == null ? List.of() : List.copyOf(bestLine);
            this.evalBefore = evalBefore;
            this.evalAfter = evalAfter;
            this.loss = loss;
            this.severity = severity;
            this.qualityTag = qualityTag;
        }
    }

    private final Path enginePath;
    private final Duration thinkTime;

    public GameAnalyzer(Path enginePath, Duration thinkTime) {
        this.enginePath = enginePath;
        this.thinkTime = thinkTime;
    }

    public Summary analyze(List<String> moves) throws IOException {
        List<Entry> result = new ArrayList<>();
        if (enginePath == null) {
            return new Summary(result);
        }
        try (StockfishClient client = new StockfishClient(enginePath)) {
            StockfishClient.AnalysisResult before = client.analyzePosition("", thinkTime);
            List<String> history = new ArrayList<>();

            for (int i = 0; i < moves.size(); i++) {
                String move = moves.get(i);
                boolean whiteMove = (i % 2 == 0);

                double evalBefore = before.scoreCp / 100.0;
                String bestMove = before.bestMove;
                List<String> pv = before.principalVariation;
                history.add(move);

                String prefix = String.join(" ", history);
                StockfishClient.AnalysisResult after = client.analyzePosition(prefix, thinkTime);
                double evalAfter = after.scoreCp / 100.0;

                double delta = (evalAfter - evalBefore) * (whiteMove ? 1 : -1);
                double loss = -delta;
                double improvement = -loss;
                boolean playedBest = bestMove != null && bestMove.equals(move);

                String severity = classify(loss);
                if (before.mate != null) {
                    severity = "Mate in " + before.mate;
                }

                String tag = determineQualityTag(playedBest, loss, improvement, severity);

                result.add(new Entry(i, whiteMove, move, bestMove, pv, evalBefore, evalAfter, loss, severity, tag));

                before = after;
            }
        }
        return new Summary(result);
    }

    public static final class Summary {
        public final double avgLoss;
        public final int inaccuracies;
        public final int mistakes;
        public final int blunders;
        public final int bestCount;
        public final double accuracyScore;
        public final double maxLoss;
        public final double minLoss;
        public final double whiteAccuracy;
        public final double blackAccuracy;
        public final int totalMoves;
        public final List<Entry> entries;

        public Summary(List<Entry> entries) {
            this.entries = entries;
            this.totalMoves = entries.size();
            this.avgLoss = entries.stream().mapToDouble(e -> Math.max(0, e.loss)).average().orElse(0);
            this.inaccuracies = (int) entries.stream().filter(e -> "Inaccuracy".equals(e.severity)).count();
            this.mistakes = (int) entries.stream().filter(e -> "Mistake".equals(e.severity)).count();
            this.blunders = (int) entries.stream().filter(e -> "Blunder".equals(e.severity)).count();
            this.bestCount = (int) entries.stream()
                .filter(e -> isTopTier(e.qualityTag))
                .count();
            double lossSum = entries.stream().mapToDouble(e -> Math.max(0, e.loss)).sum();
            double denom = entries.size() * 500.0;
            this.accuracyScore = denom == 0 ? 100 : Math.max(0, 100 - (lossSum / denom) * 100);
            this.maxLoss = entries.stream().mapToDouble(e -> Math.max(0, e.loss)).max().orElse(0);
            this.minLoss = entries.stream().mapToDouble(e -> Math.max(0, e.loss)).min().orElse(0);
            this.whiteAccuracy = computeSideAccuracy(entries, true);
            this.blackAccuracy = computeSideAccuracy(entries, false);
        }
    }

    private static double computeSideAccuracy(List<Entry> entries, boolean white) {
        double lossSum = entries.stream()
            .filter(e -> e.isWhite == white)
            .mapToDouble(e -> Math.max(0, e.loss))
            .sum();
        long count = entries.stream().filter(e -> e.isWhite == white).count();
        if (count == 0) {
            return 100;
        }
        double denom = count * 500.0;
        return Math.max(0, 100 - (lossSum / denom) * 100);
    }

    private static boolean isTopTier(String tag){
        return "Brilliant".equals(tag) || "Great".equals(tag) || "Best".equals(tag) || "Excellent".equals(tag);
    }

    private String classify(double loss) {
        double delta = Math.max(0, loss);
        if (delta >= 7.0) return "Blunder";
        if (delta >= 4.0) return "Mistake";
        if (delta >= 2.0) return "Inaccuracy";
        return "Good";
    }

    private String determineQualityTag(boolean playedBest, double loss, double improvement, String severity) {
        if (severity != null && severity.startsWith("Mate")) {
            return "Mate";
        }
        double absLoss = Math.max(0, loss);
        double absGain = Math.max(0, improvement);
        if (playedBest && absGain >= 1.5) {
            return "Brilliant";
        }
        if (playedBest && absGain >= 0.5) {
            return "Great";
        }
        if (playedBest) {
            return "Best";
        }
        if (absLoss < 0.15) {
            return "Excellent";
        }
        if (absLoss < 0.5) {
            return "Good";
        }
        if (absLoss < 1.5) {
            return "Inaccuracy";
        }
        if (absLoss < 4.0) {
            return "Mistake";
        }
        if (absLoss < 7.0) {
            return "Severe Mistake";
        }
        return "Blunder";
    }
}
