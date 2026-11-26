package main;

import main.Board.MoveHighlight;
import pieces.Piece;

import java.awt.*;

/** Swing painting of a {@link Board}: squares, highlights, legal-move dots, pieces, arrows, coordinates. */
final class BoardPainter {

    private final Board board;

    BoardPainter(Board board) {
        this.board = board;
    }

    void paint(Graphics2D g2d) {

        //Painting board
        for (int r = 0; r < board.rows; r++) {
            for (int c = 0; c < board.cols; c++) {
                g2d.setColor((c + r) % 2 == 0 ? new Color(240, 217, 181) : new Color(181, 139, 99));
                g2d.fillRect(board.drawX(c), board.drawY(r), board.tileSize, board.tileSize);
            }
        }

        if(board.lastMove != null){
            Color moveHighlightColor = board.analysisMode && board.analysisPointer >= 0
                ? qualityHighlightColor(board.currentQualityTag)
                : new Color(246, 246, 105, 160);
            g2d.setColor(moveHighlightColor);
            g2d.fillRect(board.drawX(board.lastMove.oldCol), board.drawY(board.lastMove.oldRow), board.tileSize, board.tileSize);
            g2d.fillRect(board.drawX(board.lastMove.newCol), board.drawY(board.lastMove.newRow), board.tileSize, board.tileSize);
        }

        if(board.selectedPiece != null){
            g2d.setColor(new Color(255, 255, 140, 160));
            g2d.fillRect(board.drawX(board.selectedPiece.col), board.drawY(board.selectedPiece.row), board.tileSize, board.tileSize);
        }

        // Paint legal moves (accurate Chess.com olive tone)
        if (board.selectedPiece != null) {
            Object oldAA = g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Stroke oldStroke = g2d.getStroke();

            final Color moveDot  = new Color(127, 166, 80, 140);  // olive green
            final Color captureRing = new Color(127, 166, 80, 200);

            for (int r = 0; r < board.rows; r++) {
                for (int c = 0; c < board.cols; c++) {
                    Move preview = new Move(board, board.selectedPiece, c, r);
                    if (board.isValidMove(preview)) {

                        int x = board.drawX(c);
                        int y = board.drawY(r);

                        if (preview.capture == null) {
                            int diameter = board.tileSize / 3;
                            int circleX = x + (board.tileSize - diameter) / 2;
                            int circleY = y + (board.tileSize - diameter) / 2;

                            g2d.setColor(moveDot);
                            g2d.fillOval(circleX, circleY, diameter, diameter);

                        } else {
                            int padding = board.tileSize / 8;
                            int size = board.tileSize - padding * 2;

                            g2d.setColor(captureRing);
                            g2d.setStroke(new BasicStroke(Math.max(2f, board.tileSize / 12f)));
                            g2d.drawOval(x + padding, y + padding, size, size);
                        }
                    }
                }
            }

            g2d.setStroke(oldStroke);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
        }

        //Painting each piece
        for(Piece piece : board.pieceList){
            int oldX = piece.xPos;
            int oldY = piece.yPos;
            if(board.isDraggingPiece && piece == board.selectedPiece){
                piece.xPos = board.dragScreenX;
                piece.yPos = board.dragScreenY;
            } else {
                piece.xPos = board.drawX(piece.col);
                piece.yPos = board.drawY(piece.row);
            }
            piece.paint(g2d);
            piece.xPos = oldX;
            piece.yPos = oldY;
        }

        if(!board.checkMarkers.isEmpty()){
            for(MoveHighlight threat : board.checkMarkers){
                drawArrow(g2d, threat, new Color(214, 73, 73, 220));
            }
        }
        if(board.analysisMode && board.analysisPointer >= 0 && board.lastMove != null){
            MoveHighlight actualArrow = new MoveHighlight(board.lastMove.oldCol, board.lastMove.oldRow, board.lastMove.newCol, board.lastMove.newRow);
            drawArrow(g2d, actualArrow, qualityColor(board.currentQualityTag));
        }
        if(board.bestMoveArrow != null){
            drawArrow(g2d, board.bestMoveArrow, new Color(65, 190, 120, 220));
        }

        drawCoordinates(g2d);
        drawQualityBadge(g2d);
    }


    private Color qualityColor(String tag){
        if(tag == null){
            return new Color(80, 150, 255, 200);
        }
        switch (tag){
            case "Brilliant":
                return new Color(38, 194, 163, 220);
            case "Great":
                return new Color(116, 155, 191, 210);
            case "Best":
                return new Color(129, 182, 76, 210);
            case "Excellent":
                return new Color(129, 182, 76, 210);
            case "Good":
                return new Color(149, 183, 118, 210);
            case "Inaccuracy":
                return new Color(247, 192, 69, 210);
            case "Mistake":
                return new Color(255, 167, 89, 210);
            case "Severe Mistake":
                return new Color(217, 95, 14, 210);
            case "Blunder":
                return new Color(212, 63, 58, 210);
            case "Book":
                return new Color(168, 136, 101, 210);
            case "Miss":
                return new Color(255, 119, 105, 210);
            case "Mate":
                return new Color(186, 85, 211, 220);
            default:
                return new Color(80, 150, 255, 200);
        }
    }


    private Color qualityHighlightColor(String tag){
        Color base = qualityColor(tag);
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), 140);
    }


    private void drawArrow(Graphics2D g2d, MoveHighlight arrow, Color color){
        if(arrow == null){
            return;
        }
        Color paintColor = color != null ? color : new Color(255, 255, 255, 200);
        int fromX = board.drawX(arrow.fromCol) + board.tileSize / 2;
        int fromY = board.drawY(arrow.fromRow) + board.tileSize / 2;
        int toX = board.drawX(arrow.toCol) + board.tileSize / 2;
        int toY = board.drawY(arrow.toRow) + board.tileSize / 2;

        Graphics2D g = (Graphics2D) g2d.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int headLength = Math.max(board.tileSize / 2, 18);
        int headWidth = Math.max(board.tileSize / 3, 14);
        int shaftWidth = Math.max(board.tileSize / 7, 7);

        double dx = toX - fromX;
        double dy = toY - fromY;
        double length = Math.max(1, Math.hypot(dx, dy));
        double ux = dx / length;
        double uy = dy / length;
        double px = -uy;
        double py = ux;

        int shaftEndX = (int) (toX - ux * headLength);
        int shaftEndY = (int) (toY - uy * headLength);

        java.awt.geom.GeneralPath shaft = new java.awt.geom.GeneralPath();
        shaft.moveTo(fromX + px * shaftWidth, fromY + py * shaftWidth);
        shaft.lineTo(shaftEndX + px * shaftWidth, shaftEndY + py * shaftWidth);
        shaft.lineTo(shaftEndX - px * shaftWidth, shaftEndY - py * shaftWidth);
        shaft.lineTo(fromX - px * shaftWidth, fromY - py * shaftWidth);
        shaft.closePath();

        java.awt.Polygon head = new java.awt.Polygon();
        head.addPoint(toX, toY);
        head.addPoint((int) (shaftEndX + px * headWidth), (int) (shaftEndY + py * headWidth));
        head.addPoint((int) (shaftEndX - px * headWidth), (int) (shaftEndY - py * headWidth));

        java.awt.geom.AffineTransform shadowTx = java.awt.geom.AffineTransform.getTranslateInstance(2, 3);
        Shape shaftShadow = shadowTx.createTransformedShape(shaft);
        Shape headShadow = shadowTx.createTransformedShape(head);
        g.setColor(new Color(0, 0, 0, 50));
        g.fill(shaftShadow);
        g.fill(headShadow);

        Color start = new Color(paintColor.getRed(), paintColor.getGreen(), paintColor.getBlue(), 90);
        Color end = new Color(paintColor.getRed(), paintColor.getGreen(), paintColor.getBlue(), 210);
        g.setPaint(new GradientPaint(fromX, fromY, start, toX, toY, end));
        g.fill(shaft);
        g.fill(head);

        g.setStroke(new BasicStroke(Math.max(1.5f, board.tileSize / 24f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 255, 255, 80));
        g.draw(shaft);
        g.draw(head);
        g.dispose();
    }


    private void drawQualityBadge(Graphics2D g2d){
        if(!board.analysisMode || board.analysisPointer < 0 || board.currentQualityTag == null){
            return;
        }
        String label = board.currentQualityTag.toUpperCase();
        Graphics2D g = (Graphics2D) g2d.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Font font = board.getFont().deriveFont(Font.BOLD, 16f);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(label);
        int textHeight = fm.getAscent();
        int padding = 10;
        int badgeWidth = textWidth + padding * 2;
        int badgeHeight = textHeight + padding;
        int x = board.coordinateMargin + (board.cols * board.tileSize - badgeWidth) / 2;
        int y = Math.max(6, board.coordinateMargin / 2);
        Color base = qualityColor(board.currentQualityTag);
        Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), 200);
        g.setColor(fill);
        g.fillRoundRect(x, y, badgeWidth, badgeHeight, 16, 16);
        g.setColor(new Color(0, 0, 0, 60));
        g.drawRoundRect(x, y, badgeWidth, badgeHeight, 16, 16);
        if(fill.getRed() * 0.299 + fill.getGreen() * 0.587 + fill.getBlue() * 0.114 < 140){
            g.setColor(Color.WHITE);
        } else {
            g.setColor(Color.BLACK);
        }
        g.drawString(label, x + padding, y + padding + textHeight - fm.getDescent());
        g.dispose();
    }


    private void drawCoordinates(Graphics2D g2d){
        Graphics2D g = (Graphics2D) g2d.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font font = board.getFont().deriveFont(Font.BOLD, 12f);
        g.setFont(font);
        Color text = new Color(230, 230, 230, 200);
        Color shadow = new Color(0, 0, 0, 120);

        for(int c = 0; c < board.cols; c++){
            int boardCol = board.screenToBoardCol(c);
            char file = (char) ('a' + boardCol);
            int x = board.coordinateMargin + c * board.tileSize + board.tileSize - 12;
            int topY = board.coordinateMargin - 6;
            int bottomY = board.coordinateMargin + board.boardHeightPx() + 16;
            g.setColor(shadow);
            g.drawString(String.valueOf(file), x + 1, bottomY + 1);
            g.setColor(text);
            g.drawString(String.valueOf(file), x, bottomY);
            g.setColor(shadow);
            g.drawString(String.valueOf(file), x + 1, topY + 1);
            g.setColor(text);
            g.drawString(String.valueOf(file), x, topY);
        }

        for(int r = 0; r < board.rows; r++){
            int boardRow = board.screenToBoardRow(r);
            String rank = String.valueOf(board.rows - boardRow);
            int leftX = board.coordinateMargin - 18;
            int rightX = board.coordinateMargin + board.boardWidthPx() + 6;
            int y = board.coordinateMargin + r * board.tileSize + 14;
            g.setColor(shadow);
            g.drawString(rank, leftX + 1, y + 1);
            g.setColor(text);
            g.drawString(rank, leftX, y);
            g.setColor(shadow);
            g.drawString(rank, rightX + 1, y + 1);
            g.setColor(text);
            g.drawString(rank, rightX, y);
        }
        g.dispose();
    }
}
