---- MODULE ActivityTemplateLifecycle ----
\* Models CouponTemplateServiceImpl + CouponTemplateActivationJob
\* and SeckillActivityServiceImpl + SeckillActivityActivationJob

VARIABLES
  now,            \* "before" | "during" | "after"
  dbStatus,       \* 0 (DRAFT) | 1 (ACTIVE) | 2 (ENDED)
  redisStatus,    \* "none" | "0" (PREHEATED) | "1" (ACTIVE) | "2" (ENDED)
  jobTickedAfter  \* BOOLEAN: ActivationJob executed at least once while now = "after"

vars == <<now, dbStatus, redisStatus, jobTickedAfter>>

Init ==
  /\ now = "before"
  /\ dbStatus = 0
  /\ redisStatus = "none"
  /\ jobTickedAfter = FALSE

AdvanceToDuring ==
  /\ now = "before"
  /\ now' = "during"
  /\ UNCHANGED <<dbStatus, redisStatus, jobTickedAfter>>

AdvanceToAfter ==
  /\ now = "during"
  /\ now' = "after"
  /\ UNCHANGED <<dbStatus, redisStatus, jobTickedAfter>>

\* Admin calls publish() (only allowed when now # "after")
Publish ==
  /\ now # "after"
  /\ dbStatus # 2
  /\ IF now = "before"
     THEN /\ dbStatus' = 0
          /\ redisStatus' = "0"
     ELSE /\ dbStatus' = 1
          /\ redisStatus' = "1"
  /\ UNCHANGED <<now, jobTickedAfter>>

Terminate ==
  /\ dbStatus \in {0, 1}
  /\ dbStatus' = 2
  /\ redisStatus' = "2"
  /\ UNCHANGED <<now, jobTickedAfter>>

\* ActivationJob.activatePreheated(): WHERE status=0 AND start<=now AND end>now, Redis=="0"
JobActivatePreheated ==
  /\ now = "during"
  /\ dbStatus = 0
  /\ redisStatus = "0"
  /\ dbStatus' = 1
  /\ redisStatus' = "1"
  /\ UNCHANGED <<now, jobTickedAfter>>

\* ActivationJob.endExpired(): WHERE status IN (0, 1) AND end<=now;
\* for status=0, only end when Redis=="0" (preheated), keeping unpublished drafts (Redis=="none") at 0
JobEndExpired ==
  /\ now = "after"
  /\ (dbStatus = 1 \/ (dbStatus = 0 /\ redisStatus = "0"))
  /\ dbStatus' = 2
  /\ redisStatus' = "2"
  /\ jobTickedAfter' = TRUE
  /\ UNCHANGED now

\* ActivationJob runs when now = "after", but neither active nor preheated matches (e.g., unpublished draft or already ended)
JobTickIdleAfter ==
  /\ now = "after"
  /\ ~(dbStatus = 1 \/ (dbStatus = 0 /\ redisStatus = "0"))
  /\ ~jobTickedAfter
  /\ jobTickedAfter' = TRUE
  /\ UNCHANGED <<now, dbStatus, redisStatus>>

Done ==
  /\ now = "after"
  /\ jobTickedAfter
  /\ UNCHANGED vars

Next ==
  \/ AdvanceToDuring
  \/ AdvanceToAfter
  \/ Publish
  \/ Terminate
  \/ JobActivatePreheated
  \/ JobEndExpired
  \/ JobTickIdleAfter
  \/ Done

\* ===== Invariants =====

\* If an activity was published (redisStatus /= "none") and endTime passed and the job ran,
\* it must be ENDED (2), never stuck in DRAFT/PREHEATED (0)!
PublishedMustEndAfterEndTime ==
  (now = "after" /\ jobTickedAfter /\ redisStatus # "none") => (dbStatus = 2 /\ redisStatus = "2")

TerminatedStaysEnded ==
  (dbStatus = 2) => (redisStatus = "2")

====
