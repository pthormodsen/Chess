import React, { useEffect, useMemo, useRef, useState } from 'react'
import axios from 'axios'
import { PieceIcon, pieceFromSymbol } from './pieces.jsx'
import {
  AnalysisProgress, ClassIcon, CoachCard, EvalBar, EvalGraph, ReviewMoveList, ReviewNav, ReviewSummary,
  classMeta, evalAtPly, findKeyMoment, piecesFromFen, uciSquares
} from './review.jsx'

const files = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h']

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

function sameMove(a, b) {
  return a && b && a.fromCol === b.fromCol && a.fromRow === b.fromRow && a.toCol === b.toCol && a.toRow === b.toRow
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
  // Review: the summary screen comes first; "moves" is the move-by-move review.
  const [reviewStage, setReviewStage] = useState('summary')
  // Best line being played on the board: { plyIndex, index } (index into the frame's bestLinePlies).
  const [linePreview, setLinePreview] = useState(null)

  const boardRef = useRef(null)
  const dragRef = useRef(null)
  const suppressClickRef = useRef(false)
  const legalRequestRef = useRef(0)
  // latest: id of the newest mutation; epoch: bumped when any mutation starts or ends, so polls that overlap one are dropped.
  const mutationRef = useRef({ latest: 0, epoch: 0, inFlight: 0 })
  // Last requested review ply while navigation is in flight, so fast key repeats keep moving instead of re-sending one ply.
  const navRef = useRef({ target: null, inFlight: 0 })
  const wasReviewRef = useRef(false)

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

  const reviewFrame = game?.analysisMode ? game?.analysisFrame : null
  const reviewEntries = game?.analysisEntries ?? []
  const previewPlies = linePreview && reviewFrame?.plyIndex === linePreview.plyIndex ? reviewFrame.bestLinePlies ?? [] : []
  const previewPly = previewPlies[linePreview?.index ?? -1] ?? null
  const boardPieces = previewPly ? piecesFromFen(previewPly.fen) : game?.pieces

  const piecesBySquare = useMemo(() => {
    const map = new Map()
    for (const piece of boardPieces ?? []) {
      map.set(`${piece.col},${piece.row}`, piece)
    }
    return map
  }, [boardPieces])

  const balance = materialBalance(game?.pieces)
  const whiteCaptures = splitCaptures(game?.capturedByWhite)
  const blackCaptures = splitCaptures(game?.capturedByBlack)
  const lastMove = previewPly ? uciSquares(previewPly.uci) : game?.lastMove
  const disconnected = connectionError && !game
  const engineTurn = Boolean(game?.engineTurn)
  const engineUnavailable = Boolean(game && !game.humanVsHuman && !game.engineEnabled && game.active && !game.analysisMode)
  // Stockfish games: fixed orientation from "Play as". Local games: the side to move sits at the bottom.
  const desiredFlip = game ? (game.humanVsHuman ? game.turn === 'black' : Boolean(game.enginePlaysWhite)) : false
  const [flipped, setFlipped] = useState(false)
  const analysisRunning = analyzing || game?.analysisStatus === 'running'
  const legalMoves = selected && sameSquare(legalFor.origin, selected) ? legalFor.moves : []
  const reviewPly = reviewFrame?.plyIndex ?? -1
  const reviewPlayedMove = reviewPly >= 0 && !previewPly ? uciSquares(reviewFrame.playedMove) : null
  const reviewBestMove = reviewPly >= 0 && !previewPly ? uciSquares(reviewFrame.bestMove) : null
  const reviewMeta = reviewFrame?.deliveredMate ? classMeta('Best') : classMeta(reviewFrame?.qualityTag)
  const showBestArrow = reviewBestMove && !sameMove(reviewBestMove, reviewPlayedMove)
  const nextPreviewPly = previewPlies[(linePreview?.index ?? -1) + 1] ?? null
  const reviewMovesStage = Boolean(game?.analysisMode) && reviewStage === 'moves'
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

  // Entering review always opens the summary; leaving it clears any line preview.
  useEffect(() => {
    const inReviewNow = Boolean(game?.analysisMode)
    if (inReviewNow && !wasReviewRef.current) setReviewStage('summary')
    if (!inReviewNow) setLinePreview(null)
    wasReviewRef.current = inReviewNow
  }, [game?.analysisMode])

  useEffect(() => {
    setLinePreview(null)
    // Once the server state has caught up with every request, key presses start from the shown ply again.
    if (navRef.current.inFlight === 0) navRef.current.target = null
  }, [reviewPly])

  useEffect(() => {
    function handleReviewKeys(event) {
      const target = event.target
      const tagName = target?.tagName?.toLowerCase()
      const isTyping = tagName === 'input' || tagName === 'select' || tagName === 'textarea' || target?.isContentEditable
      if (!game?.analysisMode || pending || isTyping || confirmHome || event.altKey || event.metaKey || event.ctrlKey) return

      // While a best line is on the board, the arrows walk through that line instead.
      if (linePreview && previewPlies.length) {
        const keys = { ArrowLeft: -1, ArrowRight: 1 }
        if (event.key === 'Escape') {
          event.preventDefault()
          setLinePreview(null)
        } else if (event.key in keys) {
          event.preventDefault()
          const next = linePreview.index + keys[event.key]
          if (next < 0) setLinePreview(null)
          else setLinePreview({ ...linePreview, index: Math.min(previewPlies.length - 1, next) })
        }
        return
      }

      const total = game.analysisFrame?.totalPlies ?? 0
      const current = navRef.current.target ?? reviewPly
      let destination = null
      if (event.key === 'ArrowLeft') destination = current - 1
      else if (event.key === 'ArrowRight') destination = current + 1
      else if (event.key === 'Home') destination = -1
      else if (event.key === 'End') destination = total - 1
      else if (event.key === 'ArrowDown') destination = findKeyMoment(reviewEntries, current, 1)
      else if (event.key === 'ArrowUp') destination = findKeyMoment(reviewEntries, current, -1)
      else return
      event.preventDefault()
      if (destination == null) return
      gotoPly(Math.max(-1, Math.min(total - 1, destination)))
    }

    window.addEventListener('keydown', handleReviewKeys)
    return () => window.removeEventListener('keydown', handleReviewKeys)
  }, [game?.analysisMode, reviewPly, reviewEntries, linePreview, previewPlies, pending, confirmHome, reviewStage])

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

  // Jumps the review to a ply (-1 = start). Any navigation also moves from the summary into the review.
  async function gotoPly(ply) {
    if (ply == null) return
    setReviewStage('moves')
    const nav = navRef.current
    nav.target = ply
    nav.inFlight += 1
    try {
      const { response, current } = await mutate(() => axios.post('/api/game/analysis/goto', { ply }))
      if (current) applyGameState(response.data)
      setError(null)
    } catch (err) {
      nav.target = null
      setError(formatApiError(err))
    } finally {
      nav.inFlight -= 1
    }
  }

  function playLine(index) {
    if (!reviewFrame?.bestLinePlies?.length) return
    setLinePreview({ plyIndex: reviewFrame.plyIndex, index })
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
  const reviewHeadline = reviewStage === 'summary'
    ? 'Game review'
    : reviewFrame?.plyIndex >= 0
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
  const analysisNotice = !inReview && game?.analysisStatus === 'failed' ? (game.analysisReport ?? [])[0] : null
  const showProgress = !inReview && game?.analysisStatus === 'running'
  const reviewEval = evalAtPly(reviewEntries, reviewPly)

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

          {inReview && reviewEval && (
            <EvalBar
              evaluation={reviewFrame?.deliveredMate
                ? { ...reviewEval, win: reviewFrame.whiteMove ? 100 : 0, result: reviewFrame.whiteMove ? '1-0' : '0-1' }
                : reviewEval}
              flipped={flipped}
            />
          )}
          <div className="board-frame">
            {previewPly && (
              <div className="line-banner" role="status">
                <span>Best line · {linePreview.index + 1}/{previewPlies.length}</span>
                <button type="button" onClick={() => setLinePreview(null)}>Back to game ✕</button>
              </div>
            )}
            <div className="board" ref={boardRef} role="grid" aria-label="Chess board">
              {showBestArrow && <BoardArrow move={reviewBestMove} flipped={flipped} tone="best-suggestion" label="Best move" />}
              {nextPreviewPly && <BoardArrow move={uciSquares(nextPreviewPly.uci)} flipped={flipped} tone="best-suggestion" label="Next move in line" />}

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
                const reviewBadgeHere = reviewMeta && reviewPlayedMove && reviewPlayedMove.toCol === col && reviewPlayedMove.toRow === row
                const reviewTintHere = reviewMeta && reviewPlayedMove && (reviewBadgeHere || (reviewPlayedMove.fromCol === col && reviewPlayedMove.fromRow === row))
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
                      lastHere && !reviewTintHere ? 'last-move' : '',
                      reviewTintHere ? 'review-square' : '',
                      previewPly && lastHere ? 'line-square' : '',
                      move?.capture ? 'can-capture' : '',
                      move && !move.capture ? 'can-move' : ''
                    ].filter(Boolean).join(' ')}
                    style={reviewTintHere ? { '--class-color': reviewMeta.color } : undefined}
                    onClick={() => handleSquareClick(col, row)}
                    aria-label={`${squareName(col, row)}${piece ? ` ${piece.color} ${piece.name}` : ''}`}
                  >
                    {displayCol === 0 && <span className="rank-label">{8 - row}</span>}
                    {displayRow === 7 && <span className="file-label">{files[col]}</span>}
                    {reviewBadgeHere && (
                      <span className="review-badge">
                        <ClassIcon tag={reviewFrame.deliveredMate ? 'Best' : reviewFrame.qualityTag} size={28} />
                      </span>
                    )}
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
          {analysisNotice && <div className="notice error" role="status">{analysisNotice}</div>}

          {inReview ? (
            <section className={`review-panel stage-${reviewStage}`} aria-label="Game review">
              <div className="review-head">
                <div className="review-tabs" role="tablist" aria-label="Review view">
                  <button type="button" role="tab" aria-selected={reviewStage === 'summary'}
                    className={reviewStage === 'summary' ? 'active' : ''} onClick={() => setReviewStage('summary')}>Summary</button>
                  <button type="button" role="tab" aria-selected={reviewStage === 'moves'}
                    className={reviewStage === 'moves' ? 'active' : ''} onClick={() => setReviewStage('moves')}>Moves</button>
                </div>
                <button type="button" className="quiet-action" onClick={exitAnalysis}>Exit review</button>
              </div>

              {reviewStage === 'summary' && game.analysisSummary ? (
                <div className="review-scroll">
                  <ReviewSummary
                    summary={game.analysisSummary}
                    entries={reviewEntries}
                    onSelect={gotoPly}
                    onStart={() => gotoPly(reviewPly >= 0 ? reviewPly : 0)}
                  />
                </div>
              ) : (
                <>
                  <div className="review-scroll">
                    <CoachCard
                      frame={reviewFrame}
                      entries={reviewEntries}
                      linePreview={previewPly ? linePreview : null}
                      onPlayLine={playLine}
                      onStopLine={() => setLinePreview(null)}
                    />
                    <EvalGraph entries={reviewEntries} currentPly={reviewPly} onSelect={gotoPly} />
                    <ReviewMoveList entries={reviewEntries} currentPly={reviewPly} onSelect={gotoPly} />
                  </div>
                  <ReviewNav
                    ply={reviewPly}
                    total={reviewFrame?.totalPlies ?? reviewEntries.length}
                    entries={reviewEntries}
                    disabled={!reviewFrame}
                    onGoto={gotoPly}
                  />
                </>
              )}
            </section>
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

              {showProgress && <AnalysisProgress progress={game.analysisProgress} entries={reviewEntries} />}

              <section className={`moves-section ${showProgress ? 'is-hidden' : ''}`} aria-label="Move history">
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
