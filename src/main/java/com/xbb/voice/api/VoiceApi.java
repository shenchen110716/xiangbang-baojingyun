package com.xbb.voice.api;

public interface VoiceApi {

    /** 起草结果:回读文本 + 会话 id。回读里的数字一律口语化(§5.1 防线①)。 */
    record Draft(long sessionId, String readback, boolean hasAnomaly) { }

    /** 确认结果。ambiguous=true 表示模糊应答,需要重新回读,**不发布**(§5.1)。 */
    record ConfirmResult(boolean published, Long jobId, String reply, boolean ambiguous) { }

    Draft draftJob(long callerUserId, long orgId, String title, int headcount,
                    long wageCents, String extra);

    ConfirmResult confirm(long sessionId, long callerUserId, String utterance);

    /** 5 分钟内可撤回(§5.1 防线③)。超窗返回 false 并说明原因。 */
    ConfirmResult recall(long sessionId, long callerUserId);
}
