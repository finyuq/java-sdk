/*
* Copyright 2025 - 2025 the original author or authors.
*/
package io.modelcontextprotocol.server;

import java.util.Objects;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * @author Christian Tzolov
 */
public class TomcatTestUtil {

	public static class TomcatServer {
		private final Tomcat tomcat;
		private final AnnotationConfigWebApplicationContext appContext;
		
		public TomcatServer(Tomcat tomcat, AnnotationConfigWebApplicationContext appContext) {
			this.tomcat = tomcat;
			this.appContext = appContext;
		}
		
		public Tomcat tomcat() {
			return tomcat;
		}
		
		public AnnotationConfigWebApplicationContext appContext() {
			return appContext;
		}
		
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			TomcatServer that = (TomcatServer) o;
			return Objects.equals(tomcat, that.tomcat) && 
				   Objects.equals(appContext, that.appContext);
		}
		
		@Override
		public int hashCode() {
			return Objects.hash(tomcat, appContext);
		}
		
		@Override
		public String toString() {
			return "TomcatServer{" +
				   "tomcat=" + tomcat +
				   ", appContext=" + appContext +
				   '}';
		}
	}

	public TomcatServer createTomcatServer(String contextPath, int port, Class<?> componentClass) {

		// Set up Tomcat first
		Tomcat tomcat = new Tomcat();
		tomcat.setPort(port);

		// Set Tomcat base directory to java.io.tmpdir to avoid permission issues
		String baseDir = System.getProperty("java.io.tmpdir");
		tomcat.setBaseDir(baseDir);

		// Use the same directory for document base
		Context context = tomcat.addContext(contextPath, baseDir);

		// Create and configure Spring WebMvc context
		AnnotationConfigWebApplicationContext appContext = new AnnotationConfigWebApplicationContext();
		appContext.register(componentClass);
		appContext.setServletContext(context.getServletContext());
		appContext.refresh();

		// Create DispatcherServlet with our Spring context
		DispatcherServlet dispatcherServlet = new DispatcherServlet(appContext);

		// Add servlet to Tomcat and get the wrapper
		org.apache.catalina.Wrapper wrapper = Tomcat.addServlet(context, "dispatcherServlet", dispatcherServlet);
		wrapper.setLoadOnStartup(1);
		wrapper.setAsyncSupported(true);
		context.addServletMappingDecoded("/*", "dispatcherServlet");

		try {
			// Configure and start the connector with async support
			org.apache.catalina.connector.Connector connector = tomcat.getConnector();
			connector.setAsyncTimeout(3000); // 3 seconds timeout for async requests
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		return new TomcatServer(tomcat, appContext);
	}

}
