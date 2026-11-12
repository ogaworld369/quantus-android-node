package app.quantusnode.node.depin;

import android.content.Context;

import app.quantusnode.node.depin.core.CompanionStatus;
import app.quantusnode.node.depin.core.CrashLog;
import app.quantusnode.node.depin.core.DepinCore;
import app.quantusnode.node.depin.core.Hex;
import app.quantusnode.node.depin.core.JournalStore;

import java.util.Arrays;

/**
 * Process-wide shared objects (Android side): the one JournalStore
 * instance every component in the :depin process uses, so in-memory
 * chain state can never diverge between them.
 *
 * The journal's entry authentication is wired here: the signer
 * unwraps the identity seed via the Keystore vault for each
 * append, signs, and zeroes + locks the working copy immediately;
 * the verifier needs only the device public key, captured once at
 * construction.
 *
 * The store is constructed with the identity derived HERE, from
 * the vault: the vault is the authority on who this device is, so
 * a damaged or foreign journal file can no longer dictate — or
 * garble — the identity it is read under.
 *
 * Store construction itself is additionally wrapped: the store no
 * longer throws for file content (damaged journals are
 * quarantined and recovered inside it), so what can still fail
 * here is genuine I/O trouble. Such a failure is caught once,
 * persisted to the error log, mirrored into
 * CompanionStatus.journalError for the UI / service, and the same
 * IllegalStateException is rethrown to every caller — no retry
 * loop, no repeated log spam from a periodic UI refresh, and no
 * silent null for a caller to trip over later. An identity-stage
 * failure is mirrored into CompanionStatus.identityError instead,
 * so the UI names the real cause.
 *
 * Construction is ASYNCHRONOUS (integration design section 8-7,
 * the deferred-initialisation rule): the load reads and
 * re-verifies every journal entry's signature, so its cost grows
 * with the accumulated history, and running it on a caller's
 * thread would freeze a UI caller and delay the service's
 * foreground transition on every restart. Now prefetchJournal()
 * starts construction once, on a dedicated background thread;
 * journal() (for background callers) waits on the readiness
 * latch; journalIfReady() (for the UI) returns null while
 * preparation is in flight so the caller can show a preparing
 * state instead of blocking.
 *
 * ONE failure is excepted from the caching rule above
 * (internal.25): a writer-gate verdict of not_switched_on.
 * That verdict is a function of the recorded enable
 * authorisation, and the prefetch — started from the observer
 * service's onCreate, before any command is processed — can
 * evaluate the gate BEFORE the enable command that this very
 * launch answers has recorded the authorisation. Caching
 * that verdict served a stale refusal to the initialisation
 * that followed the recording, and the observer self-stopped
 * on it (the internal.24 device failure). An
 * authorisation-dependent failure is therefore published as
 * a SOFT failure: the blocking readers below drop it and
 * rebuild, re-evaluating the gate against the then-current
 * settings. Every other failure — frozen_s1 and the rest of
 * the gate's state-only reasons included — keeps the cached,
 * fail-closed treatment. See DepinGateVerdict for the
 * classification and its reasoning.
 */
public final class DepinRuntime {
    private DepinRuntime() {}

    private static final Object JOURNAL_LOCK = new Object();
    private static volatile JournalStore journal;
    private static volatile IllegalStateException journalFailure;
    /** An authorisation-dependent construction failure
     *  (DepinGateVerdict): published for status readers, but
     *  NEVER served to a blocking reader — the blocking
     *  readers drop it and rebuild, re-evaluating the gate
     *  against the current recorded authorisation. */
    private static volatile IllegalStateException journalSoftFailure;
    /** The writer-gate reason the latest completed
     *  construction failed with, or null when it did not fail
     *  at the gate. Generation-scoped like the failure it
     *  describes; cleared by invalidation and by a successful
     *  construction. */
    private static volatile String journalGateReason;
    private static volatile java.util.concurrent.CountDownLatch
            journalLatch = new java.util.concurrent.CountDownLatch(1);
    private static boolean journalInitStarted = false;
    /** The identity the constructed store was built under (the
     *  writer resolution's outcome), and its public key. Null
     *  until construction succeeds. The observer service binds
     *  THESE, never a separately derived identity: deriving the
     *  identity from the app vault instead was the internal.11
     *  defect that made a received identity unrecordable (the
     *  store, built under the received identity, refused the
     *  vault identity at bindIdentity) — see
     *  depin-integration-transfer-ui-progress.md section 9. */
    private static volatile String journalDeviceId;
    private static volatile byte[] journalPubkey;
    /** The store an initialised observer service instance is
     *  recording through, or null (G-06, final check round 2).
     *  Guarded by JOURNAL_LOCK. While it names the cached
     *  store, invalidateJournal() must not drop that store:
     *  the next reader (the bundle export) would construct a
     *  SECOND store over the same file, and the two stores'
     *  views of the chain would diverge — exports read a stale
     *  chain, and the stale store's appends endangered the
     *  records written since (the core now refuses those
     *  appends; see JournalStore). The invariant this registry
     *  keeps is the one the audit states: within a process, a
     *  journal file has exactly one writable store. */
    private static JournalStore observerHeldStore;
    /** The generation token of the current observer hold
     *  (S-05): minted from observerHoldSequence at adoption
     *  and required, matching, at release — a stale instance's
     *  late release, keyed by store identity alone, otherwise
     *  cleared a rapidly restarted successor's hold on the
     *  same cached store. Guarded by JOURNAL_LOCK; 0 names no
     *  hold (and matches no adoption, whose tokens start at 1). */
    private static long observerHoldToken;
    /** Monotonic source of hold tokens. Guarded by
     *  JOURNAL_LOCK. */
    private static long observerHoldSequence;
    /** An invalidation arrived while an observer held the
     *  cached store and was deferred (G-06). It takes effect
     *  when the observer releases the store: the
     *  binding-changing transfer operations freeze the
     *  observer first by design, so a rebuild is only ever
     *  NEEDED once no observer holds the store — which is
     *  exactly when the release applies the deferral. Guarded
     *  by JOURNAL_LOCK. */
    private static boolean journalInvalidationDeferred;

    /** Start journal construction on a background thread (once
     *  per construction generation). Safe to call from the main
     *  thread: it never blocks. */
    public static void prefetchJournal(Context context) {
        final java.util.concurrent.CountDownLatch latch;
        synchronized (JOURNAL_LOCK) {
            if (journalInitStarted) {
                return;
            }
            journalInitStarted = true;
            latch = journalLatch;
        }
        final Context appContext = context.getApplicationContext();
        Thread t = new Thread(() -> buildJournal(appContext, latch),
                "depin-journal-init");
        t.setDaemon(true);
        t.start();
    }

    /** Drop the cached construction (store, failure and
     *  identity) so the next journal() call re-resolves the
     *  writer against the CURRENT transfer state. Called by
     *  the transfer service after every transfer operation:
     *  a completed transfer changes which identity is live,
     *  and a construction cached from before it — worse, a
     *  cached FAILURE from while the writer gate was closed
     *  for the displaced identity (the production
     *  "transfer gate: frozen_s1" self-stop loop) — must not
     *  outlive the state it described.
     *
     *  G-06 exception: while an initialised observer holds
     *  the cached store (observerAdoptedStore), the drop is
     *  DEFERRED, not performed — dropping it handed the next
     *  reader a second store over the same file while the
     *  observer kept writing through the first. The deferred
     *  drop is applied by observerReleasedStore when the
     *  observer lets the store go; the binding-changing
     *  operations freeze that service first by design, so the
     *  deferral never delays a rebuild past the moment one is
     *  actually needed. */
    public static void invalidateJournal() {
        synchronized (JOURNAL_LOCK) {
            if (journal != null && journal == observerHeldStore) {
                journalInvalidationDeferred = true;
                return;
            }
            journal = null;
            journalFailure = null;
            journalSoftFailure = null;
            journalGateReason = null;
            journalDeviceId = null;
            journalPubkey = null;
            journalInitStarted = false;
            journalLatch = new java.util.concurrent.CountDownLatch(1);
        }
        // The status error fields describe the cached
        // construction; invalidating it retires them too
        // (audit M5: a stale "frozen_s1" outlived the state
        // it described and kept the settings screen wrong).
        CompanionStatus.journalError = null;
        CompanionStatus.identityError = null;
    }

    /** Called by the observer service when it takes the
     *  constructed store as its own (ensureInitialized): from
     *  here on this instance is the store's writer, and the
     *  cache must not be dropped from under it (G-06).
     *
     *  @return the hold's generation token (S-05); the adopter
     *          must present it to observerReleasedStore */
    public static long observerAdoptedStore(JournalStore store) {
        synchronized (JOURNAL_LOCK) {
            observerHeldStore = store;
            observerHoldToken = ++observerHoldSequence;
            return observerHoldToken;
        }
    }

    /** Called when the observer service instance that adopted
     *  a store is destroyed. A deferred invalidation (a
     *  transfer operation ran while the observer was live)
     *  takes effect now, so the next reader re-resolves the
     *  writer against the current transfer state.
     *
     *  S-05: the release is keyed by the adoption token as
     *  well as the store — a token that does not match the
     *  current hold (a destroyed predecessor's late release
     *  after a successor re-adopted the same store, token 0,
     *  an unknown token, or a repeat release) clears nothing. */
    public static void observerReleasedStore(JournalStore store,
                                             long token) {
        boolean drop;
        synchronized (JOURNAL_LOCK) {
            if (observerHeldStore == store
                    && observerHoldToken == token) {
                observerHeldStore = null;
                observerHoldToken = 0;
            }
            drop = journalInvalidationDeferred
                    && observerHeldStore == null;
            if (drop) {
                journalInvalidationDeferred = false;
            }
        }
        if (drop) {
            invalidateJournal();
        }
    }

    /** A constructed journal together with the identity it
     *  was built under. */
    public static final class JournalWithIdentity {
        public final JournalStore store;
        public final String deviceId;
        public final byte[] pubkey;
        /** The observer hold token minted for this hand-out
         *  (S-05), or 0 when no hold was registered (the
         *  non-observer read). The observer presents it when
         *  releasing the hold. */
        public final long holdToken;

        JournalWithIdentity(JournalStore store, String deviceId,
                            byte[] pubkey, long holdToken) {
            this.store = store;
            this.deviceId = deviceId;
            this.pubkey = pubkey;
            this.holdToken = holdToken;
        }
    }

    /**
     * The blocking journal read WITH its identity, taken
     * atomically (audit M5). The observer used to call
     * journal() and then read journalDeviceId() /
     * journalPubkey() as separate volatile reads; a transfer
     * operation landing between them invalidates the cache —
     * the fields null out, or a NEW construction's identity
     * lands beside the OLD store — and the observer then
     * bound a mismatched or missing identity and self-stopped
     * with REASON_IDENTITY, a stop the settings record can
     * make permanent. Here the latch-generation check and
     * all three reads happen inside the one lock.
     */
    public static JournalWithIdentity journalWithIdentity(
            Context context) {
        return journalWithIdentityInternal(context, false);
    }

    /**
     * The observer's variant (final check round 3, H-03):
     * identical to {@link #journalWithIdentity}, except the
     * store hold (observerAdoptedStore) is registered INSIDE
     * the final lock section, before the store is handed
     * out. Registering it as a separate call after the
     * return left a window in which a transfer operation's
     * invalidateJournal() saw no hold, dropped the cache,
     * and the next reader built a SECOND store over the same
     * file while the observer wrote through the first — the
     * G-06 two-store hazard re-entering through the gap
     * between obtaining the store and adopting it. Only the
     * observer calls this: it is the store's writer, and it
     * releases the hold in onDestroy.
     */
    public static JournalWithIdentity journalWithIdentityForObserver(
            Context context) {
        return journalWithIdentityInternal(context, true);
    }

    private static JournalWithIdentity journalWithIdentityInternal(
            Context context, boolean forObserver) {
        // One re-evaluation per call (see journal()): a soft
        // failure is dropped and rebuilt once; if the fresh
        // evaluation refuses the same way, that refusal is
        // served to THIS caller instead of spinning.
        boolean reevaluated = false;
        while (true) {
            prefetchJournal(context);
            final java.util.concurrent.CountDownLatch latch;
            synchronized (JOURNAL_LOCK) {
                latch = journalLatch;
            }
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while waiting for the journal", e);
            }
            synchronized (JOURNAL_LOCK) {
                if (latch != journalLatch) {
                    continue; // invalidated while waiting: retry
                }
                if (journalFailure != null) {
                    throw journalFailure;
                }
                if (journalSoftFailure != null) {
                    // Authorisation-dependent verdict: it was
                    // computed against settings that an enable
                    // command may since have updated. Drop it
                    // and rebuild — the loop re-enters
                    // prefetchJournal against the new latch and
                    // the gate is evaluated fresh. Bounded to
                    // one rebuild per call: a caller whose
                    // re-evaluation still refuses is served the
                    // refusal (initialisation classifies it as
                    // an authorisation wait, DepinGateVerdict),
                    // never an unbounded rebuild loop for an
                    // authorisation that is genuinely absent.
                    if (reevaluated) {
                        throw journalSoftFailure;
                    }
                    reevaluated = true;
                    journalSoftFailure = null;
                    journalInitStarted = false;
                    journalLatch =
                            new java.util.concurrent.CountDownLatch(1);
                    continue;
                }
                if (journal == null || journalDeviceId == null
                        || journalPubkey == null) {
                    throw new IllegalStateException(
                            "journal constructed without an identity");
                }
                long holdToken = 0;
                if (forObserver) {
                    // H-03: the hold is registered in the same
                    // lock section that hands the store out —
                    // no window for an invalidation to slip
                    // between obtaining and adopting.
                    // S-05: the hold is token-keyed; the token
                    // rides the hand-out so only this adopter
                    // can release it.
                    observerHeldStore = journal;
                    observerHoldToken = ++observerHoldSequence;
                    holdToken = observerHoldToken;
                }
                return new JournalWithIdentity(journal,
                        journalDeviceId, journalPubkey.clone(),
                        holdToken);
            }
        }
    }

    /** The device id the constructed store was built under,
     *  or null while no store is constructed. */
    public static String journalDeviceId() {
        return journalDeviceId;
    }

    /** A copy of the constructed store identity's public key,
     *  or null while no store is constructed. */
    public static byte[] journalPubkey() {
        byte[] p = journalPubkey;
        return p == null ? null : p.clone();
    }

    /** True once construction has finished, successfully or not. */
    public static boolean journalReady() {
        return journalLatch.getCount() == 0;
    }

    /** The constructed store, or null while preparation is in
     *  flight (or after a failure — distinguish via
     *  journalFailure() / CompanionStatus). For the UI thread,
     *  which must never block on history-length work. */
    public static JournalStore journalIfReady() {
        return journal;
    }

    /** The construction failure every blocking caller receives,
     *  or null when construction succeeded or is still running.
     *  Authorisation-dependent failures are NOT reported here:
     *  they are transient verdicts, not construction failures
     *  (see journalSoftFailure). */
    public static IllegalStateException journalFailure() {
        return journalFailure;
    }

    /** The latest authorisation-dependent construction
     *  failure (DepinGateVerdict), or null. The blocking
     *  readers drop it and re-evaluate once per call rather
     *  than serving it as a cached failure; a read whose
     *  re-evaluation refuses the same way is served it, so
     *  this describes the most recent evaluation, which a
     *  later read may already have superseded. */
    public static IllegalStateException journalSoftFailure() {
        return journalSoftFailure;
    }

    /** The writer-gate reason the latest completed
     *  construction failed with, or null when it did not fail
     *  at the gate (or has not failed / has since succeeded).
     *  Initialisation classifies its outcome from this —
     *  structured state, not message text. */
    public static String journalGateReason() {
        return journalGateReason;
    }

    /** The store for background callers: starts construction if
     *  needed and waits for it. Throws the cached failure, as
     *  before — no retry loop, no repeated logging. If the
     *  cache is invalidated while waiting (a transfer operation
     *  landed mid-wait), the wait restarts against the new
     *  construction generation instead of reading a dropped
     *  result. */
    public static JournalStore journal(Context context) {
        // One re-evaluation per call, as in
        // journalWithIdentity(): a soft (authorisation-
        // dependent) failure is dropped and rebuilt once; if
        // the fresh evaluation refuses the same way, that
        // refusal is served to THIS caller.
        boolean reevaluated = false;
        while (true) {
            prefetchJournal(context);
            final java.util.concurrent.CountDownLatch latch;
            synchronized (JOURNAL_LOCK) {
                latch = journalLatch;
            }
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while waiting for the journal", e);
            }
            synchronized (JOURNAL_LOCK) {
                if (latch != journalLatch) {
                    continue; // invalidated while waiting: retry
                }
                if (journalFailure != null) {
                    throw journalFailure;
                }
                if (journalSoftFailure != null) {
                    // See journalWithIdentity: an
                    // authorisation-dependent verdict is
                    // dropped and re-evaluated once per call,
                    // then served to that caller if the fresh
                    // evaluation refuses the same way.
                    if (reevaluated) {
                        throw journalSoftFailure;
                    }
                    reevaluated = true;
                    journalSoftFailure = null;
                    journalInitStarted = false;
                    journalLatch =
                            new java.util.concurrent.CountDownLatch(1);
                    continue;
                }
                return journal;
            }
        }
    }

    /** A construction failure at the writer gate, carrying
     *  the gate's reason so the cache disposition
     *  (DepinGateVerdict) is decided from the verdict itself,
     *  not from message text. The message keeps the historical
     *  shape every existing reader and log already shows. */
    private static final class GateHeldException
            extends IllegalStateException {
        final String reason;
        final boolean authorizationDependent;

        GateHeldException(String reason) {
            super("journal held by the transfer state: " + reason);
            this.reason = reason;
            this.authorizationDependent =
                    DepinGateVerdict.isAuthorizationDependent(reason);
        }
    }

    private static void buildJournal(Context context,
            java.util.concurrent.CountDownLatch latch) {
        try {
            buildJournalOrThrow(context, latch);
            synchronized (JOURNAL_LOCK) {
                // A successful construction retires the previous
                // generation's gate bookkeeping (a soft failure
                // or reason describing a state this build has
                // superseded must not outlive it).
                if (latch == journalLatch) {
                    journalSoftFailure = null;
                    journalGateReason = null;
                }
            }
        } catch (IllegalStateException e) {
            synchronized (JOURNAL_LOCK) {
                // Publish the failure only if this construction
                // generation is still the current one; a result
                // superseded by an invalidation is dropped.
                if (latch == journalLatch) {
                    if (e instanceof GateHeldException) {
                        GateHeldException held =
                                (GateHeldException) e;
                        journalGateReason = held.reason;
                        if (held.authorizationDependent) {
                            // NOT the cached failure: blocking
                            // readers drop this and re-evaluate
                            // (DepinGateVerdict).
                            journalSoftFailure = e;
                        } else {
                            journalFailure = e;
                        }
                    } else {
                        journalGateReason = null;
                        journalFailure = e;
                    }
                }
            }
        } finally {
            latch.countDown();
        }
    }

    private static void buildJournalOrThrow(Context context,
            java.util.concurrent.CountDownLatch latch) {
        // A new construction supersedes the previous one's
        // status errors (audit M5): the fields are re-set by
        // THIS build's failure paths only, so a success after
        // a held build leaves no stale error behind.
        CompanionStatus.journalError = null;
        CompanionStatus.identityError = null;
        AndroidSeedVault vault = new AndroidSeedVault(context);
        final byte[] pubkey;
        try {
            // F-25 (final check rounds 1–3): the construction
            // reads the home identity CREATE-FREE first. The
            // old publicKey() call went through
            // loadOrCreateSeed() and MINTED a home seed
            // whenever the file was absent — resurrecting, as
            // a brand-new identity, a seed that had been
            // deliberately erased after its identity was sent
            // out (G-07). Minting stays legitimate in exactly
            // one state: a legacy install the transfer layer
            // has never touched (no init record, no traces),
            // where the observer's first construction IS the
            // identity's creation, as in step 2. Once the
            // transfer layer exists, an absent home seed is a
            // deliberate state (erased after a send-out, or
            // this device runs on a received identity) and
            // construction serves the writer resolution
            // without one — the RECEIVED branch below never
            // needed the shadow key.
            byte[] pk = vault.publicKeyIfPresent();
            if (pk == null) {
                java.io.File root = DepinTransferRuntime.storeRoot(context);
                boolean transferIntroduced =
                        app.quantusnode.node.depin.core.TransferState
                                .loadInit(root) != null
                        || app.quantusnode.node.depin.core.TransferInit
                                .hasTransferTraces(root);
                if (!transferIntroduced) {
                    pk = vault.publicKey();
                }
            }
            pubkey = pk;
        } catch (Exception e) {
            // The identity stage failed, not the journal: mirror
            // it into the identity-error channel so the UI names
            // the real cause.
            CompanionStatus.identityError =
                    e.getClass().getSimpleName() + ": " + e.getMessage();
            throw new IllegalStateException(
                    "identity unavailable for journal authentication", e);
        } finally {
            // The journal only needs the public key long-term.
            // lock() is the vault's wipe hook; the vault holds no
            // plaintext cache for it to wipe.
            vault.lock();
        }
        // Section 3.2 writer resolution (integration design,
        // build step 4): transfer state → active binding →
        // writer construction, in that order. Without a
        // transfer initialisation the resolution is SHADOW and
        // everything below is exactly the step-2 behaviour.
        // A null shadow id (no home seed in a transfer-
        // introduced store, F-25) simply never matches the
        // gate's writer: the resolution then serves RECEIVED
        // or HELD, never a shadow that has no seed to sign
        // with.
        String shadowDeviceId = pubkey == null ? null
                : DepinCore.deviceIdFromPubkey(pubkey);
        DepinTransferRuntime.WriterResolution resolution =
                DepinTransferRuntime.resolveWriter(context, shadowDeviceId);
        if (resolution.kind == DepinTransferRuntime.WriterResolution.HELD) {
            CompanionStatus.journalError =
                    "transfer gate: " + resolution.reason;
            throw new GateHeldException(resolution.reason);
        }
        final byte[] effectivePubkey;
        JournalStore.EntrySigner signer;
        if (resolution.kind
                == DepinTransferRuntime.WriterResolution.RECEIVED) {
            // The binding names a transferred-in identity: its
            // seed is in the transfer vault, and the journal is
            // built under THAT identity (the shadow vault is
            // not consulted for signing at all).
            final String vaultKey = app.quantusnode.node.depin.core
                    .TransferVault.receiverKey(resolution.generation);
            final byte[] receivedSeed;
            try {
                receivedSeed = DepinTransferRuntime.vault(context)
                        .get(vaultKey);
            } catch (Exception e) {
                CompanionStatus.identityError =
                        e.getClass().getSimpleName() + ": " + e.getMessage();
                throw new IllegalStateException(
                        "transferred identity unavailable", e);
            }
            if (receivedSeed == null) {
                CompanionStatus.journalError =
                        "transfer gate: received identity seed missing";
                throw new IllegalStateException(
                        "transferred identity seed missing from the "
                                + "transfer vault");
            }
            try {
                effectivePubkey =
                        app.quantusnode.node.depin.core.Ed25519
                                .publickeyFromSeed(receivedSeed);
                // Audit M12 (point-of-use proof, companion
                // form): the resolution scoped this copy to
                // the binding's generation, but the transfer
                // vault files copies under bare generation
                // numbers shared by every identity in the
                // store (audit F10) — existence at the
                // generation is not identity. The loaded
                // copy must derive the resolved identity
                // from its own bytes, or construction fails
                // closed instead of building the journal
                // under a lineage the binding never named.
                if (!app.quantusnode.node.depin.core
                        .TransferRemediation.seedDerivesIdentity(
                                receivedSeed, resolution.deviceId)) {
                    CompanionStatus.journalError =
                            "transfer gate: the received copy "
                                    + "does not derive the "
                                    + "binding identity";
                    throw new IllegalStateException(
                            "received copy does not derive the "
                                    + "binding identity");
                }
            } finally {
                Arrays.fill(receivedSeed, (byte) 0);
            }
            final byte[] expectedPubkey = effectivePubkey;
            signer = (checkpointHash, seq) -> {
                final byte[] seed;
                try {
                    seed = DepinTransferRuntime.vault(context)
                            .get(vaultKey);
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "transferred identity unavailable for "
                                    + "journal signing", e);
                }
                if (seed == null) {
                    throw new IllegalStateException(
                            "transferred identity seed missing");
                }
                try {
                    // Audit L15: the construction-time proof
                    // above covered the bytes loaded THEN;
                    // the signer re-reads the seed on every
                    // checkpoint, and the re-read is proved
                    // against the journal's own public key
                    // before it signs — a copy whose lineage
                    // changed under its generation (audit
                    // F10) fails closed here instead of
                    // writing an entry the journal's verifier
                    // would reject.
                    app.quantusnode.node.depin.core
                            .TransferRemediation.checkSigningSeed(
                                    seed, expectedPubkey);
                    return DepinCore.journalEntrySig(
                            seed, checkpointHash, seq);
                } finally {
                    Arrays.fill(seed, (byte) 0);
                }
            };
        } else {
            if (pubkey == null) {
                // The gate resolved SHADOW (or fell through)
                // but there is no home seed to sign with —
                // reachable only in a transfer-introduced
                // store whose home seed is absent (F-25).
                // Fail closed rather than mint one here.
                CompanionStatus.identityError =
                        "home identity seed absent in a "
                                + "transfer-introduced store";
                throw new IllegalStateException(
                        "home identity seed absent; refusing to "
                                + "mint one during journal construction");
            }
            effectivePubkey = pubkey;
            signer = (checkpointHash, seq) -> {
                final byte[] seed;
                try {
                    seed = vault.loadOrCreateSeed();
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "identity unavailable for journal signing", e);
                }
                try {
                    // Audit L21: the same signing-time proof
                    // as the RECEIVED branch (audit L15). This
                    // branch serves not only the no-transfer
                    // legacy case but also a SHADOW resolution
                    // (the gate names the shadow identity
                    // itself), and its signer re-reads through
                    // loadOrCreateSeed — a call that, absent
                    // the check, could CREATE a fresh seed
                    // under the journal and sign with it. The
                    // re-read is now proved against the
                    // construction-time public key before it
                    // signs; a mismatch fails closed. There is
                    // no transfer vault on this branch, so no
                    // F10-style collision is being invited —
                    // the check simply removes the need for
                    // the legacy exclusion L15 relied on.
                    app.quantusnode.node.depin.core
                            .TransferRemediation.checkSigningSeed(
                                    seed, pubkey);
                    return DepinCore.journalEntrySig(seed, checkpointHash, seq);
                } finally {
                    // Zero the working copy: the seed is
                    // re-unwrapped per append (one checkpoint per
                    // 600 s slot, so the cost is irrelevant)
                    // instead of resting in memory.
                    Arrays.fill(seed, (byte) 0);
                    vault.lock();
                }
            };
        }
        JournalStore.EntryVerifier verifier =
                (checkpointHash, seq, sig) -> DepinCore.verifyJournalEntrySig(
                        effectivePubkey, checkpointHash, seq, sig);
        try {
            JournalStore built = new JournalStore(
                    DepinFiles.journalFile(context),
                    DepinCore.deviceIdFromPubkey(effectivePubkey),
                    Hex.encode(effectivePubkey),
                    signer, verifier);
            // Design section 3.4 free-space floor (H-11): the
            // production store withholds appends rather than
            // write the device out of space for the node.
            built.setFreeFloorBytes(
                    app.quantusnode.node.depin.core.TransferStore
                            .FREE_FLOOR_BYTES);
            synchronized (JOURNAL_LOCK) {
                // Publish only if this construction generation
                // is still current (see buildJournal).
                if (latch == journalLatch) {
                    journal = built;
                    journalDeviceId =
                            DepinCore.deviceIdFromPubkey(effectivePubkey);
                    journalPubkey = effectivePubkey.clone();
                }
            }
        } catch (Throwable t) {
            CompanionStatus.journalError =
                    t.getClass().getSimpleName() + ": " + t.getMessage();
            recordError(context, "journal_open", t);
            throw new IllegalStateException(
                    "journal unavailable: "
                            + t.getClass().getSimpleName()
                            + ": " + t.getMessage(), t);
        }
    }

    // ---------------- persistent error log ----------------

    /** Record a failure durably; never throws. */
    public static void recordError(Context context, String kind, Throwable t) {
        try {
            CrashLog.recordAt(DepinFiles.crashLogFile(context), kind, t);
        } catch (Throwable ignored) {
            // Diagnostics must never take the app down with them.
        }
    }

    /** Full persisted error log text, or null when empty. */
    public static String errorLog(Context context) {
        try {
            return CrashLog.readAllAt(DepinFiles.crashLogFile(context));
        } catch (Throwable ignored) {
            // Reading diagnostics must never take the app down
            // either; an unreadable log reads as an empty one.
            return null;
        }
    }

    /** The most recent persisted error entry, or null.
     *  Residual F-18 (the companion has done this since
     *  audit L23): the superseded-copy sweep's entries are
     *  excluded — neither its action records
     *  ("superseded_sweep") nor its steady-state notes
     *  ("superseded_sweep_steady") is an error, yet the
     *  kind-blind last entry made whichever the sweep
     *  recorded most recently the displayed "last recorded
     *  error", covering the most recent real failure. Both
     *  kinds stay in the log in full; they are simply not
     *  errors for this summary slot. */
    public static String lastErrorEntry(Context context) {
        return CrashLog.lastEntryExcludingKinds(
                errorLog(context),
                "superseded_sweep", "superseded_sweep_steady");
    }
}
