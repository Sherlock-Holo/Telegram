package org.telegram.messenger;

import java.util.HashMap;
import java.util.Map;
import org.telegram.messenger.NotificationStickerMediaPolicy.Key;

/** Regression: download failures belong to a notification session, not to a sticker for the account's lifetime. */
public final class NotificationStickerLoadRecoveryTest {
    private static int assertions;
    private static final String FILE = "same-sticker.tgs";

    public static void main(String[] args) {
        laterMessageRecovers();
        concurrentMessagesShareOnlyInFlight();
        newGenerationRecovers();
        cancellationKeepsSharedLoad();
        completedSessionCannotPrepareAgain();
        nullAndEmptyNamesDoNotStart();
        System.out.println("OK: " + assertions + " load-recovery assertions passed");
    }

    private static void laterMessageRecovers() {
        NotificationStickerLoadTracker tracker = new NotificationStickerLoadTracker();
        Key first = new Key(0, 10L, 1);
        exhaust(tracker, first, 1L);
        Key next = new Key(0, 10L, 2);
        check("same sticker in new message starts", start(tracker, next, 2L, false));
        tracker.onFinished(FILE);
        check("new message gets its own retry", start(tracker, next, 2L, true));
        tracker.onFinished(FILE);
        check("same message still bounded", !start(tracker, next, 2L, true));
        check("different dialog recovers", start(tracker, new Key(0, 11L, 2), 3L, false));
        tracker.onFinished(FILE);
        check("account identity independent", start(tracker, new Key(1, 10L, 2), 2L, false));
    }

    private static void concurrentMessagesShareOnlyInFlight() {
        NotificationStickerLoadTracker tracker = new NotificationStickerLoadTracker();
        Key a = new Key(0, 20L, 1), b = new Key(0, 21L, 1);
        check("A starts", start(tracker, a, 1L, false));
        check("B joins active file", !start(tracker, b, 2L, false));
        check("retry does not duplicate active file", !start(tracker, b, 2L, true));
        tracker.onFinished(FILE);
        check("joining did not spend B initial attempt", start(tracker, b, 2L, false));
        tracker.onFinished(FILE);
        check("B initial failure does not reopen A initial", !start(tracker, a, 1L, false));
        check("A owns one retry", start(tracker, a, 1L, true));
        tracker.onFinished(FILE);
        check("B owns one retry independently", start(tracker, b, 2L, true));
        tracker.onFinished(FILE);
        check("A exhausted", !start(tracker, a, 1L, true));
        check("B exhausted", !start(tracker, b, 2L, true));
    }

    private static void newGenerationRecovers() {
        NotificationStickerLoadTracker tracker = new NotificationStickerLoadTracker();
        Key key = new Key(0, 30L, 1);
        exhaust(tracker, key, 1L);
        check("same key new generation starts", start(tracker, key, 2L, false));
        tracker.onFinished(FILE);
        Map<Key, Long> active = new HashMap<>();
        active.put(key, 2L);
        tracker.retainSessions(active);
        check("pruning old token preserves current budget", !start(tracker, key, 2L, false));
        check("current token retry remains", start(tracker, key, 2L, true));
        tracker.onFinished(FILE);
        check("current token retry bounded", !start(tracker, key, 2L, true));
    }

    private static void cancellationKeepsSharedLoad() {
        NotificationStickerLoadTracker tracker = new NotificationStickerLoadTracker();
        Key a = new Key(0, 40L, 1), b = new Key(0, 41L, 1);
        check("shared starts", start(tracker, a, 1L, false));
        tracker.forgetSession(a);
        check("cancel does not cancel shared FileLoader", tracker.isInFlight(FILE));
        check("other chat cannot duplicate", !start(tracker, b, 2L, false));
        Map<Key, Long> active = new HashMap<>();
        active.put(b, 2L);
        tracker.retainSessions(active);
        check("pruning also leaves active file", tracker.isInFlight(FILE));
        tracker.onFinished(FILE);
        check("remaining chat starts after failure", start(tracker, b, 2L, false));
        tracker.onFinished(FILE);
        check("remaining chat retry", start(tracker, b, 2L, true));
        tracker.onFinished(FILE);
        check("late finish cannot reset exhausted budget", !start(tracker, b, 2L, true));
    }

    private static void completedSessionCannotPrepareAgain() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        NotificationStickerLoadTracker tracker = new NotificationStickerLoadTracker();
        Key a = new Key(0, 50L, 1);
        NotificationStickerMediaPolicy.Decision wait = policy.onBuild(a, false, true);
        check("policy initial wait", policy.isActive(a));
        check("initial request", start(tracker, a, wait.token, false));
        tracker.onFinished(FILE);
        policy.onTimeout(a, wait.token);
        check("retry request", start(tracker, a, wait.token, true));
        tracker.onFinished(FILE);
        policy.onRetryTimeout(a, wait.token);
        check("original window ends", !policy.isActive(a));
        Map<Key, Long> active = new HashMap<>();
        tracker.retainSessions(active);
        // Same guard as Images.requestStickerNotificationLoad: pruning is not permission to re-run a closed session.
        check("completed preparation rejected", !prepareIfActive(policy, tracker, a, true));
        policy.cancelKey(a);
        check("cancelled preparation rejected", !prepareIfActive(policy, tracker, a, true));
        Key b = new Key(0, 50L, 2);
        policy.onBuild(b, false, true);
        check("new message after completed failure recovers", prepareIfActive(policy, tracker, b, false));
    }

    private static boolean prepareIfActive(NotificationStickerMediaPolicy policy, NotificationStickerLoadTracker tracker, Key key, boolean retry) {
        return policy.isActive(key) && start(tracker, key, policy.tokenOf(key), retry);
    }

    private static void nullAndEmptyNamesDoNotStart() {
        NotificationStickerLoadTracker tracker = new NotificationStickerLoadTracker();
        Key key = new Key(0, 60L, 1);
        check("null file", !tracker.shouldStart(null, key, 1L, false));
        check("empty file", !tracker.shouldStart("", key, 1L, false));
        tracker.onStarted(null, key, 1L);
        tracker.onFinished(null);
        check("null not in flight", !tracker.isInFlight(null));
    }

    private static void exhaust(NotificationStickerLoadTracker tracker, Key key, long token) {
        check("first attempt", start(tracker, key, token, false));
        check("in-flight dedup", !start(tracker, key, token, true));
        tracker.onFinished(FILE);
        check("same session no initial loop", !start(tracker, key, token, false));
        check("one retry", start(tracker, key, token, true));
        tracker.onFinished(FILE);
        check("retry exhausted", !start(tracker, key, token, true));
    }

    private static boolean start(NotificationStickerLoadTracker tracker, Key key, long token, boolean retry) {
        if (!tracker.shouldStart(FILE, key, token, retry)) return false;
        tracker.onStarted(FILE, key, token);
        return true;
    }

    private static void check(String label, boolean value) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }
}
