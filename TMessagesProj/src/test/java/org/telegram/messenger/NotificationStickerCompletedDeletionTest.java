package org.telegram.messenger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.telegram.messenger.NotificationStickerMediaPolicy.Key;
import org.telegram.messenger.NotificationStickerMediaPolicy.State;

/**
 * Drives the production policy + dispatch + guard together for the
 * "ready then delete the original sticker" race. This is not an end-to-end
 * NotificationsController test.
 */
public final class NotificationStickerCompletedDeletionTest {
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
        readyThenDeleteDoesNotAlertLeftoverText();
        syncCoversCompletedQueuedWork();
        newGenerationStillPublishes();
        siblingSurvivesWithOwnAlert();
        deletedAlertCannotDonateToSiblingWithoutAlert();
        laterRealAlertSuppressesQueuedSound();
        pendingTicketsAreCleared();
        leftoverTextIsNotALiveTarget();
        System.out.println("OK: " + assertions + " completed-deletion assertions passed");
    }

    private static void check(String name, boolean value) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }

    /** Same sequence as the independent CompletedDeletionProbe, expected to stay silent after the fix. */
    private static void readyThenDeleteDoesNotAlertLeftoverText() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key sticker = new Key(0, 10, 2);
        var prep = policy.onBuild(sticker, false, true);
        var ready = policy.onMediaReady(sticker, prep.token);
        check("ready transitions to COMPLETED", policy.stateOf(sticker) == State.COMPLETED);
        check("waiting/retrying is empty after ready", policy.waitingOrRetryingKeys(0).isEmpty());
        check("trackedKeys still lists the completed session", policy.trackedKeys(0).contains(sticker));

        ImmediateQueue queue = new ImmediateQueue();
        NotificationStickerRebuildDispatch dispatch = new NotificationStickerRebuildDispatch(queue::post);
        int[] published = {0};
        int[] sounded = {0};
        dispatch.enqueue(10, 2, ready.notifyAboutLast, ticket -> {
            // Leftover unread text mid=1 is still present and eligible.
            Set<Integer> live = liveAmong(ticket.getMessageIds(), Set.of(1));
            if (NotificationStickerTargetGuard.shouldPublish(ticket.dialogId, 0, ticket.getMessageIds(), live)) {
                published[0]++;
                if (NotificationStickerTargetGuard.shouldSound(
                        NotificationStickerTargetGuard.survivingAlert(ticket.alertsByMessage, live),
                        ticket.alertEpoch,
                        dispatch.currentAlertEpoch(10))) {
                    sounded[0]++;
                }
            }
        });
        check("rebuild is queued, not inline", queue.pending.size() == 1 && published[0] == 0);

        // Images.sync / Controller delete: cancel the completed key and drop it from the ticket.
        Set<Key> present = Set.of(); // sticker mid=2 is gone; leftover text is not a tracked key
        syncLikeImages(policy, dispatch, present);
        check("completed key cancelled after delete", policy.stateOf(sticker) == State.CANCELLED);
        check("pending ticket dropped when its only message is deleted", dispatch.pendingCount() == 0);

        queue.drain();
        check("stale alert action does not publish onto leftover text", published[0] == 0 && sounded[0] == 0);
    }

    private static void syncCoversCompletedQueuedWork() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key sticker = new Key(0, 10, 2);
        var token = policy.onBuild(sticker, false, true).token;
        policy.onMediaReady(sticker, token);
        ImmediateQueue queue = new ImmediateQueue();
        NotificationStickerRebuildDispatch dispatch = new NotificationStickerRebuildDispatch(queue::post);
        int[] runs = {0};
        dispatch.enqueue(10, 2, true, ticket -> runs[0]++);
        check("waitingOrRetrying would miss this session", policy.waitingOrRetryingKeys(0).isEmpty());
        syncLikeImages(policy, dispatch, Set.of());
        queue.drain();
        check("sync of completed keys drops the queued callback", runs[0] == 0 && dispatch.pendingCount() == 0);
    }

    private static void newGenerationStillPublishes() {
        ImmediateQueue queue = new ImmediateQueue();
        NotificationStickerRebuildDispatch dispatch = new NotificationStickerRebuildDispatch(queue::post);
        int[] runs = {0};
        dispatch.enqueue(10, 2, true, ticket -> runs[0] += 1);
        dispatch.invalidateMessage(10, 2);
        dispatch.enqueue(10, 5, true, ticket -> {
            Set<Integer> live = liveAmong(ticket.getMessageIds(), Set.of(5));
            if (NotificationStickerTargetGuard.shouldPublish(10, 0, ticket.getMessageIds(), live)
                    && NotificationStickerTargetGuard.shouldSound(true, ticket.alertEpoch, dispatch.currentAlertEpoch(10))) {
                runs[0] += 10;
            }
        });
        queue.drain();
        check("new message generation still publishes after the deleted sticker ticket is dropped", runs[0] == 10);
    }

    private static void siblingSurvivesWithOwnAlert() {
        ImmediateQueue queue = new ImmediateQueue();
        NotificationStickerRebuildDispatch dispatch = new NotificationStickerRebuildDispatch(queue::post);
        int[] sounded = {0};
        int[] publishedMid = {0};
        Map<Integer, Boolean> alerts = new LinkedHashMap<>();
        alerts.put(2, true);
        alerts.put(3, true);
        dispatch.enqueue(10, alerts, ticket -> {
            Set<Integer> live = liveAmong(ticket.getMessageIds(), Set.of(3)); // mid 2 deleted, 3 still present
            if (NotificationStickerTargetGuard.shouldPublish(10, 0, ticket.getMessageIds(), live)) {
                publishedMid[0] = live.iterator().next();
                if (NotificationStickerTargetGuard.shouldSound(
                        NotificationStickerTargetGuard.survivingAlert(ticket.alertsByMessage, live),
                        ticket.alertEpoch,
                        dispatch.currentAlertEpoch(10))) {
                    sounded[0]++;
                }
            }
        });
        dispatch.invalidateMessage(10, 2);
        check("sibling remains pending after one message is deleted", dispatch.pendingCount() == 1);
        queue.drain();
        check("surviving sibling keeps its own alert", sounded[0] == 1 && publishedMid[0] == 3);
        check("ticket cleaned after run", dispatch.pendingCount() == 0);
    }

    private static void deletedAlertCannotDonateToSiblingWithoutAlert() {
        ImmediateQueue queue = new ImmediateQueue();
        NotificationStickerRebuildDispatch dispatch = new NotificationStickerRebuildDispatch(queue::post);
        int[] sounded = {0};
        int[] published = {0};
        Map<Integer, Boolean> alerts = new LinkedHashMap<>();
        alerts.put(2, true);  // original alerting sticker, later deleted
        alerts.put(3, false); // silent fill of another ready message
        dispatch.enqueue(10, alerts, ticket -> {
            Set<Integer> live = liveAmong(ticket.getMessageIds(), Set.of(3));
            if (NotificationStickerTargetGuard.shouldPublish(10, 0, ticket.getMessageIds(), live)) {
                published[0]++;
                if (NotificationStickerTargetGuard.shouldSound(
                        NotificationStickerTargetGuard.survivingAlert(ticket.alertsByMessage, live),
                        ticket.alertEpoch,
                        dispatch.currentAlertEpoch(10))) {
                    sounded[0]++;
                }
            }
        });
        dispatch.invalidateMessage(10, 2);
        queue.drain();
        check("surviving silent sibling may still fill", published[0] == 1);
        check("deleted alerting message cannot donate sound to the sibling", sounded[0] == 0);
    }

    private static void laterRealAlertSuppressesQueuedSound() {
        ImmediateQueue queue = new ImmediateQueue();
        NotificationStickerRebuildDispatch dispatch = new NotificationStickerRebuildDispatch(queue::post);
        int[] sounded = {0};
        int[] published = {0};
        dispatch.enqueue(10, 2, true, ticket -> {
            Set<Integer> live = liveAmong(ticket.getMessageIds(), Set.of(2));
            if (NotificationStickerTargetGuard.shouldPublish(10, 0, ticket.getMessageIds(), live)) {
                published[0]++;
                if (NotificationStickerTargetGuard.shouldSound(
                        NotificationStickerTargetGuard.survivingAlert(ticket.alertsByMessage, live),
                        ticket.alertEpoch,
                        dispatch.currentAlertEpoch(10))) {
                    sounded[0]++;
                }
            }
        });
        // A real new message in the same dialog already posted an alert.
        dispatch.bumpAlertEpoch(10);
        queue.drain();
        check("original message can still be filled", published[0] == 1);
        check("old callback does not re-alert after a newer dialog alert", sounded[0] == 0);
    }

    private static void pendingTicketsAreCleared() {
        ImmediateQueue queue = new ImmediateQueue();
        NotificationStickerRebuildDispatch dispatch = new NotificationStickerRebuildDispatch(queue::post);
        dispatch.enqueue(10, 2, true, ticket -> {});
        dispatch.enqueue(10, 3, false, ticket -> {});
        check("two tickets are tracked", dispatch.pendingCount() == 2);
        dispatch.invalidateMessage(10, 2);
        check("deleting one message drops only its ticket", dispatch.pendingCount() == 1);
        dispatch.invalidate(10);
        check("dialog cancel clears remaining tickets", dispatch.pendingCount() == 0);
        dispatch.enqueue(20, 4, true, ticket -> {});
        dispatch.invalidateAll();
        check("account cancel clears tickets", dispatch.pendingCount() == 0);
        queue.drain();
        check("draining cancelled work does not resurrect tickets", dispatch.pendingCount() == 0);
    }

    private static void leftoverTextIsNotALiveTarget() {
        check("guard rejects when only an unrelated leftover id is present",
                !NotificationStickerTargetGuard.shouldPublish(10, 0, Set.of(2), Set.of(1)));
        check("guard accepts the original id when it is still present",
                NotificationStickerTargetGuard.shouldPublish(10, 0, Set.of(2), Set.of(2, 1)));
        check("survivingAlert ignores leftover text that was never in the ticket",
                !NotificationStickerTargetGuard.survivingAlert(Map.of(2, true), Set.of(1)));
        check("survivingAlert is true only for a live original alerting id",
                NotificationStickerTargetGuard.survivingAlert(Map.of(2, true), Set.of(2, 1)));
    }

    private static void syncLikeImages(
            NotificationStickerMediaPolicy policy,
            NotificationStickerRebuildDispatch dispatch,
            Set<Key> present) {
        for (Key key : new ArrayList<>(policy.trackedKeys(0))) {
            if (!present.contains(key)) {
                policy.cancelKey(key);
                dispatch.invalidateMessage(key.dialogId, key.messageId);
            }
        }
    }

    private static Set<Integer> liveAmong(Set<Integer> requested, Set<Integer> presentEligible) {
        Set<Integer> live = new HashSet<>();
        for (Integer id : requested) {
            if (presentEligible.contains(id)) live.add(id);
        }
        return live;
    }
}
