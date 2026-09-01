import React, { useEffect, useMemo, useState } from 'react'
import axios from 'axios'

const files = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h']
const ranks = ['8', '7', '6', '5', '4', '3', '2', '1']

const qualityMeta = {
  Brilliant: { label: 'brilliant', icon: '!!', tone: 'brilliant' },
  Great: { label: 'great', icon: '!', tone: 'great' },
  Best: { label: 'best', icon: '★', tone: 'best' },
  Excellent: { label: 'excellent', icon: '✓', tone: 'excellent' },
  Good: { label: 'good', icon: '•', tone: 'good' },
  Inaccuracy: { label: 'an inaccuracy', icon: '?!', tone: 'inaccuracy' },
  Mistake: { label: 'a mistake', icon: '?', tone: 'mistake' },
  'Severe Mistake': { label: 'a severe mistake', icon: '??', tone: 'mistake' },
  Blunder: { label: 'a blunder', icon: '??', tone: 'blunder' },
  Mate: { label: 'mate', icon: '#', tone: 'mate' }
}

function squareName(col, row) {
  return `${files[col]}${8 - row}`
}

function sameSquare(a, b) {
  return a && b && a.col === b.col && a.row === b.row
}

function splitCaptures(value) {
  if (!value || value === '-') return []
  return value.split(' ').filter(Boolean)
}

function formatApiError(err) {
  if (err.response?.status === 404) {
    return 'Chess backend was reached, but /api/game was not found. You are probably talking to the wrong server process.'
  }
  if (err.response?.data?.error) return err.response.data.error
  if (err.message === 'Network Error') {
    return 'Cannot reach the Chess backend. Start Spring Boot on port 8081, then reload this page.'
  }
  return err.message
}

function uciToMove(uci) {
  if (!uci || uci.length < 4) return null
  const fromCol = files.indexOf(uci[0])
  const toCol = files.indexOf(uci[2])
  const fromRow = 8 - Number(uci[1])
  const toRow = 8 - Number(uci[3])
  if ([fromCol, fromRow, toCol, toRow].some(value => !Number.isInteger(value) || value < 0 || value > 7)) {
    return null
  }
  return { fromCol, fromRow, toCol, toRow }
}

function sameMove(a, b) {
  return a && b && a.fromCol === b.fromCol && a.fromRow === b.fromRow && a.toCol === b.toCol && a.toRow === b.toRow
}

function getQualityMeta(tag) {
  return qualityMeta[tag] ?? { label: tag ?? 'move', icon: '•', tone: 'good' }
}

function scoreText(value) {
  if (Math.abs(value) >= 99) return value > 0 ? 'White mate' : 'Black mate'
  if (value === 0) return '0.00'
  return `${value > 0 ? '+' : ''}${value.toFixed(2)}`
}

export default function App() {
  const [game, setGame] = useState(null)
  const [selected, setSelected] = useState(null)
  const [legalMoves, setLegalMoves] = useState([])
  const [pending, setPending] = useState(false)
  const [analyzing, setAnalyzing] = useState(false)
  const [error, setError] = useState(null)
  const [timeControl, setTimeControl] = useState(10)
  const [humanVsHuman, setHumanVsHuman] = useState(true)
  const [playAs, setPlayAs] = useState('white')
  const [engineElo, setEngineElo] = useState(1200)
  const [copyLabel, setCopyLabel] = useState('Copy PGN')
  const [dragOrigin, setDragOrigin] = useState(null)

  useEffect(() => {
    refreshGame()
    const id = window.setInterval(refreshGame, 1000)
    return () => window.clearInterval(id)
  }, [])

  const piecesBySquare = useMemo(() => {
    const map = new Map()
    for (const piece of game?.pieces ?? []) {
      map.set(`${piece.col},${piece.row}`, piece)
    }
    return map
  }, [game])

  const whiteCaptures = splitCaptures(game?.capturedByWhite)
  const blackCaptures = splitCaptures(game?.capturedByBlack)
  const lastMove = game?.lastMove
  const disconnected = error && !game
  const engineSide = playAs === 'white' ? 'black' : 'white'
  const botTurn = !humanVsHuman && game?.turn === engineSide && game?.active
  const reviewFrame = game?.analysisMode ? game?.analysisFrame : null
  const reviewPlayedMove = reviewFrame?.plyIndex >= 0 ? uciToMove(reviewFrame.playedMove) : null
  const reviewBestMove = reviewFrame?.plyIndex >= 0 ? uciToMove(reviewFrame.bestMove) : null
  const reviewMeta = getQualityMeta(reviewFrame?.qualityTag)
  const showBestArrow = reviewBestMove && !sameMove(reviewBestMove, reviewPlayedMove)

  useEffect(() => {
    function handleReviewKeys(event) {
      const target = event.target
      const tagName = target?.tagName?.toLowerCase()
      const isTyping = tagName === 'input' || tagName === 'select' || tagName === 'textarea' || target?.isContentEditable
      if (!game?.analysisMode || analyzing || pending || isTyping) return

      if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
        event.preventDefault()
        stepAnalysis(-1)
      }
      if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
        event.preventDefault()
        stepAnalysis(1)
      }
    }

    window.addEventListener('keydown', handleReviewKeys)
    return () => window.removeEventListener('keydown', handleReviewKeys)
  }, [game?.analysisMode, game?.analysisFrame?.plyIndex, analyzing, pending])

  async function refreshGame() {
    try {
      const response = await axios.get('/api/game')
      applyGameState(response.data)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    }
  }

  function applyGameState(nextGame) {
    setGame(nextGame)
    if (typeof nextGame.timeMinutes === 'number') setTimeControl(nextGame.timeMinutes)
    if (typeof nextGame.humanVsHuman === 'boolean') setHumanVsHuman(nextGame.humanVsHuman)
    if (typeof nextGame.engineElo === 'number') setEngineElo(nextGame.engineElo)
    if (typeof nextGame.enginePlaysWhite === 'boolean') {
      setPlayAs(nextGame.enginePlaysWhite ? 'black' : 'white')
    }
  }

  function buildSettingsPayload() {
    return {
      minutes: timeControl,
      humanVsHuman,
      enginePlaysWhite: !humanVsHuman && playAs === 'black',
      engineElo
    }
  }

  function isHumanTurn() {
    if (humanVsHuman) return true
    return game?.turn !== engineSide
  }

  async function newGame() {
    setPending(true)
    setSelected(null)
    setLegalMoves([])
    try {
      const response = await axios.post('/api/game/new', buildSettingsPayload())
      applyGameState(response.data)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    } finally {
      setPending(false)
    }
  }

  async function updateSettings(next) {
    const nextSettings = {
      minutes: next.timeControl ?? timeControl,
      humanVsHuman: next.humanVsHuman ?? humanVsHuman,
      enginePlaysWhite: !(next.humanVsHuman ?? humanVsHuman) && (next.playAs ?? playAs) === 'black',
      engineElo: next.engineElo ?? engineElo
    }

    if (typeof next.timeControl === 'number') setTimeControl(next.timeControl)
    if (typeof next.humanVsHuman === 'boolean') setHumanVsHuman(next.humanVsHuman)
    if (typeof next.playAs === 'string') setPlayAs(next.playAs)
    if (typeof next.engineElo === 'number') setEngineElo(next.engineElo)

    try {
      const response = await axios.post('/api/game/settings', nextSettings)
      applyGameState(response.data)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    }
  }

  async function selectPiece(piece) {
    if (!piece || piece.color !== game?.turn || game?.gameOver || !isHumanTurn()) {
      setSelected(null)
      setLegalMoves([])
      return
    }

    setSelected({ col: piece.col, row: piece.row })
    try {
      const response = await axios.get('/api/game/legal', {
        params: { col: piece.col, row: piece.row }
      })
      setLegalMoves(response.data)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    }
  }

  async function submitMove(fromCol, fromRow, toCol, toRow) {
    setPending(true)
    try {
      const response = await axios.post('/api/game/move', { fromCol, fromRow, toCol, toRow })
      applyGameState(response.data.state)
      setSelected(null)
      setLegalMoves([])
      setError(null)
    } catch (err) {
      setError(formatApiError(err) || 'Illegal move')
      setSelected(null)
      setLegalMoves([])
      await refreshGame()
    } finally {
      setPending(false)
    }
  }

  function handleSquareClick(col, row) {
    if (pending || !game?.active || !isHumanTurn() || game?.analysisMode) return

    const piece = piecesBySquare.get(`${col},${row}`)
    const legalTarget = legalMoves.find(move => move.col === col && move.row === row)

    if (selected && legalTarget) {
      submitMove(selected.col, selected.row, col, row)
      return
    }

    if (piece) {
      selectPiece(piece)
      return
    }

    setSelected(null)
    setLegalMoves([])
  }

  async function copyPgn() {
    try {
      const response = await axios.get('/api/game/pgn')
      await navigator.clipboard.writeText(response.data.pgn)
      setCopyLabel('Copied')
      window.setTimeout(() => setCopyLabel('Copy PGN'), 1400)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    }
  }

  async function analyzeGame() {
    setAnalyzing(true)
    try {
      const response = await axios.post('/api/game/analyze')
      applyGameState(response.data)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    } finally {
      setAnalyzing(false)
    }
  }

  async function stepAnalysis(direction) {
    try {
      const endpoint = direction < 0 ? '/api/game/analysis/previous' : '/api/game/analysis/next'
      const response = await axios.post(endpoint)
      applyGameState(response.data)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    }
  }

  async function exitAnalysis() {
    try {
      const response = await axios.post('/api/game/analysis/exit')
      applyGameState(response.data)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    }
  }

  return (
    <main className="app-shell">
      <section className="game-layout">
        <div className="play-area">
          <PlayerBar
            name="Black"
            clock={game?.blackClock ?? '10:00'}
            active={game?.turn === 'black' && game?.active}
            captures={blackCaptures}
          />

          <div className="board-frame" aria-label="Chess board">
            <div className="board">
              {reviewPlayedMove && <BoardArrow move={reviewPlayedMove} tone={reviewMeta.tone} label="Played move" />}
              {showBestArrow && <BoardArrow move={reviewBestMove} tone="best-suggestion" label="Best move" />}

              {ranks.map((rank, row) => (
                <React.Fragment key={rank}>
                  {files.map((file, col) => {
                    const piece = piecesBySquare.get(`${col},${row}`)
                    const dark = (row + col) % 2 === 1
                    const selectedHere = sameSquare(selected, { col, row })
                    const move = legalMoves.find(item => item.col === col && item.row === row)
                    const lastHere = lastMove && (
                      (lastMove.fromCol === col && lastMove.fromRow === row) ||
                      (lastMove.toCol === col && lastMove.toRow === row)
                    )
                    const reviewBadgeHere = reviewPlayedMove && reviewPlayedMove.toCol === col && reviewPlayedMove.toRow === row

                    return (
                      <button
                        key={`${file}${rank}`}
                        type="button"
                        className={[
                          'square',
                          dark ? 'dark' : 'light',
                          selectedHere ? 'selected' : '',
                          lastHere ? 'last-move' : '',
                          reviewBadgeHere ? `review-square ${reviewMeta.tone}` : '',
                          move?.capture ? 'can-capture' : '',
                          move && !move.capture ? 'can-move' : ''
                        ].filter(Boolean).join(' ')}
                        onClick={() => handleSquareClick(col, row)}
                        onDragOver={event => {
                          event.preventDefault()
                          try { event.dataTransfer.dropEffect = 'move' } catch (ignored) {}
                        }}
                        onDrop={async event => {
                          event.preventDefault()
                          const origin = event.dataTransfer.getData('text/plain')
                          const [fromCol, fromRow] = origin ? origin.split(',').map(Number) : [dragOrigin?.col, dragOrigin?.row]
                          if (Number.isInteger(fromCol) && Number.isInteger(fromRow)) {
                            await submitMove(fromCol, fromRow, col, row)
                          }
                          setDragOrigin(null)
                        }}
                        aria-label={`${squareName(col, row)}${piece ? ` ${piece.color} ${piece.name}` : ''}`}
                      >
                        {col === 0 && <span className="rank-label">{rank}</span>}
                        {row === 7 && <span className="file-label">{file}</span>}
                        {reviewBadgeHere && <span className={`review-badge ${reviewMeta.tone}`}>{reviewMeta.icon}</span>}
                        {piece && (
                          <span
                            draggable={!game?.analysisMode}
                            onDragStart={event => {
                              if (game?.analysisMode) return
                              try { event.dataTransfer.effectAllowed = 'move' } catch (ignored) {}
                              event.dataTransfer.setData('text/plain', `${piece.col},${piece.row}`)
                              setDragOrigin({ col: piece.col, row: piece.row })
                              selectPiece(piece)
                            }}
                            className={`piece ${piece.color}`}
                          >
                            {piece.symbol}
                          </span>
                        )}
                      </button>
                    )
                  })}
                </React.Fragment>
              ))}
            </div>
          </div>

          <PlayerBar
            name="White"
            clock={game?.whiteClock ?? '10:00'}
            active={game?.turn === 'white' && game?.active}
            captures={whiteCaptures}
          />
        </div>

        <aside className="side-panel">
          <div className="status-block">
            <div>
              <p className="eyebrow">{game?.analysisMode ? 'Game review' : 'Live game'}</p>
              <h1>{disconnected ? 'Game server unavailable' : botTurn ? 'Engine thinking' : game?.status ?? 'Loading board'}</h1>
            </div>
            <div className={`turn-pill ${game?.turn ?? 'white'}`}>{game?.turn ?? 'white'}</div>
          </div>

          {disconnected && (
            <div className="connection-banner">
              <strong>No game state loaded.</strong>
              <span>Run `mvn spring-boot:run -Dspring-boot.run.main-class=server.ServerApplication` from the project root.</span>
            </div>
          )}

          <div className="controls-row">
            <label>
              Time
              <select value={timeControl} onChange={event => updateSettings({ timeControl: Number(event.target.value) })}>
                <option value={1}>1 min</option>
                <option value={3}>3 min</option>
                <option value={5}>5 min</option>
                <option value={10}>10 min</option>
                <option value={15}>15 min</option>
                <option value={30}>30 min</option>
              </select>
            </label>
            <button className="primary-action" type="button" onClick={newGame} disabled={pending}>
              New game
            </button>
          </div>

          <div className="setup-grid">
            <label className="toggle-row">
              <input
                type="checkbox"
                checked={!humanVsHuman}
                onChange={event => updateSettings({ humanVsHuman: !event.target.checked })}
              />
              <span>Play vs Stockfish</span>
            </label>

            <div className="segmented" aria-label="Choose side">
              <button type="button" className={playAs === 'white' ? 'active' : ''} onClick={() => updateSettings({ playAs: 'white' })} disabled={humanVsHuman}>
                White
              </button>
              <button type="button" className={playAs === 'black' ? 'active' : ''} onClick={() => updateSettings({ playAs: 'black' })} disabled={humanVsHuman}>
                Black
              </button>
            </div>

            <label>
              Elo
              <select value={engineElo} onChange={event => updateSettings({ engineElo: Number(event.target.value) })} disabled={humanVsHuman}>
                {[800, 1000, 1200, 1400, 1600, 1800, 2000, 2200, 2400, 2600, 2800].map(elo => (
                  <option value={elo} key={elo}>Elo {elo}</option>
                ))}
              </select>
            </label>
          </div>

          <div className="metric-strip">
            <div>
              <span>Material</span>
              <strong>{game?.evaluation ?? 'Material even'}</strong>
            </div>
            <div>
              <span>Result</span>
              <strong>{game?.result ?? '*'}</strong>
            </div>
          </div>

          <div className="action-grid">
            <button type="button" className="secondary-action" onClick={copyPgn}>{copyLabel}</button>
            <button type="button" className="secondary-action" onClick={analyzeGame} disabled={analyzing}>
              {analyzing ? 'Analyzing...' : 'Analyze'}
            </button>
          </div>

          <AnalysisPanel
            game={game}
            analyzing={analyzing}
            onPrevious={() => stepAnalysis(-1)}
            onNext={() => stepAnalysis(1)}
            onExit={exitAnalysis}
          />

          <div className="move-list" aria-label="Move history">
            <div className="panel-header">
              <h2>Moves</h2>
              <span>{game?.uciMoves?.length ?? 0} ply</span>
            </div>
            <div className="moves">
              {(game?.displayMoves?.length ? game.displayMoves : ['Start a game and make the first move']).map((move, index) => (
                <div className="move-row" key={`${move}-${index}`}>{move}</div>
              ))}
            </div>
          </div>

          <div className="hint-line">
            During review, the colored arrow is the played move and the green arrow is Stockfish's best move.
          </div>

          {error && <div className="error-box">{error}</div>}
        </aside>
      </section>
    </main>
  )
}

function BoardArrow({ move, tone, label }) {
  const square = 12.5
  const fromX = move.fromCol * square + square / 2
  const fromY = move.fromRow * square + square / 2
  const toX = move.toCol * square + square / 2
  const toY = move.toRow * square + square / 2
  const dx = toX - fromX
  const dy = toY - fromY
  const length = Math.hypot(dx, dy)
  if (length === 0) return null

  const ux = dx / length
  const uy = dy / length
  const headLength = 4.2
  const headWidth = 4.8
  const shaftEndX = toX - ux * headLength
  const shaftEndY = toY - uy * headLength
  const leftX = shaftEndX - uy * headWidth / 2
  const leftY = shaftEndY + ux * headWidth / 2
  const rightX = shaftEndX + uy * headWidth / 2
  const rightY = shaftEndY - ux * headWidth / 2

  return (
    <svg className={`board-arrow ${tone}`} viewBox="0 0 100 100" aria-label={label}>
      <line className="arrow-shaft" x1={fromX} y1={fromY} x2={shaftEndX} y2={shaftEndY} />
      <polygon className="arrow-head" points={`${toX},${toY} ${leftX},${leftY} ${rightX},${rightY}`} />
    </svg>
  )
}

function PlayerBar({ name, clock, active, captures }) {
  return (
    <div className={`player-bar ${active ? 'active' : ''}`}>
      <div>
        <div className="player-name">{name}</div>
        <div className="captures">
          {captures.map((piece, index) => <span key={`${piece}-${index}`}>{piece}</span>)}
        </div>
      </div>
      <div className="clock">{clock}</div>
    </div>
  )
}

function AnalysisPanel({ game, analyzing, onPrevious, onNext, onExit }) {
  const summary = game?.analysisSummary
  const frame = game?.analysisFrame
  const report = game?.analysisReport ?? []
  const canReview = game?.analysisStatus === 'ready' && frame

  return (
    <div className="analysis-panel">
      <div className="review-title">
        <span className="review-icon">♙</span>
        <h2>Game Review</h2>
        <span>{analyzing ? 'running' : game?.analysisStatus ?? 'idle'}</span>
      </div>

      {frame?.plyIndex >= 0 ? (
        <CoachFeedback frame={frame} />
      ) : (
        <div className="coach-placeholder">
          <strong>{canReview ? 'Start review' : 'Run analysis'}</strong>
          <span>{canReview ? 'Use Next to step through the key moments.' : 'Stockfish will classify each move and show the best line.'}</span>
        </div>
      )}

      {summary ? (
        <>
          <div className="accuracy-grid">
            <div><span>White</span><strong>{summary.whiteAccuracy.toFixed(1)}%</strong></div>
            <div><span>Black</span><strong>{summary.blackAccuracy.toFixed(1)}%</strong></div>
            <div><span>Best</span><strong>{summary.bestCount}</strong></div>
            <div><span>Blunders</span><strong>{summary.blunders}</strong></div>
          </div>
          <EvalGraph entries={game.analysisEntries ?? []} currentPly={frame?.plyIndex ?? -1} />
        </>
      ) : (
        <div className="analysis-empty">Run review after a few moves to get accuracy, mistakes, and best-move feedback.</div>
      )}

      <div className="review-controls">
        <button type="button" onClick={onPrevious} disabled={!canReview || frame.plyIndex < 0}>Previous</button>
        <button type="button" onClick={onNext} disabled={!canReview || frame.plyIndex >= frame.totalPlies - 1}>Next</button>
        <button type="button" onClick={onExit} disabled={!game?.analysisMode}>Exit</button>
      </div>

      <div className="report-lines">
        {report.slice(0, 8).map((line, index) => <div key={`${line}-${index}`}>{line}</div>)}
      </div>
    </div>
  )
}

function CoachFeedback({ frame }) {
  const meta = getQualityMeta(frame.qualityTag)
  const isBadMove = ['Inaccuracy', 'Mistake', 'Severe Mistake', 'Blunder'].includes(frame.qualityTag)
  const bestLine = frame.bestLine?.length ? frame.bestLine.slice(0, 6).join(' ') : null

  return (
    <div className={`coach-card ${meta.tone}`}>
      <div className="coach-title">
        <span className={`classification-pill ${meta.tone}`}>{meta.icon}</span>
        <strong>{frame.moveNumber}. {frame.whiteMove ? 'White' : 'Black'}: {frame.san} is {meta.label}</strong>
        <em>{scoreText(frame.evalAfter)}</em>
      </div>
      <p>
        {isBadMove
          ? `The move changed the evaluation from ${scoreText(frame.evalBefore)} to ${scoreText(frame.evalAfter)}, costing about ${Math.max(0, frame.loss).toFixed(2)} pawns.`
          : `The move kept the position near Stockfish's recommendation, changing the evaluation from ${scoreText(frame.evalBefore)} to ${scoreText(frame.evalAfter)}.`}
      </p>
      <div className="coach-actions">
        <div>
          <span>Best</span>
          <strong>{frame.bestMove ?? 'n/a'}</strong>
        </div>
        <div>
          <span>Played</span>
          <strong>{frame.playedMove ?? 'n/a'}</strong>
        </div>
      </div>
      {bestLine && <div className="continuation">Line: {bestLine}</div>}
    </div>
  )
}

function EvalGraph({ entries, currentPly }) {
  if (!entries.length) return null
  const points = entries.map((entry, index) => {
    const x = entries.length === 1 ? 50 : (index / (entries.length - 1)) * 100
    const clamped = Math.max(-8, Math.min(8, entry.evalAfter))
    const y = 50 - (clamped / 8) * 42
    return `${x.toFixed(2)},${y.toFixed(2)}`
  }).join(' ')
  const cursorX = currentPly <= 0 || entries.length === 1 ? 0 : (currentPly / (entries.length - 1)) * 100

  return (
    <svg className="eval-graph" viewBox="0 0 100 100" preserveAspectRatio="none" aria-label="Evaluation graph">
      <rect x="0" y="0" width="100" height="50" className="eval-white" />
      <rect x="0" y="50" width="100" height="50" className="eval-black" />
      <line x1="0" y1="50" x2="100" y2="50" className="eval-midline" />
      <polyline points={points} className="eval-line" />
      {currentPly >= 0 && <line x1={cursorX} y1="0" x2={cursorX} y2="100" className="eval-cursor" />}
    </svg>
  )
}
