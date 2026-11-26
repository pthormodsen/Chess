import { Chess } from 'chess.js'
import { uciSquares } from '../review.jsx'

// Pure board helpers for the game view: squares, moves, premoves, slides and check marks.
// Pieces are { name, color, col, row } with row 0 = Black's back rank, as the server sends them.

export const PROMOTION_PIECES = ['q', 'r', 'b', 'n']
export const PROMOTION_NAMES = { q: 'Queen', r: 'Rook', b: 'Bishop', n: 'Knight' }

export const files = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h']

export function squareName(col, row) {
  return `${files[col]}${8 - row}`
}

export function sameSquare(a, b) {
  return a && b && a.col === b.col && a.row === b.row
}

export function splitCaptures(value) {
  if (!value || value === '-') return []
  return value.split(' ').filter(Boolean)
}

export function samePremoves(a, b) {
  return a.length === b.length && a.every((move, i) => move.fromCol === b[i].fromCol && move.fromRow === b[i].fromRow
    && move.toCol === b[i].toCol && move.toRow === b[i].toRow && (move.promotion ?? null) === (b[i].promotion ?? null))
}

export function squareMap(pieces) {
  const map = new Map()
  for (const piece of pieces ?? []) map.set(`${piece.col},${piece.row}`, piece)
  return map
}

// Pieces after a move, for showing an explored move at once while Stockfish judges it.
// Handles castling (the rook moves too), en passant and promotion, so the board is right immediately.
export function withMoveApplied(pieces, move) {
  const list = pieces ?? []
  const mover = list.find(piece => piece.col === move.fromCol && piece.row === move.fromRow)
  if (!mover) return list
  const castling = mover.name === 'King' && Math.abs(move.toCol - move.fromCol) === 2
  const rookFrom = castling ? (move.toCol > move.fromCol ? 7 : 0) : null
  const rookTo = castling ? (move.toCol > move.fromCol ? 5 : 3) : null
  const enPassant = mover.name === 'Pawn' && move.fromCol !== move.toCol
    && !list.some(piece => piece.col === move.toCol && piece.row === move.toRow)
  const promotes = mover.name === 'Pawn' && (move.toRow === 0 || move.toRow === 7)
  return list
    .filter(piece => !(piece.col === move.toCol && piece.row === move.toRow))
    .filter(piece => !(enPassant && piece.col === move.toCol && piece.row === move.fromRow))
    .map(piece => {
      if (piece === mover) {
        const name = promotes ? PROMOTION_NAMES[move.promotion ?? 'q'] : piece.name
        return { ...piece, name, col: move.toCol, row: move.toRow }
      }
      if (castling && piece.name === 'Rook' && piece.color === mover.color && piece.row === move.fromRow && piece.col === rookFrom) {
        return { ...piece, col: rookTo }
      }
      return piece
    })
}

/**
 * The pieces that slide for one move, forwards or (reverse) taking it back: the mover, plus the rook when
 * the king castles. piecesAfter is the position being shown once the slide ends.
 */
export function moveSlides(move, piecesAfter, reverse) {
  if (!move) return []
  const slide = (fromCol, fromRow, toCol, toRow) => (reverse
    ? { fromCol: toCol, fromRow: toRow, toCol: fromCol, toRow: fromRow }
    : { fromCol, fromRow, toCol, toRow })
  const slides = [slide(move.fromCol, move.fromRow, move.toCol, move.toRow)]
  const landed = reverse ? { col: move.fromCol, row: move.fromRow } : { col: move.toCol, row: move.toRow }
  const mover = (piecesAfter ?? []).find(piece => piece.col === landed.col && piece.row === landed.row)
  if (mover?.name === 'King' && Math.abs(move.toCol - move.fromCol) === 2) {
    const kingside = move.toCol > move.fromCol
    slides.push(slide(kingside ? 7 : 0, move.fromRow, kingside ? 5 : 3, move.fromRow))
  }
  return slides
}

const FEN_LETTERS = { Pawn: 'p', Knight: 'n', Bishop: 'b', Rook: 'r', Queen: 'q', King: 'k' }

/** A FEN good enough to ask "in check? mated?" (no castling or en-passant rights: neither can answer a check... except e.p., rarely). */
export function fenFromPieces(pieces, turn) {
  const grid = Array.from({ length: 8 }, () => Array(8).fill(null))
  for (const piece of pieces ?? []) {
    const letter = FEN_LETTERS[piece.name]
    if (letter) grid[piece.row][piece.col] = piece.color === 'white' ? letter.toUpperCase() : letter
  }
  const placement = grid.map(rank => {
    let out = ''
    let empty = 0
    for (const cell of rank) {
      if (cell) {
        out += (empty || '') + cell
        empty = 0
      } else {
        empty += 1
      }
    }
    return out + (empty || '')
  }).join('/')
  return `${placement} ${turn === 'black' ? 'b' : 'w'} - - 0 1`
}

/** { checked: 'white'|'black'|null, mated: boolean } for a position, or nulls if the FEN is unusable. */
export function checkState(fen) {
  if (!fen) return { checked: null, mated: false }
  try {
    const chess = new Chess(fen)
    const side = chess.turn() === 'w' ? 'white' : 'black'
    return { checked: chess.inCheck() ? side : null, mated: chess.isCheckmate() }
  } catch (ignored) {
    return { checked: null, mated: false }
  }
}

/**
 * Premove targets: where this piece could go once the opponent has moved. Only your own pieces block
 * (the opponent's may move away or onto a square); the server checks the move when it is played.
 */
export function premoveTargets(pieces, piece) {
  const at = new Map((pieces ?? []).map(p => [`${p.col},${p.row}`, p]))
  const targets = []
  const add = (col, row) => {
    if (col < 0 || col > 7 || row < 0 || row > 7) return false
    const occupant = at.get(`${col},${row}`)
    if (occupant?.color === piece.color) return false
    targets.push({ col, row, capture: Boolean(occupant) })
    return true
  }
  const { col, row, color } = piece
  const forward = color === 'white' ? -1 : 1
  const homeRow = color === 'white' ? 7 : 0
  const rays = dirs => dirs.forEach(([dc, dr]) => {
    for (let c = col + dc, r = row + dr; add(c, r); c += dc, r += dr) { /* walk the ray */ }
  })
  const straight = [[1, 0], [-1, 0], [0, 1], [0, -1]]
  const diagonal = [[1, 1], [1, -1], [-1, 1], [-1, -1]]
  switch (piece.name) {
    case 'Pawn':
      add(col, row + forward)
      if (row === homeRow + forward) add(col, row + 2 * forward)
      add(col - 1, row + forward)
      add(col + 1, row + forward)
      break
    case 'Knight':
      [[1, 2], [2, 1], [-1, 2], [-2, 1], [1, -2], [2, -1], [-1, -2], [-2, -1]].forEach(([dc, dr]) => add(col + dc, row + dr))
      break
    case 'Bishop':
      rays(diagonal)
      break
    case 'Rook':
      rays(straight)
      break
    case 'Queen':
      rays([...straight, ...diagonal])
      break
    case 'King':
      [...straight, ...diagonal].forEach(([dc, dr]) => add(col + dc, row + dr))
      if (col === 4 && row === homeRow) {
        add(6, row)
        add(2, row)
      }
      break
    default:
      break
  }
  return targets
}

/** SAN of the last move in displayMoves entries like "12. Nf3 Nc6" or "1... e5". */
export function lastSan(displayMoves) {
  const entry = displayMoves?.[displayMoves.length - 1]
  return entry ? entry.trim().split(/\s+/).pop() : null
}

export function sameMove(a, b) {
  return a && b && a.fromCol === b.fromCol && a.fromRow === b.fromRow && a.toCol === b.toCol && a.toRow === b.toRow
}

const PIECE_VALUES = { Pawn: 1, Knight: 3, Bishop: 3, Rook: 5, Queen: 9, King: 0 }

// White material minus Black material, from the pieces on the board (so promotions count correctly).
export function materialBalance(pieces) {
  return (pieces ?? []).reduce((sum, piece) => {
    const value = PIECE_VALUES[piece.name] ?? 0
    return piece.color === 'white' ? sum + value : sum - value
  }, 0)
}

/** Every position of a game from its UCI moves: [{ fen, move, san }], index = plies played. Stops at an unplayable move. */
export function replayUci(uciMoves) {
  const chess = new Chess()
  const frames = [{ fen: chess.fen(), move: null, san: null }]
  for (const uci of uciMoves ?? []) {
    try {
      const played = chess.move({ from: uci.slice(0, 2), to: uci.slice(2, 4), promotion: uci[4] })
      frames.push({ fen: chess.fen(), move: uciSquares(uci), san: played.san })
    } catch (ignored) {
      break
    }
  }
  return frames
}
