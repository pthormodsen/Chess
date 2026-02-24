#!/bin/zsh
export STOCKFISH_PATH="/Users/patrik/Documents/Prosjekter/Chess/engines/stockfish/stockfish-macos-m1-apple-silicon"
mvn -q package
java -cp target/classes main.Main
