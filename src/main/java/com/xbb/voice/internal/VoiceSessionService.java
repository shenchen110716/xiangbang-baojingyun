package com.xbb.voice.internal;

import com.xbb.job.api.JobApi;
import com.xbb.voice.api.VoiceApi;
import com.xbb.voice.internal.ConfirmationWord.Verdict;
import com.xbb.voice.internal.ReadbackComposer.JobDraft;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 语音发单会话(主文档 §5.1 的完整流程)。
 *
 * <p>语音 → 抽取草稿 → **岗位域**做业务合理性校验 → 回读(口语化数字 + 异常质疑)
 * → 确认词判定 → 发布 → 5 分钟内可撤回。
 *
 * <p>按 §3.3,本类只做"采集层"的编排:业务规则(薪资是否离谱、谁能发单)
 * 全部委托给岗位域,语音层不复制一份。
 */
@Service
class VoiceSessionService implements VoiceApi {

    /** §5.1 防线③:"5 分钟内可语音撤回",给后悔权。 */
    static final Duration RECALL_WINDOW = Duration.ofMinutes(5);

    private final VoiceJobSessionRepository sessions;
    private final JobApi jobApi;
    private final Clock clock;

    VoiceSessionService(VoiceJobSessionRepository sessions, JobApi jobApi, Clock clock) {
        this.sessions = sessions;
        this.jobApi = jobApi;
        this.clock = clock;
    }

    @Override
    @Transactional("voiceTransactionManager")
    public Draft draftJob(long callerUserId, long orgId, String title, int headcount,
                           long wageCents, String extra) {
        VoiceJobSession session = sessions.save(
                new VoiceJobSession(callerUserId, orgId, title, headcount, wageCents, extra));

        // 业务合理性校验交给岗位域(§3.3:领域校验规则留在各域内)
        Optional<String> anomaly = jobApi.checkWageAnomaly(orgId, wageCents)
                .map(a -> a.reason());

        String readback = ReadbackComposer.compose(
                new JobDraft(title, headcount, wageCents, extra), anomaly);
        return new Draft(session.getId(), readback, anomaly.isPresent());
    }

    @Override
    @Transactional("voiceTransactionManager")
    public ConfirmResult confirm(long sessionId, long callerUserId, String utterance) {
        VoiceJobSession session = load(sessionId, callerUserId);

        Verdict verdict = ConfirmationWord.judge(utterance);
        if (verdict == Verdict.REJECTED) {
            return new ConfirmResult(false, null, "好的,那您想改哪一项?", false);
        }
        if (verdict == Verdict.AMBIGUOUS) {
            // §5.1:模糊应答重新回读,不放行。"嗯"很可能只是在思考或没听清
            String readback = ReadbackComposer.compose(
                    new JobDraft(session.getTitle(), session.getHeadcount(),
                            session.getWageCents(), session.getExtra()),
                    Optional.empty());
            return new ConfirmResult(false, null, "没太确定,我再说一遍:" + readback, true);
        }

        long jobId = jobApi.postJob(session.getOrgId(), session.getTitle(),
                session.getExtra() == null ? session.getTitle() : session.getExtra(),
                session.getWageCents(), callerUserId);
        session.markPublished(jobId, clock.instant());
        sessions.save(session);
        return new ConfirmResult(true, jobId, "已发布,五分钟内可以随时说撤回。", false);
    }

    @Override
    @Transactional("voiceTransactionManager")
    public ConfirmResult recall(long sessionId, long callerUserId) {
        VoiceJobSession session = load(sessionId, callerUserId);
        if (session.getStatus() != VoiceJobSession.Status.PUBLISHED) {
            throw new IllegalStateException("这一单不是已发布状态,无法撤回");
        }
        Duration elapsed = Duration.between(session.getPublishedAt(), clock.instant());
        if (elapsed.compareTo(RECALL_WINDOW) > 0) {
            return new ConfirmResult(false, session.getPublishedJobId(),
                    "已经超过五分钟撤回时限了,需要到岗位列表里手动关闭。", false);
        }
        session.markRecalled();
        sessions.save(session);
        return new ConfirmResult(false, session.getPublishedJobId(), "已撤回。", false);
    }

    private VoiceJobSession load(long sessionId, long callerUserId) {
        VoiceJobSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        // 语音场景里"说出口就执行",归属校验不能省
        if (session.getCallerUserId() != callerUserId) {
            throw new IllegalStateException("只能操作自己的语音会话");
        }
        return session;
    }
}
