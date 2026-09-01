#!/bin/zsh
export STOCKFISH_PATH="${STOCKFISH_PATH:-/Users/patrik/Documents/Prosjekter/Chess/engines/stockfish/stockfish-macos-m1-apple-silicon}"
mvn -q package
java -jar target/Chess-1.0-SNAPSHOT.jar
