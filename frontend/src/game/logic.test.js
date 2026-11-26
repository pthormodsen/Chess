import { describe, expect, it } from 'vitest'
import { piecesFromFen } from '../review.jsx'
import {
  checkState, fenFromPieces, lastSan, materialBalance, moveSlides, premoveTargets, replayUci, samePremoves,
  squareMap, squareName, withMoveApplied
} from './logic.js'

const START = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1'
// Squares as {col,row} from algebraic names (row 0 = rank 8).
const sq = name => ({ col: name.charCodeAt(0) - 97, row: 8 - Number(name[1]) })
const move = (from, to, promotion) => {
  const a = sq(from)
  const b = sq(to)
  return { fromCol: a.col, fromRow: a.row, toCol: b.col, toRow: b.row, promotion }
}
const at = (pieces, name) => squareMap(pieces).get(`${sq(name).col},${sq(name).row}`)
const targetNames = targets => targets.map(t => squareName(t.col, t.row)).sort()

describe('withMoveApplied', () => {
  it('moves a piece and captures what was on the target', () => {
    const pieces = piecesFromFen('4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1')
    const after = withMoveApplied(pieces, move('e4', 'd5'))
    expect(after).toHaveLength(3)
    expect(at(after, 'd5')).toMatchObject({ name: 'Pawn', color: 'white' })
    expect(at(after, 'e4')).toBeUndefined()
  })

  it('moves the rook too when castling', () => {
    const pieces = piecesFromFen('4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1')
    const kingside = withMoveApplied(pieces, move('e1', 'g1'))
    expect(at(kingside, 'f1')).toMatchObject({ name: 'Rook' })
    expect(at(kingside, 'h1')).toBeUndefined()
    const queenside = withMoveApplied(pieces, move('e1', 'c1'))
    expect(at(queenside, 'd1')).toMatchObject({ name: 'Rook' })
    expect(at(queenside, 'a1')).toBeUndefined()
  })

  it('removes the pawn taken en passant', () => {
    const pieces = piecesFromFen('4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1')
    const after = withMoveApplied(pieces, move('e5', 'd6'))
    expect(at(after, 'd5')).toBeUndefined()
    expect(at(after, 'd6')).toMatchObject({ name: 'Pawn', color: 'white' })
  })

  it('promotes to the chosen piece, queen by default', () => {
    const pieces = piecesFromFen('4k3/P7/8/8/8/8/8/4K3 w - - 0 1')
    expect(at(withMoveApplied(pieces, move('a7', 'a8')), 'a8').name).toBe('Queen')
    expect(at(withMoveApplied(pieces, move('a7', 'a8', 'n')), 'a8').name).toBe('Knight')
  })

  it('leaves the position alone when no piece is on the origin', () => {
    const pieces = piecesFromFen(START)
    expect(withMoveApplied(pieces, move('e4', 'e5'))).toEqual(pieces)
  })
})

describe('moveSlides', () => {
  it('slides king and rook when castling, forwards and back', () => {
    const after = piecesFromFen('4k3/8/8/8/8/8/8/R4RK1 b - - 0 1')
    expect(moveSlides(move('e1', 'g1'), after, false)).toEqual([move('e1', 'g1'), move('h1', 'f1')])
    const before = piecesFromFen('4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1')
    expect(moveSlides(move('e1', 'g1'), before, true)).toEqual([move('g1', 'e1'), move('f1', 'h1')])
  })

  it('slides only the mover otherwise', () => {
    expect(moveSlides(move('g1', 'f3'), piecesFromFen(START), false)).toHaveLength(1)
    expect(moveSlides(null, [], false)).toEqual([])
  })
})

describe('premoveTargets', () => {
  it('lets a pawn premove one or two squares and capture diagonally', () => {
    const pieces = piecesFromFen(START)
    expect(targetNames(premoveTargets(pieces, at(pieces, 'e2')))).toEqual(['d3', 'e3', 'e4', 'f3'])
  })

  it('stops rays at your own pieces but passes through squares the opponent may vacate', () => {
    const pieces = piecesFromFen('4k3/8/8/8/8/8/4p3/R3K3 w Q - 0 1')
    const rook = premoveTargets(pieces, at(pieces, 'a1'))
    expect(targetNames(rook)).toContain('a8')
    expect(targetNames(rook)).toEqual(expect.arrayContaining(['b1', 'c1', 'd1']))
    expect(targetNames(rook)).not.toContain('e1')
  })

  it('offers castling squares from the king’s home square', () => {
    const pieces = piecesFromFen('4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1')
    expect(targetNames(premoveTargets(pieces, at(pieces, 'e1')))).toEqual(expect.arrayContaining(['c1', 'g1']))
  })
})

describe('replayUci', () => {
  it('gives every position with its move and SAN', () => {
    const frames = replayUci(['e2e4', 'e7e5', 'g1f3'])
    expect(frames).toHaveLength(4)
    expect(frames[0].move).toBeNull()
    expect(frames[3].san).toBe('Nf3')
    expect(frames[3].move).toEqual(move('g1', 'f3'))
  })

  it('stops at a move that cannot be played', () => {
    expect(replayUci(['e2e4', 'e2e4'])).toHaveLength(2)
    expect(replayUci(undefined)).toHaveLength(1)
  })
})

describe('small helpers', () => {
  it('compares premove queues by squares and promotion', () => {
    expect(samePremoves([move('e2', 'e4')], [move('e2', 'e4', null)])).toBe(true)
    expect(samePremoves([move('e2', 'e4')], [move('e2', 'e3')])).toBe(false)
    expect(samePremoves([], [move('e2', 'e4')])).toBe(false)
  })

  it('reads the last SAN from the display moves', () => {
    expect(lastSan(['1. e4 e5', '2. Nf3'])).toBe('Nf3')
    expect(lastSan([])).toBeNull()
  })

  it('counts material from the board, promotions included', () => {
    expect(materialBalance(piecesFromFen(START))).toBe(0)
    expect(materialBalance(piecesFromFen('Q3k3/8/8/8/8/8/8/4K3 b - - 0 1'))).toBe(9)
  })

  it('detects check and mate from the pieces', () => {
    const mated = piecesFromFen('rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3')
    expect(checkState(fenFromPieces(mated, 'white'))).toEqual({ checked: 'white', mated: true })
    expect(checkState(fenFromPieces(piecesFromFen(START), 'white'))).toEqual({ checked: null, mated: false })
    expect(checkState('not a fen')).toEqual({ checked: null, mated: false })
  })
})
