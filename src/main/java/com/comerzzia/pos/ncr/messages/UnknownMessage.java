package com.comerzzia.pos.ncr.messages;

/**
 * Message returned by the factory when the incoming payload cannot be mapped to any
 * known NCR message implementation. The raw XML is kept so that it can still be
 * logged or displayed to assist with troubleshooting.
 */
public class UnknownMessage extends BasicNCRMessage {

    private final String rawMessage;

    public UnknownMessage(String messageName, String rawMessage) {
        this.rawMessage = rawMessage;

        if (messageName != null && !messageName.isEmpty()) {
            setName(messageName);
        } else {
            setName(this.getClass().getSimpleName());
        }
    }

    public String getRawMessage() {
        return rawMessage;
    }

    @Override
    public String toString() {
        return rawMessage != null ? rawMessage : super.toString();
    }
}
