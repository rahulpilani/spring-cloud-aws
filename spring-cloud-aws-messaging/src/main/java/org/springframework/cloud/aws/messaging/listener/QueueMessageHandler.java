/*
 * Copyright 2013-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.aws.messaging.listener;

import org.springframework.cloud.aws.messaging.support.NotificationMessageArgumentResolver;
import org.springframework.cloud.aws.messaging.support.NotificationSubjectArgumentResolver;
import org.springframework.cloud.aws.messaging.support.converter.ObjectMessageConverter;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.handler.HandlerMethod;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.support.AnnotationExceptionHandlerMethodResolver;
import org.springframework.messaging.handler.annotation.support.HeaderMethodArgumentResolver;
import org.springframework.messaging.handler.annotation.support.HeadersMethodArgumentResolver;
import org.springframework.messaging.handler.annotation.support.PayloadArgumentResolver;
import org.springframework.messaging.handler.invocation.AbstractExceptionHandlerMethodResolver;
import org.springframework.messaging.handler.invocation.AbstractMethodMessageHandler;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.handler.invocation.HandlerMethodReturnValueHandler;
import org.springframework.util.ClassUtils;
import org.springframework.util.comparator.ComparableComparator;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author Agim Emruli
 * @author Alain Sahli
 * @since 1.0
 */
public class QueueMessageHandler extends AbstractMethodMessageHandler<QueueMessageHandler.MappingInformation> {

	private static final boolean JACKSON_2_PRESENT = ClassUtils.isPresent(
			"com.fasterxml.jackson.databind.ObjectMapper", QueueMessageHandler.class.getClassLoader());

	@Override
	protected List<? extends HandlerMethodArgumentResolver> initArgumentResolvers() {
		List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
		resolvers.addAll(getCustomArgumentResolvers());

		resolvers.add(new HeaderMethodArgumentResolver(null, null));
		resolvers.add(new HeadersMethodArgumentResolver());
		resolvers.add(new NotificationMessageArgumentResolver());
		resolvers.add(new NotificationSubjectArgumentResolver());

		CompositeMessageConverter compositeMessageConverter = createPayloadArgumentCompositeConverter();
		resolvers.add(new PayloadArgumentResolver(compositeMessageConverter, new NoOpValidator()));

		return resolvers;
	}

	@Override
	protected List<? extends HandlerMethodReturnValueHandler> initReturnValueHandlers() {
		ArrayList<HandlerMethodReturnValueHandler> handlers = new ArrayList<>();
		handlers.addAll(this.getCustomReturnValueHandlers());

		return handlers;
	}

	@Override
	protected boolean isHandler(Class<?> beanType) {
		return true;
	}

	@Override
	protected MappingInformation getMappingForMethod(Method method, Class<?> handlerType) {
		MessageMapping messageMappingAnnotation = AnnotationUtils.findAnnotation(method, MessageMapping.class);
		if (messageMappingAnnotation == null) {
			return null;
		}

		if (messageMappingAnnotation.value().length < 1) {
			throw new IllegalStateException("@MessageMapping annotation must have at least one destination");
		}

		Set<String> logicalResourceIds = new HashSet<>(messageMappingAnnotation.value().length);
		logicalResourceIds.addAll(Arrays.asList(messageMappingAnnotation.value()));

		return new MappingInformation(logicalResourceIds);
	}

	@Override
	protected Set<String> getDirectLookupDestinations(MappingInformation mapping) {
		return mapping.getLogicalResourceIds();
	}

	@Override
	protected String getDestination(Message<?> message) {
		return message.getHeaders().get(Headers.LOGICAL_RESOURCE_ID_MESSAGE_HEADER_KEY).toString();
	}

	@Override
	protected MappingInformation getMatchingMapping(MappingInformation mapping, Message<?> message) {
		if (mapping.getLogicalResourceIds().contains(getDestination(message))) {
			return mapping;
		} else {
			return null;
		}
	}

	@Override
	protected Comparator<MappingInformation> getMappingComparator(Message<?> message) {
		return new ComparableComparator<>();
	}

	@Override
	protected AbstractExceptionHandlerMethodResolver createExceptionHandlerMethodResolverFor(Class<?> beanType) {
		return new AnnotationExceptionHandlerMethodResolver(beanType);
	}

	@Override
	protected void handleNoMatch(Set<MappingInformation> ts, String lookupDestination, Message<?> message) {
		this.logger.warn("No match found");
	}

	@Override
	protected void processHandlerMethodException(HandlerMethod handlerMethod, Exception ex, Message<?> message) {
		super.processHandlerMethodException(handlerMethod, ex, message);
		throw new MessagingException("An exception occurred while invoking the handler method", ex);
	}

	private CompositeMessageConverter createPayloadArgumentCompositeConverter() {
		List<MessageConverter> payloadArgumentConverters = new ArrayList<>();

		if (JACKSON_2_PRESENT) {
			MappingJackson2MessageConverter jacksonMessageConverter = new MappingJackson2MessageConverter();
			jacksonMessageConverter.setSerializedPayloadClass(String.class);
			jacksonMessageConverter.setStrictContentTypeMatch(true);
			payloadArgumentConverters.add(jacksonMessageConverter);
		}

		ObjectMessageConverter objectMessageConverter = new ObjectMessageConverter();
		objectMessageConverter.setStrictContentTypeMatch(true);
		payloadArgumentConverters.add(objectMessageConverter);

		payloadArgumentConverters.add(new SimpleMessageConverter());

		return new CompositeMessageConverter(payloadArgumentConverters);
	}

	@SuppressWarnings("ComparableImplementedButEqualsNotOverridden")
	protected static class MappingInformation implements Comparable<MappingInformation> {

		private final Set<String> logicalResourceIds;

		public MappingInformation(Set<String> logicalResourceIds) {
			this.logicalResourceIds = Collections.unmodifiableSet(logicalResourceIds);
		}

		public Set<String> getLogicalResourceIds() {
			return this.logicalResourceIds;
		}

		@SuppressWarnings("NullableProblems")
		@Override
		public int compareTo(MappingInformation o) {
			return 0;
		}
	}

	private static final class NoOpValidator implements Validator {

		@Override
		public boolean supports(Class<?> clazz) {
			return false;
		}

		@Override
		public void validate(Object target, Errors errors) {
		}
	}

	/**
	 * @author Alain Sahli
	 * @author Agim Emruli
	 */
	@SuppressWarnings("ClassNamingConvention") // Only used internally and prefixed with the parent class
	public static final class Headers {

		public static final String LOGICAL_RESOURCE_ID_MESSAGE_HEADER_KEY = "LogicalResourceId";

		private Headers() {
			// Avoid instantiation
		}

	}
}
