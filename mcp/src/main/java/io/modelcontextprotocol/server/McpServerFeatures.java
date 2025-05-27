/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * MCP server features specification that a particular server can choose to support.
 *
 * @author Dariusz Jędrzejczyk
 */
public class McpServerFeatures {

	/**
	 * Asynchronous server features specification.
	 *
	 * @param serverInfo The server implementation details
	 * @param serverCapabilities The server capabilities
	 * @param tools The list of tool specifications
	 * @param resources The map of resource specifications
	 * @param resourceTemplates The list of resource templates
	 * @param prompts The map of prompt specifications
	 * @param rootsChangeConsumers The list of consumers that will be notified when the
	 * roots list changes
	 * @param instructions The server instructions text
	 */
	public static class Async {
		private final McpSchema.Implementation serverInfo;
		private final McpSchema.ServerCapabilities serverCapabilities;
		private final List<McpServerFeatures.AsyncToolSpecification> tools;
		private final Map<String, AsyncResourceSpecification> resources;
		private final List<McpSchema.ResourceTemplate> resourceTemplates;
		private final Map<String, McpServerFeatures.AsyncPromptSpecification> prompts;
		private final List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers;
		private final String instructions;
		
		public McpSchema.Implementation serverInfo() {
			return serverInfo;
		}
		
		public McpSchema.ServerCapabilities serverCapabilities() {
			return serverCapabilities;
		}
		
		public List<McpServerFeatures.AsyncToolSpecification> tools() {
			return tools;
		}
		
		public Map<String, AsyncResourceSpecification> resources() {
			return resources;
		}
		
		public List<McpSchema.ResourceTemplate> resourceTemplates() {
			return resourceTemplates;
		}
		
		public Map<String, McpServerFeatures.AsyncPromptSpecification> prompts() {
			return prompts;
		}
		
		public List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers() {
			return rootsChangeConsumers;
		}
		
		public String instructions() {
			return instructions;
		}

		/**
		 * Create an instance and validate the arguments.
		 * @param serverInfo The server implementation details
		 * @param serverCapabilities The server capabilities
		 * @param tools The list of tool specifications
		 * @param resources The map of resource specifications
		 * @param resourceTemplates The list of resource templates
		 * @param prompts The map of prompt specifications
		 * @param rootsChangeConsumers The list of consumers that will be notified when
		 * the roots list changes
		 * @param instructions The server instructions text
		 */
		Async(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
				List<McpServerFeatures.AsyncToolSpecification> tools, Map<String, AsyncResourceSpecification> resources,
				List<McpSchema.ResourceTemplate> resourceTemplates,
				Map<String, McpServerFeatures.AsyncPromptSpecification> prompts,
				List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers,
				String instructions) {

			Assert.notNull(serverInfo, "Server info must not be null");

			this.serverInfo = serverInfo;
			this.serverCapabilities = (serverCapabilities != null) ? serverCapabilities
					: new McpSchema.ServerCapabilities(null, // experimental
							new McpSchema.ServerCapabilities.LoggingCapabilities(), // Enable
																					// logging
																					// by
																					// default
							!Utils.isEmpty(prompts) ? new McpSchema.ServerCapabilities.PromptCapabilities(false) : null,
							!Utils.isEmpty(resources)
									? new McpSchema.ServerCapabilities.ResourceCapabilities(false, false) : null,
							!Utils.isEmpty(tools) ? new McpSchema.ServerCapabilities.ToolCapabilities(false) : null);

			this.tools = (tools != null) ? tools : new ArrayList<>();
			this.resources = (resources != null) ? resources : new HashMap<>();
			this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates : new ArrayList<>();
			this.prompts = (prompts != null) ? prompts : new HashMap<>();
			this.rootsChangeConsumers = (rootsChangeConsumers != null) ? rootsChangeConsumers : new ArrayList<>();
			this.instructions = instructions;
		}
		
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Async async = (Async) o;
			return Objects.equals(serverInfo, async.serverInfo) &&
					Objects.equals(serverCapabilities, async.serverCapabilities) &&
					Objects.equals(tools, async.tools) &&
					Objects.equals(resources, async.resources) &&
					Objects.equals(resourceTemplates, async.resourceTemplates) &&
					Objects.equals(prompts, async.prompts) &&
					Objects.equals(rootsChangeConsumers, async.rootsChangeConsumers) &&
					Objects.equals(instructions, async.instructions);
		}

		@Override
		public int hashCode() {
			return Objects.hash(serverInfo, serverCapabilities, tools, resources, resourceTemplates, prompts, 
					rootsChangeConsumers, instructions);
		}

		@Override
		public String toString() {
			return "Async{" +
					"serverInfo=" + serverInfo +
					", serverCapabilities=" + serverCapabilities +
					", tools=" + tools +
					", resources=" + resources +
					", resourceTemplates=" + resourceTemplates +
					", prompts=" + prompts +
					", rootsChangeConsumers=" + rootsChangeConsumers +
					", instructions='" + instructions + '\'' +
					'}';
		}

		/**
		 * Convert a synchronous specification into an asynchronous one and provide
		 * blocking code offloading to prevent accidental blocking of the non-blocking
		 * transport.
		 * @param syncSpec a potentially blocking, synchronous specification.
		 * @return a specification which is protected from blocking calls specified by the
		 * user.
		 */
		static Async fromSync(Sync syncSpec) {
			List<McpServerFeatures.AsyncToolSpecification> tools = new ArrayList<>();
			for (SyncToolSpecification tool : syncSpec.tools()) {
				tools.add(AsyncToolSpecification.fromSync(tool));
			}

			Map<String, AsyncResourceSpecification> resources = new HashMap<>();
			syncSpec.resources().forEach((key, resource) -> {
				resources.put(key, AsyncResourceSpecification.fromSync(resource));
			});

			Map<String, AsyncPromptSpecification> prompts = new HashMap<>();
			syncSpec.prompts().forEach((key, prompt) -> {
				prompts.put(key, AsyncPromptSpecification.fromSync(prompt));
			});

			List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootChangeConsumers = new ArrayList<>();

			for (BiConsumer<McpSyncServerExchange, List<McpSchema.Root>> rootChangeConsumer : syncSpec.rootsChangeConsumers()) {
				rootChangeConsumers.add((exchange, list) -> Mono
					.<Void>fromRunnable(() -> rootChangeConsumer.accept(new McpSyncServerExchange(exchange), list))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			return new Async(syncSpec.serverInfo(), syncSpec.serverCapabilities(), tools, resources,
					syncSpec.resourceTemplates(), prompts, rootChangeConsumers, syncSpec.instructions());
		}
	}

	/**
	 * Synchronous server features specification.
	 *
	 * @param serverInfo The server implementation details
	 * @param serverCapabilities The server capabilities
	 * @param tools The list of tool specifications
	 * @param resources The map of resource specifications
	 * @param resourceTemplates The list of resource templates
	 * @param prompts The map of prompt specifications
	 * @param rootsChangeConsumers The list of consumers that will be notified when the
	 * roots list changes
	 * @param instructions The server instructions text
	 */
	public static class Sync {
		private final McpSchema.Implementation serverInfo;
		private final McpSchema.ServerCapabilities serverCapabilities;
		private final List<McpServerFeatures.SyncToolSpecification> tools;
		private final Map<String, McpServerFeatures.SyncResourceSpecification> resources;
		private final List<McpSchema.ResourceTemplate> resourceTemplates;
		private final Map<String, McpServerFeatures.SyncPromptSpecification> prompts;
		private final List<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> rootsChangeConsumers;
		private final String instructions;
		
		public McpSchema.Implementation serverInfo() {
			return serverInfo;
		}
		
		public McpSchema.ServerCapabilities serverCapabilities() {
			return serverCapabilities;
		}
		
		public List<McpServerFeatures.SyncToolSpecification> tools() {
			return tools;
		}
		
		public Map<String, McpServerFeatures.SyncResourceSpecification> resources() {
			return resources;
		}
		
		public List<McpSchema.ResourceTemplate> resourceTemplates() {
			return resourceTemplates;
		}
		
		public Map<String, McpServerFeatures.SyncPromptSpecification> prompts() {
			return prompts;
		}
		
		public List<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> rootsChangeConsumers() {
			return rootsChangeConsumers;
		}
		
		public String instructions() {
			return instructions;
		}

		/**
		 * Create an instance and validate the arguments.
		 * @param serverInfo The server implementation details
		 * @param serverCapabilities The server capabilities
		 * @param tools The list of tool specifications
		 * @param resources The map of resource specifications
		 * @param resourceTemplates The list of resource templates
		 * @param prompts The map of prompt specifications
		 * @param rootsChangeConsumers The list of consumers that will be notified when
		 * the roots list changes
		 * @param instructions The server instructions text
		 */
		Sync(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
				List<McpServerFeatures.SyncToolSpecification> tools,
				Map<String, McpServerFeatures.SyncResourceSpecification> resources,
				List<McpSchema.ResourceTemplate> resourceTemplates,
				Map<String, McpServerFeatures.SyncPromptSpecification> prompts,
				List<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> rootsChangeConsumers,
				String instructions) {

			Assert.notNull(serverInfo, "Server info must not be null");

			this.serverInfo = serverInfo;
			this.serverCapabilities = (serverCapabilities != null) ? serverCapabilities
					: new McpSchema.ServerCapabilities(null, // experimental
							new McpSchema.ServerCapabilities.LoggingCapabilities(), // Enable
																					// logging
																					// by
																					// default
							!Utils.isEmpty(prompts) ? new McpSchema.ServerCapabilities.PromptCapabilities(false) : null,
							!Utils.isEmpty(resources)
									? new McpSchema.ServerCapabilities.ResourceCapabilities(false, false) : null,
							!Utils.isEmpty(tools) ? new McpSchema.ServerCapabilities.ToolCapabilities(false) : null);

			this.tools = (tools != null) ? tools : new ArrayList<>();
			this.resources = (resources != null) ? resources : new HashMap<>();
			this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates : new ArrayList<>();
			this.prompts = (prompts != null) ? prompts : new HashMap<>();
			this.rootsChangeConsumers = (rootsChangeConsumers != null) ? rootsChangeConsumers : new ArrayList<>();
			this.instructions = instructions;
		}
		
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Sync sync = (Sync) o;
			return Objects.equals(serverInfo, sync.serverInfo) &&
					Objects.equals(serverCapabilities, sync.serverCapabilities) &&
					Objects.equals(tools, sync.tools) &&
					Objects.equals(resources, sync.resources) &&
					Objects.equals(resourceTemplates, sync.resourceTemplates) &&
					Objects.equals(prompts, sync.prompts) &&
					Objects.equals(rootsChangeConsumers, sync.rootsChangeConsumers) &&
					Objects.equals(instructions, sync.instructions);
		}

		@Override
		public int hashCode() {
			return Objects.hash(serverInfo, serverCapabilities, tools, resources, resourceTemplates, prompts, 
					rootsChangeConsumers, instructions);
		}

		@Override
		public String toString() {
			return "Sync{" +
					"serverInfo=" + serverInfo +
					", serverCapabilities=" + serverCapabilities +
					", tools=" + tools +
					", resources=" + resources +
					", resourceTemplates=" + resourceTemplates +
					", prompts=" + prompts +
					", rootsChangeConsumers=" + rootsChangeConsumers +
					", instructions='" + instructions + '\'' +
					'}';

	}

	/**
	 * Specification of a tool with its asynchronous handler function. Tools are the
	 * primary way for MCP servers to expose functionality to AI models. Each tool
	 * represents a specific capability, such as:
	 * <ul>
	 * <li>Performing calculations
	 * <li>Accessing external APIs
	 * <li>Querying databases
	 * <li>Manipulating files
	 * <li>Executing system commands
	 * </ul>
	 *
	 * <p>
	 * Example tool specification: <pre>{@code
	 * new McpServerFeatures.AsyncToolSpecification(
	 *     new Tool(
	 *         "calculator",
	 *         "Performs mathematical calculations",
	 *         new JsonSchemaObject()
	 *             .required("expression")
	 *             .property("expression", JsonSchemaType.STRING)
	 *     ),
	 *     (exchange, args) -> {
	 *         String expr = (String) args.get("expression");
	 *         return Mono.fromSupplier(() -> evaluate(expr))
	 *             .map(result -> new CallToolResult("Result: " + result));
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param tool The tool definition including name, description, and parameter schema
	 * @param call The function that implements the tool's logic, receiving arguments and
	 * returning results. The function's first argument is an
	 * {@link McpAsyncServerExchange} upon which the server can interact with the
	 * connected client. The second arguments is a map of tool arguments.
	 */
	public static class AsyncToolSpecification {
		private final McpSchema.Tool tool;
		private final BiFunction<McpAsyncServerExchange, Map<String, Object>, Mono<McpSchema.CallToolResult>> call;
		
		public AsyncToolSpecification(McpSchema.Tool tool,
				BiFunction<McpAsyncServerExchange, Map<String, Object>, Mono<McpSchema.CallToolResult>> call) {
			this.tool = tool;
			this.call = call;
		}
		
		public McpSchema.Tool tool() {
			return tool;
		}
		
		public BiFunction<McpAsyncServerExchange, Map<String, Object>, Mono<McpSchema.CallToolResult>> call() {
			return call;
		}
		
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AsyncToolSpecification that = (AsyncToolSpecification) o;
			return Objects.equals(tool, that.tool) &&
					Objects.equals(call, that.call);
		}
		
		@Override
		public int hashCode() {
			return Objects.hash(tool, call);
		}
		
		@Override
		public String toString() {
			return "AsyncToolSpecification{" +
					"tool=" + tool +
					", call=" + call +
					'}';
		}

		static AsyncToolSpecification fromSync(SyncToolSpecification tool) {
			// FIXME: This is temporary, proper validation should be implemented
			if (tool == null) {
				return null;
			}
			return new AsyncToolSpecification(tool.tool(),
					(exchange, map) -> Mono
						.fromCallable(() -> tool.call().apply(new McpSyncServerExchange(exchange), map))
						.subscribeOn(Schedulers.boundedElastic()));
		}
	}

	/**
	 * Specification of a resource with its asynchronous handler function. Resources
	 * provide context to AI models by exposing data such as:
	 * <ul>
	 * <li>File contents
	 * <li>Database records
	 * <li>API responses
	 * <li>System information
	 * <li>Application state
	 * </ul>
	 *
	 * <p>
	 * Example resource specification: <pre>{@code
	 * new McpServerFeatures.AsyncResourceSpecification(
	 *     new Resource("docs", "Documentation files", "text/markdown"),
	 *     (exchange, request) ->
	 *         Mono.fromSupplier(() -> readFile(request.getPath()))
	 *             .map(ReadResourceResult::new)
	 * )
	 * }</pre>
	 *
	 * @param resource The resource definition including name, description, and MIME type
	 * @param readHandler The function that handles resource read requests. The function's
	 * first argument is an {@link McpAsyncServerExchange} upon which the server can
	 * interact with the connected client. The second arguments is a
	 * {@link io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest}.
	 */
	public static class AsyncResourceSpecification {
		private final McpSchema.Resource resource;
		private final BiFunction<McpAsyncServerExchange, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler;
		
		public AsyncResourceSpecification(McpSchema.Resource resource,
				BiFunction<McpAsyncServerExchange, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler) {
			this.resource = resource;
			this.readHandler = readHandler;
		}
		
		public McpSchema.Resource resource() {
			return resource;
		}
		
		public BiFunction<McpAsyncServerExchange, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler() {
			return readHandler;
		}
		
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AsyncResourceSpecification that = (AsyncResourceSpecification) o;
			return Objects.equals(resource, that.resource) &&
					Objects.equals(readHandler, that.readHandler);
		}
		
		@Override
		public int hashCode() {
			return Objects.hash(resource, readHandler);
		}
		
		@Override
		public String toString() {
			return "AsyncResourceSpecification{" +
					"resource=" + resource +
					", readHandler=" + readHandler +
					'}';
		}

		static AsyncResourceSpecification fromSync(SyncResourceSpecification resource) {
			// FIXME: This is temporary, proper validation should be implemented
			if (resource == null) {
				return null;
			}
			return new AsyncResourceSpecification(resource.resource(),
					(exchange, req) -> Mono
						.fromCallable(() -> resource.readHandler().apply(new McpSyncServerExchange(exchange), req))
						.subscribeOn(Schedulers.boundedElastic()));
		}
	}

	/**
	 * Specification of a prompt template with its asynchronous handler function. Prompts
	 * provide structured templates for AI model interactions, supporting:
	 * <ul>
	 * <li>Consistent message formatting
	 * <li>Parameter substitution
	 * <li>Context injection
	 * <li>Response formatting
	 * <li>Instruction templating
	 * </ul>
	 *
	 * <p>
	 * Example prompt specification: <pre>{@code
	 * new McpServerFeatures.AsyncPromptSpecification(
	 *     new Prompt("analyze", "Code analysis template"),
	 *     (exchange, request) -> {
	 *         String code = request.getArguments().get("code");
	 *         return Mono.just(new GetPromptResult(
	 *             "Analyze this code:\n\n" + code + "\n\nProvide feedback on:"
	 *         ));
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param prompt The prompt definition including name and description
	 * @param promptHandler The function that processes prompt requests and returns
	 * formatted templates. The function's first argument is an
	 * {@link McpAsyncServerExchange} upon which the server can interact with the
	 * connected client. The second arguments is a
	 * {@link io.modelcontextprotocol.spec.McpSchema.GetPromptRequest}.
	 */
	public static class AsyncPromptSpecification {
		private final McpSchema.Prompt prompt;
		private final BiFunction<McpAsyncServerExchange, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler;
		
		public AsyncPromptSpecification(McpSchema.Prompt prompt,
				BiFunction<McpAsyncServerExchange, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler) {
			this.prompt = prompt;
			this.promptHandler = promptHandler;
		}
		
		public McpSchema.Prompt prompt() {
			return prompt;
		}
		
		public BiFunction<McpAsyncServerExchange, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler() {
			return promptHandler;
		}
		
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AsyncPromptSpecification that = (AsyncPromptSpecification) o;
			return Objects.equals(prompt, that.prompt) &&
					Objects.equals(promptHandler, that.promptHandler);
		}
		
		@Override
		public int hashCode() {
			return Objects.hash(prompt, promptHandler);
		}
		
		@Override
		public String toString() {
			return "AsyncPromptSpecification{" +
					"prompt=" + prompt +
					", promptHandler=" + promptHandler +
					'}';
		}

		static AsyncPromptSpecification fromSync(SyncPromptSpecification prompt) {
			// FIXME: This is temporary, proper validation should be implemented
			if (prompt == null) {
				return null;
			}
			return new AsyncPromptSpecification(prompt.prompt(),
					(exchange, req) -> Mono
						.fromCallable(() -> prompt.promptHandler().apply(new McpSyncServerExchange(exchange), req))
						.subscribeOn(Schedulers.boundedElastic()));
		}
	}

	/**
	 * Specification of a tool with its synchronous handler function. Tools are the
	 * primary way for MCP servers to expose functionality to AI models. Each tool
	 * represents a specific capability, such as:
	 * <ul>
	 * <li>Performing calculations
	 * <li>Accessing external APIs
	 * <li>Querying databases
	 * <li>Manipulating files
	 * <li>Executing system commands
	 * </ul>
	 *
	 * <p>
	 * Example tool specification: <pre>{@code
	 * new McpServerFeatures.SyncToolSpecification(
	 *     new Tool(
	 *         "calculator",
	 *         "Performs mathematical calculations",
	 *         new JsonSchemaObject()
	 *             .required("expression")
	 *             .property("expression", JsonSchemaType.STRING)
	 *     ),
	 *     (exchange, args) -> {
	 *         String expr = (String) args.get("expression");
	 *         return new CallToolResult("Result: " + evaluate(expr));
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param tool The tool definition including name, description, and parameter schema
	 * @param call The function that implements the tool's logic, receiving arguments and
	 * returning results. The function's first argument is an
	 * {@link McpSyncServerExchange} upon which the server can interact with the connected
	 * client. The second arguments is a map of arguments passed to the tool.
	 */
	public static class SyncToolSpecification {
		private final McpSchema.Tool tool;
		private final BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> call;
		
		public SyncToolSpecification(McpSchema.Tool tool,
				BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> call) {
			this.tool = tool;
			this.call = call;
		}
		
		public McpSchema.Tool tool() {
			return tool;
		}
		
		public BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> call() {
			return call;
		}
		
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SyncToolSpecification that = (SyncToolSpecification) o;
			return Objects.equals(tool, that.tool) &&
					Objects.equals(call, that.call);
		}
		
		@Override
		public int hashCode() {
			return Objects.hash(tool, call);
		}
		
		@Override
		public String toString() {
			return "SyncToolSpecification{" +
					"tool=" + tool +
					", call=" + call +
					'}';
		}
	}

	/**
	 * Specification of a resource with its synchronous handler function. Resources
	 * provide context to AI models by exposing data such as:
	 * <ul>
	 * <li>File contents
	 * <li>Database records
	 * <li>API responses
	 * <li>System information
	 * <li>Application state
	 * </ul>
	 *
	 * <p>
	 * Example resource specification: <pre>{@code
	 * new McpServerFeatures.SyncResourceSpecification(
	 *     new Resource("docs", "Documentation files", "text/markdown"),
	 *     (exchange, request) -> {
	 *         String content = readFile(request.getPath());
	 *         return new ReadResourceResult(content);
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param resource The resource definition including name, description, and MIME type
	 * @param readHandler The function that handles resource read requests. The function's
	 * first argument is an {@link McpSyncServerExchange} upon which the server can
	 * interact with the connected client. The second arguments is a
	 * {@link io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest}.
	 */
	public static class SyncResourceSpecification {
		private final McpSchema.Resource resource;
		private final BiFunction<McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler;
		
		public SyncResourceSpecification(McpSchema.Resource resource,
				BiFunction<McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler) {
			this.resource = resource;
			this.readHandler = readHandler;
		}
		
		public McpSchema.Resource resource() {
			return resource;
		}
		
		public BiFunction<McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler() {
			return readHandler;
		}
		
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SyncResourceSpecification that = (SyncResourceSpecification) o;
			return Objects.equals(resource, that.resource) &&
					Objects.equals(readHandler, that.readHandler);
		}
		
		@Override
		public int hashCode() {
			return Objects.hash(resource, readHandler);
		}
		
		@Override
		public String toString() {
			return "SyncResourceSpecification{" +
					"resource=" + resource +
					", readHandler=" + readHandler +
					'}';
		}
	}

	/**
	 * Specification of a prompt template with its synchronous handler function. Prompts
	 * provide structured templates for AI model interactions, supporting:
	 * <ul>
	 * <li>Consistent message formatting
	 * <li>Parameter substitution
	 * <li>Context injection
	 * <li>Response formatting
	 * <li>Instruction templating
	 * </ul>
	 *
	 * <p>
	 * Example prompt specification: <pre>{@code
	 * new McpServerFeatures.SyncPromptSpecification(
	 *     new Prompt("analyze", "Code analysis template"),
	 *     (exchange, request) -> {
	 *         String code = request.getArguments().get("code");
	 *         return new GetPromptResult(
	 *             "Analyze this code:\n\n" + code + "\n\nProvide feedback on:"
	 *         );
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param prompt The prompt definition including name and description
	 * @param promptHandler The function that processes prompt requests and returns
	 * formatted templates. The function's first argument is an
	 * {@link McpSyncServerExchange} upon which the server can interact with the connected
	 * client. The second arguments is a
	 * {@link io.modelcontextprotocol.spec.McpSchema.GetPromptRequest}.
	 */
	public static class SyncPromptSpecification {
		private final McpSchema.Prompt prompt;
		private final BiFunction<McpSyncServerExchange, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler;
		
		public SyncPromptSpecification(McpSchema.Prompt prompt,
				BiFunction<McpSyncServerExchange, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler) {
			this.prompt = prompt;
			this.promptHandler = promptHandler;
		}
		
		public McpSchema.Prompt prompt() {
			return prompt;
		}
		
		public BiFunction<McpSyncServerExchange, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler() {
			return promptHandler;
		}
		
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SyncPromptSpecification that = (SyncPromptSpecification) o;
			return Objects.equals(prompt, that.prompt) &&
					Objects.equals(promptHandler, that.promptHandler);
		}
		
		@Override
		public int hashCode() {
			return Objects.hash(prompt, promptHandler);
		}
		
		@Override
		public String toString() {
			return "SyncPromptSpecification{" +
					"prompt=" + prompt +
					", promptHandler=" + promptHandler +
					'}';
		}
	}

}
