/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.web.servlet.mvc.method.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Properties;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;

import org.springframework.beans.DirectFieldAccessor;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.RequestPath;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.PathPatternsParameterizedTest;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.testfixture.servlet.MockHttpServletRequest;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPatternParser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Test fixture for {@link CrossOrigin @CrossOrigin} annotated methods.
 *
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @author Nicolas Labrot
 * @author Rossen Stoyanchev
 */
class CrossOriginTests {

	@SuppressWarnings("unused")
	static Stream<TestRequestMappingInfoHandlerMapping> pathPatternsArguments() {
		StaticWebApplicationContext wac = new StaticWebApplicationContext();
		Properties props = new Properties();
		props.setProperty("myOrigin", "https://example.com");
		wac.getEnvironment().getPropertySources().addFirst(new PropertiesPropertySource("ps", props));
		wac.registerSingleton("ppc", PropertySourcesPlaceholderConfigurer.class);
		wac.refresh();

		TestRequestMappingInfoHandlerMapping mapping1 = new TestRequestMappingInfoHandlerMapping();
		mapping1.setPatternParser(new PathPatternParser());
		wac.getAutowireCapableBeanFactory().initializeBean(mapping1, "mapping1");

		TestRequestMappingInfoHandlerMapping mapping2 = new TestRequestMappingInfoHandlerMapping();
		wac.getAutowireCapableBeanFactory().initializeBean(mapping2, "mapping2");

		return Stream.of(mapping1, mapping2);
	}


	private final MockHttpServletRequest request = new MockHttpServletRequest();


	@BeforeEach
	void setup() {
		this.request.setMethod("GET");
		this.request.addHeader(HttpHeaders.ORIGIN, "https://domain.com/");
	}


	@PathPatternsParameterizedTest
	void noAnnotationWithoutOrigin(TestRequestMappingInfoHandlerMapping mapping) throws Exception {
		mapping.registerHandler(new MethodLevelController());
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/no");
		HandlerExecutionChain chain = mapping.getHandler(request);
		assertThat(getCorsConfiguration(chain, false)).isNull();
	}

	@PathPatternsParameterizedTest
	void noAnnotationWithAccessControlRequestMethod(TestRequestMappingInfoHandlerMapping mapping) throws Exception {
		mapping.registerHandler(new MethodLevelController());
		MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/no");
		request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
		HandlerExecutionChain chain = mapping.getHandler(request);
		assertThat(chain).isNotNull();
		assertThat(chain.getHandler().toString())
				.endsWith("RequestMappingInfoHandlerMapping$HttpOptionsHandler#handle()");
	}

	@PathPatternsParameterizedTest
	void noAnnotationWithPreflightRequest(TestRequestMappingInfoHandlerMapping mapping) throws Exception {
		mapping.registerHandler(new MethodLevelController());
		MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/no");
		request.addHeader(HttpHeaders.ORIGIN, "https://domain.com/");
		request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
		HandlerExecutionChain chain = mapping.getHandler(request);
		assertThat(chain).isNotNull();
		assertThat(chain.getHandler().getClass().getName()).endsWith("AbstractHandlerMapping$PreFlightHandler");
	}

	@PathPatternsParameterizedTest  // SPR-12931
	void noAnnotationWithOrigin(TestRequestMappingInfoHandlerMapping mapping) throws Exception {
		mapping.registerHandler(new MethodLevelController());
		this.request.setRequestURI("/no");
		HandlerExecutionChain chain = mapping.getHandler(request);
		assertThat(getCorsConfiguration(chain, false)).isNull();
	}

	@PathPatternsParameterizedTest  // SPR-12931
	void noAnnotationPostWithOrigin(TestRequestMappingInfoHandlerMapping mapping) throws Exception {
		mapping.registerHandler(new MethodLevelController());
		this.request.setMethod("POST");
		this.request.setRequestURI("/no");
		HandlerExecutionChain chain = mapping.getHandler(request);
		assertThat(getCorsConfiguration(chain, false)).isNull();
	}

	@PathPatternsParameterizedTest
	void defaultAnnotation(TestRequestMappingInfoHandlerMapping mapping) throws Exception {
		mapping.registerHandler(new MethodLevelController());
		this.request.setRequestURI("/default");
		HandlerExecutionChain chain = mapping.getHandler(request);
		CorsConfiguration config = getCorsConfiguration(chain, false);
		assertThat(config).isNotNull();
		assertThat(config.getAllowedMethods()).containsExactly("GET");
		assertThat(config.getAllowedOrigins()).containsExactly("*");
		assertThat(config.getAllowCredentials()).isNull();
		assertThat(config.getAllowedHeaders()).containsExactly("*");
		assertThat(CollectionUtils.isEmpty(config.getExposedHeaders())).isTrue();
		assertThat(config.getMaxAge()).isEqualTo(new Long(1800));
	}

	@PathPatternsParameterizedTest
	void customized(TestRequestMappingInfoHandlerMapping mapping) throws Exception {
		mapping.registerHandler(new MethodLevelController());
		this.request.setRequestURI("/customized");
		HandlerExecutionChain chain = mapping.getHandler(request);
		CorsConfiguration config = getCorsConfiguration(chain, false);
		assertThat(config).isNotNull();
		assertThat(config.getAllowedMethods()).containsExactly("DELETE");
		assertThat(config.getAllowedOrigins()).containsExactly("https://site1.com", "https://site2.com");
		assertThat(config.getAllowedHeaders()).containsExactly("header1", "header2");
		assertThat(config.getExposedHeaders()).containsExactly("header3", "header4");
		assertThat(config.getMaxAge()).isEqualTo(new Long(123));
		assertThat(config.getAllowCredentials()).isFalse();
	}

	@PathPatternsParameterizedTest
	void customOriginDefinedViaValueAttribute(TestRequestMappingInfoHandlerMapping mapping) throws Exception {
		mapping.registerHandler(new MethodLevelController());
		this.request.setRequestURI("/customOrigin");
		HandlerExecutionChain chain = mapping.getHandler(request);
		CorsConfiguration config = getCorsConfiguration(chain, false);
		assertThat(config).isNotNull();
		assertThat(config.getAllowedOrigins()).isEqualTo(Collections.singletonList("https://example.com"));
		assertThat(config.getAllowCredentials()).isNull();
	}

	@PathPatternsParameterizedTest
	void customOriginDefinedViaPlaceholder(TestRequestMappingInfoHandlerMapping mapping) throws Exception {
		mapping.registerHandler(new MethodLevelController());
		this.request.setRequestURI("/someOrigin");
		HandlerExecutionChain chain = mapping.getHandler(request);
		CorsConfiguration config = getCorsConfiguration(chain, false);
		assertThat(config).isNotNull();
		assertThat(config.getAllowedOrigins()).isEqualTo(Collections.singletonList("https://example.com"));
		assertThat(config.getAllowCredentials()).isNull();
	}

	@PathPatternsParameterizedTest
	void bogusAllowCredentialsValue(TestRequestMappingInfoHandlerMapping mapping) {
		assertThatIllegalStateException().isThrownBy(() ->
				mapping.registerHandler(new MethodLevelControllerWithBogusAllowCredentialsValue()))
			.withMessageContaining("@CrossOrigin's allowCredentials")
			.withMessageContaining("current value is [bogus]");
	}

	@PathPatternsParameterizedTest
	void classLevel(TestRequestMappingInfoHandlerMapping mapping) throws Exception {
		mapping.registerHandler(new ClassLevelController());

		this.request.setRequestURI("/foo");
		HandlerExecutionChain chain = mapping.getHandler(request);
		CorsConfiguration config = getCorsConfiguration(chain, false);
		assertThat(config).isNotNull();
		assertThat(config.getAllowedMethods()).containsExactly("GET");
		assertThat(config.getAllowedOrigins()).containsExactly("*");
		assertThat(config.getAllowCredentials()).isFalse();

		this.request.setRequestURI("/bar");
		chain = mapping.getHandler(request);
		config = getCorsConfiguration(chain, false);
		assertThat(config).isNotNull();
		assertThat(config.getAllowedMethods()).containsExactly("GET");
		assertThat(config.getAllowedOrigins()).containsExactly("*");
		assertThat(config.getAllowCredentials()).isFalse();

		this.request.setRequestURI("/baz");
		chain = mapping.getHandler(request);
		config = getCorsConfiguration(chain, false);
		assertThat(config).isNotNull();
		assertThat(config.getAllowedMethods()).containsExactly("GET");
		assertThat(config.getAllowedOrigins()).containsExactly("*");
		assertThat(config.getAllowCredentials()).isTrue();
	}

	@PathPatternsParameterizedTest // SPR-13468
	void classLevelComposedAnnotation(TestRequestMappingInfoHandlerMapping mapping) throws Exception {
		mapping.registerHandler(new ClassLevelMappingWithComposedAnnotation());

		this.request.setRequestURI("/foo");
		HandlerExecutionChain chain = mapping.getHandler(request);
		CorsConfiguration config = getCorsConfiguration(chain, false);
		assertThat(config).isNotNull();
		assertThat(config.getAllowedMethods()).containsExactly("GET");
		assertThat(config.getAllowedOrigins()).containsExactly("http://www.foo.example/");
		assertThat(config.getAllowCredentials()).isTrue();
	}

	@PathPatternsParameterizedTest // SPR-13468
	void methodLevelComposedAnnotation(TestRequestMappingInfoHandlerMapping mapping) throws Exception {
		mapping.registerHandler(new MethodLevelMappingWithComposedAnnotation());

		this.request.setRequestURI("/foo");
		HandlerExecutionChain chain = mapping.getHandler(request);
		CorsConfiguration config = getCorsConfiguration(chain, false);
		assertThat(config).isNotNull();
		assertThat(config.getAllowedMethods()).containsExactly("GET");
		assertThat(config.getAllowedOrigins()).containsExactly("http://www.foo.example/");
		assertThat(config.getAllowCredentials()).isTrue();
	}

	@PathPatternsParameterizedTest
	void preFlightRequest(TestRequestMappingInfoHandlerMapping mapping) throws Exception {
		mapping.registerHandler(new MethodLevelController());
		this.request.setMethod("OPTIONS");
		this.request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
		this.request.setRequestURI("/default");
		HandlerExecutionChain chain = mapping.getHandler(request);
		CorsConfiguration config = getCorsConfiguration(chain, true);
		assertThat(config).isNotNull();
		assertThat(config.getAllowedMethods()).containsExactly("GET");
		assertThat(config.getAllowedOrigins()).containsExactly("*");
		assertThat(config.getAllowCredentials()).isNull();
		assertThat(config.getAllowedHeaders()).containsExactly("*");
		assertThat(CollectionUtils.isEmpty(config.getExposedHeaders())).isTrue();
		assertThat(config.getMaxAge()).isEqualTo(new Long(1800));
	}

	@PathPatternsParameterizedTest
	void ambiguousHeaderPreFlightRequest(TestRequestMappingInfoHandlerMapping mapping) throws Exception {
		mapping.registerHandler(new MethodLevelController());
		this.request.setMethod("OPTIONS");
		this.request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
		this.request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "header1");
		this.request.setRequestURI("/ambiguous-header");
		HandlerExecutionChain chain = mapping.getHandler(request);
		CorsConfiguration config = getCorsConfiguration(chain, true);
		assertThat(config).isNotNull();
		assertThat(config.getAllowedMethods()).containsExactly("*");
		assertThat(config.getAllowedOrigins()).containsExactly("*");
		assertThat(config.getAllowedHeaders()).containsExactly("*");
		assertThat(config.getAllowCredentials()).isTrue();
		assertThat(CollectionUtils.isEmpty(config.getExposedHeaders())).isTrue();
		assertThat(config.getMaxAge()).isNull();
	}

	@PathPatternsParameterizedTest
	void ambiguousProducesPreFlightRequest(TestRequestMappingInfoHandlerMapping mapping) throws Exception {
		mapping.registerHandler(new MethodLevelController());
		this.request.setMethod("OPTIONS");
		this.request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
		this.request.setRequestURI("/ambiguous-produces");
		HandlerExecutionChain chain = mapping.getHandler(request);
		CorsConfiguration config = getCorsConfiguration(chain, true);
		assertThat(config).isNotNull();
		assertThat(config.getAllowedMethods()).containsExactly("*");
		assertThat(config.getAllowedOrigins()).containsExactly("*");
		assertThat(config.getAllowedHeaders()).containsExactly("*");
		assertThat(config.getAllowCredentials()).isTrue();
		assertThat(CollectionUtils.isEmpty(config.getExposedHeaders())).isTrue();
		assertThat(config.getMaxAge()).isNull();
	}

	@PathPatternsParameterizedTest
	void preFlightRequestWithoutRequestMethodHeader(TestRequestMappingInfoHandlerMapping mapping) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/default");
		request.addHeader(HttpHeaders.ORIGIN, "https://domain2.com");
		assertThat(mapping.getHandler(request)).isNull();
	}


	@Nullable
	private CorsConfiguration getCorsConfiguration(@Nullable HandlerExecutionChain chain, boolean isPreFlightRequest) {
		assertThat(chain).isNotNull();
		if (isPreFlightRequest) {
			Object handler = chain.getHandler();
			assertThat(handler.getClass().getSimpleName().equals("PreFlightHandler")).isTrue();
			DirectFieldAccessor accessor = new DirectFieldAccessor(handler);
			return (CorsConfiguration)accessor.getPropertyValue("config");
		}
		else {
			HandlerInterceptor[] interceptors = chain.getInterceptors();
			if (interceptors != null) {
				for (HandlerInterceptor interceptor : interceptors) {
					if (interceptor.getClass().getSimpleName().equals("CorsInterceptor")) {
						DirectFieldAccessor accessor = new DirectFieldAccessor(interceptor);
						return (CorsConfiguration) accessor.getPropertyValue("config");
					}
				}
			}
		}
		return null;
	}


	@Controller
	@SuppressWarnings("unused")
	private static class MethodLevelController {

		@GetMapping("/no")
		public void noAnnotation() {
		}

		@PostMapping("/no")
		public void noAnnotationPost() {
		}

		@CrossOrigin
		@GetMapping(path = "/default")
		public void defaultAnnotation() {
		}

		@CrossOrigin
		@GetMapping(path = "/default", params = "q")
		public void defaultAnnotationWithParams() {
		}

		@CrossOrigin
		@GetMapping(path = "/ambiguous-header", headers = "header1=a")
		public void ambiguousHeader1a() {
		}

		@CrossOrigin
		@GetMapping(path = "/ambiguous-header", headers = "header1=b")
		public void ambiguousHeader1b() {
		}

		@CrossOrigin
		@GetMapping(path = "/ambiguous-produces", produces = "application/xml")
		public String ambiguousProducesXml() {
			return "<a></a>";
		}

		@CrossOrigin
		@GetMapping(path = "/ambiguous-produces", produces = "application/json")
		public String ambiguousProducesJson() {
			return "{}";
		}

		@CrossOrigin(origins = { "https://site1.com", "https://site2.com" },
				allowedHeaders = { "header1", "header2" },
				exposedHeaders = { "header3", "header4" },
				methods = RequestMethod.DELETE,
				maxAge = 123,
				allowCredentials = "false")
		@RequestMapping(path = "/customized", method = { RequestMethod.GET, RequestMethod.POST })
		public void customized() {
		}

		@CrossOrigin("https://example.com")
		@RequestMapping("/customOrigin")
		public void customOriginDefinedViaValueAttribute() {
		}

		@CrossOrigin("${myOrigin}")
		@RequestMapping("/someOrigin")
		public void customOriginDefinedViaPlaceholder() {
		}
	}


	@Controller
	@SuppressWarnings("unused")
	private static class MethodLevelControllerWithBogusAllowCredentialsValue {

		@CrossOrigin(allowCredentials = "bogus")
		@RequestMapping("/bogus")
		public void bogusAllowCredentialsValue() {
		}
	}


	@Controller
	@CrossOrigin(allowCredentials = "false")
	private static class ClassLevelController {

		@RequestMapping(path = "/foo", method = RequestMethod.GET)
		public void foo() {
		}

		@CrossOrigin
		@RequestMapping(path = "/bar", method = RequestMethod.GET)
		public void bar() {
		}

		@CrossOrigin(allowCredentials = "true")
		@RequestMapping(path = "/baz", method = RequestMethod.GET)
		public void baz() {
		}

	}


	@Target({ElementType.METHOD, ElementType.TYPE})
	@Retention(RetentionPolicy.RUNTIME)
	@CrossOrigin
	private @interface ComposedCrossOrigin {

		String[] origins() default {};

		String allowCredentials() default "";
	}


	@Controller
	@ComposedCrossOrigin(origins = "http://www.foo.example/", allowCredentials = "true")
	private static class ClassLevelMappingWithComposedAnnotation {

		@RequestMapping(path = "/foo", method = RequestMethod.GET)
		public void foo() {
		}
	}


	@Controller
	private static class MethodLevelMappingWithComposedAnnotation {

		@RequestMapping(path = "/foo", method = RequestMethod.GET)
		@ComposedCrossOrigin(origins = "http://www.foo.example/", allowCredentials = "true")
		public void foo() {
		}
	}


	private static class TestRequestMappingInfoHandlerMapping extends RequestMappingHandlerMapping {

		void registerHandler(Object handler) {
			super.detectHandlerMethods(handler);
		}

		@Override
		protected boolean isHandler(Class<?> beanType) {
			return AnnotationUtils.findAnnotation(beanType, Controller.class) != null;
		}

		@Override
		protected RequestMappingInfo getMappingForMethod(Method method, Class<?> handlerType) {
			RequestMapping annotation = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
			if (annotation != null) {
				RequestMappingInfo.BuilderConfiguration options = new RequestMappingInfo.BuilderConfiguration();
				if (getPatternParser() != null) {
					options.setPatternParser(getPatternParser());
				}
				return RequestMappingInfo.paths(annotation.value())
						.methods(annotation.method())
						.params(annotation.params())
						.headers(annotation.headers())
						.consumes(annotation.consumes())
						.produces(annotation.produces())
						.options(options)
						.build();
			}
			else {
				return null;
			}
		}

		@Override
		protected String initLookupPath(HttpServletRequest request) {
			// At runtime this is done by the DispatcherServlet
			if (getPatternParser() != null) {
				RequestPath requestPath = ServletRequestPathUtils.parseAndCache(request);
				return requestPath.pathWithinApplication().value();
			}
			return super.initLookupPath(request);
		}
	}

}
