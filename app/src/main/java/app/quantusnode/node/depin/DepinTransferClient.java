package app.quantusnode.node.depin;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

/**
 * The main process's client for device-transfer operations
 * (audit H-1): the counterpart of DepinModule's bundle-export
 * facade, for DepinTransferService in the :depin process.
 *
 * The main process never touches the transfer machinery
 * itself — no seed, no vault, no sender/receiver machine.
 * It stages the file the user picked at
 * DepinFiles.transferInputFile, sends one MSG_TRANSFER_OP,
 * and receives the outcome asynchronously; produced messages
 * are read back from DepinFiles.transferOutputFile. The seed
 * boundary is the process boundary, exactly as the design's
 * section 2 / 3.1 draw it.
 *
 * Discipline, mirroring DepinModule.exportBundle:
 * - Binding DOES auto-create here (unlike the observer
 *   binding): the transfer service has no launch-claim
 *   lifecycle of its own to protect — it exists purely to
 *   serve these requests, and the first request of a session
 *   is what brings it up. A request made before the
 *   connection lands is held and sent on connect.
 * - Only one operation may be in flight. The UI already
 *   serialises its requests (audit H-3); a second request
 *   while one is pending is refused immediately with
 *   T_RESULT_ERROR instead of queueing over it.
 * - If the service connection dies with an operation pending
 *   (the F-13 case: the :depin process was killed mid-
 *   operation), the pending callback is failed with
 *   RESULT_UNAVAILABLE rather than never firing. The
 *   operation's durable state is on disk in the transfer
 *   store; re-issuing after reconnect is the recovery path,
 *   as it was in-process.
 *
 * Callbacks arrive on the client's own reply thread; the
 * caller marshals to the UI thread itself.
 */
public final class DepinTransferClient {

    /** Client-level outcome when no live transfer service can
     *  run the operation (never bound, or the connection died
     *  with the operation pending). Mirrors
     *  DepinModule.BUNDLE_NOT_RUNNING's placement. */
    public static final int RESULT_UNAVAILABLE = 3;

    /** Invoked once per accepted request, on the client's
     *  reply thread. result is a DepinIpc.T_RESULT_* value or
     *  RESULT_UNAVAILABLE; data carries the reply payload
     *  (empty for RESULT_UNAVAILABLE). */
    public interface Callback {
        void onTransferResult(int op, int result, Bundle data);
    }

    private static DepinTransferClient instance;

    private final Context appContext;
    private final Messenger replyMessenger;

    private volatile Messenger serviceMessenger;
    private volatile boolean alive = false;
    private volatile boolean bindRequested = false;

    /** The single in-flight request, if any. Guarded by
     *  synchronisation on this client (requests are issued
     *  from the UI's serialised executor, replies land on the
     *  reply thread). */
    private Pending pending;

    private static final class Pending {
        final int op;
        final Bundle params;
        final Callback callback;

        Pending(int op, Bundle params, Callback callback) {
            this.op = op;
            this.params = params;
            this.callback = callback;
        }
    }

    public static synchronized DepinTransferClient get(
            Context context) {
        if (instance == null) {
            instance = new DepinTransferClient(context);
        }
        return instance;
    }

    private DepinTransferClient(Context context) {
        this.appContext = context.getApplicationContext();
        HandlerThread replyThread =
                new HandlerThread("depin-transfer-reply");
        replyThread.start();
        Handler replyHandler = new Handler(replyThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == DepinIpc.MSG_TRANSFER_RESULT
                        && msg.getData() != null) {
                    Bundle data = msg.getData();
                    int op = data.getInt(DepinIpc.KEY_T_OP, -1);
                    int result = data.getInt(DepinIpc.KEY_T_RESULT,
                            DepinIpc.T_RESULT_ERROR);
                    Pending p = takePending(op);
                    if (p != null) {
                        p.callback.onTransferResult(op, result, data);
                    }
                    // A reply with no matching pending request is
                    // a stale delivery (the caller was already
                    // failed by a disconnect); it changes nothing.
                } else {
                    super.handleMessage(msg);
                }
            }
        };
        this.replyMessenger = new Messenger(replyHandler);
    }

    // ---------------- public surface ----------------

    /**
     * Run one transfer operation in the :depin process. The
     * callback fires exactly once: with the service's reply,
     * with an immediate T_RESULT_ERROR when another operation
     * is already in flight, or with RESULT_UNAVAILABLE when
     * the connection dies first.
     */
    public void request(int op, Bundle params, Callback callback) {
        Pending p = new Pending(op,
                params != null ? params : new Bundle(), callback);
        synchronized (this) {
            if (pending != null) {
                Bundle data = new Bundle();
                data.putInt(DepinIpc.KEY_T_OP, op);
                data.putInt(DepinIpc.KEY_T_RESULT,
                        DepinIpc.T_RESULT_ERROR);
                data.putString(DepinIpc.KEY_T_ERROR_CLASS,
                        "IllegalStateException");
                data.putString(DepinIpc.KEY_T_ERROR,
                        "a transfer operation is already in progress");
                callback.onTransferResult(
                        op, DepinIpc.T_RESULT_ERROR, data);
                return;
            }
            pending = p;
        }
        ensureBound();
        sendPending();
    }

    // ---------------- internals ----------------

    private synchronized Pending takePending(int op) {
        Pending p = pending;
        if (p != null && p.op == op) {
            pending = null;
            return p;
        }
        return null;
    }

    private void failPendingUnavailable() {
        Pending p;
        synchronized (this) {
            p = pending;
            pending = null;
        }
        if (p != null) {
            p.callback.onTransferResult(
                    p.op, RESULT_UNAVAILABLE, new Bundle());
        }
    }

    /** Send the held request if the connection is up; called
     *  on request and on connect. */
    private void sendPending() {
        Pending p;
        synchronized (this) {
            p = pending;
        }
        if (p == null) {
            return;
        }
        Messenger svc = serviceMessenger;
        if (!alive || svc == null) {
            return; // held until onServiceConnected
        }
        Message msg = Message.obtain(null, DepinIpc.MSG_TRANSFER_OP);
        Bundle data = new Bundle(p.params);
        data.putInt(DepinIpc.KEY_T_OP, p.op);
        msg.setData(data);
        msg.replyTo = replyMessenger;
        try {
            svc.send(msg);
        } catch (RemoteException e) {
            // Dead binder: same conclusion as a disconnect.
            serviceMessenger = null;
            alive = false;
            bindRequested = false;
            failPendingUnavailable();
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name,
                                       IBinder binder) {
            serviceMessenger = new Messenger(binder);
            alive = true;
            sendPending();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceMessenger = null;
            alive = false;
            bindRequested = false;
            failPendingUnavailable();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            serviceMessenger = null;
            alive = false;
            bindRequested = false;
            failPendingUnavailable();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            serviceMessenger = null;
            alive = false;
            bindRequested = false;
            failPendingUnavailable();
        }
    };

    /** Bind to the transfer service, auto-creating it: this
     *  service exists only to serve these requests, so the
     *  binding is its legitimate lifecycle (contrast the
     *  observer binding in DepinModule, which must never be
     *  what brings the observer into existence). */
    private void ensureBound() {
        if (alive || bindRequested) {
            return;
        }
        Intent intent = new Intent(appContext,
                DepinTransferService.class);
        try {
            if (appContext.bindService(intent, connection,
                    Context.BIND_AUTO_CREATE)) {
                bindRequested = true;
            } else {
                failPendingUnavailable();
            }
        } catch (Throwable t) {
            failPendingUnavailable();
        }
    }
}
