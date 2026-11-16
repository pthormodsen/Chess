import React, { useEffect, useRef } from 'react'

// ---------- Move classifications (chess.com-style names, colours and glyphs) ----------

export const CLASSES = {
  Brilliant: { label: 'Brilliant', phrase: 'brilliant', color: '#26c2a3', glyph: '!!' },
  Great: { label: 'Great', phrase: 'a great move', color: '#749bbf', glyph: '!' },
  Best: { label: 'Best', phrase: 'the best move', color: '#81b64c', glyph: 'star' },
  Excellent: { label: 'Excellent', phrase: 'excellent', color: '#81b64c', glyph: 'thumb' },
  Good: { label: 'Good', phrase: 'good', color: '#95b776', glyph: 'check' },
  Book: { label: 'Book', phrase: 'a book move', color: '#a88865', glyph: 'book' },
  Inaccuracy: { label: 'Inaccuracy', phrase: 'an inaccuracy', color: '#f7c045', glyph: '?!' },
  Mistake: { label: 'Mistake', phrase: 'a mistake', color: '#ffa459', glyph: '?' },
  Miss: { label: 'Miss', phrase: 'a miss', color: '#ff7769', glyph: 'cross' },
  Blunder: { label: 'Blunder', phrase: 'a blunder', color: '#fa412d', glyph: '??' }
}

export const CLASS_ORDER = ['Brilliant', 'Great', 'Best', 'Excellent', 'Good', 'Book', 'Inaccuracy', 'Mistake', 'Miss', 'Blunder']
const KEY_MOMENT_CLASSES = new Set(['Brilliant', 'Great', 'Miss', 'Mistake', 'Blunder'])

export function classMeta(tag) {
  return CLASSES[tag] ?? null
}

export function isKeyMoment(tag) {
  return KEY_MOMENT_CLASSES.has(tag)
}

/** Next/previous key moment from the current ply; null when there is none in that direction. */
export function findKeyMoment(entries, fromPly, direction) {
  if (direction > 0) {
    return entries.find(entry => entry.plyIndex > fromPly && isKeyMoment(entry.qualityTag))?.plyIndex ?? null
  }
  for (let i = entries.length - 1; i >= 0; i--) {
    if (entries[i].plyIndex < fromPly && isKeyMoment(entries[i].qualityTag)) return entries[i].plyIndex
  }
  return null
}

const GLYPH_PATHS = {
  star: 'M12 3.6l2.5 5.4 5.9.6-4.4 4 1.3 5.8L12 16.4l-5.3 3 1.3-5.8-4.4-4 5.9-.6z',
  check: 'M6.2 12.6l3.7 3.7 7.9-8.4',
  cross: 'M7.5 7.5l9 9M16.5 7.5l-9 9',
  thumb: 'M9 10.5l3.1-5.3c.9-.4 2 .2 2 1.3l-.6 3.3h4c1.1 0 1.9 1 1.6 2l-1.4 5.5c-.2.8-.9 1.3-1.7 1.3H9zM5 10.5h2.6v8.1H5z',
  book: 'M6 6.2c2.3-.6 4.3-.3 6 1v11.2c-1.7-1.3-3.7-1.6-6-1zM18 6.2c-2.3-.6-4.3-.3-6 1v11.2c1.7-1.3 3.7-1.6 6-1z'
}

/** Round classification badge; text glyphs (!!, ?!) or small drawn icons. */
export function ClassIcon({ tag, size = 20, className = '', title }) {
  const meta = classMeta(tag)
  if (!meta) return null
  const path = GLYPH_PATHS[meta.glyph]
  const stroke = meta.glyph === 'check' || meta.glyph === 'cross'
  return (
    <svg
      className={`class-icon ${className}`}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      role="img"
      aria-label={title ?? meta.label}
    >
      <circle cx="12" cy="12" r="12" fill={meta.color} />
      {path ? (
        <path
          d={path}
          fill={stroke ? 'none' : '#fff'}
          stroke="#fff"
          strokeWidth={stroke ? 2.6 : meta.glyph === 'book' ? 0.9 : 0.6}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      ) : (
        <text x="12" y="16.4" textAnchor="middle" fontSize={meta.glyph.length > 1 ? 11 : 13} fontWeight="900" fill="#fff"
          fontFamily="Inter, system-ui, sans-serif">
          {meta.glyph}
        </text>
      )}
    </svg>
  )
}

// ---------- Evaluations ----------

const FILES = 'abcdefgh'

/** White's win chance (0–100) for an evaluation in pawns, lichess model (matches the server). */
export function winFromPawns(pawns) {
  const cp = Math.max(-1000, Math.min(1000, pawns * 100))
  return 50 + 50 * (2 / (1 + Math.exp(-0.00368208 * cp)) - 1)
}

/** Evaluation after ply (or of the start position for -1), all from White's perspective. */
export function evalAtPly(entries, ply) {
  if (ply < 0 || !entries[ply]) {
    const first = entries[0]
    const pawns = first ? first.evalBefore : 0.2
    return { pawns, mate: null, win: winFromPawns(pawns), result: null }
  }
  const entry = entries[ply]
  const san = entry.san ?? ''
  const result = san.endsWith('#') ? (entry.whiteMove ? '1-0' : '0-1') : null
  const win = result ? (result === '1-0' ? 100 : 0) : entry.winAfter ?? (entry.mateAfter != null ? (entry.mateAfter > 0 ? 100 : 0) : winFromPawns(entry.evalAfter))
  return { pawns: entry.evalAfter, mate: entry.mateAfter, win, result }
}

export function evalText({ pawns, mate, result }) {
  if (result) return result
  if (mate != null) return `${mate > 0 ? '' : '-'}M${Math.abs(mate)}`
  if (Math.abs(pawns) >= 100) return pawns > 0 ? '1-0' : '0-1'
  const rounded = Math.abs(pawns) < 0.05 ? 0 : pawns
  return `${rounded > 0 ? '+' : ''}${rounded.toFixed(1)}`
}

/** Vertical evaluation bar; White's share grows from the bottom (top when the board is flipped). */
export function EvalBar({ evaluation, flipped }) {
  const whiteShare = Math.max(2, Math.min(98, evaluation.win))
  const whiteAhead = evaluation.win >= 50
  const label = evalText(evaluation).replace('+', '')
  return (
    <div className={`eval-bar ${flipped ? 'flipped' : ''}`} role="meter" aria-valuemin={0} aria-valuemax={100}
      aria-valuenow={Math.round(evaluation.win)} aria-label={`Evaluation ${evalText(evaluation)}`}>
      <div className="eval-bar-white" style={{ height: `${whiteShare}%` }} />
      <span className={`eval-bar-label ${whiteAhead ? 'white-side' : 'black-side'}`}>{label}</span>
    </div>
  )
}

/** Win-chance graph with dots on key moments. Clicking or tapping anywhere jumps to that move. */
export function EvalGraph({ entries, total, currentPly, onSelect, compact = false }) {
  const count = Math.max(total ?? entries.length, entries.length, 1)
  const x = index => (count <= 1 ? 50 : ((index + 1) / count) * 100)
  const y = win => 100 - win
  const points = [`0,${y(evalAtPly(entries, -1).win).toFixed(2)}`]
  entries.forEach((entry, index) => points.push(`${x(index).toFixed(2)},${y(evalAtPly(entries, index).win).toFixed(2)}`))
  const lastX = entries.length ? x(entries.length - 1) : 0
  const area = `0,100 ${points.join(' ')} ${lastX.toFixed(2)},100`

  function handlePointer(event) {
    if (!onSelect || !entries.length) return
    const rect = event.currentTarget.getBoundingClientRect()
    const ratio = (event.clientX - rect.left) / rect.width
    const ply = Math.round(ratio * count) - 1
    onSelect(Math.max(-1, Math.min(entries.length - 1, ply)))
  }

  return (
    <div className={`eval-graph ${compact ? 'compact' : ''} ${onSelect ? 'interactive' : ''}`}
      onClick={handlePointer} role={onSelect ? 'slider' : 'img'} aria-label="Evaluation graph"
      aria-valuemin={-1} aria-valuemax={count - 1} aria-valuenow={currentPly}>
      <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
        <rect x="0" y="0" width="100" height="100" className="eval-graph-black" />
        <polygon points={area} className="eval-graph-white" />
        <line x1="0" y1="50" x2="100" y2="50" className="eval-graph-mid" />
        {currentPly >= 0 && <line x1={x(currentPly)} y1="0" x2={x(currentPly)} y2="100" className="eval-graph-cursor" />}
      </svg>
      {entries.map((entry, index) => {
        if (!isKeyMoment(entry.qualityTag)) return null
        const meta = classMeta(entry.qualityTag)
        return (
          <span
            key={entry.plyIndex}
            className={`eval-graph-dot ${index === currentPly ? 'current' : ''}`}
            style={{ left: `${x(index)}%`, top: `${y(evalAtPly(entries, index).win)}%`, background: meta.color }}
            title={`${moveLabel(entry)} · ${meta.label}`}
          />
        )
      })}
    </div>
  )
}

export function moveLabel(entry) {
  const number = Math.floor(entry.plyIndex / 2) + 1
  return `${number}${entry.whiteMove ? '.' : '...'} ${entry.san ?? entry.playedMove}`
}

// ---------- While analysing ----------

export function AnalysisProgress({ progress, entries }) {
  const done = progress?.done ?? entries.length
  const total = Math.max(1, progress?.total ?? done)
  const percent = Math.round((done / total) * 100)
  return (
    <section className="analysis-progress" aria-label="Analysis progress">
      <div className="progress-head">
        <strong>Analyzing game</strong>
        <span>{done} / {total} moves · {percent}%</span>
      </div>
      <div className="progress-track" role="progressbar" aria-valuemin={0} aria-valuemax={total} aria-valuenow={done}>
        <div className="progress-fill" style={{ width: `${percent}%` }} />
      </div>
      <EvalGraph entries={entries} total={total} currentPly={-1} compact />
      <ReviewMoveList entries={entries} currentPly={-1} compact />
    </section>
  )
}

// ---------- Summary ----------

export function ReviewSummary({ summary, entries, onStart, onSelect, starting }) {
  const white = summary.white ?? {}
  const black = summary.black ?? {}
  return (
    <section className="review-summary" aria-label="Game review summary">
      <div className="accuracy-cards">
        <AccuracyCard side="white" value={summary.whiteAccuracy} />
        <span className="accuracy-label">Accuracy</span>
        <AccuracyCard side="black" value={summary.blackAccuracy} />
      </div>
      {summary.openingName && (
        <div className="opening-line">
          <ClassIcon tag="Book" size={18} />
          <span>{summary.eco && <b>{summary.eco}</b>} {summary.openingName}</span>
        </div>
      )}
      <EvalGraph entries={entries} currentPly={-1} onSelect={onSelect} />
      <table className="class-table">
        <thead>
          <tr>
            <th scope="col"><span className="visually-hidden">White</span></th>
            <th scope="col"><span className="visually-hidden">Classification</span></th>
            <th scope="col"><span className="visually-hidden">Black</span></th>
          </tr>
        </thead>
        <tbody>
          {CLASS_ORDER.map(tag => {
            const meta = classMeta(tag)
            const w = white.counts?.[tag] ?? 0
            const b = black.counts?.[tag] ?? 0
            return (
              <tr key={tag} className={w + b === 0 ? 'zero' : ''}>
                <td className="count" style={{ color: w ? meta.color : undefined }}>{w}</td>
                <th scope="row">
                  <ClassIcon tag={tag} size={18} />
                  <span>{meta.label}</span>
                </th>
                <td className="count" style={{ color: b ? meta.color : undefined }}>{b}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
      <button type="button" className="primary-action start-review" onClick={onStart} disabled={starting}>
        Start review
      </button>
    </section>
  )
}

function AccuracyCard({ side, value }) {
  return (
    <div className={`accuracy-card ${side}`}>
      <span className="accuracy-side">{side === 'white' ? 'White' : 'Black'}</span>
      <strong>{(value ?? 0).toFixed(1)}</strong>
    </div>
  )
}

// ---------- Move list ----------

export function ReviewMoveList({ entries, currentPly, onSelect, compact = false }) {
  const listRef = useRef(null)
  const rows = []
  for (let i = 0; i < entries.length; i += 2) rows.push([entries[i], entries[i + 1]])

  useEffect(() => {
    const list = listRef.current
    if (!list) return
    const target = currentPly >= 0 ? list.querySelector(`[data-ply="${currentPly}"]`) : null
    if (target) {
      // Scroll only the list (never the page), keeping the current move in view.
      const listBox = list.getBoundingClientRect()
      const box = target.getBoundingClientRect()
      if (box.top < listBox.top || box.bottom > listBox.bottom) {
        list.scrollTo({ top: list.scrollTop + (box.top - listBox.top) - list.clientHeight / 2 + box.height / 2, behavior: 'smooth' })
      }
      // Desktop: the side panel scrolls too; keep the list itself in view. (On phones the page scrolls, so leave it.)
      const panel = list.closest('.review-scroll')
      if (panel && window.matchMedia('(min-width: 921px)').matches) {
        const panelBox = panel.getBoundingClientRect()
        if (listBox.bottom > panelBox.bottom) panel.scrollTo({ top: panel.scrollTop + listBox.bottom - panelBox.bottom, behavior: 'smooth' })
      }
    } else if (compact) {
      list.scrollTop = list.scrollHeight
    }
  }, [currentPly, entries.length, compact])

  if (!entries.length) return null
  return (
    <ol className={`review-moves ${compact ? 'compact' : ''}`} ref={listRef}>
      {rows.map(([whiteEntry, blackEntry], index) => (
        <li key={index} className="review-move-row">
          <span className="move-number">{index + 1}.</span>
          <MoveCell entry={whiteEntry} current={currentPly} onSelect={onSelect} />
          <MoveCell entry={blackEntry} current={currentPly} onSelect={onSelect} />
        </li>
      ))}
    </ol>
  )
}

function MoveCell({ entry, current, onSelect }) {
  if (!entry) return <span />
  const meta = classMeta(entry.qualityTag)
  const isCurrent = entry.plyIndex === current
  const showIcon = meta && entry.qualityTag !== 'Good' && entry.qualityTag !== 'Excellent'
  const content = (
    <>
      <span className="san" style={isKeyMoment(entry.qualityTag) ? { color: meta.color } : undefined}>{entry.san ?? entry.playedMove}</span>
      {showIcon && <ClassIcon tag={entry.qualityTag} size={15} />}
    </>
  )
  if (!onSelect) return <span className="review-move" data-ply={entry.plyIndex}>{content}</span>
  return (
    <button
      type="button"
      className={`review-move ${isCurrent ? 'current' : ''}`}
      data-ply={entry.plyIndex}
      onClick={() => onSelect(entry.plyIndex)}
      aria-current={isCurrent ? 'step' : undefined}
      aria-label={`${moveLabel(entry)}${meta ? `, ${meta.label}` : ''}`}
    >
      {content}
    </button>
  )
}

// ---------- Coach card ----------

export function CoachCard({ frame, entries, linePreview, onPlayLine, onStopLine }) {
  if (!frame || frame.plyIndex < 0) {
    return (
      <div className="coach-card neutral">
        <p className="coach-text">Starting position. Step forward with → or tap a move to see how each one was rated.</p>
      </div>
    )
  }
  const tag = frame.qualityTag
  const meta = classMeta(tag)
  const evaluation = frame.deliveredMate
    ? { pawns: 0, mate: null, result: frame.whiteMove ? '1-0' : '0-1' }
    : evalAtPly(entries, frame.plyIndex)
  const mover = frame.whiteMove ? 'White' : 'Black'
  const plies = frame.bestLinePlies ?? []
  const playedBest = frame.bestMove && frame.bestMove === frame.playedMove
  const lineIndex = linePreview ? linePreview.index : -1

  return (
    <div className="coach-card" style={meta ? { '--class-color': meta.color } : undefined}>
      <div className="coach-head">
        {meta && <ClassIcon tag={tag} size={30} />}
        <div className="coach-title">
          <strong>
            {frame.moveNumber}{frame.whiteMove ? '.' : '...'} {frame.san}
            <span className="coach-verdict"> is {frame.deliveredMate ? 'checkmate' : meta?.phrase ?? 'played'}</span>
          </strong>
          <span className="coach-mover">{frame.opening ? `${mover} · ${frame.opening}` : mover}</span>
        </div>
        <span className={`eval-chip ${evaluation.win < 50 && !evaluation.result ? 'black' : ''}`}>{evalText(evaluation)}</span>
      </div>
      {frame.explanation && <p className="coach-text">{frame.explanation}</p>}
      {frame.gameEndMessage && <p className="coach-end">Game over · {frame.gameEndMessage}</p>}

      {plies.length > 0 && (
        <div className="best-line">
          <div className="best-line-head">
            <span>{playedBest ? 'Engine line' : <>Best was <b>{frame.bestMoveSan ?? plies[0].san}</b></>}</span>
            {linePreview ? (
              <button type="button" className="line-action" onClick={onStopLine}>Back to game</button>
            ) : (
              <button type="button" className="line-action" onClick={() => onPlayLine(0)}>▶ Show on board</button>
            )}
          </div>
          <div className="line-moves">
            {plies.map((ply, index) => {
              const ply0 = frame.plyIndex // the line starts from the position before this move
              const absolute = ply0 + index
              const number = Math.floor(absolute / 2) + 1
              const whiteToMove = absolute % 2 === 0
              const prefix = whiteToMove ? `${number}.` : index === 0 ? `${number}...` : ''
              return (
                <button
                  key={`${ply.uci}-${index}`}
                  type="button"
                  className={`line-move ${index === lineIndex ? 'current' : ''}`}
                  onClick={() => onPlayLine(index)}
                >
                  {prefix && <span className="line-number">{prefix}</span>}{ply.san}
                </button>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}

// ---------- Navigation ----------

export function ReviewNav({ ply, total, entries, disabled, onGoto }) {
  const previousKey = findKeyMoment(entries, ply, -1)
  const nextKey = findKeyMoment(entries, ply, 1)
  const last = total - 1
  return (
    <div className="review-nav">
      <button type="button" onClick={() => onGoto(-1)} disabled={disabled || ply < 0} aria-label="First move (Home)" title="First move (Home)">⏮</button>
      <button type="button" onClick={() => onGoto(ply - 1)} disabled={disabled || ply < 0} aria-label="Previous move (←)" title="Previous move (←)">◀</button>
      <button type="button" onClick={() => onGoto(ply + 1)} disabled={disabled || ply >= last} aria-label="Next move (→)" title="Next move (→)">▶</button>
      <button type="button" onClick={() => onGoto(last)} disabled={disabled || ply >= last} aria-label="Last move (End)" title="Last move (End)">⏭</button>
      <button type="button" className="key-moment prev" onClick={() => onGoto(previousKey)} disabled={disabled || previousKey == null}
        aria-label="Previous key moment (↑)" title="Previous key moment (↑)">↶ Key</button>
      <button type="button" className="key-moment" onClick={() => onGoto(nextKey)} disabled={disabled || nextKey == null}
        aria-label="Next key moment (↓)" title="Next key moment (↓)">Next key moment ↷</button>
    </div>
  )
}

// ---------- Board helpers ----------

const FEN_PIECES = { p: 'Pawn', n: 'Knight', b: 'Bishop', r: 'Rook', q: 'Queen', k: 'King' }

/** Pieces from a FEN in the same shape as the server's piece views (row 0 = rank 8). */
export function piecesFromFen(fen) {
  const pieces = []
  const placement = (fen ?? '').split(' ')[0]
  placement.split('/').forEach((rank, row) => {
    let col = 0
    for (const char of rank) {
      if (/\d/.test(char)) {
        col += Number(char)
        continue
      }
      const name = FEN_PIECES[char.toLowerCase()]
      if (name) {
        const color = char === char.toUpperCase() ? 'white' : 'black'
        pieces.push({ name, color, symbol: char, col, row })
      }
      col += 1
    }
  })
  return pieces
}

export function uciSquares(uci) {
  if (!uci || uci.length < 4) return null
  const fromCol = FILES.indexOf(uci[0])
  const toCol = FILES.indexOf(uci[2])
  const fromRow = 8 - Number(uci[1])
  const toRow = 8 - Number(uci[3])
  if ([fromCol, fromRow, toCol, toRow].some(value => !Number.isInteger(value) || value < 0 || value > 7)) return null
  return { fromCol, fromRow, toCol, toRow }
}
