package dev.quasar.net;

/** The connection state machine as defined by the protocol. */
public enum ProtocolState {
    HANDSHAKING,
    STATUS,
    LOGIN,
    CONFIGURATION,
    PLAY,
    /** Local terminal state: the channel is closing and further packets are dropped. */
    CLOSED;

    /** Maps the "next state" field of the handshake intention packet. */
    public static ProtocolState fromIntent(int intent) {
        return switch (intent) {
            case 1 -> STATUS;
            case 2, 3 -> LOGIN; // 3 == transfer, treated as a normal login here
            default -> throw new IllegalArgumentException("Unknown handshake intent " + intent);
        };
    }
}
