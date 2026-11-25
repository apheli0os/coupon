---- MODULE StockReconcileDebounce ----
EXTENDS Naturals

CONSTANTS Total, MaxAutoFix

VARIABLES
  dbCount,
  redisStock,
  inFlightToken,
  pendingDir,
  phase,
  observedStock,
  autoFixCount,
  alertCount,
  lastAction,
  lastHadInFlight,
  lastHadPendingSameDir,
  lastDriftAbs

vars == <<dbCount, redisStock, inFlightToken, pendingDir, phase, observedStock, autoFixCount, alertCount, lastAction, lastHadInFlight, lastHadPendingSameDir, lastDriftAbs>>

ExpectedStock == Total - dbCount

DriftDir ==
  IF ExpectedStock > redisStock THEN "UNDER"
  ELSE IF ExpectedStock < redisStock THEN "OVER"
  ELSE "NONE"

AbsDiff ==
  IF ExpectedStock >= redisStock THEN ExpectedStock - redisStock
  ELSE redisStock - ExpectedStock

Init ==
  /\ dbCount = 0
  /\ redisStock = Total
  /\ inFlightToken = 0
  /\ pendingDir = "NONE"
  /\ phase = "IDLE"
  /\ observedStock = Total
  /\ autoFixCount = 0
  /\ alertCount = 0
  /\ lastAction = "INIT"
  /\ lastHadInFlight = FALSE
  /\ lastHadPendingSameDir = FALSE
  /\ lastDriftAbs = 0

ClaimPreDeduct ==
  /\ redisStock > 0
  /\ dbCount + inFlightToken < Total
  /\ redisStock' = redisStock - 1
  /\ inFlightToken' = inFlightToken + 1
  /\ lastAction' = "CLAIM"
  /\ lastHadInFlight' = (inFlightToken > 0)
  /\ lastHadPendingSameDir' = FALSE
  /\ lastDriftAbs' = AbsDiff
  /\ UNCHANGED <<dbCount, pendingDir, phase, observedStock, autoFixCount, alertCount>>

ConsumerCommit ==
  /\ inFlightToken > 0
  /\ dbCount < Total
  /\ inFlightToken' = inFlightToken - 1
  /\ dbCount' = dbCount + 1
  /\ lastAction' = "COMMIT"
  /\ lastHadInFlight' = TRUE
  /\ lastHadPendingSameDir' = FALSE
  /\ lastDriftAbs' = AbsDiff
  /\ UNCHANGED <<redisStock, pendingDir, phase, observedStock, autoFixCount, alertCount>>

SimulateUnderDrift ==
  /\ phase = "IDLE"
  /\ inFlightToken = 0
  /\ redisStock > 0
  /\ redisStock' = redisStock - 1
  /\ lastAction' = "DRIFT"
  /\ lastHadInFlight' = FALSE
  /\ lastHadPendingSameDir' = FALSE
  /\ lastDriftAbs' = AbsDiff
  /\ UNCHANGED <<dbCount, inFlightToken, pendingDir, phase, observedStock, autoFixCount, alertCount>>

SimulateOverDrift ==
  /\ phase = "IDLE"
  /\ inFlightToken = 0
  /\ redisStock < Total
  /\ redisStock' = redisStock + 1
  /\ lastAction' = "DRIFT"
  /\ lastHadInFlight' = FALSE
  /\ lastHadPendingSameDir' = FALSE
  /\ lastDriftAbs' = AbsDiff
  /\ UNCHANGED <<dbCount, inFlightToken, pendingDir, phase, observedStock, autoFixCount, alertCount>>

ReconcileMatched ==
  /\ phase = "IDLE"
  /\ inFlightToken = 0
  /\ DriftDir = "NONE"
  /\ pendingDir' = "NONE"
  /\ lastAction' = "MATCH"
  /\ lastHadInFlight' = FALSE
  /\ lastHadPendingSameDir' = FALSE
  /\ lastDriftAbs' = 0
  /\ UNCHANGED <<dbCount, redisStock, inFlightToken, phase, observedStock, autoFixCount, alertCount>>

ReconcileRound1 ==
  /\ phase = "IDLE"
  /\ inFlightToken = 0
  /\ DriftDir # "NONE"
  /\ pendingDir # DriftDir
  /\ pendingDir' = DriftDir
  /\ lastAction' = "ROUND1"
  /\ lastHadInFlight' = FALSE
  /\ lastHadPendingSameDir' = FALSE
  /\ lastDriftAbs' = AbsDiff
  /\ UNCHANGED <<dbCount, redisStock, inFlightToken, phase, observedStock, autoFixCount, alertCount>>

ObserveRound2 ==
  /\ phase = "IDLE"
  /\ inFlightToken = 0
  /\ DriftDir # "NONE"
  /\ pendingDir = DriftDir
  /\ AbsDiff <= MaxAutoFix
  /\ autoFixCount < Total
  /\ phase' = "OBSERVED"
  /\ observedStock' = redisStock
  /\ lastAction' = "OBSERVE"
  /\ lastHadInFlight' = FALSE
  /\ lastHadPendingSameDir' = TRUE
  /\ lastDriftAbs' = AbsDiff
  /\ UNCHANGED <<dbCount, redisStock, inFlightToken, pendingDir, autoFixCount, alertCount>>

CommitRound2AutoFix ==
  /\ phase = "OBSERVED"
  /\ inFlightToken = 0
  /\ redisStock = observedStock
  /\ redisStock' = ExpectedStock
  /\ pendingDir' = "NONE"
  /\ phase' = "IDLE"
  /\ autoFixCount' = autoFixCount + 1
  /\ lastAction' = "AUTOFIX"
  /\ lastHadInFlight' = FALSE
  /\ lastHadPendingSameDir' = TRUE
  /\ lastDriftAbs' = AbsDiff
  /\ UNCHANGED <<dbCount, inFlightToken, observedStock, alertCount>>

AbortRound2OnCasMismatch ==
  /\ phase = "OBSERVED"
  /\ (redisStock # observedStock \/ inFlightToken > 0)
  /\ phase' = "IDLE"
  /\ lastAction' = "CAS_ABORT"
  /\ lastHadInFlight' = (inFlightToken > 0)
  /\ lastHadPendingSameDir' = TRUE
  /\ lastDriftAbs' = AbsDiff
  /\ UNCHANGED <<dbCount, redisStock, inFlightToken, pendingDir, observedStock, autoFixCount, alertCount>>

ReconcileRound2Alert ==
  /\ phase = "IDLE"
  /\ inFlightToken = 0
  /\ DriftDir # "NONE"
  /\ pendingDir = DriftDir
  /\ AbsDiff > MaxAutoFix
  /\ alertCount < Total
  /\ pendingDir' = "NONE"
  /\ alertCount' = alertCount + 1
  /\ lastAction' = "ALERT"
  /\ lastHadInFlight' = FALSE
  /\ lastHadPendingSameDir' = TRUE
  /\ lastDriftAbs' = AbsDiff
  /\ UNCHANGED <<dbCount, redisStock, inFlightToken, phase, observedStock, autoFixCount>>

Next ==
  \/ ClaimPreDeduct
  \/ ConsumerCommit
  \/ SimulateUnderDrift
  \/ SimulateOverDrift
  \/ ReconcileMatched
  \/ ReconcileRound1
  \/ ObserveRound2
  \/ CommitRound2AutoFix
  \/ AbortRound2OnCasMismatch
  \/ ReconcileRound2Alert

NoFixWhileTokenInFlight ==
  (lastAction \in {"AUTOFIX", "ALERT"}) => ~lastHadInFlight

NoAutoFixWithoutPriorSameDirectionObservation ==
  (lastAction = "AUTOFIX") => (lastHadPendingSameDir /\ lastDriftAbs > 0 /\ lastDriftAbs <= MaxAutoFix)

LargeDriftRaisesAlertWithoutAutoFix ==
  (lastAction = "ALERT") => (lastHadPendingSameDir /\ lastDriftAbs > MaxAutoFix)

CasPreventsOverwritingConcurrentClaim ==
  (lastAction = "AUTOFIX") => (redisStock = ExpectedStock /\ inFlightToken = 0)

====