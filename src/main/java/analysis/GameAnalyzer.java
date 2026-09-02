package analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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

                double evalBefore = toWhitePerspective(before, whiteMove);
                String bestMove = before.bestMove;
                List<String> pv = before.principalVariation;
                history.add(move);

                String prefix = String.join(" ", history);
                StockfishClient.AnalysisResult after = client.analyzePosition(prefix, thinkTime);
                double evalAfter = toWhitePerspective(after, !whiteMove);

                double delta = (evalAfter - evalBefore) * (whiteMove ? 1 : -1);
                double loss = -delta;
                double improvement = -loss;
                boolean playedBest = bestMove != null && bestMove.equals(move);
                double winLoss = winPercentLoss(whiteMove, evalBefore, evalAfter);
                double winGain = Math.max(0, -winLoss);

                String severity = classifyWinLoss(winLoss);
                if (before.mate != null) {
                    severity = "Mate in " + before.mate;
                }

                String tag = determineQualityTag(playedBest, winLoss, winGain, severity);

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
        public final SideSummary white;
        public final SideSummary black;
        public final int totalMoves;
        public final List<Entry> entries;

        public Summary(List<Entry> entries) {
            this.entries = entries;
            this.totalMoves = entries.size();
            this.avgLoss = entries.stream().mapToDouble(e -> Math.max(0, e.loss)).average().orElse(0);
            this.inaccuracies = (int) entries.stream().filter(e -> winPercentLoss(e) >= 5 && winPercentLoss(e) < 10).count();
            this.mistakes = (int) entries.stream().filter(e -> winPercentLoss(e) >= 10 && winPercentLoss(e) < 20).count();
            this.blunders = (int) entries.stream().filter(e -> winPercentLoss(e) >= 20).count();
            this.bestCount = (int) entries.stream()
                .filter(e -> "Best".equals(e.qualityTag))
                .count();
            this.accuracyScore = computeAccuracy(entries);
            this.maxLoss = entries.stream().mapToDouble(e -> Math.max(0, e.loss)).max().orElse(0);
            this.minLoss = entries.stream().mapToDouble(e -> Math.max(0, e.loss)).min().orElse(0);
            this.white = SideSummary.from(entries, true);
            this.black = SideSummary.from(entries, false);
            this.whiteAccuracy = white.accuracy;
            this.blackAccuracy = black.accuracy;
        }
    }

    public static final class SideSummary {
        public final double accuracy;
        public final int best;
        public final int excellent;
        public final int good;
        public final int inaccuracies;
        public final int mistakes;
        public final int blunders;
        public final int totalMoves;

        private SideSummary(double accuracy, int best, int excellent, int good, int inaccuracies,
                            int mistakes, int blunders, int totalMoves) {
            this.accuracy = accuracy;
            this.best = best;
            this.excellent = excellent;
            this.good = good;
            this.inaccuracies = inaccuracies;
            this.mistakes = mistakes;
            this.blunders = blunders;
            this.totalMoves = totalMoves;
        }

        static SideSummary from(List<Entry> entries, boolean white) {
            List<Entry> sideEntries = sideEntries(entries, white);
            return new SideSummary(
                computeAccuracy(sideEntries),
                countTag(sideEntries, "Best") + countTag(sideEntries, "Brilliant") + countTag(sideEntries, "Great"),
                countTag(sideEntries, "Excellent"),
                countTag(sideEntries, "Good"),
                (int) sideEntries.stream().filter(e -> winPercentLoss(e) >= 5 && winPercentLoss(e) < 10).count(),
                (int) sideEntries.stream().filter(e -> winPercentLoss(e) >= 10 && winPercentLoss(e) < 20).count(),
                (int) sideEntries.stream().filter(e -> winPercentLoss(e) >= 20).count(),
                sideEntries.size()
            );
        }
    }

    private static List<Entry> sideEntries(List<Entry> entries, boolean white) {
        return entries.stream()
            .filter(e -> e.isWhite == white)
            .toList();
    }

    private static int countTag(List<Entry> entries, String tag) {
        return (int) entries.stream().filter(e -> tag.equals(e.qualityTag)).count();
    }

    private static double computeSideAccuracy(List<Entry> entries, boolean white) {
        List<Entry> sideEntries = entries.stream()
            .filter(e -> e.isWhite == white)
            .toList();
        return computeAccuracy(sideEntries);
    }

    static double computeAccuracy(List<Entry> entries) {
        if (entries.isEmpty()) {
            return 100;
        }
        List<Double> accuracies = entries.stream()
            .map(GameAnalyzer::lichessMoveAccuracy)
            .toList();
        List<Double> weights = volatilityWeights(entries);

        double weighted = weightedMean(accuracies, weights);
        double harmonic = harmonicMean(accuracies);
        return clamp((weighted + harmonic) / 2, 0, 100);
    }

    static double lichessMoveAccuracy(Entry entry) {
        double winLoss = winPercentLoss(entry);
        if(winLoss <= 0){
            return 100;
        }
        double raw = 103.1668100711649 * Math.exp(-0.04354415386753951 * winLoss) - 3.166924740191411;
        double capped = capNonBestMove(entry, raw);
        return clamp(capped, 0, 100);
    }

    private static double capNonBestMove(Entry entry, double moveAccuracy) {
        boolean playedBest = entry.bestMove != null && entry.bestMove.equals(entry.playedMove);
        if(playedBest || "Brilliant".equals(entry.qualityTag) || "Great".equals(entry.qualityTag)){
            return moveAccuracy;
        }
        double cap = switch (entry.qualityTag) {
            case "Excellent" -> 96;
            case "Good" -> 91;
            case "Inaccuracy" -> 78;
            case "Mistake" -> 58;
            case "Blunder" -> 28;
            default -> 96;
        };
        return Math.min(moveAccuracy, cap);
    }

    static double winPercentLoss(Entry entry) {
        return winPercentLoss(entry.isWhite, entry.evalBefore, entry.evalAfter);
    }

    private static double winPercentLoss(boolean isWhite, double evalBefore, double evalAfter) {
        double before = playerWinPercent(isWhite, evalBefore);
        double after = playerWinPercent(isWhite, evalAfter);
        return Math.max(0, before - after);
    }

    static double winPercent(double pawnsFromWhitePerspective) {
        double cp = clamp(pawnsFromWhitePerspective * 100, -1000, 1000);
        return 50 + 50 * ((2 / (1 + Math.exp(-0.00368208 * cp))) - 1);
    }

    private static double playerWinPercent(Entry entry, double whitePerspectivePawns) {
        return playerWinPercent(entry.isWhite, whitePerspectivePawns);
    }

    private static double playerWinPercent(boolean isWhite, double whitePerspectivePawns) {
        return winPercent(isWhite ? whitePerspectivePawns : -whitePerspectivePawns);
    }

    private static List<Double> volatilityWeights(List<Entry> entries) {
        List<Double> winPercents = new ArrayList<>();
        winPercents.add(winPercent(0));
        entries.forEach(entry -> winPercents.add(winPercent(entry.evalAfter)));

        int windowSize = (int) clamp(entries.size() / 10.0, 2, 8);
        List<Double> weights = new ArrayList<>();
        int leadingCopies = Math.max(0, Math.min(windowSize, winPercents.size()) - 2);
        for(int i = 0; i < leadingCopies; i++){
            weights.add(windowWeight(winPercents, 0, windowSize));
        }
        for(int start = 0; start + windowSize <= winPercents.size(); start++){
            weights.add(windowWeight(winPercents, start, windowSize));
        }
        while(weights.size() < entries.size()){
            weights.add(weights.isEmpty() ? 1.0 : weights.get(weights.size() - 1));
        }
        return weights.size() > entries.size() ? weights.subList(0, entries.size()) : weights;
    }

    private static double windowWeight(List<Double> values, int start, int size) {
        int end = Math.min(values.size(), start + size);
        if(start >= end){
            return 0.5;
        }
        double mean = values.subList(start, end).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = values.subList(start, end).stream()
            .mapToDouble(value -> Math.pow(value - mean, 2))
            .average()
            .orElse(0);
        return clamp(Math.sqrt(variance), 0.5, 12);
    }

    private static double weightedMean(List<Double> values, List<Double> weights) {
        double weightedSum = 0;
        double weightSum = 0;
        for(int i = 0; i < values.size(); i++){
            double weight = i < weights.size() ? weights.get(i) : 1.0;
            weightedSum += values.get(i) * weight;
            weightSum += weight;
        }
        return weightSum == 0 ? values.stream().mapToDouble(Double::doubleValue).average().orElse(100) : weightedSum / weightSum;
    }

    private static double harmonicMean(List<Double> values) {
        if(values.stream().anyMatch(value -> value <= 0)){
            return 0;
        }
        double inverseSum = values.stream().mapToDouble(value -> 1.0 / value).sum();
        return inverseSum == 0 ? 100 : values.size() / inverseSum;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    static double toWhitePerspective(StockfishClient.AnalysisResult result, boolean whiteToMove) {
        double sideToMoveScore = result.scoreCp / 100.0;
        return whiteToMove ? sideToMoveScore : -sideToMoveScore;
    }

    private String classifyWinLoss(double winLoss) {
        if (winLoss >= 20) return "Blunder";
        if (winLoss >= 10) return "Mistake";
        if (winLoss >= 5) return "Inaccuracy";
        return "Good";
    }

    private String determineQualityTag(boolean playedBest, double winLoss, double winGain, String severity) {
        if (severity != null && severity.startsWith("Mate")) {
            return "Mate";
        }
        if (playedBest && winGain >= 10) {
            return "Brilliant";
        }
        if (playedBest && winGain >= 5) {
            return "Great";
        }
        if (playedBest) {
            return "Best";
        }
        if (winLoss < 2) {
            return "Excellent";
        }
        if (winLoss < 5) {
            return "Good";
        }
        if (winLoss < 10) {
            return "Inaccuracy";
        }
        if (winLoss < 20) {
            return "Mistake";
        }
        return "Blunder";
    }
}
