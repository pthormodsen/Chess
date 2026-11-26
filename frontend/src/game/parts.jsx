import React, { useEffect, useRef } from 'react'
import { PieceIcon, pieceFromSymbol } from '../pieces.jsx'

export function SoundIcon({ on }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path fill="currentColor" stroke="none" d="M4 9.5h3.2L12 5.5v13l-4.8-4H4z" />
      {on ? (
        <>
          <path d="M15.5 9.2a4 4 0 0 1 0 5.6" />
          <path d="M18.2 6.6a7.6 7.6 0 0 1 0 10.8" />
        </>
      ) : (
        <path d="M16 9.5l5 5m0-5l-5 5" />
      )}
    </svg>
  )
}

export function CrownIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path fill="currentColor" d="M3 8.5l4.5 3.5L12 5l4.5 7L21 8.5 19.2 18H4.8z" />
    </svg>
  )
}

export function ConfirmDialog({ title, children, actions, onCancel }) {
  const dialogRef = useRef(null)

  useEffect(() => {
    const previouslyFocused = document.activeElement
    dialogRef.current?.querySelector('[data-autofocus="true"]')?.focus()
    function handleKey(event) {
      if (event.key === 'Escape') onCancel()
    }
    window.addEventListener('keydown', handleKey)
    return () => {
      window.removeEventListener('keydown', handleKey)
      previouslyFocused?.focus?.()
    }
  }, [])

  return (
    <div className="dialog-backdrop" onPointerDown={event => { if (event.target === event.currentTarget) onCancel() }}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="dialog-title" ref={dialogRef}>
        <h2 id="dialog-title">{title}</h2>
        <p>{children}</p>
        <div className="dialog-actions">
          {actions.map(action => (
            <button
              key={action.tone}
              type="button"
              className={`dialog-button ${action.tone}`}
              data-autofocus={action.autoFocus ? 'true' : undefined}
              onClick={action.onClick}
            >
              {action.label}
            </button>
          ))}
        </div>
      </div>
    </div>
  )
}

export function PlayerBar({ side, name, detail, clock, active, clockRunning, untimed, showClock, thinking, captures, advantage }) {
  return (
    <div className={`player-bar ${active ? 'active' : ''}`}>
      <div className="player-identity">
        <span className={`player-avatar ${side}`} aria-hidden="true">
          <PieceIcon name="King" color={side} decorative />
        </span>
        <div className="player-text">
          <div className="player-name">
            {name}
            {detail && <span className="player-detail">{detail}</span>}
            {thinking && <span className="thinking-dots" aria-label="thinking"><i /><i /><i /></span>}
          </div>
          <div className="captures">
            {captures.map((symbol, index) => {
              const captured = pieceFromSymbol(symbol)
              return captured
                ? <PieceIcon key={`${symbol}-${index}`} name={captured.name} color={captured.color} className={`captured-piece ${captured.color}`} />
                : <span key={`${symbol}-${index}`}>{symbol}</span>
            })}
            {advantage > 0 && (
              <span className="material-advantage" aria-label={`up ${advantage} in material`}>+{advantage}</span>
            )}
          </div>
        </div>
      </div>
      {showClock && (
        <div
          className={`clock ${clockRunning ? 'running' : ''} ${untimed ? 'untimed' : ''}`}
          title={untimed ? 'Imported game – no clock' : undefined}
        >
          {clock}
        </div>
      )}
    </div>
  )
}

// displayMoves entries look like "12. Nf3 Nc6", "12. Nf3" or "1... e5" (when Black moved first).
function parseMoveRows(displayMoves) {
  return displayMoves.map(entry => {
    const match = /^(\d+)\.(\.\.)?\s*(\S+)?(?:\s+(\S+))?/.exec(entry)
    if (!match) return { number: '', white: entry, black: null }
    const [, number, blackFirst, first, second] = match
    return blackFirst ? { number, white: '…', black: first ?? null } : { number, white: first ?? '', black: second ?? null }
  })
}

export function MoveTable({ displayMoves, emptyText, currentPly, onSelect }) {
  const listRef = useRef(null)
  const rows = parseMoveRows(displayMoves)
  const lastRow = rows.length - 1
  const lastIsBlack = lastRow >= 0 && Boolean(rows[lastRow].black)
  // Ply numbers (1 = first move); a game that starts with Black's move has no ply for row 0's white cell.
  const offset = rows[0]?.white === '…' ? 1 : 0
  const totalPlies = rows.length * 2 - offset - (lastIsBlack ? 0 : 1)
  const shownPly = currentPly ?? totalPlies

  useEffect(() => {
    const list = listRef.current
    if (list) list.scrollTop = list.scrollHeight
  }, [displayMoves.length, lastIsBlack])

  // Keep the move being looked at in view while browsing.
  useEffect(() => {
    const list = listRef.current
    const cell = list?.querySelector('.move-san.current')
    if (!list || !cell || currentPly == null) return
    const top = cell.offsetTop - list.offsetTop
    if (top < list.scrollTop) list.scrollTop = top - 4
    else if (top + cell.offsetHeight > list.scrollTop + list.clientHeight) list.scrollTop = top + cell.offsetHeight - list.clientHeight + 4
  }, [currentPly])

  const cell = (san, ply) => san && san !== '…'
    ? (
      <button type="button" className={`move-san ${ply === shownPly ? 'current' : ''}`} onClick={() => onSelect?.(ply)}>
        {san}
      </button>
    )
    : <span className="move-san">{san ?? ''}</span>

  if (!rows.length) {
    return <div className="moves empty">{emptyText}</div>
  }

  return (
    <ol className="moves" ref={listRef}>
      {rows.map((row, index) => (
        <li className="move-row" key={`${row.number}-${index}`}>
          <span className="move-number">{row.number}.</span>
          {cell(row.white, index * 2 + 1 - offset)}
          {cell(row.black, index * 2 + 2 - offset)}
        </li>
      ))}
    </ol>
  )
}
