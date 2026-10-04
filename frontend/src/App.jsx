import React, { useEffect, useMemo, useRef, useState } from 'react'
import axios from 'axios'
import { PieceIcon, pieceFromSymbol } from './pieces.jsx'

const files = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h']

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
  // Only used when the board says the move actually delivered checkmate (frame.deliveredMate).
  Mate: { label: 'checkmate', icon: '#', tone: 'mate' }
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
  // Engine mate scores are evaluation, never a classification: a stray "Mate" tag falls back to a neutral label.
  if (tag === 'Mate') return { label: 'a strong move', icon: '•', tone: 'good' }
  return qualityMeta[tag] ?? { label: tag ?? 'move', icon: '•', tone: 'good' }
}

// Review classification for a frame: "checkmate" only when the board confirms the move mated.
function frameQualityMeta(frame) {
  return frame?.deliveredMate ? qualityMeta.Mate : getQualityMeta(frame?.qualityTag)
}

// Evaluation text. `mate` is Stockfish's mate-in-N (White-positive) and is shown as such; a large
// centipawn score is always shown as a number, never as mate.
function scoreText(value, mate) {
  if (mate != null) return `${mate > 0 ? 'White' : 'Black'} mates in ${Math.abs(mate)}`
  if (value === 0) return '0.00'
  return `${value > 0 ? '+' : ''}${value.toFixed(2)}`
}

function scoreBadge(frame) {
  if (frame.deliveredMate) return '#'
  if (frame.mateAfter != null) return `${frame.mateAfter > 0 ? '+' : '-'}M${Math.abs(frame.mateAfter)}`
  return scoreText(frame.evalAfter)
}

const SESSION_KEY = 'chess.sessionId'
let memorySessionId = null

function generateSessionId() {
  return window.crypto?.randomUUID?.() ?? `session-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function getSessionId() {
  try {
    const existing = window.localStorage.getItem(SESSION_KEY)
    if (existing) return existing
    const generated = generateSessionId()
    window.localStorage.setItem(SESSION_KEY, generated)
    return generated
  } catch (ignored) {
    // Storage can be blocked (privacy modes); keep a per-tab session instead of failing to load.
    memorySessionId = memorySessionId ?? generateSessionId()
    return memorySessionId
  }
}

const PIECE_VALUES = { Pawn: 1, Knight: 3, Bishop: 3, Rook: 5, Queen: 9, King: 0 }

// White material minus Black material, from the pieces on the board (so promotions count correctly).
function materialBalance(pieces) {
  return (pieces ?? []).reduce((sum, piece) => {
    const value = PIECE_VALUES[piece.name] ?? 0
    return piece.color === 'white' ? sum + value : sum - value
  }, 0)
}

const PROMOTION_PIECES = ['q', 'r', 'b', 'n']
const PROMOTION_NAMES = { q: 'Queen', r: 'Rook', b: 'Bishop', n: 'Knight' }
const DRAG_THRESHOLD_PX = 6
const POLL_INTERVAL_MS = 1000
const boardIndexes = [0, 1, 2, 3, 4, 5, 6, 7]
const noLegalMoves = { origin: null, moves: [] }

export default function App() {
  const [game, setGame] = useState(null)
  const [selected, setSelected] = useState(null)
  // Legal targets are stored with the square they were fetched for, so stale responses can't be used for another piece.
  const [legalFor, setLegalFor] = useState(noLegalMoves)
  const [pending, setPending] = useState(false)
  const [analyzing, setAnalyzing] = useState(false)
  const [error, setError] = useState(null)
  const [connectionError, setConnectionError] = useState(null)
  const [timeControl, setTimeControl] = useState(10)
  const [humanVsHuman, setHumanVsHuman] = useState(true)
  const [playAs, setPlayAs] = useState('white')
  const [engineElo, setEngineElo] = useState(1200)
  const [copyLabel, setCopyLabel] = useState('Copy PGN')
  const [dragGhost, setDragGhost] = useState(null)
  const [promotion, setPromotion] = useState(null)
  const [pgnText, setPgnText] = useState('')
  const [showImport, setShowImport] = useState(false)
  const [importing, setImporting] = useState(false)
  // null = automatic (open until the first move); true/false once the player toggles it.
  const [settingsOpen, setSettingsOpen] = useState(null)
  const [confirmHome, setConfirmHome] = useState(false)

  const boardRef = useRef(null)
  const dragRef = useRef(null)
  const suppressClickRef = useRef(false)
  const legalRequestRef = useRef(0)
  // latest: id of the newest mutation; epoch: bumped when any mutation starts or ends, so polls that overlap one are dropped.
  const mutationRef = useRef({ latest: 0, epoch: 0, inFlight: 0 })

  useEffect(() => {
    axios.defaults.headers.common['X-Chess-Session'] = getSessionId()
    let stopped = false
    let timer = null

    async function poll() {
      if (!document.hidden) await refreshGame()
      if (!stopped) timer = window.setTimeout(poll, POLL_INTERVAL_MS)
    }

    poll()
    return () => {
      stopped = true
      window.clearTimeout(timer)
    }
  }, [])

  const piecesBySquare = useMemo(() => {
    const map = new Map()
    for (const piece of game?.pieces ?? []) {
      map.set(`${piece.col},${piece.row}`, piece)
    }
    return map
  }, [game])

  const balance = materialBalance(game?.pieces)
  const whiteCaptures = splitCaptures(game?.capturedByWhite)
  const blackCaptures = splitCaptures(game?.capturedByBlack)
  const lastMove = game?.lastMove
  const disconnected = connectionError && !game
  const engineTurn = Boolean(game?.engineTurn)
  const engineUnavailable = Boolean(game && !game.humanVsHuman && !game.engineEnabled && game.active && !game.analysisMode)
  // Stockfish games: fixed orientation from "Play as". Local games: the side to move sits at the bottom.
  const desiredFlip = game ? (game.humanVsHuman ? game.turn === 'black' : Boolean(game.enginePlaysWhite)) : false
  const [flipped, setFlipped] = useState(false)
  const analysisRunning = analyzing || game?.analysisStatus === 'running'
  const legalMoves = selected && sameSquare(legalFor.origin, selected) ? legalFor.moves : []
  const reviewFrame = game?.analysisMode ? game?.analysisFrame : null
  const reviewPlayedMove = reviewFrame?.plyIndex >= 0 ? uciToMove(reviewFrame.playedMove) : null
  const reviewBestMove = reviewFrame?.plyIndex >= 0 ? uciToMove(reviewFrame.bestMove) : null
  const reviewMeta = frameQualityMeta(reviewFrame)
  const showBestArrow = reviewBestMove && !sameMove(reviewBestMove, reviewPlayedMove)
  const plyCount = game?.uciMoves?.length ?? 0

  // Turn the board only after a move has fully landed: never mid-drag, never with the promotion picker open,
  // and never while stepping through a review (orientation stays as it was when review began).
  useEffect(() => {
  if (game?.analysisMode || dragGhost || promotion) return

  if (!game?.humanVsHuman) {
    setFlipped(desiredFlip)
    return
  }

  const timer = window.setTimeout(() => setFlipped(desiredFlip), 250)
  return () => window.clearTimeout(timer)
}, [desiredFlip, game?.humanVsHuman, game?.analysisMode, dragGhost, promotion])

  // Any change of position invalidates the current selection, drag and promotion prompt.
  useEffect(() => {
    clearSelection()
    dragRef.current = null
    setDragGhost(null)
    setPromotion(null)
  }, [plyCount, game?.analysisMode, game?.active])

  useEffect(() => {
    function handleReviewKeys(event) {
      const target = event.target
      const tagName = target?.tagName?.toLowerCase()
      const isTyping = tagName === 'input' || tagName === 'select' || tagName === 'textarea' || target?.isContentEditable
      if (!game?.analysisMode || analysisRunning || pending || isTyping || confirmHome) return

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
  }, [game?.analysisMode, game?.analysisFrame?.plyIndex, analysisRunning, pending, confirmHome])

  async function refreshGame() {
    const tracker = mutationRef.current
    const epochAtStart = tracker.epoch
    try {
      const response = await axios.get('/api/game')
      // Drop polls that overlapped a mutation: the server may have answered them before the mutation applied.
      if (epochAtStart === tracker.epoch && tracker.inFlight === 0) {
        applyGameState(response.data)
      }
      setConnectionError(null)
    } catch (err) {
      setConnectionError(formatApiError(err))
    }
  }

  // Runs a state-changing request; `current` is false when a newer mutation started meanwhile.
  async function mutate(request) {
    const tracker = mutationRef.current
    const id = ++tracker.latest
    tracker.epoch += 1
    tracker.inFlight += 1
    try {
      const response = await request()
      return { response, current: id === tracker.latest }
    } finally {
      tracker.inFlight -= 1
      tracker.epoch += 1
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

  function canMovePiece(piece) {
    const waitingForStart = game?.awaitingStart && !game?.humanVsHuman
    return Boolean(piece && game?.active && !pending && !promotion && !game?.analysisMode && !engineTurn && !waitingForStart && piece.color === game?.turn)
  }

  function clearSelection() {
    legalRequestRef.current += 1
    setSelected(null)
    setLegalFor(noLegalMoves)
  }

  async function newGame() {
    setPending(true)
    clearSelection()
    setSettingsOpen(null)
    try {
      const { response, current } = await mutate(() => axios.post('/api/game/new', buildSettingsPayload()))
      if (current) applyGameState(response.data)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    } finally {
      setPending(false)
    }
  }

  // Setup → playing. Stockfish (if it has White) makes its first move only after this.
  async function startGame() {
    setPending(true)
    clearSelection()
    try {
      const { response, current } = await mutate(() => axios.post('/api/game/start', buildSettingsPayload()))
      if (current) applyGameState(response.data)
      setSettingsOpen(null)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
      await refreshGame()
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
      const { response, current } = await mutate(() => axios.post('/api/game/settings', nextSettings))
      if (current) applyGameState(response.data)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
      await refreshGame() // roll the optimistic controls back to the server's settings
    }
  }

  async function fetchLegalMoves(origin) {
    const response = await axios.get('/api/game/legal', { params: { col: origin.col, row: origin.row } })
    return response.data
  }

  async function selectPiece(piece) {
    if (!canMovePiece(piece)) {
      clearSelection()
      return
    }

    const origin = { col: piece.col, row: piece.row }
    const requestId = ++legalRequestRef.current
    setSelected(origin)
    setLegalFor({ origin, moves: [] })
    try {
      const moves = await fetchLegalMoves(origin)
      if (requestId === legalRequestRef.current) setLegalFor({ origin, moves })
    } catch (err) {
      if (requestId === legalRequestRef.current) setError(formatApiError(err))
    }
  }

  // Validates a from→to pair against the legal targets of that exact origin, then asks for a promotion piece if needed.
  async function attemptMove(origin, target) {
    if (sameSquare(origin, target)) return
    const piece = piecesBySquare.get(`${origin.col},${origin.row}`)
    if (!canMovePiece(piece)) return

    let moves = sameSquare(legalFor.origin, origin) ? legalFor.moves : null
    if (!moves || moves.length === 0) {
      try {
        moves = await fetchLegalMoves(origin)
      } catch (err) {
        setError(formatApiError(err))
        return
      }
    }
    if (!moves.some(move => sameSquare(move, target))) return

    if (piece.name === 'Pawn' && (target.row === 0 || target.row === 7)) {
      setPromotion({ origin, target, color: piece.color })
      return
    }
    submitMove(origin, target)
  }

  async function submitMove(origin, target, promotionPiece) {
    const scrollPosition = { left: window.scrollX, top: window.scrollY }
    setPending(true)
    setPromotion(null)
    let failed = false
    try {
      const payload = { fromCol: origin.col, fromRow: origin.row, toCol: target.col, toRow: target.row }
      if (promotionPiece) payload.promotion = promotionPiece
      const { response, current } = await mutate(() => axios.post('/api/game/move', payload))
      if (current) applyGameState(response.data.state)
      window.requestAnimationFrame(() => window.scrollTo(scrollPosition.left, scrollPosition.top))
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
      failed = true
    } finally {
      clearSelection()
      setPending(false)
    }
    if (failed) await refreshGame()
  }

  function handleSquareClick(col, row) {
    if (suppressClickRef.current) return
    if (pending || promotion || !game?.active || engineTurn || game?.analysisMode) return
    if (game?.awaitingStart && !game?.humanVsHuman) return

    const target = { col, row }
    const piece = piecesBySquare.get(`${col},${row}`)

    if (selected && legalMoves.some(move => sameSquare(move, target))) {
      attemptMove(selected, target)
      return
    }

    if (piece && sameSquare(selected, target)) {
      clearSelection()
      return
    }

    if (piece) {
      selectPiece(piece)
      return
    }

    clearSelection()
  }

  function squareFromPoint(clientX, clientY) {
    const rect = boardRef.current?.getBoundingClientRect()
    if (!rect) return null
    const displayCol = Math.floor(((clientX - rect.left) / rect.width) * 8)
    const displayRow = Math.floor(((clientY - rect.top) / rect.height) * 8)
    if (displayCol < 0 || displayCol > 7 || displayRow < 0 || displayRow > 7) return null
    return {
      col: flipped ? 7 - displayCol : displayCol,
      row: flipped ? 7 - displayRow : displayRow
    }
  }

  // Pointer events (mouse, touch and pen) replace HTML5 drag-and-drop, which mobile browsers don't support.
  function handlePiecePointerDown(event, piece) {
    if (event.button !== 0 || !canMovePiece(piece)) return
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

  function handlePiecePointerMove(event) {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== event.pointerId) return
    if (!drag.active) {
      if (Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY) < DRAG_THRESHOLD_PX) return
      drag.active = true
      if (!sameSquare(selected, drag.origin)) selectPiece(drag.piece)
    }
    const rect = boardRef.current?.getBoundingClientRect()
    if (!rect) return
    setDragGhost({ piece: drag.piece, x: event.clientX - rect.left, y: event.clientY - rect.top })
  }

  function handlePiecePointerUp(event) {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== event.pointerId) return
    dragRef.current = null
    setDragGhost(null)
    if (!drag.active) return // a tap: the click handler selects or moves

    // The click that follows a drag must not re-select or move.
    suppressClickRef.current = true
    window.setTimeout(() => { suppressClickRef.current = false }, 0)
    const target = squareFromPoint(event.clientX, event.clientY)
    if (target) attemptMove(drag.origin, target)
  }

  function handlePiecePointerCancel() {
    dragRef.current = null
    setDragGhost(null)
  }

  // Home/brand: open the start/setup state. A game in progress is never discarded without confirmation.
  function goHome() {
    if (plyCount > 0 || game?.analysisMode || (game && !game.awaitingStart && game.active)) {
      setConfirmHome(true)
      return
    }
    showSetupHome()
  }

  function showSetupHome() {
    setShowImport(false)
    setSettingsOpen(true)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  async function startOverFromHome() {
    setConfirmHome(false)
    await newGame()
    showSetupHome()
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

  // The server analyzes in the background; polling picks up analysisStatus "running" → "ready"/"failed".
  async function analyzeGame() {
    setAnalyzing(true)
    try {
      const { response, current } = await mutate(() => axios.post('/api/game/analyze'))
      if (current) applyGameState(response.data)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    } finally {
      setAnalyzing(false)
    }
  }

  async function importPgn() {
    if (!pgnText.trim()) {
      setError('Paste PGN text or upload a .pgn file first.')
      return
    }
    setImporting(true)
    try {
      const { response, current } = await mutate(() => axios.post('/api/game/import-pgn', { pgn: pgnText }))
      if (current) applyGameState(response.data)
      clearSelection()
      setShowImport(false)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    } finally {
      setImporting(false)
    }
  }

  async function handlePgnFile(event) {
    const file = event.target.files?.[0]
    if (!file) return
    try {
      setPgnText(await file.text())
      setShowImport(true)
      setError(null)
    } catch (err) {
      setError(`Could not read PGN file: ${err.message}`)
    } finally {
      event.target.value = ''
    }
  }

  async function stepAnalysis(direction) {
    try {
      const endpoint = direction < 0 ? '/api/game/analysis/previous' : '/api/game/analysis/next'
      const { response, current } = await mutate(() => axios.post(endpoint))
      if (current) applyGameState(response.data)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    }
  }

  async function exitAnalysis() {
    try {
      const { response, current } = await mutate(() => axios.post('/api/game/analysis/exit'))
      if (current) applyGameState(response.data)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    }
  }

  const topSide = flipped ? 'white' : 'black'
  const bottomSide = flipped ? 'black' : 'white'
  const engineSide = game?.enginePlaysWhite ? 'white' : 'black'
  const playerName = side => {
    if (game?.humanVsHuman ?? true) return side === 'white' ? 'White' : 'Black'
    return side === engineSide ? 'Stockfish' : 'You'
  }
  const sideBar = side => (
    <PlayerBar
      side={side}
      name={playerName(side)}
      detail={!(game?.humanVsHuman ?? true) && side === engineSide ? `${game?.engineElo ?? engineElo}` : null}
      clock={(side === 'white' ? game?.whiteClock : game?.blackClock) ?? '10:00'}
      active={game?.turn === side && game?.active && !game?.awaitingStart}
      thinking={engineTurn && side === engineSide}
      captures={side === 'white' ? whiteCaptures : blackCaptures}
      advantage={side === 'white' ? balance : -balance}
    />
  )
  const reviewHeadline = reviewFrame?.plyIndex >= 0
    ? `${reviewFrame.moveNumber}${reviewFrame.whiteMove ? '.' : '...'} ${reviewFrame.san}${reviewFrame.gameEndMessage ? ` · ${reviewFrame.gameEndMessage}` : ''}`
    : 'Starting position'
  const headline = disconnected
    ? 'Game server unavailable'
    : game?.analysisMode
      ? reviewHeadline
    : engineUnavailable
      ? 'Stockfish unavailable'
      : engineTurn
        ? 'Engine thinking…'
        : game?.status ?? 'Loading board'
  const result = game?.result && game.result !== '*' ? game.result : null
  const inReview = Boolean(game?.analysisMode)
  // Setup is open before the first move and tucked away once the game is under way, unless the player toggles it.
  const showSetup = settingsOpen ?? Boolean(game ? game.awaitingStart : plyCount === 0)
  // Settings configure the next game; once a game is running they are locked until New game.
  const setupLocked = Boolean(game && !game.awaitingStart)
  const setupSummary = [
    `${timeControl} min`,
    humanVsHuman ? '2 players' : `vs Stockfish · ${playAs === 'white' ? 'White' : 'Black'} · ${engineElo}`
  ].join(' · ')
  const analysisNotice = !inReview && (game?.analysisStatus === 'running' || game?.analysisStatus === 'failed')
    ? (game.analysisReport ?? [])[0]
    : null

  return (
    <main className="app-shell">
      <nav className="app-rail" aria-label="Main">
        <button type="button" className="brand" onClick={goHome} title="Home – new game setup">
          <span className="brand-mark" aria-hidden="true">
            <PieceIcon name="Knight" color="white" decorative />
          </span>
          <span className="brand-name">Chess</span>
        </button>
      </nav>

      <section className={`game-layout ${inReview ? 'is-review' : 'is-live'}`}>
        <div className="play-area">
          {sideBar(topSide)}

          <div className="board-frame">
            <div className="board" ref={boardRef} role="grid" aria-label="Chess board">
              {reviewPlayedMove && <BoardArrow move={reviewPlayedMove} flipped={flipped} tone={reviewMeta.tone} label="Played move" />}
              {showBestArrow && <BoardArrow move={reviewBestMove} flipped={flipped} tone="best-suggestion" label="Best move" />}

              {boardIndexes.map(displayRow => boardIndexes.map(displayCol => {
                const row = flipped ? 7 - displayRow : displayRow
                const col = flipped ? 7 - displayCol : displayCol
                const square = { col, row }
                const piece = piecesBySquare.get(`${col},${row}`)
                const dark = (row + col) % 2 === 1
                const selectedHere = sameSquare(selected, square)
                const move = legalMoves.find(item => sameSquare(item, square))
                const lastHere = lastMove && (
                  (lastMove.fromCol === col && lastMove.fromRow === row) ||
                  (lastMove.toCol === col && lastMove.toRow === row)
                )
                const reviewBadgeHere = reviewPlayedMove && reviewPlayedMove.toCol === col && reviewPlayedMove.toRow === row
                const movable = canMovePiece(piece)
                const draggingHere = dragGhost && sameSquare(dragGhost.piece, square)

                return (
                  <button
                    key={squareName(col, row)}
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
                    aria-label={`${squareName(col, row)}${piece ? ` ${piece.color} ${piece.name}` : ''}`}
                  >
                    {displayCol === 0 && <span className="rank-label">{8 - row}</span>}
                    {displayRow === 7 && <span className="file-label">{files[col]}</span>}
                    {reviewBadgeHere && <span className={`review-badge ${reviewMeta.tone}`}>{reviewMeta.icon}</span>}
                    {piece && (
                      <span
                        className={['piece', piece.color, movable ? 'movable' : '', draggingHere ? 'dragging' : ''].filter(Boolean).join(' ')}
                        onPointerDown={event => handlePiecePointerDown(event, piece)}
                        onPointerMove={handlePiecePointerMove}
                        onPointerUp={handlePiecePointerUp}
                        onPointerCancel={handlePiecePointerCancel}
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
                        onClick={() => submitMove(promotion.origin, promotion.target, letter)}
                        aria-label={`Promote to ${PROMOTION_NAMES[letter]}`}
                      >
                        <PieceIcon name={PROMOTION_NAMES[letter]} color={promotion.color} decorative />
                      </button>
                    ))}
                  </div>
                  <button type="button" className="promotion-cancel" onClick={() => { setPromotion(null); clearSelection() }}>
                    Cancel
                  </button>
                </div>
              )}
            </div>
          </div>

          {sideBar(bottomSide)}
        </div>

        <aside className="side-panel">
          <header className="panel-status">
            <div className="status-text">
              <span className="status-kicker">{inReview ? 'Game review' : 'Live game'}</span>
              <h1 aria-live="polite">{headline}</h1>
            </div>
            <div className="status-meta">
              {result
                ? <span className="result-chip">{result}</span>
                : <span className={`turn-dot ${game?.turn ?? 'white'}`} title={`${game?.turn ?? 'white'} to move`} />}
            </div>
          </header>

          {disconnected && (
            <div className="notice warning">
              <strong>No game state loaded.</strong>
              <span>Run `mvn spring-boot:run -Dspring-boot.run.main-class=server.ServerApplication` from the project root.</span>
            </div>
          )}
          {error && <div className="notice error" role="alert">{error}</div>}
          {connectionError && game && <div className="notice error" role="alert">{connectionError}</div>}
          {analysisNotice && (
            <div className={`notice ${game.analysisStatus === 'failed' ? 'error' : 'info'}`} role="status">
              {game.analysisStatus === 'running' && <span className="spinner" aria-hidden="true" />}
              {analysisNotice}
            </div>
          )}

          {inReview ? (
            <ReviewPanel
              game={game}
              analyzing={analysisRunning}
              onPrevious={() => stepAnalysis(-1)}
              onNext={() => stepAnalysis(1)}
              onExit={exitAnalysis}
            />
          ) : (
            <>
              <section className={`setup ${showSetup ? 'open' : ''}`}>
                <button
                  type="button"
                  className="setup-toggle"
                  aria-expanded={showSetup}
                  onClick={() => setSettingsOpen(!showSetup)}
                >
                  <span className="setup-label">Game setup</span>
                  <span className="setup-summary">{setupSummary}</span>
                  <span className="chevron" aria-hidden="true">▾</span>
                </button>

                {showSetup && (
                  <div className="setup-body">
                    {setupLocked && <p className="setup-note">Settings are locked during a game. Press New game to change them.</p>}
                    <div className="field">
                      <span className="field-label">Opponent</span>
                      <div className="segmented" role="group" aria-label="Opponent">
                        <button type="button" className={humanVsHuman ? 'active' : ''} aria-pressed={humanVsHuman} onClick={() => updateSettings({ humanVsHuman: true })} disabled={setupLocked}>
                          2 players
                        </button>
                        <button type="button" className={!humanVsHuman ? 'active' : ''} aria-pressed={!humanVsHuman} onClick={() => updateSettings({ humanVsHuman: false })} disabled={setupLocked}>
                          Stockfish
                        </button>
                      </div>
                    </div>

                    {!humanVsHuman && (
                      <div className="field-row">
                        <div className="field">
                          <span className="field-label">Play as</span>
                          <div className="segmented" role="group" aria-label="Choose side">
                            <button type="button" className={playAs === 'white' ? 'active' : ''} aria-pressed={playAs === 'white'} onClick={() => updateSettings({ playAs: 'white' })} disabled={setupLocked}>
                              White
                            </button>
                            <button type="button" className={playAs === 'black' ? 'active' : ''} aria-pressed={playAs === 'black'} onClick={() => updateSettings({ playAs: 'black' })} disabled={setupLocked}>
                              Black
                            </button>
                          </div>
                        </div>
                        <label className="field">
                          <span className="field-label">Strength</span>
                          <select value={engineElo} onChange={event => updateSettings({ engineElo: Number(event.target.value) })} disabled={setupLocked}>
                            {[800, 1000, 1200, 1400, 1600, 1800, 2000, 2200, 2400, 2600, 2800].map(elo => (
                              <option value={elo} key={elo}>Elo {elo}</option>
                            ))}
                          </select>
                        </label>
                      </div>
                    )}

                    <label className="field">
                      <span className="field-label">Time</span>
                      <select value={timeControl} onChange={event => updateSettings({ timeControl: Number(event.target.value) })} disabled={setupLocked}>
                        <option value={1}>1 min</option>
                        <option value={3}>3 min</option>
                        <option value={5}>5 min</option>
                        <option value={10}>10 min</option>
                        <option value={15}>15 min</option>
                        <option value={30}>30 min</option>
                      </select>
                    </label>
                  </div>
                )}
              </section>

              <section className="moves-section" aria-label="Move history">
                <div className="section-head">
                  <h2>Moves</h2>
                  <span>{plyCount} ply</span>
                </div>
                <MoveTable
                  displayMoves={game?.displayMoves ?? []}
                  emptyText={game?.awaitingStart && !game?.humanVsHuman ? 'Press Start game to begin.' : 'Make the first move to start the game.'}
                />
              </section>

              {showImport && (
                <section className="import-drawer" aria-label="Import PGN">
                  <div className="section-head">
                    <h2>Import PGN</h2>
                    <label className="file-picker">
                      Upload file
                      <input type="file" accept=".pgn,.txt" onChange={handlePgnFile} />
                    </label>
                  </div>
                  <textarea
                    value={pgnText}
                    onChange={event => setPgnText(event.target.value)}
                    placeholder='Paste PGN, e.g. 1. e4 c6 2. Qh5 d5'
                    rows={4}
                  />
                  <div className="drawer-actions">
                    <button type="button" className="quiet-action" onClick={() => setShowImport(false)}>Cancel</button>
                    <button type="button" className="secondary-action" onClick={importPgn} disabled={importing}>
                      {importing ? 'Importing…' : 'Import for review'}
                    </button>
                  </div>
                </section>
              )}

              <footer className="panel-actions">
                {game?.awaitingStart ? (
                  <button className="primary-action" type="button" onClick={startGame} disabled={pending}>
                    Start game
                  </button>
                ) : (
                  <button className="primary-action" type="button" onClick={newGame} disabled={pending}>
                    New game
                  </button>
                )}
                <div className="quiet-actions">
                  <button type="button" className="quiet-action" onClick={analyzeGame} disabled={analysisRunning}>
                    {analysisRunning ? 'Analyzing…' : 'Analyze'}
                  </button>
                  <button type="button" className="quiet-action" onClick={copyPgn}>{copyLabel}</button>
                  <button type="button" className={`quiet-action ${showImport ? 'active' : ''}`} onClick={() => setShowImport(value => !value)}>
                    Import PGN
                  </button>
                </div>
              </footer>
            </>
          )}
        </aside>
      </section>

      {confirmHome && (
        <ConfirmDialog
          title="Leave this game?"
          onCancel={() => setConfirmHome(false)}
          actions={[
            { label: copyLabel === 'Copy PGN' ? 'Copy PGN first' : copyLabel, onClick: copyPgn, tone: 'quiet' },
            { label: 'Keep playing', onClick: () => setConfirmHome(false), tone: 'secondary', autoFocus: true },
            { label: 'Start new game', onClick: startOverFromHome, tone: 'primary' }
          ]}
        >
          {game?.analysisMode
            ? 'Starting over ends the current review and clears this game from the board.'
            : `Your current game (${plyCount} ${plyCount === 1 ? 'move' : 'moves'}) will be cleared. Copy the PGN first if you want to keep it.`}
        </ConfirmDialog>
      )}
    </main>
  )
}

function ConfirmDialog({ title, children, actions, onCancel }) {
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

function BoardArrow({ move, flipped, tone, label }) {
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

function PlayerBar({ side, name, detail, clock, active, thinking, captures, advantage }) {
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
      <div className={`clock ${active ? 'running' : ''}`}>{clock}</div>
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

function MoveTable({ displayMoves, emptyText }) {
  const listRef = useRef(null)
  const rows = parseMoveRows(displayMoves)
  const lastRow = rows.length - 1
  const lastIsBlack = lastRow >= 0 && Boolean(rows[lastRow].black)

  useEffect(() => {
    const list = listRef.current
    if (list) list.scrollTop = list.scrollHeight
  }, [displayMoves.length, lastIsBlack])

  if (!rows.length) {
    return <div className="moves empty">{emptyText}</div>
  }

  return (
    <ol className="moves" ref={listRef}>
      {rows.map((row, index) => (
        <li className="move-row" key={`${row.number}-${index}`}>
          <span className="move-number">{row.number}.</span>
          <span className={`move-san ${index === lastRow && !lastIsBlack ? 'current' : ''}`}>{row.white}</span>
          <span className={`move-san ${index === lastRow && lastIsBlack ? 'current' : ''}`}>{row.black ?? ''}</span>
        </li>
      ))}
    </ol>
  )
}

function ReviewPanel({ game, analyzing, onPrevious, onNext, onExit }) {
  const summary = game?.analysisSummary
  const frame = game?.analysisFrame
  const report = game?.analysisReport ?? []
  const canReview = game?.analysisStatus === 'ready' && frame
  const atStart = !canReview || frame.plyIndex < 0
  const atEnd = !canReview || frame.plyIndex >= frame.totalPlies - 1

  return (
    <section className="review-panel" aria-label="Game review">
      <div className="review-head">
        <h2>Analysis</h2>
        <span className="review-status">{analyzing ? 'running' : game?.analysisStatus ?? 'idle'}</span>
        <button type="button" className="quiet-action" onClick={onExit} disabled={!game?.analysisMode}>Exit review</button>
      </div>

      <div className="review-scroll">
        {summary ? (
          <>
            <AccuracyTable summary={summary} />
            <EvalGraph entries={game.analysisEntries ?? []} currentPly={frame?.plyIndex ?? -1} />
          </>
        ) : (
          <p className="review-hint">Run review after a few moves to get accuracy, mistakes, and best-move feedback.</p>
        )}

        {frame?.plyIndex >= 0 ? (
          <CoachFeedback frame={frame} />
        ) : (
          <p className="review-hint">
            <strong>{canReview ? 'Start review' : 'Run analysis'}</strong>{' '}
            {canReview ? 'Use Next (or the arrow keys) to step through the game.' : 'Stockfish will classify each move and show the best line.'}
          </p>
        )}

        <div className="section-head compact">
          <h2>Moves</h2>
          <span>{frame ? `${Math.max(0, frame.plyIndex + 1)} / ${frame.totalPlies}` : ''}</span>
        </div>
        <MoveTable displayMoves={game?.displayMoves ?? []} emptyText="Starting position" />

        {report.length > 0 && (
          <details className="report-details">
            <summary>Full report</summary>
            <div className="report-lines">
              {report.slice(0, 8).map((line, index) => <div key={`${line}-${index}`}>{line}</div>)}
            </div>
          </details>
        )}
      </div>

      <div className="review-nav">
        <button type="button" onClick={onPrevious} disabled={atStart} aria-label="Previous move">‹ Previous</button>
        <button type="button" onClick={onNext} disabled={atEnd} aria-label="Next move">Next ›</button>
      </div>
    </section>
  )
}

function AccuracyTable({ summary }) {
  const sideOf = (side, fallbackAccuracy) => side ?? {
    accuracy: fallbackAccuracy ?? 100,
    best: 0,
    excellent: 0,
    good: 0,
    inaccuracies: 0,
    mistakes: 0,
    blunders: 0,
    totalMoves: 0
  }
  const white = sideOf(summary.white, summary.whiteAccuracy)
  const black = sideOf(summary.black, summary.blackAccuracy)
  const rows = [
    ['Best', 'best', 'best'],
    ['Excellent', 'excellent', 'excellent'],
    ['Good', 'good', 'good'],
    ['Inaccuracy', 'inaccuracies', 'inaccuracy'],
    ['Mistake', 'mistakes', 'mistake'],
    ['Blunder', 'blunders', 'blunder']
  ]

  return (
    <table className="accuracy-table">
      <thead>
        <tr>
          <th scope="col"><span className="visually-hidden">Category</span></th>
          <th scope="col">White</th>
          <th scope="col">Black</th>
        </tr>
      </thead>
      <tbody>
        <tr className="accuracy-row">
          <th scope="row">Accuracy</th>
          <td><strong>{white.accuracy.toFixed(1)}</strong></td>
          <td><strong>{black.accuracy.toFixed(1)}</strong></td>
        </tr>
        {rows.map(([label, key, tone]) => (
          <tr key={key}>
            <th scope="row"><span className={`tone-dot ${tone}`} aria-hidden="true" />{label}</th>
            <td>{white[key]}</td>
            <td>{black[key]}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function CoachFeedback({ frame }) {
  const meta = frameQualityMeta(frame)
  const isBadMove = !frame.deliveredMate && ['Inaccuracy', 'Mistake', 'Severe Mistake', 'Blunder'].includes(frame.qualityTag)
  const bestLine = frame.bestLine?.length ? frame.bestLine.slice(0, 6).join(' ') : null
  const before = scoreText(frame.evalBefore, frame.mateBefore)
  const after = scoreText(frame.evalAfter, frame.mateAfter)
  let explanation
  if (frame.deliveredMate) {
    explanation = 'This move checkmated the opponent and ended the game.'
  } else if (isBadMove && (frame.mateBefore != null || frame.mateAfter != null)) {
    // Mate scores are stored as ±100 internally; a pawn-loss figure would be meaningless here.
    explanation = `The move changed the evaluation from ${before} to ${after}.`
  } else if (isBadMove) {
    explanation = `The move changed the evaluation from ${before} to ${after}, costing about ${Math.max(0, frame.loss).toFixed(2)} pawns.`
  } else {
    explanation = `The move kept the position near Stockfish's recommendation, changing the evaluation from ${before} to ${after}.`
  }

  return (
    <div className={`coach-card ${meta.tone}`}>
      <div className="coach-title">
        <span className={`classification-pill ${meta.tone}`}>{meta.icon}</span>
        <strong>{frame.moveNumber}. {frame.whiteMove ? 'White' : 'Black'}: {frame.san} is {meta.label}</strong>
        <em>{scoreBadge(frame)}</em>
      </div>
      <p>{explanation}</p>
      {frame.gameEndMessage && !frame.deliveredMate && (
        <p className="game-end-note">
          Game over: {frame.gameEndMessage}
          {frame.gameEndReason === 'timeout' ? ` (${frame.gameEndMessage.startsWith('White') ? 'Black' : 'White'} ran out of time).` : '.'}
        </p>
      )}
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
