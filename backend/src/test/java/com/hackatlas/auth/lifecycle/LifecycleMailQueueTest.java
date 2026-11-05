package com.hackatlas.auth.lifecycle;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.hackatlas.web.ApiFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class LifecycleMailQueueTest {
    @Test
    void rejectsOverflowBeforeAcknowledgementAndRunsAcceptedJobsAfterCapacityIsReleased() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        try (var queue = new LifecycleMailQueue(1)) {
            queue.submit(() -> { entered.countDown(); waitFor(release); });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            queue.submit(completed::countDown);
            var rejected = catchThrowableOfType(() -> queue.submit(() -> { }), ApiFailure.class);
            assertThat(rejected.status()).isEqualTo(503);
            assertThat(rejected.code()).isEqualTo("SERVICE_UNAVAILABLE");
            release.countDown();
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); }
    }

    @Test
    void closingDropsPendingWorkAndRejectsNewSubmissions() throws Exception {
        var queue = new LifecycleMailQueue(1);
        var entered = new CountDownLatch(1);
        var pendingRan = new AtomicBoolean();
        try {
            queue.submit(() -> { entered.countDown(); waitFor(new CountDownLatch(1)); });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            queue.submit(() -> pendingRan.set(true));
        } finally { queue.close(); }
        assertThat(pendingRan).isFalse();
        assertThat(catchThrowableOfType(() -> queue.submit(() -> { }), ApiFailure.class).status()).isEqualTo(503);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void taskFailureDoesNotLeakExceptionPayloadAndDoesNotKillLaterJobs(CapturedOutput output) throws Exception {
        var completed = new CountDownLatch(1);
        try (var queue = new LifecycleMailQueue(2)) {
            queue.submit(() -> { throw new IllegalStateException("synthetic-private-mail-payload"); });
            queue.submit(completed::countDown);
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(output.getAll()).doesNotContain("synthetic-private-mail-payload");
    }

    private static void waitFor(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
