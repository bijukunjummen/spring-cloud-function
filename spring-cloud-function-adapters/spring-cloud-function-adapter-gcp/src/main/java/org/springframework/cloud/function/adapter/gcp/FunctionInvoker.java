/*
 * Copyright 2020-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.function.adapter.gcp;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.cloud.functions.Context;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.cloud.functions.RawBackgroundFunction;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.cloud.function.context.FunctionalSpringApplication;
import org.springframework.cloud.function.context.catalog.SimpleFunctionRegistry.FunctionInvocationWrapper;
import org.springframework.cloud.function.context.config.ContextFunctionCatalogAutoConfiguration;
import org.springframework.cloud.function.context.config.RoutingFunction;
import org.springframework.cloud.function.utils.FunctionClassUtils;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.Assert;
import org.springframework.util.MimeTypeUtils;

/**
 * Implementation of {@link HttpFunction} and {@link RawBackgroundFunction} for Google
 * Cloud Function (GCF). This is the Spring Cloud Function adapter for GCF HTTP and Raw
 * Background function.
 *
 * @author Dmitry Solomakha
 * @author Mike Eltsufin
 * @author Oleg Zhurakousky
 * @author Biju Kunjummen
 * @since 3.0.4
 */
public class FunctionInvoker implements HttpFunction, RawBackgroundFunction {

	private static final Log log = LogFactory.getLog(FunctionInvoker.class);

	/**
	 * Constant specifying Http Status Code. Accessible to users by calling 'FunctionInvoker.HTTP_STATUS_CODE'
	 */
	public static final String HTTP_STATUS_CODE = "statusCode";

	private String functionName = "";

	protected FunctionCatalog catalog;

	private FunctionInvocationWrapper function;

	public FunctionInvoker() {
		this(FunctionClassUtils.getStartClass());
	}

	public FunctionInvoker(Class<?> configurationClass) {
		init(configurationClass);
	}

	private void init(Class<?> configurationClass) {
		if (System.getenv().containsKey("spring.cloud.function.definition")) {
			this.functionName = System.getenv("spring.cloud.function.definition");
		}

		// Default to GSON if implementation not specified.
		if (!System.getenv().containsKey(ContextFunctionCatalogAutoConfiguration.JSON_MAPPER_PROPERTY)) {
			System.setProperty(ContextFunctionCatalogAutoConfiguration.JSON_MAPPER_PROPERTY, "gson");
		}

		initialize(configurationClass);
	}

	private <I> Function<Message<I>, Message<byte[]>> lookupFunction() {
		Function<Message<I>, Message<byte[]>> function = this.catalog.lookup(functionName,
			MimeTypeUtils.APPLICATION_JSON.toString());
		Assert.notNull(function, "'function' with name '" + functionName + "' must not be null");
		return function;
	}

	/**
	 * The implementation of a GCF {@link HttpFunction} that will be used as the entry
	 * point from GCF.
	 */
	@Override
	public void service(HttpRequest httpRequest, HttpResponse httpResponse) throws Exception {

		Function<Message<BufferedReader>, Message<byte[]>> function = lookupFunction();

		Message<BufferedReader> message = this.function.getInputType() == Void.class || this.function.getInputType() == null ? null
			: MessageBuilder.withPayload(httpRequest.getReader()).copyHeaders(httpRequest.getHeaders()).build();

		Message<byte[]> result = function.apply(message);

		if (result != null) {
			MessageHeaders headers = result.getHeaders();
			httpResponse.getWriter().write(new String(result.getPayload(), StandardCharsets.UTF_8));
			for (Entry<String, Object> header : headers.entrySet()) {
				Object values = header.getValue();
				if (values instanceof Collection<?>) {
					String headerValue = ((Collection<?>) values).stream().map(item -> item.toString()).collect(Collectors.joining(","));
					httpResponse.appendHeader(header.getKey(), headerValue);
				}
				else {
					httpResponse.appendHeader(header.getKey(), header.getValue().toString());
				}
			}
			httpRequest.getContentType().ifPresent(contentType -> httpResponse.setContentType(contentType));

			if (headers.containsKey(HTTP_STATUS_CODE)) {
				if (headers.get(HTTP_STATUS_CODE) instanceof Integer) {
					httpResponse.setStatusCode((int) headers.get(HTTP_STATUS_CODE));
				}
				else {
					log.warn("The statusCode should be an Integer value");
				}
			}
		}
	}

	/**
	 * The implementation of a GCF {@link RawBackgroundFunction} that will be used as the
	 * entry point from GCF.
	 *
	 * @param json    the payload.
	 * @param context event context.
	 * @since 3.0.5
	 */
	@Override
	public void accept(String json, Context context) {
		Function<Message<String>, Message<byte[]>> function = lookupFunction();
		Message<String> message = this.function.getInputType() == Void.class ? null
			: MessageBuilder.withPayload(json).setHeader("gcf_context", context).build();

		Message<byte[]> result = function.apply(message);

		if (result != null) {
			log.info("Dropping background function result: " + new String(result.getPayload()));
		}
	}

	private void initialize(Class<?> configurationClass) {
		log.info("Initializing: " + configurationClass);
		SpringApplication springApplication = springApplication(configurationClass);
		ConfigurableApplicationContext context = springApplication.run();
		context.getAutowireCapableBeanFactory().autowireBean(this);
		this.catalog = context.getBean(FunctionCatalog.class);
		initFunctionConsumerOrSupplierFromCatalog(configurationClass);
	}

	private void initFunctionConsumerOrSupplierFromCatalog(Object targetContext) {
		this.function = this.catalog.lookup(this.functionName, "application/json");
		if (this.function == null) {
			Set<String> names = this.catalog.getNames(null);
			this.function = catalog.lookup(RoutingFunction.FUNCTION_NAME, "application/json");
		}

		if (this.function.isOutputTypePublisher()) {
			this.function.setSkipOutputConversion(true);
		}
		Assert.notNull(this.function, "Failed to lookup function " + this.functionName);

		this.functionName = this.function.getFunctionDefinition();
		log.info("Located function: '" + this.functionName + "'");
	}

	private SpringApplication springApplication(Class<?> configurationClass) {
		SpringApplication application = new FunctionalSpringApplication(configurationClass);
		application.setWebApplicationType(WebApplicationType.NONE);
		return application;
	}

}
