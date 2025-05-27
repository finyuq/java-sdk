/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ModelPreferences;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunctions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

public class WebFluxSseIntegrationTests {

	private static final int PORT = 8182;

	private static final String CUSTOM_SSE_ENDPOINT = "/somePath/sse";

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	private DisposableServer httpServer;

	private WebFluxSseServerTransportProvider mcpServerTransportProvider;

	ConcurrentHashMap<String, McpClient.SyncSpec> clientBuilders = new ConcurrentHashMap<>();

	@BeforeEach
	public void before() {

		this.mcpServerTransportProvider = new WebFluxSseServerTransportProvider.Builder()
			.objectMapper(new ObjectMapper())
			.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
			.sseEndpoint(CUSTOM_SSE_ENDPOINT)
			.build();

		HttpHandler httpHandler = RouterFunctions.toHttpHandler(mcpServerTransportProvider.getRouterFunction());
		ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(httpHandler);
		this.httpServer = HttpServer.create().port(PORT).handle(adapter).bindNow();

		clientBuilders.put("httpclient",
				McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT)
					.sseEndpoint(CUSTOM_SSE_ENDPOINT)
					.build()));
		clientBuilders.put("webflux",
				McpClient
					.sync(WebFluxSseClientTransport.builder(WebClient.builder().baseUrl("http://localhost:" + PORT))
						.sseEndpoint(CUSTOM_SSE_ENDPOINT)
						.build()));

	}

	@AfterEach
	public void after() {
		if (httpServer != null) {
			httpServer.disposeNow();
		}
	}

	// ---------------------------------------
	// Sampling Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateMessageWithoutSamplingCapabilities(String clientType) {

		McpClient.Builder clientBuilder = clientBuilders.get(clientType);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					exchange.createMessage(mock(McpSchema.CreateMessageRequest.class)).block();

					return Mono.just(mock(CallToolResult.class));
				});

		McpServer server = McpServer.async(mcpServerTransportProvider).serverInfo("test-server", "1.0.0").tools(tool).build();

		try (McpClient client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0"))
			.build();) {

			assertThat(client.initialize()).isNotNull();

			try {
				Map<String, Object> emptyParams = new HashMap<>();
				client.callTool(new McpSchema.CallToolRequest("tool1", emptyParams));
			}
			catch (McpError e) {
				assertThat(e).isInstanceOf(McpError.class)
					.hasMessage("Client must be configured with sampling capabilities");
			}
		}
		server.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateMessageSuccess(String clientType) throws InterruptedException {

		McpClient.Builder clientBuilder = clientBuilders.get(clientType);

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.messages()).hasSize(1);
			assertThat(request.messages().get(0).content()).isInstanceOf(McpSchema.TextContent.class);

			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		List<McpSchema.TextContent> contentList = new ArrayList<>();
		contentList.add(new McpSchema.TextContent("CALL RESPONSE"));
		CallToolResult callResponse = new McpSchema.CallToolResult(contentList, null);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					List<McpSchema.SamplingMessage> messages = new ArrayList<>();
					messages.add(new McpSchema.SamplingMessage(McpSchema.Role.USER, 
							new McpSchema.TextContent("Test message")));
					
					List<String> emptyHints = new ArrayList<>();
					
					McpSchema.CreateMessageRequest craeteMessageRequest = McpSchema.CreateMessageRequest.builder()
						.messages(messages)
						.modelPreferences(ModelPreferences.builder()
							.hints(emptyHints)
							.costPriority(1.0)
							.speedPriority(1.0)
							.intelligencePriority(1.0)
							.build())
						.build();

					StepVerifier.create(exchange.createMessage(craeteMessageRequest)).consumeNextWith(result -> {
						assertThat(result).isNotNull();
						assertThat(result.role()).isEqualTo(Role.USER);
						assertThat(result.content()).isInstanceOf(McpSchema.TextContent.class);
						assertThat(((McpSchema.TextContent) result.content()).text()).isEqualTo("Test message");
						assertThat(result.model()).isEqualTo("MockModelName");
						assertThat(result.stopReason()).isEqualTo(CreateMessageResult.StopReason.STOP_SEQUENCE);
					}).verifyComplete();

					return Mono.just(callResponse);
				});

		McpServer mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.tools(tool)
			.build();

		try (McpClient mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			Map<String, Object> emptyParams = new HashMap<>();
			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", emptyParams));

			assertThat(response).isNotNull();
			assertThat(response).isEqualTo(callResponse);
		}
		mcpServer.close();
	}

	// ---------------------------------------
	// Roots Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsSuccess(String clientType) {
		McpClient.Builder clientBuilder = clientBuilders.get(clientType);

		List<Root> roots = new ArrayList<>();
		roots.add(new Root("uri1://", "root1"));
		roots.add(new Root("uri2://", "root2"));

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		McpServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
			.build();

		try (McpClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(rootsRef.get()).isNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(roots);
			});

			// Remove a root
			mcpClient.removeRoot(roots.get(0).uri());

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				List<Root> expectedRoots1 = new ArrayList<>();
				expectedRoots1.add(roots.get(1));
				assertThat(rootsRef.get()).containsAll(expectedRoots1);
			});

			// Add a new root
			Root root3 = new Root("uri3://", "root3");
			mcpClient.addRoot(root3);

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				List<Root> expectedRoots2 = new ArrayList<>();
				expectedRoots2.add(roots.get(1));
				expectedRoots2.add(root3);
				assertThat(rootsRef.get()).containsAll(expectedRoots2);
			});
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsWithoutCapability(String clientType) {

		McpClient.Builder clientBuilder = clientBuilders.get(clientType);

		McpServerFeatures.SyncToolSpecification tool = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					exchange.listRoots(); // try to list roots

					return mock(CallToolResult.class);
				});

		McpServer mcpServer = McpServer.sync(mcpServerTransportProvider).rootsChangeHandler((exchange, rootsUpdate) -> {
		}).tools(tool).build();

		try (
				// Create client without roots capability
				McpClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().build()).build()) {

			assertThat(mcpClient.initialize()).isNotNull();

			// Attempt to list roots should fail
			try {
				Map<String, Object> emptyParams = new HashMap<>();
				mcpClient.callTool(new McpSchema.CallToolRequest("tool1", emptyParams));
			}
			catch (McpError e) {
				assertThat(e).isInstanceOf(McpError.class).hasMessage("Roots not supported");
			}
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsNotifciationWithEmptyRootsList(String clientType) {
		McpClient.Builder clientBuilder = clientBuilders.get(clientType);

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		McpServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
			.build();

		List<Root> emptyRoots = new ArrayList<>();
		try (McpClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(emptyRoots) // Empty roots list
			.build()) {

			assertThat(mcpClient.initialize()).isNotNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).isEmpty();
			});
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsWithMultipleHandlers(String clientType) {

		McpClient.Builder clientBuilder = clientBuilders.get(clientType);

		List<Root> roots = new ArrayList<>();
		roots.add(new Root("uri1://", "root1"));

		AtomicReference<List<Root>> rootsRef1 = new AtomicReference<>();
		AtomicReference<List<Root>> rootsRef2 = new AtomicReference<>();

		McpServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef1.set(rootsUpdate))
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef2.set(rootsUpdate))
			.build();

		try (McpClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef1.get()).containsAll(roots);
				assertThat(rootsRef2.get()).containsAll(roots);
			});
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsServerCloseWithActiveSubscription(String clientType) {

		McpClient.Builder clientBuilder = clientBuilders.get(clientType);

		List<Root> roots = new ArrayList<>();
		roots.add(new Root("uri1://", "root1"));

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		McpServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
			.build();

		try (McpClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(roots);
			});
		}

		mcpServer.close();
	}

	// ---------------------------------------
	// Tools Tests
	// ---------------------------------------

	String emptyJsonSchema = "{\n" +
			"\"$schema\": \"http://json-schema.org/draft-07/schema#\",\n" +
			"\"type\": \"object\",\n" +
			"\"properties\": {}\n" +
			"}";

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testToolCallSuccess(String clientType) {

		McpClient.Builder clientBuilder = clientBuilders.get(clientType);

		List<McpSchema.TextContent> contentList = new ArrayList<>();
		contentList.add(new McpSchema.TextContent("CALL RESPONSE"));
		McpSchema.CallToolResult callResponse = new McpSchema.CallToolResult(contentList, null);
		
		McpServerFeatures.SyncToolSpecification tool1 = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {
					// perform a blocking call to a remote service
					String response = RestClient.create()
						.get()
						.uri("https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md")
						.retrieve()
						.body(String.class);
					assertThat(response).isNotBlank();
					return callResponse;
				});

		McpServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		try (McpClient mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(mcpClient.listTools().tools()).contains(tool1.tool());

			Map<String, Object> emptyParams = new HashMap<>();
			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", emptyParams));

			assertThat(response).isNotNull();
			assertThat(response).isEqualTo(callResponse);
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testToolListChangeHandlingSuccess(String clientType) {

		McpClient.Builder clientBuilder = clientBuilders.get(clientType);

		List<McpSchema.TextContent> contentList = new ArrayList<>();
		contentList.add(new McpSchema.TextContent("CALL RESPONSE"));
		McpSchema.CallToolResult callResponse = new McpSchema.CallToolResult(contentList, null);
		
		McpServerFeatures.SyncToolSpecification tool1 = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {
					// perform a blocking call to a remote service
					String response = RestClient.create()
						.get()
						.uri("https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md")
						.retrieve()
						.body(String.class);
					assertThat(response).isNotBlank();
					return callResponse;
				});

		AtomicReference<List<Tool>> rootsRef = new AtomicReference<>();

		McpServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		try (McpClient mcpClient = clientBuilder.toolsChangeConsumer(toolsUpdate -> {
			// perform a blocking call to a remote service
			String response = RestClient.create()
				.get()
				.uri("https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md")
				.retrieve()
				.body(String.class);
			assertThat(response).isNotBlank();
			rootsRef.set(toolsUpdate);
		}).build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(rootsRef.get()).isNull();

			assertThat(mcpClient.listTools().tools()).contains(tool1.tool());

			mcpServer.notifyToolsListChanged();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				List<Tool> expectedTools1 = new ArrayList<>();
				expectedTools1.add(tool1.tool());
				assertThat(rootsRef.get()).containsAll(expectedTools1);
			});

			// Remove a tool
			mcpServer.removeTool("tool1");

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).isEmpty();
			});

			// Add a new tool
			McpServerFeatures.SyncToolSpecification tool2 = new McpServerFeatures.SyncToolSpecification(
					new McpSchema.Tool("tool2", "tool2 description", emptyJsonSchema),
					(exchange, request) -> callResponse);

			mcpServer.addTool(tool2);

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				List<Tool> expectedTools2 = new ArrayList<>();
				expectedTools2.add(tool2.tool());
				assertThat(rootsRef.get()).containsAll(expectedTools2);
			});
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testInitialize(String clientType) {

		McpClient.Builder clientBuilder = clientBuilders.get(clientType);

		McpServer mcpServer = McpServer.sync(mcpServerTransportProvider).build();

		try (McpClient mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();
		}

		mcpServer.close();
	}

	// ---------------------------------------
	// Logging Tests
	// ---------------------------------------

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testLoggingNotification(String clientType) {
		// Create a list to store received logging notifications
		List<McpSchema.LoggingMessageNotification> receivedNotifications = new ArrayList<>();

		McpClient.Builder clientBuilder = clientBuilders.get(clientType);

		// Create server with a tool that sends logging notifications
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("logging-test", "Test logging notifications", emptyJsonSchema),
				(exchange, request) -> {

					// Create and send notifications with different levels

				//@formatter:off
					return exchange // This should be filtered out (DEBUG < NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
								.level(McpSchema.LoggingLevel.DEBUG)
								.logger("test-logger")
								.data("Debug message")
								.build())
					.then(exchange // This should be sent (NOTICE >= NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
								.level(McpSchema.LoggingLevel.NOTICE)
								.logger("test-logger")
								.data("Notice message")
								.build()))
					.then(exchange // This should be sent (ERROR > NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
							.level(McpSchema.LoggingLevel.ERROR)
							.logger("test-logger")
							.data("Error message")
							.build()))
					.then(exchange // This should be filtered out (INFO < NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
								.level(McpSchema.LoggingLevel.INFO)
								.logger("test-logger")
								.data("Another info message")
								.build()))
					.then(exchange // This should be sent (ERROR >= NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
								.level(McpSchema.LoggingLevel.ERROR)
								.logger("test-logger")
								.data("Another error message")
								.build()))
					.thenReturn(new CallToolResult("Logging test completed", false));
					//@formatter:on
				});

		McpServer mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().logging().tools(true).build())
			.tools(tool)
			.build();

		try (
				// Create client with logging notification handler
				McpClient mcpClient = clientBuilder.loggingConsumer(notification -> {
					receivedNotifications.add(notification);
				}).build()) {

			// Initialize client
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Set minimum logging level to NOTICE
			mcpClient.setLoggingLevel(McpSchema.LoggingLevel.NOTICE);

			// Call the tool that sends logging notifications
			Map<String, Object> emptyParams = new HashMap<>();
			CallToolResult result = mcpClient.callTool(new McpSchema.CallToolRequest("logging-test", emptyParams));
			assertThat(result).isNotNull();
			assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("Logging test completed");

			// Wait for notifications to be processed
			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {

				// Should have received 3 notifications (1 NOTICE and 2 ERROR)
				assertThat(receivedNotifications).hasSize(3);

				Map<String, McpSchema.LoggingMessageNotification> notificationMap = receivedNotifications.stream()
					.collect(Collectors.toMap(n -> n.data(), n -> n));

				// First notification should be NOTICE level
				assertThat(notificationMap.get("Notice message").level()).isEqualTo(McpSchema.LoggingLevel.NOTICE);
				assertThat(notificationMap.get("Notice message").logger()).isEqualTo("test-logger");
				assertThat(notificationMap.get("Notice message").data()).isEqualTo("Notice message");

				// Second notification should be ERROR level
				assertThat(notificationMap.get("Error message").level()).isEqualTo(McpSchema.LoggingLevel.ERROR);
				assertThat(notificationMap.get("Error message").logger()).isEqualTo("test-logger");
				assertThat(notificationMap.get("Error message").data()).isEqualTo("Error message");

				// Third notification should be ERROR level
				assertThat(notificationMap.get("Another error message").level())
					.isEqualTo(McpSchema.LoggingLevel.ERROR);
				assertThat(notificationMap.get("Another error message").logger()).isEqualTo("test-logger");
				assertThat(notificationMap.get("Another error message").data()).isEqualTo("Another error message");
			});
		}
		mcpServer.close();
	}

}
