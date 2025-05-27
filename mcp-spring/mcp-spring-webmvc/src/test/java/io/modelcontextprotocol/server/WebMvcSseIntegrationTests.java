/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
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
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

class WebMvcSseIntegrationTests {

	private static final int PORT = 8183;

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private WebMvcSseServerTransportProvider mcpServerTransportProvider;

	McpClient.SyncSpec clientBuilder;

	@Configuration
	@EnableWebMvc
	static class TestConfig {

		@Bean
		public WebMvcSseServerTransportProvider webMvcSseServerTransportProvider() {
			return new WebMvcSseServerTransportProvider(new ObjectMapper(), MESSAGE_ENDPOINT);
		}

		@Bean
		public RouterFunction<ServerResponse> routerFunction(WebMvcSseServerTransportProvider transportProvider) {
			return transportProvider.getRouterFunction();
		}

	}

	private TomcatTestUtil.TomcatServer tomcatServer;

	@BeforeEach
	public void before() {

		tomcatServer = new TomcatTestUtil().createTomcatServer("", PORT, TestConfig.class);

		try {
			tomcatServer.tomcat().start();
			assertThat(tomcatServer.tomcat().getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		clientBuilder = McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT).build());

		// Get the transport from Spring context
		mcpServerTransportProvider = tomcatServer.appContext().getBean(WebMvcSseServerTransportProvider.class);

	}

	@AfterEach
	public void after() {
		if (mcpServerTransportProvider != null) {
			mcpServerTransportProvider.closeGracefully().block();
		}
		if (tomcatServer.appContext() != null) {
			tomcatServer.appContext().close();
		}
		if (tomcatServer.tomcat() != null) {
			try {
				tomcatServer.tomcat().stop();
				tomcatServer.tomcat().destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
			}
		}
	}

	// ---------------------------------------
	// Sampling Tests
	// ---------------------------------------
	@Test
	void testCreateMessageWithoutSamplingCapabilities() {

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					exchange.createMessage(mock(McpSchema.CreateMessageRequest.class)).block();

					return Mono.just(mock(CallToolResult.class));
				});

		//@formatter:off
		McpServer server = McpServer.async(mcpServerTransportProvider)
				.serverInfo("test-server", "1.0.0")
				.tools(tool)
				.build();
		
		try (
			// Create client without sampling capabilities
			McpClient client = clientBuilder
				.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0"))
				.build()) {//@formatter:on

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

	@Test
	void testCreateMessageSuccess() throws InterruptedException {

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.messages()).hasSize(1);
			assertThat(request.messages().get(0).content()).isInstanceOf(McpSchema.TextContent.class);

			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		List<McpSchema.Content> contentList = Collections.singletonList(new McpSchema.TextContent("CALL RESPONSE"));
		CallToolResult callResponse = new McpSchema.CallToolResult(contentList, null);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					McpSchema.CreateMessageRequest.Builder craeteMessageRequest = McpSchema.CreateMessageRequest.builder()
						.messages(Collections.singletonList(new McpSchema.SamplingMessage(McpSchema.Role.USER,
								new McpSchema.TextContent("Test message"))))
						.modelPreferences(ModelPreferences.builder()
							.hints(Collections.emptyList())
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

		//@formatter:off		
		McpServer mcpServer = McpServer.async(mcpServerTransportProvider)
				.serverInfo("test-server", "1.0.0")
				.tools(tool)
				.build();

		try (
			McpClient mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
				.capabilities(ClientCapabilities.builder().sampling().build())
				.sampling(samplingHandler)
				.build()) {//@formatter:on

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			Map<String, Object> emptyParams = new HashMap<>();
			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", emptyParams));

			assertThat(response).isNotNull().isEqualTo(callResponse);
		}
		mcpServer.close();
	}

	// ---------------------------------------
	// Roots Tests
	// ---------------------------------------
	@Test
	void testRootsSuccess() {
		List<Root> roots = Arrays.asList(new Root("uri1://", "root1"), new Root("uri2://", "root2"));

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
				List<Root> expectedRoots = Arrays.asList(roots.get(1));
				assertThat(rootsRef.get()).containsAll(expectedRoots);
			});

			// Add a new root
			Root root3 = new Root("uri3://", "root3");
			mcpClient.addRoot(root3);

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				List<Root> expectedRoots = new ArrayList<>();
				expectedRoots.add(roots.get(1));
				expectedRoots.add(root3);
				assertThat(rootsRef.get()).containsAll(expectedRoots);
			});
		}

		mcpServer.close();
	}

	@Test
	void testRootsWithoutCapability() {

		McpServerFeatures.SyncToolSpecification tool = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					exchange.listRoots(); // try to list roots

					return mock(CallToolResult.class);
				});

		McpServer mcpServer = McpServer.sync(mcpServerTransportProvider).rootsChangeHandler((exchange, rootsUpdate) -> {
		}).tools(tool).build();

		try (
				// Create client without roots capability
				// No roots capability
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

	@Test
	void testRootsNotifciationWithEmptyRootsList() {
		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		McpServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
			.build();

		try (McpClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(new ArrayList<>()) // Empty roots list
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).isEmpty();
			});
		}

		mcpServer.close();
	}

	@Test
	void testRootsWithMultipleHandlers() {
		List<Root> roots = Arrays.asList(new Root("uri1://", "root1"));

		AtomicReference<List<Root>> rootsRef1 = new AtomicReference<>();
		AtomicReference<List<Root>> rootsRef2 = new AtomicReference<>();

		McpServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef1.set(rootsUpdate))
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef2.set(rootsUpdate))
			.build();

		try (McpClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build()) {

			assertThat(mcpClient.initialize()).isNotNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef1.get()).containsAll(roots);
				assertThat(rootsRef2.get()).containsAll(roots);
			});
		}

		mcpServer.close();
	}

	@Test
	void testRootsServerCloseWithActiveSubscription() {
		List<Root> roots = Arrays.asList(new Root("uri1://", "root1"));

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
			"\t\"$schema\": \"http://json-schema.org/draft-07/schema#\",\n" +
			"\t\"type\": \"object\",\n" +
			"\t\"properties\": {}\n" +
			"}";

	@Test
	void testToolCallSuccess() {

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

			assertThat(response).isNotNull().isEqualTo(callResponse);
		}

		mcpServer.close();
	}

	@Test
	void testToolListChangeHandlingSuccess() {

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
				List<Tool> expectedTools = new ArrayList<>();
				expectedTools.add(tool1.tool());
				assertThat(rootsRef.get()).containsAll(expectedTools);
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
				List<Tool> expectedTools = new ArrayList<>();
				expectedTools.add(tool2.tool());
				assertThat(rootsRef.get()).containsAll(expectedTools);
			});
		}

		mcpServer.close();
	}

	@Test
	void testInitialize() {

		McpServer mcpServer = McpServer.sync(mcpServerTransportProvider).build();

		try (McpClient mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();
		}

		mcpServer.close();
	}

}
