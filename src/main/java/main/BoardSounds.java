package main;

import javazoom.jl.player.Player;

import java.awt.GraphicsEnvironment;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;

/** The desktop app's move sounds (MP3 via JLayer). Silent when headless, and after the first playback failure. */
final class BoardSounds {

    final byte[] move = load("sounds/move-self.mp3");
    final byte[] capture = load("sounds/capture.mp3");
    final byte[] promote = load("sounds/promote.mp3");
    final byte[] castle = load("sounds/castle.mp3");
    final byte[] check = load("sounds/move-check.mp3");
    final byte[] notify = load("sounds/notify.mp3");
    private volatile boolean enabled = !GraphicsEnvironment.isHeadless();

    private static byte[] load(String resource){
        try (InputStream is = BoardSounds.class.getClassLoader().getResourceAsStream(resource)) {
            if(is == null){
                System.err.println("Missing sound: " + resource);
                return null;
            }
            return is.readAllBytes();
        } catch (Exception e){
            System.err.println("Unable to load sound " + resource + ": " + e.getMessage());
            return null;
        }
    }

    void play(byte[] data){
        if(!enabled || data == null){
            return;
        }
        byte[] copy = Arrays.copyOf(data, data.length);
        new Thread(() -> {
            try (BufferedInputStream bis = new BufferedInputStream(new ByteArrayInputStream(copy))) {
                new Player(bis).play();
            } catch (Exception e){
                System.err.println("Unable to play sound: " + e.getMessage());
                enabled = false;
            }
        }).start();
    }
}
