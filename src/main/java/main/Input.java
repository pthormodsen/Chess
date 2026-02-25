package main;

import pieces.Piece;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;

public class Input extends MouseAdapter {

    Board board;
    private boolean hasDragged = false;

    public Input(Board board){
        this.board = board;
    }

    @Override
    public void mousePressed(MouseEvent e) {

        hasDragged = false;
        int col = board.screenToBoardCol(e.getX() / board.tileSize);
        int row = board.screenToBoardRow(e.getY() / board.tileSize);

        if(!board.isGameActive()){
            return;
        }

        if(!board.isInsideBoard(col, row)){
            return;
        }

        Piece pieceXY = board.getPiece(col,row);
        if(pieceXY != null){
            if(board.selectedPiece == null || board.sameTeam(board.selectedPiece, pieceXY)){
                board.selectedPiece = pieceXY;
            }
        }

    }

    @Override
    public void mouseDragged(MouseEvent e) {

        if(!board.isGameActive()){
            return;
        }

        if(!board.isGameActive()){
            return;
        }

        hasDragged = true;
        if(board.selectedPiece != null){
            board.updateDragPosition(e.getX() - board.tileSize / 2, e.getY() - board.tileSize / 2);
            board.repaint();
        }

    }

    @Override
    public void mouseReleased(MouseEvent e) {

        int col = board.screenToBoardCol(e.getX() / board.tileSize);
        int row = board.screenToBoardRow(e.getY() / board.tileSize);

        if(!board.isGameActive()){
            board.selectedPiece = null;
            board.repaint();
            return;
        }

        if(!hasDragged){
            board.stopDragging();
            return;
        }

        if(board.selectedPiece != null){

            if(!board.isInsideBoard(col, row)){
                board.stopDragging();
                snapBackSelectedPiece();
                board.selectedPiece = null;
                board.repaint();
                return;
            }

            Move move = new Move(board, board.selectedPiece, col, row);

            if(board.isValidMove(move)){
                board.makeMove(move);
            } else {
                snapBackSelectedPiece();
            }
        }
        board.selectedPiece = null;
        board.stopDragging();
        board.repaint();

    }

    private void snapBackSelectedPiece(){
        board.selectedPiece.xPos = board.selectedPiece.col * board.tileSize;
        board.selectedPiece.yPos = board.selectedPiece.row * board.tileSize;
    }

    @Override
    public void mouseClicked(MouseEvent e) {

        if(!board.isGameActive()){
            return;
        }

        int col = board.screenToBoardCol(e.getX() / board.tileSize);
        int row = board.screenToBoardRow(e.getY() / board.tileSize);

        if(!board.isInsideBoard(col, row)){
            return;
        }

        if(board.selectedPiece == null){
            Piece target = board.getPiece(col, row);
            if(target != null){
                board.selectedPiece = target;
                int screenX = board.boardToScreenCol(col) * board.tileSize;
                int screenY = board.boardToScreenRow(row) * board.tileSize;
                board.updateDragPosition(screenX, screenY);
                board.repaint();
            }
            return;
        }

        Piece clickedPiece = board.getPiece(col, row);
        if(clickedPiece != null && board.sameTeam(board.selectedPiece, clickedPiece)){
            board.selectedPiece = clickedPiece;
            board.repaint();
            return;
        }

        Move move = new Move(board, board.selectedPiece, col, row);
        if(board.isValidMove(move)){
            board.makeMove(move);
        } else {
            snapBackSelectedPiece();
        }
        board.selectedPiece = null;
        board.stopDragging();
        board.repaint();
    }

}
