package analysis;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import uci.StockfishClient;

/**
 * Game review: runs Stockfish over every position of a game and classifies each move.
 *
 * <p>Searches use a fixed depth, one thread and a fresh hash, so re-running the same game gives the same
 * result regardless of server load. Every position is searched once with MultiPV; the position after a move
 * doubles as the position before the next one.
 */
public class GameAnalyzer {

    public static final int DEFAULT_DEPTH = 14;
    public static final int DEFAULT_MULTI_PV = 2;
    private static final int HASH_MB = 32;
    private static final int MAX_LINE_PLIES = 10;

    public static final class Entry {
        public final int plyIndex;
        public final boolean isWhite;
        public final String playedMove;
        public final String bestMove;
        public final List<String> bestLine;
        /** Evaluation in pawns from White's perspective (±100 for a forced mate). */
        public final double evalBefore;
        public final double evalAfter;
        /** Pawns lost by the mover versus the best move (only meaningful without mate scores). */
        public final double loss;
        public final String severity;
        public final String qualityTag;
        /**
         * Stockfish "mate in N" for the position before/after this move, signed from White's perspective
         * (+N: White mates in N, -N: Black mates in N); null when the engine reported a centipawn score.
         * This is evaluation information only: whether a move actually checkmated is decided by the board.
         */
        public final Integer mateBefore;
        public final Integer mateAfter;

        /** SAN of the played move, the best move and the best line (may be null/empty if unknown). */
        public final String san;
        public final String bestMoveSan;
        public final List<String> bestLineSan;
        /** White's win chance (0–100) before and after the move; NaN when built from raw evaluations only. */
        public final double winBefore;
        public final double winAfter;
        /** Win-chance points the mover lost versus the best move (0 for the best move); NaN if not computed. */
        public final double winLoss;
        public final String explanation;
        /** FEN of the position before the move, for rendering lines. */
        public final String fenBefore;
        /** Name of the opening reached by this move while still in book, else null. */
        public final String opening;

        public Entry(int plyIndex, boolean isWhite, String playedMove, String bestMove,
                     List<String> bestLine, double evalBefore, double evalAfter,
                     double loss, String severity, String qualityTag) {
            this(plyIndex, isWhite, playedMove, bestMove, bestLine, evalBefore, evalAfter, loss, severity, qualityTag, null, null);
        }

        public Entry(int plyIndex, boolean isWhite, String playedMove, String bestMove,
                     List<String> bestLine, double evalBefore, double evalAfter,
                     double loss, String severity, String qualityTag, Integer mateBefore, Integer mateAfter) {
            this(plyIndex, isWhite, playedMove, bestMove, bestLine, evalBefore, evalAfter, loss, severity, qualityTag,
                mateBefore, mateAfter, null, null, List.of(), Double.NaN, Double.NaN, Double.NaN, null, null, null);
        }

        public Entry(int plyIndex, boolean isWhite, String playedMove, String bestMove,
                     List<String> bestLine, double evalBefore, double evalAfter,
                     double loss, String severity, String qualityTag, Integer mateBefore, Integer mateAfter,
                     String san, String bestMoveSan, List<String> bestLineSan,
                     double winBefore, double winAfter, double winLoss,
                     String explanation, String fenBefore, String opening) {
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
            this.mateBefore = mateBefore;
            this.mateAfter = mateAfter;
            this.san = san;
            this.bestMoveSan = bestMoveSan;
            this.bestLineSan = bestLineSan == null ? List.of() : List.copyOf(bestLineSan);
            this.winBefore = winBefore;
            this.winAfter = winAfter;
            this.winLoss = winLoss;
            this.explanation = explanation;
            this.fenBefore = fenBefore;
            this.opening = opening;
        }
    }

    /** Receives each finished move as soon as it is classified; called on the analysis thread. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int done, int total, List<Entry> entriesSoFar);
    }

    /** Optional store for finished reviews, keyed by settings + moves (results are deterministic). */
    public interface ResultCache {
        Summary get(String key);

        void put(String key, Summary summary);
    }

    private final Path enginePath;
    private final int depth;
    private final int multiPv;
    private ResultCache cache;

    public GameAnalyzer(Path enginePath) {
        this(enginePath, DEFAULT_DEPTH, DEFAULT_MULTI_PV);
    }

    public GameAnalyzer(Path enginePath, int depth, int multiPv) {
        this.enginePath = enginePath;
        this.depth = Math.max(1, depth);
        this.multiPv = Math.max(1, multiPv);
    }

    public GameAnalyzer withCache(ResultCache cache) {
        this.cache = cache;
        return this;
    }

    public Summary analyze(List<String> moves) throws IOException {
        return analyze(moves, null);
    }

    public Summary analyze(List<String> moves, ProgressListener listener) throws IOException {
        if (enginePath == null) {
            return new Summary(List.of());
        }
        String cacheKey = depth + ":" + multiPv + ":" + String.join(" ", moves);
        if (cache != null) {
            Summary cached = cache.get(cacheKey);
            if (cached != null) {
                if (listener != null) {
                    listener.onProgress(cached.entries.size(), moves.size(), cached.entries);
                }
                return cached;
            }
        }

        List<Entry> result = new ArrayList<>();
        OpeningBook book = OpeningBook.standard();
        OpeningBook.Opening opening = null;
        boolean inBook = true;

        try (StockfishClient client = new StockfishClient(enginePath)) {
            client.configureForAnalysis(multiPv, HASH_MB);
            client.newGame();

            Board board = new Board();
            List<String> history = new ArrayList<>();
            String lastCaptureSquare = null;
            PositionEval before = evaluate(client, history, board);

            for (int i = 0; i < moves.size(); i++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Analysis cancelled");
                }
                String uci = moves.get(i);
                Move move = Tactics.parseLegal(board, uci);
                if (move == null) {
                    throw new IOException("Illegal move in game: " + uci);
                }
                Board boardBefore = board.clone();
                Side mover = board.getSideToMove();
                String san = Tactics.toSan(boardBefore, uci);
                boolean capture = Tactics.isCapture(boardBefore, move);
                boolean recapture = capture && Tactics.squareName(move.getTo()).equals(lastCaptureSquare);
                lastCaptureSquare = capture ? Tactics.squareName(move.getTo()) : null;
                board.doMove(move);
                history.add(uci);
                PositionEval after = evaluate(client, history, board);

                inBook = inBook && book.isBook(board);
                String openingHere = null;
                if (inBook) {
                    OpeningBook.Opening named = book.opening(board);
                    if (named != null) {
                        opening = named;
                    }
                    openingHere = opening == null ? null : opening.name();
                }

                Entry previous = result.isEmpty() ? null : result.get(result.size() - 1);
                result.add(buildEntry(i, mover, uci, move, san, boardBefore, board, before, after, inBook, openingHere, previous, recapture));
                if (listener != null) {
                    listener.onProgress(i + 1, moves.size(), Collections.unmodifiableList(new ArrayList<>(result)));
                }
                before = after;
            }
        }
        Summary summary = new Summary(result, opening == null ? null : opening.eco(), opening == null ? null : opening.name());
        if (cache != null) {
            cache.put(cacheKey, summary);
        }
        return summary;
    }

    /** A searched (or terminal) position. Lines are from the side to move's perspective, best first. */
    record PositionEval(Side sideToMove, List<StockfishClient.Line> lines, boolean mated, boolean drawn) {

        /** Side to move's win chance after the best move. */
        double sideToMoveWin() {
            if (mated) return 0;
            if (drawn || lines.isEmpty()) return 50;
            return lineWin(lines.get(0));
        }

        double whiteWin() {
            return sideToMove == Side.WHITE ? sideToMoveWin() : 100 - sideToMoveWin();
        }

        /** Pawns from White's perspective; ±100 for mate. */
        double whitePawns() {
            double stm;
            if (mated) stm = -100;
            else if (drawn || lines.isEmpty()) stm = 0;
            else {
                StockfishClient.Line top = lines.get(0);
                stm = top.mate != null ? (top.mate > 0 ? 100 : -100) : top.scoreCp / 100.0;
            }
            return sideToMove == Side.WHITE ? stm : -stm;
        }

        /** Mate-in-N from White's perspective, or null. */
        Integer whiteMate() {
            if (mated || drawn || lines.isEmpty() || lines.get(0).mate == null) return null;
            int mate = lines.get(0).mate;
            return sideToMove == Side.WHITE ? mate : -mate;
        }

        /** Mate-in-N for {@code side} (positive), or null. */
        Integer mateFor(Side side) {
            Integer white = whiteMate();
            if (white == null) return null;
            int forSide = side == Side.WHITE ? white : -white;
            return forSide > 0 ? forSide : null;
        }

        StockfishClient.Line top() {
            return lines.isEmpty() ? null : lines.get(0);
        }
    }

    static double lineWin(StockfishClient.Line line) {
        if (line.mate != null) {
            return line.mate > 0 ? 100 : 0;
        }
        return winPercent(line.scoreCp / 100.0);
    }

    private PositionEval evaluate(StockfishClient client, List<String> history, Board board) throws IOException {
        Side stm = board.getSideToMove();
        if (board.isMated()) {
            return new PositionEval(stm, List.of(), true, false);
        }
        if (board.isStaleMate() || board.isInsufficientMaterial()) {
            return new PositionEval(stm, List.of(), false, true);
        }
        List<StockfishClient.Line> lines = client.analyzeLines(String.join(" ", history), depth);
        return new PositionEval(stm, lines, false, false);
    }

    private Entry buildEntry(int ply, Side mover, String uci, Move move, String san, Board before, Board after,
                             PositionEval evalBefore, PositionEval evalAfter, boolean book, String opening,
                             Entry previous, boolean recapture) {
        boolean white = mover == Side.WHITE;
        StockfishClient.Line top = evalBefore.top();
        String bestMove = top == null ? null : top.firstMove();
        List<String> bestLine = top == null ? List.of() : limit(top.pv, MAX_LINE_PLIES);

        double winBest = evalBefore.sideToMoveWin();
        double winAfter = 100 - evalAfter.sideToMoveWin();
        Double winSecond = evalBefore.lines.size() >= 2 ? lineWin(evalBefore.lines.get(1)) : null;
        boolean playedBest = playedBestLine(evalBefore, uci);

        int captured = Tactics.capturedValue(before, move);
        Tactics.Capture offered = after.isMated() ? null : Tactics.bestCapture(after);
        int sacrifice = offered == null ? 0 : Math.max(0, offered.gain() - captured);

        Double opponentLastLoss = previous == null || Double.isNaN(previous.winLoss) ? null : previous.winLoss;
        Double winBeforeOpponentMove = previous == null || Double.isNaN(previous.winBefore) ? null
            : (white ? previous.winBefore : 100 - previous.winBefore);

        Integer moverMateBefore = evalBefore.mateFor(mover);
        Integer moverMateAfter = after.isMated() ? Integer.valueOf(0) : evalAfter.mateFor(mover);
        Integer opponentMateAfter = evalAfter.mateFor(mover.flip());

        MoveClassifier.Result result = MoveClassifier.classify(new MoveClassifier.Input(
            winBest, winAfter, winSecond, playedBest || after.isMated(), book, sacrifice, recapture,
            moverMateBefore, moverMateAfter != null, opponentLastLoss, winBeforeOpponentMove));

        double pawnsBefore = evalBefore.whitePawns();
        double pawnsAfter = evalAfter.whitePawns();
        double pawnLoss = (pawnsBefore - pawnsAfter) * (white ? 1 : -1);
        String evalText = formatEval(pawnsBefore, evalBefore.whiteMate()) + " → " + formatEval(pawnsAfter, evalAfter.whiteMate());

        String explanation = MoveExplainer.explain(new MoveExplainer.Context(
            before, after, move, result.tag(), bestLine,
            evalAfter.top() == null ? List.of() : evalAfter.top().pv,
            moverMateBefore, moverMateAfter == null || moverMateAfter == 0 ? null : moverMateAfter, opponentMateAfter,
            sacrifice >= 2 ? offered : null, recapture, opening, evalText));

        return new Entry(ply, white, uci, bestMove, bestLine, pawnsBefore, pawnsAfter, pawnLoss,
            MoveClassifier.byLoss(result.loss()), result.tag(), evalBefore.whiteMate(), evalAfter.whiteMate(),
            san, bestMove == null ? null : Tactics.toSan(before, bestMove), Tactics.toSan(before, bestLine),
            evalBefore.whiteWin(), evalAfter.whiteWin(), result.loss(), explanation, before.getFen(), opening);
    }

    /** Played the top line, or another line the engine scores exactly the same. */
    private static boolean playedBestLine(PositionEval eval, String uci) {
        StockfishClient.Line top = eval.top();
        if (top == null) {
            return false;
        }
        for (StockfishClient.Line line : eval.lines) {
            boolean sameScore = line.scoreCp == top.scoreCp && java.util.Objects.equals(line.mate, top.mate);
            if (uci.equals(line.firstMove()) && (line == top || sameScore)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> limit(List<String> list, int max) {
        return list.size() <= max ? list : list.subList(0, max);
    }

    static String formatEval(double whitePawns, Integer whiteMate) {
        if (whiteMate != null) {
            return (whiteMate > 0 ? "+M" : "-M") + Math.abs(whiteMate);
        }
        if (Math.abs(whitePawns) >= 100) {
            return whitePawns > 0 ? "1-0" : "0-1";
        }
        return String.format(Locale.ROOT, "%+.1f", whitePawns);
    }

    public static final class Summary {
        public final double avgLoss;
        public final int inaccuracies;
        public final int mistakes;
        public final int misses;
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
        public final String eco;
        public final String openingName;

        public Summary(List<Entry> entries) {
            this(entries, null, null);
        }

        public Summary(List<Entry> entries, String eco, String openingName) {
            this.entries = List.copyOf(entries);
            this.eco = eco;
            this.openingName = openingName;
            this.totalMoves = entries.size();
            this.avgLoss = entries.stream().mapToDouble(e -> Math.max(0, e.loss)).average().orElse(0);
            this.inaccuracies = countTag(entries, MoveClassifier.INACCURACY);
            this.mistakes = countTag(entries, MoveClassifier.MISTAKE);
            this.misses = countTag(entries, MoveClassifier.MISS);
            this.blunders = countTag(entries, MoveClassifier.BLUNDER);
            this.bestCount = countTag(entries, MoveClassifier.BEST);
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
        /** Best + Great + Brilliant, as in the classic summary. */
        public final int best;
        public final int excellent;
        public final int good;
        public final int inaccuracies;
        public final int mistakes;
        public final int blunders;
        public final int totalMoves;
        /** Count per classification, in {@link MoveClassifier#ORDER}. */
        public final Map<String, Integer> counts;

        private SideSummary(double accuracy, Map<String, Integer> counts, int totalMoves) {
            this.accuracy = accuracy;
            this.counts = Collections.unmodifiableMap(counts);
            this.best = counts.get(MoveClassifier.BEST) + counts.get(MoveClassifier.GREAT) + counts.get(MoveClassifier.BRILLIANT);
            this.excellent = counts.get(MoveClassifier.EXCELLENT);
            this.good = counts.get(MoveClassifier.GOOD);
            this.inaccuracies = counts.get(MoveClassifier.INACCURACY);
            this.mistakes = counts.get(MoveClassifier.MISTAKE);
            this.blunders = counts.get(MoveClassifier.BLUNDER);
            this.totalMoves = totalMoves;
        }

        static SideSummary from(List<Entry> entries, boolean white) {
            List<Entry> sideEntries = entries.stream().filter(e -> e.isWhite == white).toList();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String tag : MoveClassifier.ORDER) {
                counts.put(tag, countTag(sideEntries, tag));
            }
            return new SideSummary(computeAccuracy(sideEntries), counts, sideEntries.size());
        }
    }

    private static int countTag(List<Entry> entries, String tag) {
        return (int) entries.stream().filter(e -> tag.equals(e.qualityTag)).count();
    }

    static double computeAccuracy(List<Entry> entries) {
        if (entries.isEmpty()) {
            return 100;
        }
        List<Double> accuracies = entries.stream().map(GameAnalyzer::moveAccuracy).toList();
        List<Double> weights = volatilityWeights(entries);
        double weighted = weightedMean(accuracies, weights);
        double harmonic = harmonicMean(accuracies);
        return clamp((weighted + harmonic) / 2, 0, 100);
    }

    /** Lichess-style per-move accuracy from the win-chance loss, capped by the move's classification. */
    static double moveAccuracy(Entry entry) {
        if (MoveClassifier.BOOK.equals(entry.qualityTag)) {
            return 100;
        }
        double winLoss = winPercentLoss(entry);
        double raw = winLoss <= 0 ? 100
            : 103.1668100711649 * Math.exp(-0.04354415386753951 * winLoss) - 3.166924740191411;
        return clamp(Math.min(raw, MoveClassifier.accuracyCap(entry.qualityTag)), 0, 100);
    }

    /** Kept for callers of the old name. */
    static double lichessMoveAccuracy(Entry entry) {
        return moveAccuracy(entry);
    }

    static double winPercentLoss(Entry entry) {
        if (!Double.isNaN(entry.winLoss)) {
            return entry.winLoss;
        }
        double before = entryWhiteWin(entry.evalBefore, entry.mateBefore);
        double after = entryWhiteWin(entry.evalAfter, entry.mateAfter);
        double moverBefore = entry.isWhite ? before : 100 - before;
        double moverAfter = entry.isWhite ? after : 100 - after;
        return Math.max(0, moverBefore - moverAfter);
    }

    private static double entryWhiteWin(double pawns, Integer mate) {
        if (mate != null) {
            return mate > 0 ? 100 : 0;
        }
        return winPercent(pawns);
    }

    /** White's win chance (0–100) for an evaluation in pawns from White's perspective (lichess model). */
    public static double winPercent(double pawnsFromWhitePerspective) {
        double cp = clamp(pawnsFromWhitePerspective * 100, -1000, 1000);
        return 50 + 50 * ((2 / (1 + Math.exp(-0.00368208 * cp))) - 1);
    }

    private static double entryWhiteWin(Entry entry) {
        if (!Double.isNaN(entry.winAfter)) {
            return entry.winAfter;
        }
        return entryWhiteWin(entry.evalAfter, entry.mateAfter);
    }

    private static List<Double> volatilityWeights(List<Entry> entries) {
        List<Double> winPercents = new ArrayList<>();
        winPercents.add(winPercent(0));
        entries.forEach(entry -> winPercents.add(entryWhiteWin(entry)));

        int windowSize = (int) clamp(entries.size() / 10.0, 2, 8);
        List<Double> weights = new ArrayList<>();
        int leadingCopies = Math.max(0, Math.min(windowSize, winPercents.size()) - 2);
        for (int i = 0; i < leadingCopies; i++) {
            weights.add(windowWeight(winPercents, 0, windowSize));
        }
        for (int start = 0; start + windowSize <= winPercents.size(); start++) {
            weights.add(windowWeight(winPercents, start, windowSize));
        }
        while (weights.size() < entries.size()) {
            weights.add(weights.isEmpty() ? 1.0 : weights.get(weights.size() - 1));
        }
        return weights.size() > entries.size() ? weights.subList(0, entries.size()) : weights;
    }

    private static double windowWeight(List<Double> values, int start, int size) {
        int end = Math.min(values.size(), start + size);
        if (start >= end) {
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
        for (int i = 0; i < values.size(); i++) {
            double weight = i < weights.size() ? weights.get(i) : 1.0;
            weightedSum += values.get(i) * weight;
            weightSum += weight;
        }
        return weightSum == 0 ? values.stream().mapToDouble(Double::doubleValue).average().orElse(100) : weightedSum / weightSum;
    }

    private static double harmonicMean(List<Double> values) {
        if (values.stream().anyMatch(value -> value <= 0)) {
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

    /** Engine mate-in-N signed from White's perspective; null for centipawn scores and for "mate 0" (already mated). */
    static Integer toWhitePerspectiveMate(StockfishClient.AnalysisResult result, boolean whiteToMove) {
        if (result == null || result.mate == null || result.mate == 0) {
            return null;
        }
        return whiteToMove ? result.mate : -result.mate;
    }
}
