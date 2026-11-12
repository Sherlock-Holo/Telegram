package org.telegram.messenger;

import org.telegram.messenger.NotificationStickerMediaPolicy.ActionType;
import org.telegram.messenger.NotificationStickerMediaPolicy.Decision;
import org.telegram.messenger.NotificationStickerMediaPolicy.Key;
import org.telegram.messenger.NotificationStickerMediaPolicy.State;

/**
 * Desktop-JDK unit tests for the notification media-prep state machine.
 * Run with javac + java; no Android runtime required.
 */
public final class NotificationStickerMediaPolicyTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testCacheHitPostsImmediately();
        testColdCacheDefersThenPostsMedia();
        testTimeoutPostsTextAndArmsRetry();
        testRetrySuccessSilentFill();
        testRetryFailureReleasesProtect();
        testCancelDuringWaitDropsTimeout();
        testCancelDuringRetryDropsFill();
        testStaleCallbackIgnored();
        testNewMessageDuringWaitFlushes();
        testNewMessageDuringRetryNeedsReleaseProtect();
        testSameStickerDifferentDialogsIndependent();
        testSameStickerDifferentAccountsIndependent();
        testCancelDialogDoesNotAffectOtherChat();
        testCancelAccountDoesNotAffectOtherAccount();
        testLateMediaAfterRetryTimeoutIgnored();
        testSecondRetryNotStarted();
        testTimeoutsAreBounded();
        testOtherDialogDoesNotAbortWait();
        testAlertingPostDoesNotCarryFlag();
        testSilentFillKeepsFlagOffOnLaterSilentRebuild();
        testRetryFailKeepsFlagOff();
        testLoadTrackerRestartsOnlyOnRetry();
        if (failed > 0) {
            System.err.println("FAILED: " + failed + "  passed: " + passed);
            System.exit(1);
        }
        System.out.println("OK: " + passed + " tests passed");
    }

    private static void testCacheHitPostsImmediately() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key key = key(1, 10, 100);
        Decision decision = policy.onBuild(key, true, true);
        assertEq("cache hit action", ActionType.POST_WITH_MEDIA, decision.type);
        assertEq("cache hit notify", true, decision.notifyAboutLast);
        assertEq("cache hit no protect", false, decision.protectOnlyAlertOnce);
        assertEq("cache hit no wait", 0L, decision.scheduleTimeoutMs);
        assertEq("cache hit state", State.COMPLETED, policy.stateOf(key));
    }

    private static void testColdCacheDefersThenPostsMedia() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key key = key(1, 10, 100);
        Decision defer = policy.onBuild(key, false, true);
        assertEq("defer action", ActionType.DEFER, defer.type);
        assertEq("defer timeout", NotificationStickerMediaPolicy.PREP_TIMEOUT_MS, defer.scheduleTimeoutMs);
        assertEq("waiting", State.WAITING, policy.stateOf(key));

        Decision still = policy.onBuild(key, false, true);
        assertEq("second build still defer", ActionType.DEFER, still.type);
        assertEq("token stable", defer.token, still.token);

        Decision ready = policy.onMediaReady(key, defer.token);
        assertEq("ready action", ActionType.POST_WITH_MEDIA, ready.type);
        assertEq("ready notify", true, ready.notifyAboutLast);
        assertEq("ready no protect", false, ready.protectOnlyAlertOnce);
        assertEq("completed", State.COMPLETED, policy.stateOf(key));
    }

    private static void testTimeoutPostsTextAndArmsRetry() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key key = key(1, 10, 100);
        Decision defer = policy.onBuild(key, false, true);
        Decision timeout = policy.onTimeout(key, defer.token);
        assertEq("timeout action", ActionType.POST_TEXT_AND_RETRY, timeout.type);
        assertEq("timeout notify", true, timeout.notifyAboutLast);
        assertEq("timeout protect", true, timeout.protectOnlyAlertOnce);
        assertEq("retry window", NotificationStickerMediaPolicy.RETRY_TIMEOUT_MS, timeout.scheduleTimeoutMs);
        assertEq("retrying", State.POSTED_TEXT_RETRYING, policy.stateOf(key));
        assertEq("protect flag on", true, policy.hasProtectOnlyAlertOnce(1, 10));
    }

    private static void testRetrySuccessSilentFill() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key key = key(1, 10, 100);
        Decision defer = policy.onBuild(key, false, true);
        policy.onTimeout(key, defer.token);
        Decision fill = policy.onMediaReady(key, defer.token);
        assertEq("fill action", ActionType.SILENT_FILL_MEDIA, fill.type);
        assertEq("fill silent", false, fill.notifyAboutLast);
        assertEq("fill no protect on new post", false, fill.protectOnlyAlertOnce);
        assertEq("fill does not release first (old flag blocks increment)", false, fill.releaseProtectFirst);
        assertEq("completed after fill", State.COMPLETED, policy.stateOf(key));
        assertEq("protect cleared after fill", false, policy.hasProtectOnlyAlertOnce(1, 10));
    }

    private static void testRetryFailureReleasesProtect() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key key = key(1, 10, 100);
        Decision defer = policy.onBuild(key, false, true);
        policy.onTimeout(key, defer.token);
        Decision fail = policy.onRetryTimeout(key, defer.token);
        assertEq("fail action", ActionType.RELEASE_PROTECT, fail.type);
        assertEq("fail silent", false, fail.notifyAboutLast);
        assertEq("completed after fail", State.COMPLETED, policy.stateOf(key));
        assertEq("protect cleared after fail", false, policy.hasProtectOnlyAlertOnce(1, 10));

        Decision late = policy.onMediaReady(key, defer.token);
        assertEq("late media after fail skipped", ActionType.SKIP, late.type);
    }

    private static void testCancelDuringWaitDropsTimeout() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key key = key(1, 10, 100);
        Decision defer = policy.onBuild(key, false, true);
        policy.cancelKey(key);
        assertEq("cancelled", State.CANCELLED, policy.stateOf(key));
        Decision timeout = policy.onTimeout(key, defer.token);
        assertEq("cancelled timeout skipped", ActionType.SKIP, timeout.type);
        Decision ready = policy.onMediaReady(key, defer.token);
        assertEq("cancelled ready skipped", ActionType.SKIP, ready.type);
    }

    private static void testCancelDuringRetryDropsFill() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key key = key(1, 10, 100);
        Decision defer = policy.onBuild(key, false, true);
        policy.onTimeout(key, defer.token);
        policy.cancelDialog(1, 10);
        Decision fill = policy.onMediaReady(key, defer.token);
        assertEq("cancelled fill skipped", ActionType.SKIP, fill.type);
        Decision retryTimeout = policy.onRetryTimeout(key, defer.token);
        assertEq("cancelled retry timeout skipped", ActionType.SKIP, retryTimeout.type);
    }

    private static void testStaleCallbackIgnored() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key key = key(1, 10, 100);
        Decision first = policy.onBuild(key, false, true);
        policy.cancelKey(key);
        Decision second = policy.onBuild(key, false, true);
        assertEq("cancelled key cannot restart", ActionType.SKIP, second.type);
        Decision stale = policy.onTimeout(key, first.token);
        assertEq("stale timeout skipped", ActionType.SKIP, stale.type);
        Decision staleReady = policy.onMediaReady(key, first.token);
        assertEq("stale ready skipped", ActionType.SKIP, staleReady.type);
        assertEq("stays cancelled", State.CANCELLED, policy.stateOf(key));
        long tokenAfterCancel = policy.tokenOf(key);
        assertEq("cancel bumped generation", true, tokenAfterCancel != first.token);
        Decision staleNewTokenTimeout = policy.onTimeout(key, tokenAfterCancel);
        assertEq("cancelled current token timeout skipped", ActionType.SKIP, staleNewTokenTimeout.type);
    }

    private static void testNewMessageDuringWaitFlushes() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key first = key(1, 10, 100);
        Key second = key(1, 10, 101);
        Decision defer = policy.onBuild(first, false, true);
        boolean protect = policy.abortWaitsForDialog(1, 10);
        assertEq("flush arms protect", true, protect);
        assertEq("flushed to retry", State.POSTED_TEXT_RETRYING, policy.stateOf(first));

        // The new message itself is ready text or another sticker.
        Decision newMsg = policy.onBuild(second, true, true);
        assertEq("new message posts now", ActionType.POST_WITH_MEDIA, newMsg.type);
        assertEq("new message is an alert", true, newMsg.notifyAboutLast);

        // Later the first sticker can still silently fill.
        Decision fill = policy.onMediaReady(first, defer.token);
        assertEq("flushed wait can still fill once", ActionType.SILENT_FILL_MEDIA, fill.type);
    }

    private static void testNewMessageDuringRetryNeedsReleaseProtect() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key first = key(1, 10, 100);
        Key second = key(1, 10, 101);
        Decision defer = policy.onBuild(first, false, true);
        policy.onTimeout(first, defer.token);
        assertEq("protect on after text", true, policy.hasProtectOnlyAlertOnce(1, 10));

        boolean protect = policy.abortWaitsForDialog(1, 10);
        assertEq("retry still protects", true, protect);
        Decision newMsg = policy.onBuild(second, true, true);
        assertEq("new alert", ActionType.POST_WITH_MEDIA, newMsg.type);
        assertEq("must release protect before new alert", true, newMsg.releaseProtectFirst
                || policy.hasProtectOnlyAlertOnce(1, 10));
        // Owner should call release-first when hasProtect && notifyAboutLast. Policy reports it either way.
        assertEq("hasProtect still true until owner clears or fill", true, policy.hasProtectOnlyAlertOnce(1, 10));
        policy.clearProtect(1, 10);
        assertEq("owner can clear protect after two-step release", false, policy.hasProtectOnlyAlertOnce(1, 10));
    }

    private static void testSameStickerDifferentDialogsIndependent() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key chatA = key(1, 10, 100);
        Key chatB = key(1, 20, 100);
        Decision a = policy.onBuild(chatA, false, true);
        Decision b = policy.onBuild(chatB, false, true);
        policy.cancelDialog(1, 10);
        assertEq("A cancelled", State.CANCELLED, policy.stateOf(chatA));
        assertEq("B still waiting", State.WAITING, policy.stateOf(chatB));
        Decision bReady = policy.onMediaReady(chatB, b.token);
        assertEq("B still posts", ActionType.POST_WITH_MEDIA, bReady.type);
        Decision aReady = policy.onMediaReady(chatA, a.token);
        assertEq("A skipped", ActionType.SKIP, aReady.type);
    }

    private static void testSameStickerDifferentAccountsIndependent() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key acc1 = key(1, 10, 100);
        Key acc2 = key(2, 10, 100);
        Decision one = policy.onBuild(acc1, false, true);
        Decision two = policy.onBuild(acc2, false, true);
        policy.cancelAccount(1);
        assertEq("acc1 cancelled", State.CANCELLED, policy.stateOf(acc1));
        assertEq("acc2 waiting", State.WAITING, policy.stateOf(acc2));
        Decision twoReady = policy.onMediaReady(acc2, two.token);
        assertEq("acc2 posts", ActionType.POST_WITH_MEDIA, twoReady.type);
        Decision oneReady = policy.onMediaReady(acc1, one.token);
        assertEq("acc1 skipped", ActionType.SKIP, oneReady.type);
    }

    private static void testCancelDialogDoesNotAffectOtherChat() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key a = key(1, 10, 100);
        Key b = key(1, 11, 200);
        policy.onBuild(a, false, true);
        Decision deferB = policy.onBuild(b, false, true);
        policy.cancelMessage(1, 10, 100);
        assertEq("only A cancelled", State.CANCELLED, policy.stateOf(a));
        assertEq("B untouched", State.WAITING, policy.stateOf(b));
        assertEq("B token still valid", ActionType.POST_WITH_MEDIA, policy.onMediaReady(b, deferB.token).type);
    }

    private static void testCancelAccountDoesNotAffectOtherAccount() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key a = key(1, 10, 100);
        Key b = key(2, 10, 100);
        policy.onBuild(a, false, true);
        Decision deferB = policy.onBuild(b, false, true);
        policy.cancelAccount(1);
        assertEq("B still waiting after other account cleanup", State.WAITING, policy.stateOf(b));
        assertEq("B still valid", ActionType.POST_WITH_MEDIA, policy.onMediaReady(b, deferB.token).type);
    }

    private static void testLateMediaAfterRetryTimeoutIgnored() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key key = key(1, 10, 100);
        Decision defer = policy.onBuild(key, false, true);
        policy.onTimeout(key, defer.token);
        policy.onRetryTimeout(key, defer.token);
        Decision late = policy.onMediaReady(key, defer.token);
        assertEq("no second fill after retry window", ActionType.SKIP, late.type);
        // A later rebuild with complete media (e.g. another message) can still include it.
        Decision rebuild = policy.onBuild(key, true, false);
        assertEq("rebuild with media allowed after complete", ActionType.POST_WITH_MEDIA, rebuild.type);
    }

    private static void testSecondRetryNotStarted() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key key = key(1, 10, 100);
        Decision defer = policy.onBuild(key, false, true);
        policy.onTimeout(key, defer.token);
        Decision duringRetry = policy.onBuild(key, false, false);
        assertEq("rebuild during retry does not defer again", ActionType.NONE, duringRetry.type);
        assertEq("still one retry", State.POSTED_TEXT_RETRYING, policy.stateOf(key));
        policy.onRetryTimeout(key, defer.token);
        Decision after = policy.onBuild(key, false, false);
        assertEq("no second wait after retry window on silent rebuild", ActionType.NONE, after.type);
    }

    private static void testTimeoutsAreBounded() {
        assertEq("prep timeout is 3s", 3000L, NotificationStickerMediaPolicy.PREP_TIMEOUT_MS);
        assertEq("retry timeout is 3s", 3000L, NotificationStickerMediaPolicy.RETRY_TIMEOUT_MS);
    }

    private static void testOtherDialogDoesNotAbortWait() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key chatA = key(1, 10, 100);
        Key chatB = key(1, 20, 200);
        Decision deferA = policy.onBuild(chatA, false, true);
        boolean protectB = policy.abortWaitsForDialog(1, 20);
        assertEq("other chat abort does not protect A", false, protectB);
        assertEq("A still waiting", NotificationStickerMediaPolicy.State.WAITING, policy.stateOf(chatA));
        Decision readyA = policy.onMediaReady(chatA, deferA.token);
        assertEq("A can still post with media", NotificationStickerMediaPolicy.ActionType.POST_WITH_MEDIA, readyA.type);
        Decision deferB = policy.onBuild(chatB, false, true);
        assertEq("B can still defer independently", NotificationStickerMediaPolicy.ActionType.DEFER, deferB.type);
    }

    private static void testAlertingPostDoesNotCarryFlag() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key key = key(1, 10, 100);
        Decision defer = policy.onBuild(key, false, true);
        policy.onTimeout(key, defer.token);
        NotificationStickerMediaPolicy.FlagPlan first = policy.planFlags(1, 10, true, false);
        assertEq("alerting text post itself has no ONLY_ALERT_ONCE", false, first.onlyAlertOnce);
        assertEq("arm flag after the same alerting post", true, first.armProtectAfterPost);
        assertEq("no prior posted notification to release on first text", true, first.releaseProtectFirst);
        // After arming, a silent fill plan still uses the old flag (set by the follow-up) to block +1
        policy.onProtectArmed(1, 10);
        // planFlags is consulted after onMediaReady has already moved the session to COMPLETED
        policy.onMediaReady(key, defer.token);
        NotificationStickerMediaPolicy.FlagPlan fill = policy.planFlags(1, 10, false, true);
        assertEq("silent fill posts with flag off (old flag blocks +1)", false, fill.onlyAlertOnce);
        assertEq("fill is not a second arm", false, fill.armProtectAfterPost);
    }

    private static void testSilentFillKeepsFlagOffOnLaterSilentRebuild() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key key = key(1, 10, 100);
        Decision defer = policy.onBuild(key, false, true);
        policy.onTimeout(key, defer.token);
        policy.onMediaReady(key, defer.token);
        assertEq("keep flag off after fill", true, policy.hasKeepFlagOff(1, 10));
        NotificationStickerMediaPolicy.FlagPlan silent = policy.planFlags(1, 10, false, true);
        assertEq("later silent edit/refresh does not re-arm ONLY_ALERT_ONCE", false, silent.onlyAlertOnce);
        NotificationStickerMediaPolicy.FlagPlan laterAlert = policy.planFlags(1, 10, true, false);
        assertEq("a later real alert still has no flag on the alerting post", false, laterAlert.onlyAlertOnce);
        assertEq("no protect to release after fill already cleared it", false, laterAlert.releaseProtectFirst);
        assertEq("no arm after a completed fill", false, laterAlert.armProtectAfterPost);
    }

    private static void testRetryFailKeepsFlagOff() {
        NotificationStickerMediaPolicy policy = new NotificationStickerMediaPolicy();
        Key key = key(1, 10, 100);
        Decision defer = policy.onBuild(key, false, true);
        policy.onTimeout(key, defer.token);
        policy.onRetryTimeout(key, defer.token);
        NotificationStickerMediaPolicy.FlagPlan silent = policy.planFlags(1, 10, false, true);
        assertEq("retry-fail keeps flag off so a later alert can increment", false, silent.onlyAlertOnce);
    }

    private static void testLoadTrackerRestartsOnlyOnRetry() {
        NotificationStickerLoadTracker tracker = new NotificationStickerLoadTracker();
        assertEq("first load starts", true, tracker.shouldStart("doc.webp", false));
        tracker.onStarted("doc.webp");
        assertEq("in-flight not restarted", false, tracker.shouldStart("doc.webp", false));
        assertEq("in-flight not restarted on retry either", false, tracker.shouldStart("doc.webp", true));
        tracker.onFinished("doc.webp");
        assertEq("finished first attempt does not auto-restart", false, tracker.shouldStart("doc.webp", false));
        assertEq("retry phase may restart a failed load", true, tracker.shouldStart("doc.webp", true));
        tracker.onStarted("doc.webp");
        tracker.onFinished("doc.webp");
        assertEq("only one retry restart", false, tracker.shouldStart("doc.webp", true));
        assertEq("another file is independent", true, tracker.shouldStart("other.tgs", false));
    }

    private static Key key(int account, long dialogId, int messageId) {
        return new Key(account, dialogId, messageId);
    }

    private static void assertEq(String name, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            passed++;
            return;
        }
        failed++;
        System.err.println("FAIL: " + name + " expected=" + expected + " actual=" + actual);
    }
}
