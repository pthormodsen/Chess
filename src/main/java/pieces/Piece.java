package pieces;

import main.Board;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

public class Piece {

    public int col, row;
    public int xPos, yPos;

    public boolean isWhite;
    public String name;
    public int value;

    public boolean isFirstMove = true;

    BufferedImage sheet;
    protected int sheetScale;
    {
        try (InputStream spriteStream = ClassLoader.getSystemResourceAsStream("pieces.png")) {
            if(spriteStream == null){
                throw new IllegalStateException("Unable to locate sprite sheet 'pieces.png' on the classpath.");
            }
            sheet = ImageIO.read(spriteStream);
            if(sheet == null){
                throw new IllegalStateException("Failed to decode sprite sheet 'pieces.png'.");
            }
            sheetScale = sheet.getWidth()/6;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load sprite sheet 'pieces.png'.", e);
        }
    }


    Image sprite;
    Board board;

    public Piece(Board board) {
        this.board = board;
    }

    public boolean isValidMovement(int col, int row){
        return true;
    }

    public boolean moveCollidesWithPiece(int col, int row){
        return false;
    }

    public void paint(Graphics g2d){
        g2d.drawImage(sprite, xPos, yPos, null);
    }

}
