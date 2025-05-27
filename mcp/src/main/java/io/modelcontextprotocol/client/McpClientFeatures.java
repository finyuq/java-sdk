/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Representation of features and capabilities for Model Context Protocol (MCP) clients.
 * This class provides two record types for managing client features:
 * <ul>
 * <li>{@link Async} for non-blocking operations with Project Reactor's Mono responses
 * <li>{@link Sync} for blocking operations with direct responses
 * </ul>
 *
 * <p>
 * Each feature specification includes:
 * <ul>
 * <li>Client implementation information and capabilities
 * <li>Root URI mappings for resource access
 * <li>Change notification handlers for tools, resources, and prompts
 * <li>Logging message consumers
 * <li>Message sampling handlers for request processing
 * </ul>
 *
 * <p>
 * The class supports conversion between synchronous and asynchronous specifications
 * through the {@link Async#fromSync} method, which ensures proper handling of blocking
 * operations in non-blocking contexts by scheduling them on a bounded elastic scheduler.
 *
 * @author Dariusz Jędrzejczyk
 * @see McpClient
 * @see McpSchema.Implementation
 * @see McpSchema.ClientCapabilities
 */
class McpClientFeatures {

	/**
	 * Asynchronous client features specification providing the capabilities and request
	 * and notification handlers.
	 *
	 * @param clientInfo the client implementation information.
	 * @param clientCapabilities the client capabilities.
	 * @param roots the roots.
	 * @param toolsChangeConsumers the tools change consumers.
	 * @param resourcesChangeConsumers the resources change consumers.
	 * @param promptsChangeConsumers the prompts change consumers.
	 * @param loggingConsumers the logging consumers.
	 * @param samplingHandler the sampling handler.
	 */
	static class Async {
		private final McpSchema.Implementation clientInfo;
		private final McpSchema.ClientCapabilities clientCapabilities;
		private final Map<String, McpSchema.Root> roots;
		private final List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers;
		private final List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers;
		private final List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers;
		private final List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers;
		private final Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler;
		
		Async(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
			Map<String, McpSchema.Root> roots, List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers,
			List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers,
			List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers,
			List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers,
			Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler) {
			this.clientInfo = clientInfo;
			this.clientCapabilities = clientCapabilities;
			this.roots = roots;
			this.toolsChangeConsumers = toolsChangeConsumers;
			this.resourcesChangeConsumers = resourcesChangeConsumers;
			this.promptsChangeConsumers = promptsChangeConsumers;
			this.loggingConsumers = loggingConsumers;
			this.samplingHandler = samplingHandler;
		}
		
		public McpSchema.Implementation clientInfo() {
			return clientInfo;
		}
		
		public McpSchema.ClientCapabilities clientCapabilities() {
			return clientCapabilities;
		}
		
		public Map<String, McpSchema.Root> roots() {
			return roots;
		}
		
		public List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers() {
			return toolsChangeConsumers;
		}
		
		public List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers() {
			return resourcesChangeConsumers;
		}
		
		public List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers() {
			return promptsChangeConsumers;
		}
		
		public List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers() {
			return loggingConsumers;
		}
		
		public Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler() {
			return samplingHandler;
		}
		
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Async async = (Async) o;
			return Objects.equals(clientInfo, async.clientInfo) &&
				   Objects.equals(clientCapabilities, async.clientCapabilities) &&
				   Objects.equals(roots, async.roots) &&
				   Objects.equals(toolsChangeConsumers, async.toolsChangeConsumers) &&
				   Objects.equals(resourcesChangeConsumers, async.resourcesChangeConsumers) &&
				   Objects.equals(promptsChangeConsumers, async.promptsChangeConsumers) &&
				   Objects.equals(loggingConsumers, async.loggingConsumers) &&
				   Objects.equals(samplingHandler, async.samplingHandler);
		}
		
		@Override
		public int hashCode() {
			return Objects.hash(clientInfo, clientCapabilities, roots, toolsChangeConsumers,
								resourcesChangeConsumers, promptsChangeConsumers, loggingConsumers,
								samplingHandler);
		}
		
		@Override
		public String toString() {
			return "Async{" +
				   "clientInfo=" + clientInfo +
				   ", clientCapabilities=" + clientCapabilities +
				   ", roots=" + roots +
				   ", toolsChangeConsumers=" + toolsChangeConsumers +
				   ", resourcesChangeConsumers=" + resourcesChangeConsumers +
				   ", promptsChangeConsumers=" + promptsChangeConsumers +
				   ", loggingConsumers=" + loggingConsumers +
				   ", samplingHandler=" + samplingHandler +
				   '}';
		}

		/**
		 * Create an instance and validate the arguments.
		 * @param clientCapabilities the client capabilities.
		 * @param roots the roots.
		 * @param toolsChangeConsumers the tools change consumers.
		 * @param resourcesChangeConsumers the resources change consumers.
		 * @param promptsChangeConsumers the prompts change consumers.
		 * @param loggingConsumers the logging consumers.
		 * @param samplingHandler the sampling handler.
		 */
		public Async(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
				Map<String, McpSchema.Root> roots,
				List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers,
				List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers,
				List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers,
				List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers,
				Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler) {

			Assert.notNull(clientInfo, "Client info must not be null");
			this.clientInfo = clientInfo;
			this.clientCapabilities = (clientCapabilities != null) ? clientCapabilities
					: new McpSchema.ClientCapabilities(null,
							!Utils.isEmpty(roots) ? new McpSchema.ClientCapabilities.RootCapabilities(false) : null,
							samplingHandler != null ? new McpSchema.ClientCapabilities.Sampling() : null);
			this.roots = roots != null ? new ConcurrentHashMap<>(roots) : new ConcurrentHashMap<>();

			this.toolsChangeConsumers = toolsChangeConsumers != null ? toolsChangeConsumers : List.of();
			this.resourcesChangeConsumers = resourcesChangeConsumers != null ? resourcesChangeConsumers : List.of();
			this.promptsChangeConsumers = promptsChangeConsumers != null ? promptsChangeConsumers : List.of();
			this.loggingConsumers = loggingConsumers != null ? loggingConsumers : List.of();
			this.samplingHandler = samplingHandler;
		}

		/**
		 * Convert a synchronous specification into an asynchronous one and provide
		 * blocking code offloading to prevent accidental blocking of the non-blocking
		 * transport.
		 * @param syncSpec a potentially blocking, synchronous specification.
		 * @return a specification which is protected from blocking calls specified by the
		 * user.
		 */
		public static Async fromSync(Sync syncSpec) {
			List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers = new ArrayList<>();
			for (Consumer<List<McpSchema.Tool>> consumer : syncSpec.toolsChangeConsumers()) {
				toolsChangeConsumers.add(t -> Mono.<Void>fromRunnable(() -> consumer.accept(t))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers = new ArrayList<>();
			for (Consumer<List<McpSchema.Resource>> consumer : syncSpec.resourcesChangeConsumers()) {
				resourcesChangeConsumers.add(r -> Mono.<Void>fromRunnable(() -> consumer.accept(r))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers = new ArrayList<>();

			for (Consumer<List<McpSchema.Prompt>> consumer : syncSpec.promptsChangeConsumers()) {
				promptsChangeConsumers.add(p -> Mono.<Void>fromRunnable(() -> consumer.accept(p))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers = new ArrayList<>();
			for (Consumer<McpSchema.LoggingMessageNotification> consumer : syncSpec.loggingConsumers()) {
				loggingConsumers.add(l -> Mono.<Void>fromRunnable(() -> consumer.accept(l))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler = r -> Mono
				.fromCallable(() -> syncSpec.samplingHandler().apply(r))
				.subscribeOn(Schedulers.boundedElastic());
			return new Async(syncSpec.clientInfo(), syncSpec.clientCapabilities(), syncSpec.roots(),
					toolsChangeConsumers, resourcesChangeConsumers, promptsChangeConsumers, loggingConsumers,
					samplingHandler);
		}
	}

	/**
	 * Synchronous client features specification providing the capabilities and request
	 * and notification handlers.
	 *
	 * @param clientInfo the client implementation information.
	 * @param clientCapabilities the client capabilities.
	 * @param roots the roots.
	 * @param toolsChangeConsumers the tools change consumers.
	 * @param resourcesChangeConsumers the resources change consumers.
	 * @param promptsChangeConsumers the prompts change consumers.
	 * @param loggingConsumers the logging consumers.
	 * @param samplingHandler the sampling handler.
	 */
	public static class Sync {
		private final McpSchema.Implementation clientInfo;
		private final McpSchema.ClientCapabilities clientCapabilities;
		private final Map<String, McpSchema.Root> roots;
		private final List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers;
		private final List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers;
		private final List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers;
		private final List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers;
		private final Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler;
		
		public Sync(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
			Map<String, McpSchema.Root> roots, List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers,
			List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers,
			List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers,
			List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers,
			Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler) {
			this.clientInfo = clientInfo;
			this.clientCapabilities = clientCapabilities;
			this.roots = roots;
			this.toolsChangeConsumers = toolsChangeConsumers;
			this.resourcesChangeConsumers = resourcesChangeConsumers;
			this.promptsChangeConsumers = promptsChangeConsumers;
			this.loggingConsumers = loggingConsumers;
			this.samplingHandler = samplingHandler;
		}
		
		public McpSchema.Implementation clientInfo() {
			return clientInfo;
		}
		
		public McpSchema.ClientCapabilities clientCapabilities() {
			return clientCapabilities;
		}
		
		public Map<String, McpSchema.Root> roots() {
			return roots;
		}
		
		public List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers() {
			return toolsChangeConsumers;
		}
		
		public List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers() {
			return resourcesChangeConsumers;
		}
		
		public List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers() {
			return promptsChangeConsumers;
		}
		
		public List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers() {
			return loggingConsumers;
		}
		
		public Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler() {
			return samplingHandler;
		}
		
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Sync sync = (Sync) o;
			return Objects.equals(clientInfo, sync.clientInfo) &&
				   Objects.equals(clientCapabilities, sync.clientCapabilities) &&
				   Objects.equals(roots, sync.roots) &&
				   Objects.equals(toolsChangeConsumers, sync.toolsChangeConsumers) &&
				   Objects.equals(resourcesChangeConsumers, sync.resourcesChangeConsumers) &&
				   Objects.equals(promptsChangeConsumers, sync.promptsChangeConsumers) &&
				   Objects.equals(loggingConsumers, sync.loggingConsumers) &&
				   Objects.equals(samplingHandler, sync.samplingHandler);
		}
		
		@Override
		public int hashCode() {
			return Objects.hash(clientInfo, clientCapabilities, roots, toolsChangeConsumers,
								resourcesChangeConsumers, promptsChangeConsumers, loggingConsumers,
								samplingHandler);
		}
		
		@Override
		public String toString() {
			return "Sync{" +
				   "clientInfo=" + clientInfo +
				   ", clientCapabilities=" + clientCapabilities +
				   ", roots=" + roots +
				   ", toolsChangeConsumers=" + toolsChangeConsumers +
				   ", resourcesChangeConsumers=" + resourcesChangeConsumers +
				   ", promptsChangeConsumers=" + promptsChangeConsumers +
				   ", loggingConsumers=" + loggingConsumers +
				   ", samplingHandler=" + samplingHandler +
				   '}';
		}

		/**
		 * Create an instance and validate the arguments.
		 * @param clientInfo the client implementation information.
		 * @param clientCapabilities the client capabilities.
		 * @param roots the roots.
		 * @param toolsChangeConsumers the tools change consumers.
		 * @param resourcesChangeConsumers the resources change consumers.
		 * @param promptsChangeConsumers the prompts change consumers.
		 * @param loggingConsumers the logging consumers.
		 * @param samplingHandler the sampling handler.
		 */
		public Sync(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
				Map<String, McpSchema.Root> roots, List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers,
				List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers,
				List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers,
				List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers,
				Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler) {

			Assert.notNull(clientInfo, "Client info must not be null");
			this.clientInfo = clientInfo;
			this.clientCapabilities = (clientCapabilities != null) ? clientCapabilities
					: new McpSchema.ClientCapabilities(null,
							!Utils.isEmpty(roots) ? new McpSchema.ClientCapabilities.RootCapabilities(false) : null,
							samplingHandler != null ? new McpSchema.ClientCapabilities.Sampling() : null);
			this.roots = roots != null ? new HashMap<>(roots) : new HashMap<>();

			this.toolsChangeConsumers = toolsChangeConsumers != null ? toolsChangeConsumers : List.of();
			this.resourcesChangeConsumers = resourcesChangeConsumers != null ? resourcesChangeConsumers : List.of();
			this.promptsChangeConsumers = promptsChangeConsumers != null ? promptsChangeConsumers : List.of();
			this.loggingConsumers = loggingConsumers != null ? loggingConsumers : List.of();
			this.samplingHandler = samplingHandler;
		}
	}

}
