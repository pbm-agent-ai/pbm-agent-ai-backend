package com.pbm.command.exception;

/**
 * AgentRun 접근 권한이 없을 때 사용하는 예외.
 */
public class AgentRunAccessDeniedException extends RuntimeException {

    public AgentRunAccessDeniedException(String message) {
        super(message);
    }
}
