#!/usr/bin/env python3
"""Builds src/main/resources/training/puzzles.tsv from the Lichess puzzle database (CC0).

Reads the decompressed CSV on stdin and keeps a deterministic, rating-stratified sample of
well-tested puzzles (enough plays, a stable rating, liked by players):

    curl -sL https://database.lichess.org/lichess_db_puzzle.csv.zst \
      | zstd -dc | python3 scripts/build-puzzles.py > src/main/resources/training/puzzles.tsv

CSV columns: PuzzleId,FEN,Moves,Rating,RatingDeviation,Popularity,NbPlays,Themes,GameUrl,OpeningTags
Output (TSV): id, fen, moves, rating, themes. The FEN is the position before the opponent's move;
the first move is the opponent's, the rest alternate between the solver and the opponent.
"""
import csv
import random
import sys

PER_BUCKET = 800          # puzzles per 100-point rating band
MIN_RATING, MAX_RATING = 400, 3000
MIN_PLAYS = 300
MAX_DEVIATION = 90
MIN_POPULARITY = 80


def main():
    rng = random.Random(20261126)
    buckets = {}
    seen = {}
    reader = csv.reader(sys.stdin)
    header = next(reader)
    if header[0] != 'PuzzleId':
        raise SystemExit(f'unexpected header: {header}')
    for row in reader:
        if len(row) < 8:
            continue
        try:
            rating = int(row[3])
            deviation = int(row[4])
            popularity = int(row[5])
            plays = int(row[6])
        except ValueError:
            continue
        if not (MIN_RATING <= rating < MAX_RATING) or deviation > MAX_DEVIATION:
            continue
        if plays < MIN_PLAYS or popularity < MIN_POPULARITY:
            continue
        band = rating // 100
        reservoir = buckets.setdefault(band, [])
        count = seen[band] = seen.get(band, 0) + 1
        item = (row[0], row[1], row[2], rating, row[7])
        # Reservoir sampling keeps a uniform sample per band in one pass over a multi-million-line file.
        if len(reservoir) < PER_BUCKET:
            reservoir.append(item)
        else:
            slot = rng.randrange(count)
            if slot < PER_BUCKET:
                reservoir[slot] = item

    out = sys.stdout
    out.write('id\tfen\tmoves\trating\tthemes\n')
    rows = sorted((item for band in buckets.values() for item in band), key=lambda item: (item[3], item[0]))
    for puzzle_id, fen, moves, rating, themes in rows:
        out.write(f'{puzzle_id}\t{fen}\t{moves}\t{rating}\t{themes}\n')
    print(f'{len(rows)} puzzles in {len(buckets)} rating bands', file=sys.stderr)


if __name__ == '__main__':
    main()
