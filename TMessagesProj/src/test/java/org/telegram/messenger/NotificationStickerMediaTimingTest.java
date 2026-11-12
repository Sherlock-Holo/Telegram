package org.telegram.messenger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.telegram.messenger.NotificationStickerMediaPolicy.Key;
import org.telegram.messenger.NotificationStickerMediaPolicy.State;

/** Exercises the production timer registry and rebuild target accumulator with a virtual dispatch queue. */
public final class NotificationStickerMediaTimingTest {
    private static int assertions;
    private static final class Task {
        final Runnable run;
        final long due;
        Task(Runnable run, long due) { this.run = run; this.due = due; }
    }
    private static final class Queue implements NotificationStickerTimeouts.Queue {
        long now;
        final List<Task> pending = new ArrayList<>();
        public void post(Runnable run, long delay) { pending.add(new Task(run, now + delay)); }
        public void cancel(Runnable run) { pending.removeIf(t -> t.run == run); }
        void advanceTo(long time) {
            while (true) {
                Task task = pending.stream().filter(t -> t.due <= time).min(Comparator.comparingLong(t -> t.due)).orElse(null);
                if (task == null) break;
                pending.remove(task);
                now = task.due;
                task.run.run();
            }
            now = time;
        }
    }
    public static void main(String[] args) {
        newMessageReplacesPrepDeadline();
        stalePrepCannotRemoveRetry();
        repeatedBuildCannotExtendRetry();
        cancelledRetryCannotFire();
        targetsKeepDialogIdentity();
        System.out.println("OK: " + assertions + " timing/routing assertions passed");
    }
    private static void check(String name, boolean value) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }
    private static void newMessageReplacesPrepDeadline() {
        NotificationStickerMediaPolicy p = new NotificationStickerMediaPolicy();
        Queue queue = new Queue();
        NotificationStickerTimeouts timers = new NotificationStickerTimeouts(queue);
        Key a = new Key(0, 10, 1);
        long token = p.onBuild(a, false, true).token;
        timers.ensure(a, token, false, 3000, () -> p.onTimeout(a, token));
        queue.advanceTo(1000);
        p.abortWaitsForDialog(0, 10); // Same-chat text arrives while cover is pending.
        timers.ensure(a, token, true, 3000, () -> p.onRetryTimeout(a, token));
        check("prep timer replaced not duplicated", queue.pending.size() == 1);
        check("retry deadline starts at transition", queue.pending.get(0).due == 4000);
        queue.advanceTo(3000);
        check("old prep deadline does not finish retry", p.stateOf(a) == State.POSTED_TEXT_RETRYING);
        queue.advanceTo(4000);
        check("retry actually completes", p.stateOf(a) == State.COMPLETED);
        check("protection actually released", !p.hasProtectOnlyAlertOnce(0, 10));
        check("no timers remain", queue.pending.isEmpty());
    }
    private static void stalePrepCannotRemoveRetry() {
        NotificationStickerMediaPolicy p = new NotificationStickerMediaPolicy();
        Queue queue = new Queue();
        NotificationStickerTimeouts timers = new NotificationStickerTimeouts(queue);
        Key a = new Key(0, 10, 1);
        long token = p.onBuild(a, false, true).token;
        timers.ensure(a, token, false, 3000, () -> p.onTimeout(a, token));
        Runnable stale = queue.pending.get(0).run;
        p.abortWaitsForDialog(0, 10);
        timers.ensure(a, token, true, 3000, () -> p.onRetryTimeout(a, token));
        stale.run(); // Simulate an old callback already dequeued when cancelled.
        check("stale callback does not remove replacement", queue.pending.size() == 1);
        queue.advanceTo(3000);
        check("replacement still runs", p.stateOf(a) == State.COMPLETED);
    }
    private static void repeatedBuildCannotExtendRetry() {
        Queue queue = new Queue();
        NotificationStickerTimeouts timers = new NotificationStickerTimeouts(queue);
        Key a = new Key(0, 10, 1);
        int[] calls = {0};
        timers.ensure(a, 1, true, 3000, () -> calls[0]++);
        queue.advanceTo(2000);
        timers.ensure(a, 1, true, 3000, () -> calls[0]++);
        check("same phase not rearmed", queue.pending.get(0).due == 3000);
        queue.advanceTo(3000);
        check("retry fires exactly once at original deadline", calls[0] == 1);
    }
    private static void cancelledRetryCannotFire() {
        Queue queue = new Queue();
        NotificationStickerTimeouts timers = new NotificationStickerTimeouts(queue);
        Key a = new Key(0, 10, 1), b = new Key(1, 10, 1);
        int[] calls = {0};
        timers.ensure(a, 1, true, 3000, () -> calls[0] += 100);
        Runnable stale = queue.pending.get(0).run;
        timers.ensure(b, 2, true, 3000, () -> calls[0]++);
        timers.cancel(a);
        stale.run();
        queue.advanceTo(3000);
        check("cancel suppresses stale callbacks without cancelling other account", calls[0] == 1);
    }
    private static void targetsKeepDialogIdentity() {
        NotificationStickerMediaPolicy p = new NotificationStickerMediaPolicy();
        Key a = new Key(0, 10, 1);
        long token = p.onBuild(a, false, true).token;
        p.abortWaitsForDialog(0, 20); // B text arrives; A remains pending.
        check("B does not flush A", p.stateOf(a) == State.WAITING);
        NotificationStickerMediaPolicy.RebuildTargets targets = new NotificationStickerMediaPolicy.RebuildTargets();
        targets.record(a.dialogId, a.messageId, p.onMediaReady(a, token).notifyAboutLast);
        check("A's completion preserves A as alert target", Boolean.TRUE.equals(targets.dialogAlert(10L)));
        check("B cannot inherit A's alert", !targets.containsDialog(20L));
        targets.record(10, 99, false); // Another silent fill in A must not erase its pending first alert.
        targets.record(30, 1, false);
        check("same dialog OR preserves first alert", Boolean.TRUE.equals(targets.dialogAlert(10L)));
        check("different dialog fill stays silent", Boolean.FALSE.equals(targets.dialogAlert(30L)));
        // onBeforeShow consumes only its current target, dispatches the remaining dialog IDs.
        check("building B cannot consume A alert", targets.removeDialog(20L) == null && targets.containsDialog(10L));
    }
}
