package com.pbm.command.exception;

/**
 * AgentRun을 찾을 수 없을 때 발생하는 예외.
 */
public class AgentRunNotFoundException extends RuntimeException {

    public AgentRunNotFoundException(String message) {
        super(message);
    }
}
