package org.telegram.messenger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.telegram.messenger.NotificationStickerMediaPolicy.State;
import org.telegram.messenger.NotificationStickerPrepBridge.AfterDownload;

/**
 * Exercises the production prep-after-download bridge and generation-bound rebuild
 * dispatcher. These are the same classes NotificationStickerImages uses; this is not
 * a parallel model.
 */
public final class NotificationStickerCallbackTest {
    private static int assertions;

    private static final class ImmediateQueue {
        final List<Runnable> pending = new ArrayList<>();
        void post(Runnable run) { pending.add(run); }
        void drain() {
            while (!pending.isEmpty()) {
                pending.remove(0).run();
            }
        }
    }

    public static void main(String[] args) {
        downloadInFirstWindowStartsDecode();
        downloadInRetryWindowStartsDecode();
        expiredSessionDoesNotStartDecode();
        cancelledOrIneligibleIgnored();
        staticCompleteCollectsWithoutDecode();
        openInvalidatesQueuedRebuild();
        newGenerationNotPollutedByStaleCallback();
        otherDialogUnaffected();
        validQueuedRebuildStillRuns();
        guardRejectsOpenedEvenIfListed();
        System.out.println("OK: " + assertions + " callback/dispatch assertions passed");
    }

    private static void check(String name, boolean value) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }

    // t0 download starts, t2 file arrives while WAITING: start decode immediately, do not wait for t3.
    private static void downloadInFirstWindowStartsDecode() {
        AfterDownload action = NotificationStickerPrepBridge.afterDownload(
                State.WAITING, false, true, true);
        check("WAITING + document ready starts decode in first window", action == AfterDownload.PREPARE_DECODE);
    }

    // t3 text+retry, t4 fileLoaded still without cover: decode in the existing retry window.
    private static void downloadInRetryWindowStartsDecode() {
        AfterDownload action = NotificationStickerPrepBridge.afterDownload(
                State.POSTED_TEXT_RETRYING, false, true, true);
        check("RETRYING + document ready starts decode in second window", action == AfterDownload.PREPARE_DECODE);
    }

    // t6 retry completed: do not start notification-side decode or extend the window.
    private static void expiredSessionDoesNotStartDecode() {
        check("COMPLETED does not start decode",
                NotificationStickerPrepBridge.afterDownload(State.COMPLETED, false, true, true) == AfterDownload.IGNORE);
        check("CANCELLED does not start decode",
                NotificationStickerPrepBridge.afterDownload(State.CANCELLED, false, true, true) == AfterDownload.IGNORE);
        check("NONE does not start decode",
                NotificationStickerPrepBridge.afterDownload(State.NONE, false, true, true) == AfterDownload.IGNORE);
    }

    private static void cancelledOrIneligibleIgnored() {
        check("ineligible WAITING ignored",
                NotificationStickerPrepBridge.afterDownload(State.WAITING, false, true, false) == AfterDownload.IGNORE);
        check("retry without document or cover waits",
                NotificationStickerPrepBridge.afterDownload(State.POSTED_TEXT_RETRYING, false, false, true) == AfterDownload.IGNORE);
    }

    private static void staticCompleteCollectsWithoutDecode() {
        check("static/complete WAITING collects only",
                NotificationStickerPrepBridge.afterDownload(State.WAITING, true, false, true) == AfterDownload.COLLECT_ONLY);
        check("complete retry collects only for silent fill",
                NotificationStickerPrepBridge.afterDownload(State.POSTED_TEXT_RETRYING, true, false, true) == AfterDownload.COLLECT_ONLY);
        check("complete does not request decode",
                NotificationStickerPrepBridge.afterDownload(State.WAITING, true, true, true) == AfterDownload.COLLECT_ONLY);
    }

    // B queues A; then A is opened (invalidate) before the closure runs.
    private static void openInvalidatesQueuedRebuild() {
        ImmediateQueue queue = new ImmediateQueue();
        NotificationStickerRebuildDispatch dispatch = new NotificationStickerRebuildDispatch(queue::post);
        int[] runs = {0};
        dispatch.enqueue(10, 2, true, ticket -> {
            if (NotificationStickerTargetGuard.shouldPublish(ticket.dialogId, 10, ticket.getMessageIds(), Set.of(2))) runs[0]++;
            else runs[0] += 100;
        });
        check("closure is queued not run inline", queue.pending.size() == 1 && runs[0] == 0);
        dispatch.invalidate(10); // setOpenedDialogId / cancelDialog
        long opened = 10;
        queue.drain();
        check("opened chat does not run stale publish", runs[0] == 0);
        check("generation advanced", dispatch.currentGeneration(10) == 1);
        check("guard would also reject if generation were skipped",
                !NotificationStickerTargetGuard.shouldPublish(10, opened, Set.of(2), Set.of(2)));
    }

    private static void newGenerationNotPollutedByStaleCallback() {
        ImmediateQueue queue = new ImmediateQueue();
        NotificationStickerRebuildDispatch dispatch = new NotificationStickerRebuildDispatch(queue::post);
        int[] runs = {0};
        dispatch.enqueue(10, 2, true, ticket -> { if (NotificationStickerTargetGuard.shouldSound(ticket.alertsByMessage.getOrDefault(2, false), ticket.alertEpoch, dispatch.currentAlertEpoch(10))) { runs[0] += 1; } });
        Runnable stale = queue.pending.get(0);
        dispatch.invalidate(10);
        dispatch.enqueue(10, 3, false, ticket -> { runs[0] += NotificationStickerTargetGuard.shouldSound(ticket.alertsByMessage.getOrDefault(3, false), ticket.alertEpoch, dispatch.currentAlertEpoch(10)) ? 100 : 10; });
        stale.run(); // already dequeued old generation
        check("stale same-dialog callback does not run", runs[0] == 0);
        queue.drain();
        check("new generation silent fill runs alone", runs[0] == 10);
    }

    private static void otherDialogUnaffected() {
        ImmediateQueue queue = new ImmediateQueue();
        NotificationStickerRebuildDispatch dispatch = new NotificationStickerRebuildDispatch(queue::post);
        int[] runs = {0};
        dispatch.enqueue(10, 2, true, ticket -> { runs[0] += 1; });
        dispatch.enqueue(20, 4, true, ticket -> { runs[0] += 10; });
        dispatch.invalidate(10);
        queue.drain();
        check("invalidating A does not drop B", runs[0] == 10);
    }

    private static void validQueuedRebuildStillRuns() {
        ImmediateQueue queue = new ImmediateQueue();
        NotificationStickerRebuildDispatch dispatch = new NotificationStickerRebuildDispatch(queue::post);
        int[] runs = {0};
        dispatch.enqueue(10, 2, true, ticket -> { runs[0]++; });
        dispatch.invalidateAll(); // logout of another account would be a different Images instance; this is same account
        // After invalidateAll, the queued ticket must not run.
        queue.drain();
        check("account invalidation suppresses queued rebuilds", runs[0] == 0);
        dispatch.enqueue(10, 2, true, ticket -> {
            if (NotificationStickerTargetGuard.shouldPublish(ticket.dialogId, 0, ticket.getMessageIds(), Set.of(2))) runs[0]++;
        });
        queue.drain();
        check("a later valid enqueue still publishes", runs[0] == 1);
    }

    private static void guardRejectsOpenedEvenIfListed() {
        check("listed but opened is not publishable",
                !NotificationStickerTargetGuard.shouldPublish(10, 10, Set.of(1), Set.of(1)));
        check("listed and not opened is publishable",
                NotificationStickerTargetGuard.shouldPublish(10, 0, Set.of(1), Set.of(1)));
        check("missing or ineligible message is not publishable",
                !NotificationStickerTargetGuard.shouldPublish(10, 0, Set.of(1), Set.of()));
        check("logout/empty equivalent is not publishable",
                !NotificationStickerTargetGuard.shouldPublish(10, 20, Set.of(1), Set.of()));
    }
}
