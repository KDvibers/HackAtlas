package com.hackatlas;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.hackatlas.auth.lifecycle.LifecycleMailQueue;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class LifecycleJobsIT extends N2HttpSupport {
    @Autowired LifecycleMailQueue queue;
    @Autowired MeterRegistry metrics;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.testcontainers.postgresql.PostgreSQLContainer postgres;

    @BeforeEach void settlePreviousJobs() throws Exception { drain(); }

    @Test
    void smtpInboxIsNonRootAndAllItsMappedPortsAreBoundToLoopback() {
        var container=LifecycleIntegrationConfiguration.MAIL.getContainerInfo();
        assertThat(container.getConfig().getUser()).isEqualTo("1000:1000");
        for (var info:java.util.List.of(container,postgres.getContainerInfo())) {
            for (var bindings:info.getNetworkSettings().getPorts().getBindings().values()) {
                if (bindings != null) for (var binding:bindings) assertThat(binding.getHostIp()).isEqualTo("127.0.0.1");
            }
        }
    }

    @Test
    void aQueuedAnonymousRequestCannotRebindToCredentialsChangedAtTheSameClockInstant() throws Exception {
        var initial=resetRequest(email,null);
        var grant=call("POST","/auth/password-reset/verify",null,codePayload(initial,code(email,initial)),200);
        LifecycleIntegrationConfiguration.CLOCK.advance(Duration.ofSeconds(60));
        var release=pauseWorker();
        try {
            resetRequest(email,null); // Accepted before the password change, but lookup is still queued.
            call("POST","/auth/password-reset/confirm",null,json.writeValueAsString(Map.of(
                    "resetToken",grant.path("resetToken").stringValue(),"newPassword","changed-while-job-waits")),204);
        } finally { release.countDown(); }
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hackatlas.email_action_challenge WHERE user_id=?",Integer.class,user)).isZero();
    }

    @Test
    void queuedVerificationIsCancelledAfterDeletion() throws Exception {
        var release=pauseWorker();
        try {
            verificationRequest();
            call("POST","/me/delete-account",token,json.writeValueAsString(Map.of("password",PASSWORD)),204);
        } finally { release.countDown(); }
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hackatlas.user_account WHERE id=?",Integer.class,user)).isZero();
        assertThat(mailbox("GET","/view/latest.txt?query=to:"+email).statusCode()).isEqualTo(404);
    }

    @Test
    void requestAcknowledgementDoesNotWaitForAnAccountLookup() {
        new TransactionTemplate(transactions).execute(status -> {
            jdbc.execute("LOCK TABLE hackatlas.user_account IN ACCESS EXCLUSIVE MODE");
            try { resetRequest(email,null); }
            catch (Exception exception) { throw new AssertionError("Reset ACK attempted a synchronous account lookup",exception); }
            return null;
        });
    }

    @Test
    void fullQueueReturnsTheSame503ForKnownAndUnknownAddresses() throws Exception {
        var release=pauseWorker();
        try {
            for (int index=0;index<16;index++) queue.submit(() -> { });
            for (String address:new String[]{email,"missing-"+UUID.randomUUID()+"@example.com"}) {
                var body=call("POST","/auth/password-reset/request",null,json.writeValueAsString(Map.of("email",address)),503);
                assertThat(body.path("code").stringValue()).isEqualTo("SERVICE_UNAVAILABLE");
            }
        } finally { release.countDown(); }
        drain();
    }

    @Test
    void knownUnknownAndDeletedAddressesHaveTheSameAcceptedShapeAndNoUnknownMailbox() throws Exception {
        String deleted="deleted-"+UUID.randomUUID()+"@example.com";
        var removed=register(deleted,PASSWORD,"Removed");
        call("POST","/me/delete-account",removed.path("accessToken").stringValue(),json.writeValueAsString(Map.of("password",PASSWORD)),204);
        String missing="missing-"+UUID.randomUUID()+"@example.com";
        var known=resetRequest(email,null);
        var absent=resetRequest(missing,null);
        var erased=resetRequest(deleted,null);
        for (var item:java.util.List.of(known,absent,erased)) {
            assertThat(item.size()).isEqualTo(3);
            assertThat(item.path("challengeId").stringValue()).matches("[A-Za-z0-9_-]{43}");
            assertThat(item.path("expiresAt")).isEqualTo(known.path("expiresAt"));
            assertThat(item.path("resendAfterSeconds").intValue()).isEqualTo(60);
        }
        drain();
        assertThat(mailbox("GET","/view/latest.txt?query=to:"+missing).statusCode()).isEqualTo(404);
        assertThat(mailbox("GET","/view/latest.txt?query=to:"+deleted).statusCode()).isEqualTo(404);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void smtpFailureAfterAcknowledgementDoesNotChange202OrLogCredentials(CapturedOutput output) throws Exception {
        double before=count("failed");
        var mail=LifecycleIntegrationConfiguration.MAIL;
        mail.getDockerClient().pauseContainerCmd(mail.getContainerId()).exec();
        try {
            var accepted=resetRequest(email,null);
            await().atMost(12,TimeUnit.SECONDS).until(() -> count("failed")>before);
            assertThat(accepted.path("challengeId").stringValue()).hasSize(43);
            assertThat(output.getAll()).doesNotContain(email,token,PASSWORD,accepted.path("challengeId").stringValue());
        } finally { mail.getDockerClient().unpauseContainerCmd(mail.getContainerId()).exec(); }
    }

    @Test
    void aFailedNotificationCannotUndoTheCommittedPasswordOrTurn204IntoAnError() throws Exception {
        var initial=resetRequest(email,null);
        var grant=call("POST","/auth/password-reset/verify",null,codePayload(initial,code(email,initial)),200);
        drain();
        double before=count("notification_dropped");
        var mail=LifecycleIntegrationConfiguration.MAIL;
        mail.getDockerClient().pauseContainerCmd(mail.getContainerId()).exec();
        try {
            call("POST","/auth/password-reset/confirm",null,json.writeValueAsString(Map.of(
                    "resetToken",grant.path("resetToken").stringValue(),"newPassword","committed-despite-mail-failure")),204);
            await().atMost(12,TimeUnit.SECONDS).until(() -> count("notification_dropped")>before);
        } finally { mail.getDockerClient().unpauseContainerCmd(mail.getContainerId()).exec(); }
        call("POST","/auth/login",null,loginPayload(email,"committed-despite-mail-failure"),200);
        call("POST","/auth/login",null,loginPayload(email,PASSWORD),401);
    }

    private double count(String outcome) { return metrics.counter("hackatlas.lifecycle.delivery","outcome",outcome).count(); }
    private CountDownLatch pauseWorker() throws Exception {
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        queue.submit(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); } });
        assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
        return release;
    }
    private void drain() throws Exception {
        var finished=new CountDownLatch(1);
        await().atMost(12,TimeUnit.SECONDS).until(() -> {
            try { queue.submit(finished::countDown); return true; }
            catch (com.hackatlas.web.ApiFailure full) {
                if (full.status() != 503) throw full;
                return false;
            }
        });
        assertThat(finished.await(12,TimeUnit.SECONDS)).as("accepted background jobs finish").isTrue();
    }
}
