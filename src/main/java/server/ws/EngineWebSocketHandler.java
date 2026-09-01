package server.ws;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import uci.StockfishClient;
import uci.StockfishClient.AnalysisResult;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Very small WebSocket handler: expects JSON like {"moves":"e2e4 e7e5","movetimeMs":500}
 * and replies with a JSON containing bestMove.
 */
public class EngineWebSocketHandler extends TextWebSocketHandler {

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        String payload = message.getPayload();
        // very small and tolerant parsing to avoid adding heavy JSON libs here
        String moves = extractJsonString(payload, "moves");
        String movetimeStr = extractJsonString(payload, "movetimeMs");
        long movetime = 500;
        try { if(movetimeStr != null) movetime = Long.parseLong(movetimeStr); } catch(Exception ignored){}

        String stockfishPath = System.getenv("STOCKFISH_PATH");
        if(stockfishPath == null) stockfishPath = "engines/stockfish/stockfish-macos-m1-apple-silicon";

        try (StockfishClient client = new StockfishClient(Path.of(stockfishPath))) {
            AnalysisResult res = client.analyzePosition(moves, Duration.ofMillis(movetime));
            String reply = "{\"bestMove\":\"" + (res == null || res.bestMove == null ? "" : res.bestMove) + "\"}";
            session.sendMessage(new TextMessage(reply));
        } catch (IOException e) {
            session.sendMessage(new TextMessage("{\"error\": \"" + e.getMessage() + "\"}"));
        }
    }

    private String extractJsonString(String json, String key){
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if(idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if(colon < 0) return null;
        int start = colon + 1;
        // skip spaces
        while(start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        char c = json.charAt(start);
        if(c == '"'){
            int end = json.indexOf('"', start+1);
            if(end < 0) return null;
            return json.substring(start+1, end);
        } else {
            // number
            int end = start;
            while(end < json.length() && (Character.isDigit(json.charAt(end))|| json.charAt(end)=='-')) end++;
            return json.substring(start, end);
        }
    }
}
