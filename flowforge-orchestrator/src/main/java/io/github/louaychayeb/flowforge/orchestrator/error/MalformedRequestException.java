package io.github.louaychayeb.flowforge.orchestrator.error;

import java.util.Map;

/** A request the API cannot act on as sent, e.g. a header that is too long; the client must change it. */
public class MalformedRequestException extends FlowForgeException {

    public MalformedRequestException(String message, Map<String, ?> details) {
        super(ErrorCode.MALFORMED_REQUEST, message, details);
    }
}
