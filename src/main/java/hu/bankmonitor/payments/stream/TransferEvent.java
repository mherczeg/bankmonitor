package hu.bankmonitor.payments.stream;

/**
 * One Hint on the Event Stream: what happened, and to which Transfer. Nothing else.
 *
 * <p>That emptiness is the design decision the whole live-update story rests on (design
 * decision 17). A frame carrying only a cache key is one the browser cannot merge into
 * anything, reconcile against anything or need in order — so the frontend's entire handling
 * of the stream is one pure function from a frame to a list of stale query keys.
 * <em>The stream carries hints; the REST endpoint carries truth.</em>
 *
 * <p>It is the deliberate opposite of what the outbox sends. An Outbox Event carries the
 * payload a consuming service needs, because a service calling back for the amount is the
 * coupling design decision 11 rejected polling to avoid; a browser calling back for the
 * amount is a request to an API it is already holding open a connection to.
 *
 * @param type       which of the three ways the Transfer finished
 * @param transferId the Transfer the browser should consider stale
 */
public record TransferEvent(TransferEventType type, long transferId) {
}
