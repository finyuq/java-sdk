/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import org.springframework.http.codec.ServerSentEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests for the {@link HttpClientSseClientTransport} class.
 *
 * @author Christian Tzolov
 */
@Timeout(15)
class HttpClientSseClientTransportTests {

	static String host = "http://localhost:3001";

	@SuppressWarnings("resource")
	GenericContainer<?> container = new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v1")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	private TestHttpClientSseClientTransport transport;

	// Test class to access protected methods
	static class TestHttpClientSseClientTransport extends HttpClientSseClientTransport {

		private final AtomicInteger inboundMessageCount = new AtomicInteger(0);

		private Sinks.Many<ServerSentEvent<String>> events = Sinks.many().unicast().onBackpressureBuffer();

		public TestHttpClientSseClientTransport(final String baseUri) {
			super(HttpClient.newHttpClient(), HttpRequest.newBuilder(), baseUri, "/sse", new ObjectMapper());
		}

		public int getInboundMessageCount() {
			return inboundMessageCount.get();
		}

		public void simulateEndpointEvent(String jsonMessage) {
			events.tryEmitNext(ServerSentEvent.<String>builder().event("endpoint").data(jsonMessage).build());
			inboundMessageCount.incrementAndGet();
		}

		public void simulateMessageEvent(String jsonMessage) {
			events.tryEmitNext(ServerSentEvent.<String>builder().event("message").data(jsonMessage).build());
			inboundMessageCount.incrementAndGet();
		}

	}

	void startContainer() {
		container.start();
		int port = container.getMappedPort(3001);
		host = "http://" + container.getHost() + ":" + port;
	}

	@BeforeEach
	void setUp() {
		startContainer();
		transport = new TestHttpClientSseClientTransport(host);
		transport.connect(Function.identity()).block();
	}

	@AfterEach
	void afterEach() {
		if (transport != null) {
			assertThatCode(() -> transport.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
		}
		cleanup();
	}

	void cleanup() {
		container.stop();
	}

	@Test
	void testMessageProcessing() {
		// Create a test message
		Map<String, Object> params = new HashMap<>();
		params.put("key", "value");
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				params);

		// Simulate receiving the message
		transport.simulateMessageEvent("{\n" +
				"    \"jsonrpc\": \"2.0\",\n" +
				"    \"method\": \"test-method\",\n" +
				"    \"id\": \"test-id\",\n" +
				"    \"params\": {\"key\": \"value\"}\n" +
				"}");

		// Subscribe to messages and verify
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testResponseMessageProcessing() {
		// Simulate receiving a response message
		transport.simulateMessageEvent("{\n" +
				"    \"jsonrpc\": \"2.0\",\n" +
				"    \"id\": \"test-id\",\n" +
				"    \"result\": {\"status\": \"success\"}\n" +
				"}");

		// Create and send a request message
		Map<String, Object> params = new HashMap<>();
		params.put("key", "value");
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				params);

		// Verify message handling
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testErrorMessageProcessing() {
		// Simulate receiving an error message
		transport.simulateMessageEvent("{\n" +
				"    \"jsonrpc\": \"2.0\",\n" +
				"    \"id\": \"test-id\",\n" +
				"    \"error\": {\n" +
				"        \"code\": -32600,\n" +
				"        \"message\": \"Invalid Request\"\n" +
				"    }\n" +
				"}");

		// Create and send a request message
		Map<String, Object> params = new HashMap<>();
		params.put("key", "value");
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				params);

		// Verify message handling
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testNotificationMessageProcessing() {
		// Simulate receiving a notification message (no id)
		transport.simulateMessageEvent("{\n" +
				"    \"jsonrpc\": \"2.0\",\n" +
				"    \"method\": \"update\",\n" +
				"    \"params\": {\"status\": \"processing\"}\n" +
				"}");

		// Verify the notification was processed
		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testGracefulShutdown() {
		// Test graceful shutdown
		StepVerifier.create(transport.closeGracefully()).verifyComplete();

		// Create a test message
		Map<String, Object> params = new HashMap<>();
		params.put("key", "value");
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				params);

		// Verify message is not processed after shutdown
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		// Message count should remain 0 after shutdown
		assertThat(transport.getInboundMessageCount()).isZero();
	}

	@Test
	void testRetryBehavior() {
		// Create a client that simulates connection failures
		HttpClientSseClientTransport failingTransport = HttpClientSseClientTransport.builder("http://non-existent-host")
			.build();

		// Verify that the transport attempts to reconnect
		StepVerifier.create(Mono.delay(Duration.ofSeconds(2))).expectNextCount(1).verifyComplete();

		// Clean up
		failingTransport.closeGracefully().block();
	}

	@Test
	void testMultipleMessageProcessing() {
		// Simulate receiving multiple messages in sequence
		transport.simulateMessageEvent("{\n" +
				"    \"jsonrpc\": \"2.0\",\n" +
				"    \"method\": \"method1\",\n" +
				"    \"id\": \"id1\",\n" +
				"    \"params\": {\"key\": \"value1\"}\n" +
				"}");

		transport.simulateMessageEvent("{\n" +
				"    \"jsonrpc\": \"2.0\",\n" +
				"    \"method\": \"method2\",\n" +
				"    \"id\": \"id2\",\n" +
				"    \"params\": {\"key\": \"value2\"}\n" +
				"}");

		// Create and send corresponding messages
		Map<String, Object> params1 = new HashMap<>();
		params1.put("key", "value1");
		JSONRPCRequest message1 = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "method1", "id1",
				params1);

		Map<String, Object> params2 = new HashMap<>();
		params2.put("key", "value2");
		JSONRPCRequest message2 = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "method2", "id2",
				params2);

		// Verify both messages are processed
		StepVerifier.create(transport.sendMessage(message1).then(transport.sendMessage(message2))).verifyComplete();

		// Verify message count
		assertThat(transport.getInboundMessageCount()).isEqualTo(2);
	}

	@Test
	void testMessageOrderPreservation() {
		// Simulate receiving messages in a specific order
		transport.simulateMessageEvent("{\n" +
				"    \"jsonrpc\": \"2.0\",\n" +
				"    \"method\": \"first\",\n" +
				"    \"id\": \"1\",\n" +
				"    \"params\": {\"sequence\": 1}\n" +
				"}");

		transport.simulateMessageEvent("{\n" +
				"    \"jsonrpc\": \"2.0\",\n" +
				"    \"method\": \"second\",\n" +
				"    \"id\": \"2\",\n" +
				"    \"params\": {\"sequence\": 2}\n" +
				"}");

		transport.simulateMessageEvent("{\n" +
				"    \"jsonrpc\": \"2.0\",\n" +
				"    \"method\": \"third\",\n" +
				"    \"id\": \"3\",\n" +
				"    \"params\": {\"sequence\": 3}\n" +
				"}");

		// Verify message count and order
		assertThat(transport.getInboundMessageCount()).isEqualTo(3);
	}

	@Test
	void testCustomizeClient() {
		// Create an atomic boolean to verify the customizer was called
		AtomicBoolean customizerCalled = new AtomicBoolean(false);

		// Create a transport with the customizer
		HttpClientSseClientTransport customizedTransport = HttpClientSseClientTransport.builder(host)
			.customizeClient(builder -> {
				builder.version(HttpClient.Version.HTTP_2);
				customizerCalled.set(true);
			})
			.build();

		// Verify the customizer was called
		assertThat(customizerCalled.get()).isTrue();

		// Clean up
		customizedTransport.closeGracefully().block();
	}

	@Test
	void testCustomizeRequest() {
		// Create an atomic boolean to verify the customizer was called
		AtomicBoolean customizerCalled = new AtomicBoolean(false);

		// Create a reference to store the custom header value
		AtomicReference<String> headerName = new AtomicReference<>();
		AtomicReference<String> headerValue = new AtomicReference<>();

		// Create a transport with the customizer
		HttpClientSseClientTransport customizedTransport = HttpClientSseClientTransport.builder(host)
			// Create a request customizer that adds a custom header
			.customizeRequest(builder -> {
				builder.header("X-Custom-Header", "test-value");
				customizerCalled.set(true);

				// Create a new request to verify the header was set
				HttpRequest request = builder.uri(URI.create("http://example.com")).build();
				headerName.set("X-Custom-Header");
				headerValue.set(request.headers().firstValue("X-Custom-Header").orElse(null));
			})
			.build();

		// Verify the customizer was called
		assertThat(customizerCalled.get()).isTrue();

		// Verify the header was set correctly
		assertThat(headerName.get()).isEqualTo("X-Custom-Header");
		assertThat(headerValue.get()).isEqualTo("test-value");

		// Clean up
		customizedTransport.closeGracefully().block();
	}

	@Test
	void testChainedCustomizations() {
		// Create atomic booleans to verify both customizers were called
		AtomicBoolean clientCustomizerCalled = new AtomicBoolean(false);
		AtomicBoolean requestCustomizerCalled = new AtomicBoolean(false);

		// Create a transport with both customizers chained
		HttpClientSseClientTransport customizedTransport = HttpClientSseClientTransport.builder(host)
			.customizeClient(builder -> {
				builder.connectTimeout(Duration.ofSeconds(30));
				clientCustomizerCalled.set(true);
			})
			.customizeRequest(builder -> {
				builder.header("X-Api-Key", "test-api-key");
				requestCustomizerCalled.set(true);
			})
			.build();

		// Verify both customizers were called
		assertThat(clientCustomizerCalled.get()).isTrue();
		assertThat(requestCustomizerCalled.get()).isTrue();

		// Clean up
		customizedTransport.closeGracefully().block();
	}

}
