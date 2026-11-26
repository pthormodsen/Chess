import React, { useEffect, useMemo, useRef, useState } from 'react'
import { PieceIcon } from './pieces.jsx'

export const FILES = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h']
export const PROMOTION_PIECES = ['q', 'r', 'b', 'n']
export const PROMOTION_NAMES = { q: 'Queen', r: 'Rook', b: 'Bishop', n: 'Knight' }
const DRAG_THRESHOLD_PX = 6
const boardIndexes = [0, 1, 2, 3, 4, 5, 6, 7]

export function squareName(col, row) {
  return `${FILES[col]}${8 - row}`
}

export function sameSquare(a, b) {
  return Boolean(a && b && a.col === b.col && a.row === b.row)
}

/**
 * A self-contained playable board for the trainers (puzzles, later openings): click or drag to move, with
 * a promotion picker. The caller owns the position and the rules:
 *  - pieces: [{ name, color, col, row }]
 *  - canMove(piece): whether this piece may be picked up now
 *  - targetsFrom({ col, row }): legal targets [{ col, row, capture, promotion }] (synchronous)
 *  - onMove(origin, target, promotion): a legal move was made
 *  - marks: { 'col,row': className } extra square classes (hints, right/wrong tints)
 *  - arrows: [{ move: {fromCol, fromRow, toCol, toRow}, tone, label }]
 * Same CSS classes as the game board, so it looks and sizes the same.
 */
export function ChessBoard({ pieces, flipped, lastMove, canMove, targetsFrom, onMove, marks, arrows, resetKey }) {
  const [selected, setSelected] = useState(null)
  const [dragGhost, setDragGhost] = useState(null)
  const [promotion, setPromotion] = useState(null)
  const boardRef = useRef(null)
  const dragRef = useRef(null)
  const suppressClickRef = useRef(false)

  const piecesBySquare = useMemo(() => {
    const map = new Map()
    for (const piece of pieces ?? []) map.set(`${piece.col},${piece.row}`, piece)
    return map
  }, [pieces])

  // A new position (or a reset from the caller) drops any half-made move.
  useEffect(() => {
    setSelected(null)
    setPromotion(null)
    setDragGhost(null)
    dragRef.current = null
  }, [resetKey])

  const movable = piece => Boolean(piece && !promotion && canMove(piece))
  const targets = selected ? targetsFrom(selected) : []

  function attempt(origin, target) {
    if (sameSquare(origin, target)) return false
    const move = targetsFrom(origin).find(item => sameSquare(item, target))
    if (!move) return false
    if (move.promotion) {
      setPromotion({ origin, target, color: piecesBySquare.get(`${origin.col},${origin.row}`)?.color ?? 'white' })
      return true
    }
    setSelected(null)
    onMove(origin, target, null)
    return true
  }

  function choosePromotion(letter) {
    const pending = promotion
    setPromotion(null)
    setSelected(null)
    if (pending) onMove(pending.origin, pending.target, letter)
  }

  function handleSquareClick(col, row) {
    if (suppressClickRef.current || promotion) return
    const square = { col, row }
    const piece = piecesBySquare.get(`${col},${row}`)
    if (selected && attempt(selected, square)) return
    if (piece && sameSquare(selected, square)) {
      setSelected(null)
      return
    }
    setSelected(movable(piece) ? square : null)
  }

  function squareFromPoint(clientX, clientY) {
    const rect = boardRef.current?.getBoundingClientRect()
    if (!rect) return null
    const displayCol = Math.floor(((clientX - rect.left) / rect.width) * 8)
    const displayRow = Math.floor(((clientY - rect.top) / rect.height) * 8)
    if (displayCol < 0 || displayCol > 7 || displayRow < 0 || displayRow > 7) return null
    return { col: flipped ? 7 - displayCol : displayCol, row: flipped ? 7 - displayRow : displayRow }
  }

  function handlePointerDown(event, piece) {
    if (event.button !== 0 || !movable(piece)) return
    dragRef.current = {
      pointerId: event.pointerId,
      piece,
      origin: { col: piece.col, row: piece.row },
      startX: event.clientX,
      startY: event.clientY,
      active: false
    }
    try { event.currentTarget.setPointerCapture(event.pointerId) } catch (ignored) {}
  }

  function handlePointerMove(event) {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== event.pointerId) return
    if (!drag.active) {
      if (Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY) < DRAG_THRESHOLD_PX) return
      drag.active = true
      setSelected(drag.origin)
    }
    const rect = boardRef.current?.getBoundingClientRect()
    if (rect) setDragGhost({ piece: drag.piece, x: event.clientX - rect.left, y: event.clientY - rect.top })
  }

  function handlePointerUp(event) {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== event.pointerId) return
    dragRef.current = null
    setDragGhost(null)
    if (!drag.active) return // a tap: the click handler selects or moves
    suppressClickRef.current = true
    window.setTimeout(() => { suppressClickRef.current = false }, 0)
    const target = squareFromPoint(event.clientX, event.clientY)
    if (!target || !attempt(drag.origin, target)) {
      // Dropped off the board or on an illegal square: keep the piece selected to try again.
      if (!target || !sameSquare(target, drag.origin)) setSelected(drag.origin)
    }
  }

  function handlePointerCancel() {
    dragRef.current = null
    setDragGhost(null)
  }

  return (
    <div className="board-frame">
      <div className="board" ref={boardRef} role="grid" aria-label="Chess board">
        {(arrows ?? []).map((arrow, index) => (
          <BoardArrow key={index} move={arrow.move} flipped={flipped} tone={arrow.tone} label={arrow.label} />
        ))}

        {boardIndexes.map(displayRow => boardIndexes.map(displayCol => {
          const row = flipped ? 7 - displayRow : displayRow
          const col = flipped ? 7 - displayCol : displayCol
          const square = { col, row }
          const piece = piecesBySquare.get(`${col},${row}`)
          const target = targets.find(item => sameSquare(item, square))
          const lastHere = lastMove && (
            (lastMove.fromCol === col && lastMove.fromRow === row) ||
            (lastMove.toCol === col && lastMove.toRow === row)
          )
          const mark = marks?.[`${col},${row}`]
          const draggingHere = dragGhost && sameSquare(dragGhost.piece, square)
          return (
            <button
              key={squareName(col, row)}
              type="button"
              className={[
                'square',
                (row + col) % 2 === 1 ? 'dark' : 'light',
                sameSquare(selected, square) ? 'selected' : '',
                lastHere && !mark ? 'last-move' : '',
                mark ?? '',
                target?.capture ? 'can-capture' : '',
                target && !target.capture ? 'can-move' : ''
              ].filter(Boolean).join(' ')}
              onClick={() => handleSquareClick(col, row)}
              aria-label={`${squareName(col, row)}${piece ? ` ${piece.color} ${piece.name}` : ''}`}
            >
              {displayCol === 0 && <span className="rank-label">{8 - row}</span>}
              {displayRow === 7 && <span className="file-label">{FILES[col]}</span>}
              {piece && (
                <span
                  className={['piece', piece.color, movable(piece) ? 'movable' : '', draggingHere ? 'dragging' : ''].filter(Boolean).join(' ')}
                  onPointerDown={event => handlePointerDown(event, piece)}
                  onPointerMove={handlePointerMove}
                  onPointerUp={handlePointerUp}
                  onPointerCancel={handlePointerCancel}
                >
                  <PieceIcon name={piece.name} color={piece.color} decorative />
                </span>
              )}
            </button>
          )
        }))}

        {dragGhost && (
          <span
            className={`piece drag-ghost ${dragGhost.piece.color}`}
            style={{ left: `${dragGhost.x}px`, top: `${dragGhost.y}px` }}
            aria-hidden="true"
          >
            <PieceIcon name={dragGhost.piece.name} color={dragGhost.piece.color} decorative />
          </span>
        )}

        {promotion && (
          <div className="promotion-picker" role="dialog" aria-label="Choose promotion piece">
            <div className="promotion-options">
              {PROMOTION_PIECES.map(letter => (
                <button
                  key={letter}
                  type="button"
                  className={`promotion-option ${promotion.color}`}
                  onClick={() => choosePromotion(letter)}
                  aria-label={`Promote to ${PROMOTION_NAMES[letter]}`}
                >
                  <PieceIcon name={PROMOTION_NAMES[letter]} color={promotion.color} decorative />
                </button>
              ))}
            </div>
            <button type="button" className="promotion-cancel" onClick={() => { setPromotion(null); setSelected(null) }}>
              Cancel
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

export function BoardArrow({ move, flipped, tone, label }) {
  const square = 12.5
  const toDisplay = index => (flipped ? 7 - index : index)
  const fromX = toDisplay(move.fromCol) * square + square / 2
  const fromY = toDisplay(move.fromRow) * square + square / 2
  const toX = toDisplay(move.toCol) * square + square / 2
  const toY = toDisplay(move.toRow) * square + square / 2
  const dx = toX - fromX
  const dy = toY - fromY
  const length = Math.hypot(dx, dy)
  if (length === 0) return null

  const ux = dx / length
  const uy = dy / length
  const startInset = Math.min(1.8, length * 0.16)
  const headLength = 3.3
  const headWidth = 3.7
  const startX = fromX + ux * startInset
  const startY = fromY + uy * startInset
  const shaftEndX = toX - ux * headLength
  const shaftEndY = toY - uy * headLength
  const leftX = shaftEndX - uy * headWidth / 2
  const leftY = shaftEndY + ux * headWidth / 2
  const rightX = shaftEndX + uy * headWidth / 2
  const rightY = shaftEndY - ux * headWidth / 2

  return (
    <svg className={`board-arrow ${tone}`} viewBox="0 0 100 100" role="img" aria-label={label}>
      <line className="arrow-shaft" x1={startX} y1={startY} x2={shaftEndX} y2={shaftEndY} />
      <polygon className="arrow-head" points={`${toX},${toY} ${leftX},${leftY} ${rightX},${rightY}`} />
    </svg>
  )
}
