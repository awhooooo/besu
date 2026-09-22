package co.rsk.peg.host;

/** How the bridge was invoked. Mirrors RSKj's MessageCall.MsgType for the cases the bridge distinguishes. */
public enum CallKind {
    CALL,
    CALLCODE,
    DELEGATECALL,
    STATICCALL
}
