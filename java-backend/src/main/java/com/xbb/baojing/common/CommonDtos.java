package com.xbb.baojing.common;

/** Shared request DTOs used by more than one controller — ports
 * backend/schemas/agent.py's AgentIn, reused verbatim by enterprises.py
 * (unit admins), agents.py (salespeople) and operators.py. */
public final class CommonDtos {
    private CommonDtos() {}

    public record AgentIn(String username, String password, String name, String phone) {}
}
