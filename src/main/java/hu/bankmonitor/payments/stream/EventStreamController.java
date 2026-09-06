package hu.bankmonitor.payments.stream;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The one event stream, under the {@code /api} prefix the security chain opens by name.
 *
 * <p>The whole endpoint is a subscription: no path variable, no query parameter and no
 * request body, because a message says only that some Transfer changed and the browser
 * decides whether it cares. Design decision 17 has why that is one endpoint rather than one
 * per Transfer.
 *
 * <p>The path is written out again in the tests that call it, on {@code TransferController}'s
 * reasoning: a test reading the same constant as the mapping would follow a rename silently
 * instead of failing on it, and this is a path a deployed frontend has hard-coded.
 */
@RestController
class EventStreamController {

	private final TransferEventStream stream;

	EventStreamController(TransferEventStream stream) {
		this.stream = stream;
	}

	/**
	 * Opens the stream. The response ends when the client disconnects or the connection times
	 * out, and in both cases the browser reconnects and refetches — the same recovery path a
	 * first load takes.
	 *
	 * <p><b>Hidden from the OpenAPI document deliberately.</b> springdoc describes what a
	 * handler returns, and what this one returns is an unbounded {@code text/event-stream}
	 * rather than a body — left alone it publishes the {@link SseEmitter} object's own
	 * properties as the response schema, which describes a Spring class rather than this
	 * endpoint. Design decision 26 has the frontend generating its types from that document
	 * precisely because nothing in it is written by hand, so an entry that is wrong costs more
	 * there than an entry that is absent; and the frontend reads this stream with
	 * {@code EventSource}, not with a generated client.
	 */
	@Hidden
	@GetMapping(path = "/api/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	SseEmitter subscribe() {
		return stream.open();
	}
}
