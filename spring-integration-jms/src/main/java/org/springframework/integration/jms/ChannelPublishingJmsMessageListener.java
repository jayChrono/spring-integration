/*
 * Copyright 2002-2023 the original author or authors.
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

package org.springframework.integration.jms;

import java.util.Map;

import jakarta.jms.DeliveryMode;
import jakarta.jms.Destination;
import jakarta.jms.InvalidDestinationException;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.log.LogAccessor;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.integration.core.MessagingTemplate;
import org.springframework.integration.expression.ExpressionUtils;
import org.springframework.integration.gateway.MessagingGatewaySupport;
import org.springframework.integration.support.DefaultMessageBuilderFactory;
import org.springframework.integration.support.MessageBuilderFactory;
import org.springframework.integration.support.management.TrackableComponent;
import org.springframework.integration.support.utils.IntegrationUtils;
import org.springframework.jms.listener.SessionAwareMessageListener;
import org.springframework.jms.support.JmsUtils;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.converter.SimpleMessageConverter;
import org.springframework.jms.support.destination.DestinationResolver;
import org.springframework.jms.support.destination.DynamicDestinationResolver;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.ErrorMessage;
import org.springframework.util.Assert;

/**
 * JMS MessageListener that converts a JMS Message into a Spring Integration
 * Message and sends that Message to a channel. If the 'expectReply' value is
 * <code>true</code>, it will also wait for a Spring Integration reply Message
 * and convert that into a JMS reply.
 *
 * @author Mark Fisher
 * @author Juergen Hoeller
 * @author Oleg Zhurakousky
 * @author Artem Bilan
 * @author Gary Russell
 */
public class ChannelPublishingJmsMessageListener
		implements SessionAwareMessageListener<jakarta.jms.Message>, InitializingBean,
		TrackableComponent, BeanFactoryAware {

	protected final LogAccessor logger = new LogAccessor(getClass()); // NOSONAR final

	private final GatewayDelegate gatewayDelegate = new GatewayDelegate();

	private boolean expectReply;

	private MessageConverter messageConverter = new SimpleMessageConverter();

	private boolean extractRequestPayload = true;

	private boolean extractReplyPayload = true;

	private Object defaultReplyDestination;

	private String correlationKey;

	private long replyTimeToLive = jakarta.jms.Message.DEFAULT_TIME_TO_LIVE;

	private int replyPriority = jakarta.jms.Message.DEFAULT_PRIORITY;

	private int replyDeliveryMode = jakarta.jms.Message.DEFAULT_DELIVERY_MODE;

	private boolean explicitQosEnabledForReplies;

	private DestinationResolver destinationResolver = new DynamicDestinationResolver();

	private Expression replyToExpression;

	private JmsHeaderMapper headerMapper = new DefaultJmsHeaderMapper();

	private BeanFactory beanFactory;

	private MessageBuilderFactory messageBuilderFactory = new DefaultMessageBuilderFactory();

	private StandardEvaluationContext evaluationContext;

	/**
	 * Specify whether a JMS reply Message is expected.
	 * @param expectReply true if a reply is expected.
	 */
	public void setExpectReply(boolean expectReply) {
		this.expectReply = expectReply;
	}

	public void setComponentName(String componentName) {
		this.gatewayDelegate.setComponentName(componentName);
	}

	public void setRequestChannel(MessageChannel requestChannel) {
		this.gatewayDelegate.setRequestChannel(requestChannel);
	}

	public void setRequestChannelName(String requestChannelName) {
		this.gatewayDelegate.setRequestChannelName(requestChannelName);
	}

	public void setReplyChannel(MessageChannel replyChannel) {
		this.gatewayDelegate.setReplyChannel(replyChannel);
	}

	public void setReplyChannelName(String replyChannelName) {
		this.gatewayDelegate.setReplyChannelName(replyChannelName);
	}

	public void setErrorChannel(MessageChannel errorChannel) {
		this.gatewayDelegate.setErrorChannel(errorChannel);
	}

	public void setErrorChannelName(String errorChannelName) {
		this.gatewayDelegate.setErrorChannelName(errorChannelName);
	}

	public void setRequestTimeout(long requestTimeout) {
		this.gatewayDelegate.setRequestTimeout(requestTimeout);
	}

	public void setReplyTimeout(long replyTimeout) {
		this.gatewayDelegate.setReplyTimeout(replyTimeout);
	}

	public void setErrorOnTimeout(boolean errorOnTimeout) {
		this.gatewayDelegate.setErrorOnTimeout(errorOnTimeout);
	}

	@Override
	public void setShouldTrack(boolean shouldTrack) {
		this.gatewayDelegate.setShouldTrack(shouldTrack);
	}

	@Override
	public String getComponentName() {
		return this.gatewayDelegate.getComponentName();
	}

	@Override
	public String getComponentType() {
		return this.gatewayDelegate.getComponentType();
	}

	/**
	 * Set the default reply destination to send reply messages to. This will
	 * be applied in case of a request message that does not carry a
	 * "JMSReplyTo" field.
	 * @param defaultReplyDestination The default reply destination.
	 */
	public void setDefaultReplyDestination(Destination defaultReplyDestination) {
		this.defaultReplyDestination = defaultReplyDestination;
	}

	/**
	 * Set the name of the default reply queue to send reply messages to.
	 * This will be applied in case of a request message that does not carry a
	 * "JMSReplyTo" field.
	 * <p>Alternatively, specify a JMS Destination object as "defaultReplyDestination".
	 * @param destinationName The default reply destination name.
	 * @see #setDestinationResolver
	 * @see #setDefaultReplyDestination(jakarta.jms.Destination)
	 */
	public void setDefaultReplyQueueName(String destinationName) {
		this.defaultReplyDestination = new DestinationNameHolder(destinationName, false);
	}

	/**
	 * Set the name of the default reply topic to send reply messages to.
	 * This will be applied in case of a request message that does not carry a
	 * "JMSReplyTo" field.
	 * <p>Alternatively, specify a JMS Destination object as "defaultReplyDestination".
	 * @param destinationName The default reply topic name.
	 * @see #setDestinationResolver
	 * @see #setDefaultReplyDestination(jakarta.jms.Destination)
	 */
	public void setDefaultReplyTopicName(String destinationName) {
		this.defaultReplyDestination = new DestinationNameHolder(destinationName, true);
	}

	/**
	 * Specify the time-to-live property for JMS reply Messages.
	 * @param replyTimeToLive The reply time to live.
	 * @see jakarta.jms.MessageProducer#setTimeToLive(long)
	 */
	public void setReplyTimeToLive(long replyTimeToLive) {
		this.replyTimeToLive = replyTimeToLive;
	}

	/**
	 * Specify the priority value for JMS reply Messages.
	 * @param replyPriority The reply priority.
	 * @see jakarta.jms.MessageProducer#setPriority(int)
	 */
	public void setReplyPriority(int replyPriority) {
		this.replyPriority = replyPriority;
	}

	/**
	 * Specify the delivery mode for JMS reply Messages.
	 * @param replyDeliveryPersistent true for a persistent reply message.
	 * @see jakarta.jms.MessageProducer#setDeliveryMode(int)
	 */
	public void setReplyDeliveryPersistent(boolean replyDeliveryPersistent) {
		this.replyDeliveryMode = replyDeliveryPersistent ? DeliveryMode.PERSISTENT : DeliveryMode.NON_PERSISTENT;
	}

	/**
	 * Provide the name of a JMS property that should be copied from the request
	 * Message to the reply Message. If this value is NULL (the default) then the
	 * JMSMessageID from the request will be copied into the JMSCorrelationID of the reply
	 * unless there is already a value in the JMSCorrelationID property of the newly created
	 * reply Message in which case nothing will be copied. If the JMSCorrelationID of the
	 * request Message should be copied into the JMSCorrelationID of the reply Message
	 * instead, then this value should be set to "JMSCorrelationID".
	 * Any other value will be treated as a JMS String Property to be copied as-is
	 * from the request Message into the reply Message with the same property name.
	 * @param correlationKey The correlation key.
	 */
	public void setCorrelationKey(String correlationKey) {
		this.correlationKey = correlationKey;
	}

	/**
	 * Specify whether explicit QoS should be enabled for replies
	 * (for timeToLive, priority, and deliveryMode settings).
	 * @param explicitQosEnabledForReplies true to enable explicit QoS.
	 */
	public void setExplicitQosEnabledForReplies(boolean explicitQosEnabledForReplies) {
		this.explicitQosEnabledForReplies = explicitQosEnabledForReplies;
	}

	/**
	 * Set the DestinationResolver that should be used to resolve reply
	 * destination names for this listener.
	 * <p>The default resolver is a DynamicDestinationResolver. Specify a
	 * JndiDestinationResolver for resolving destination names as JNDI locations.
	 * @param destinationResolver The destination resolver.
	 * @see org.springframework.jms.support.destination.DynamicDestinationResolver
	 * @see org.springframework.jms.support.destination.JndiDestinationResolver
	 */
	public void setDestinationResolver(DestinationResolver destinationResolver) {
		Assert.notNull(destinationResolver, "destinationResolver must not be null");
		this.destinationResolver = destinationResolver;
	}

	/**
	 * Set a SpEL expression to resolve a 'replyTo' destination from a request
	 * {@link jakarta.jms.Message} as a root evaluation object
	 * if {@link jakarta.jms.Message#getJMSReplyTo()} is null.
	 * @param replyToExpression the SpEL expression for 'replyTo' destination.
	 * @since 6.1
	 */
	public void setReplyToExpression(Expression replyToExpression) {
		this.replyToExpression = replyToExpression;
	}

	/**
	 * Provide a {@link MessageConverter} implementation to use when
	 * converting between JMS Messages and Spring Integration Messages.
	 * If none is provided, a {@link SimpleMessageConverter} will
	 * be used.
	 * @param messageConverter The message converter.
	 */
	public void setMessageConverter(MessageConverter messageConverter) {
		this.messageConverter = messageConverter;
	}

	/**
	 * Provide a {@link JmsHeaderMapper} implementation to use when
	 * converting between JMS Messages and Spring Integration Messages.
	 * If none is provided, a {@link DefaultJmsHeaderMapper} will be used.
	 * @param headerMapper The header mapper.
	 */
	public void setHeaderMapper(JmsHeaderMapper headerMapper) {
		this.headerMapper = headerMapper;
	}

	/**
	 * Specify whether the JMS request Message's body should be extracted prior
	 * to converting into a Spring Integration Message. This value is set to
	 * <code>true</code> by default. To send the JMS Message itself as a
	 * Spring Integration Message payload, set this to <code>false</code>.
	 * @param extractRequestPayload true if the request payload should be extracted.
	 */
	public void setExtractRequestPayload(boolean extractRequestPayload) {
		this.extractRequestPayload = extractRequestPayload;
	}

	/**
	 * Specify whether the Spring Integration reply Message's payload should be
	 * extracted prior to converting into a JMS Message. This value is set to
	 * <code>true</code> by default. To send the Spring Integration Message
	 * itself as the JMS Message's body, set this to <code>false</code>.
	 * @param extractReplyPayload true if the reply payload should be extracted.
	 */
	public void setExtractReplyPayload(boolean extractReplyPayload) {
		this.extractReplyPayload = extractReplyPayload;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	@Override
	public void onMessage(jakarta.jms.Message jmsMessage, Session session) throws JMSException {
		Message<?> requestMessage;
		try {
			final Object result;
			if (this.extractRequestPayload) {
				result = this.messageConverter.fromMessage(jmsMessage);
				this.logger.debug(() -> "converted JMS Message [" + jmsMessage + "] to integration Message payload ["
						+ result + "]");
			}
			else {
				result = jmsMessage;
			}

			Map<String, Object> headers = this.headerMapper.toHeaders(jmsMessage);
			requestMessage =
					(result instanceof Message<?>) ?
							this.messageBuilderFactory.fromMessage((Message<?>) result).copyHeaders(headers).build() :
							this.messageBuilderFactory.withPayload(result).copyHeaders(headers).build();
		}
		catch (RuntimeException e) {
			MessageChannel errorChannel = this.gatewayDelegate.getErrorChannel();
			if (errorChannel == null) {
				throw e;
			}
			this.gatewayDelegate.getMessagingTemplate()
					.send(errorChannel,
							this.gatewayDelegate.buildErrorMessage(
									new MessagingException("Inbound conversion failed for: " + jmsMessage, e)));
			return;
		}

		if (!this.expectReply) {
			this.gatewayDelegate.send(requestMessage);
		}
		else {
			Message<?> replyMessage = this.gatewayDelegate.sendAndReceiveMessage(requestMessage);
			if (replyMessage != null) {
				Destination destination = getReplyDestination(jmsMessage, session);
				this.logger.debug(() -> "Reply destination: " + destination);
				// convert SI Message to JMS Message
				final Object replyResult;
				if (this.extractReplyPayload) {
					replyResult = replyMessage.getPayload();
				}
				else {
					replyResult = replyMessage;
				}
				try {
					jakarta.jms.Message jmsReply = this.messageConverter.toMessage(replyResult, session);
					// map SI Message Headers to JMS Message Properties/Headers
					this.headerMapper.fromHeaders(replyMessage.getHeaders(), jmsReply);
					copyCorrelationIdFromRequestToReply(jmsMessage, jmsReply);
					sendReply(jmsReply, destination, session);
				}
				catch (RuntimeException ex) {
					this.logger.error(ex, () -> "Failed to generate JMS Reply Message from: " + replyResult);
					throw ex;
				}
			}
			else {
				this.logger.debug("expected a reply but none was received");
			}
		}
	}

	@Override
	public void afterPropertiesSet() {
		if (this.beanFactory != null) {
			this.gatewayDelegate.setBeanFactory(this.beanFactory);
		}
		this.gatewayDelegate.afterPropertiesSet();
		this.messageBuilderFactory = IntegrationUtils.getMessageBuilderFactory(this.beanFactory);
		this.evaluationContext = ExpressionUtils.createStandardEvaluationContext(this.beanFactory);
	}

	protected void start() {
		this.gatewayDelegate.start();
	}

	protected void stop() {
		this.gatewayDelegate.stop();
	}

	private void copyCorrelationIdFromRequestToReply(jakarta.jms.Message requestMessage,
			jakarta.jms.Message replyMessage) throws JMSException {

		if (this.correlationKey != null) {
			if (this.correlationKey.equals("JMSCorrelationID")) {
				replyMessage.setJMSCorrelationID(requestMessage.getJMSCorrelationID());
			}
			else {
				String value = requestMessage.getStringProperty(this.correlationKey);
				if (value != null) {
					replyMessage.setStringProperty(this.correlationKey, value);
				}
				else {
					this.logger.warn(() -> "No property value available on request Message for correlationKey '"
							+ this.correlationKey + "'");
				}
			}
		}
		else if (replyMessage.getJMSCorrelationID() == null) {
			replyMessage.setJMSCorrelationID(requestMessage.getJMSMessageID());
		}
	}

	/**
	 * Determine a reply destination for the given message.
	 * It will first check the JMS Reply-To {@link Destination}
	 * of the supplied request message;
	 * if that is null, then the configured {@link #replyToExpression} is evaluated
	 * (if any), then a{@link #resolveDefaultReplyDestination default reply destination}
	 * is returned; if this too is null,
	 * then an {@link InvalidDestinationException} is thrown.
	 * @param request the original incoming JMS message
	 * @param session the JMS Session to operate on
	 * @return the reply destination (never null)
	 * @throws JMSException if thrown by JMS API methods
	 * @throws InvalidDestinationException if no {@link Destination} can be determined
	 * @see #setDefaultReplyDestination
	 * @see jakarta.jms.Message#getJMSReplyTo()
	 */
	private Destination getReplyDestination(jakarta.jms.Message request, Session session) throws JMSException {
		Destination replyTo = resolveReplyTo(request, session);
		if (replyTo == null) {
			replyTo = resolveDefaultReplyDestination(session);
			if (replyTo == null) {
				throw new InvalidDestinationException("Cannot determine reply destination: " +
						"Request message does not contain reply-to destination, and no default reply destination set.");
			}
		}
		return replyTo;

	}

	@Nullable
	private Destination resolveReplyTo(jakarta.jms.Message request, Session session) throws JMSException {
		Destination replyTo = request.getJMSReplyTo();
		if (replyTo == null) {
			if (this.replyToExpression != null) {
				Object replyToValue = this.replyToExpression.getValue(this.evaluationContext, request);
				if (replyToValue instanceof Destination destination) {
					return destination;
				}
				else if (replyToValue instanceof String destinationName) {
					return this.destinationResolver.resolveDestinationName(session, destinationName, false);
				}
			}
		}
		return replyTo;
	}

	/**
	 * Resolve the default reply destination into a JMS {@link Destination}, using this
	 * listener's {@link DestinationResolver} in case of a destination name.
	 * @param session The session.
	 * @return the located {@link Destination}
	 * @throws jakarta.jms.JMSException if resolution failed
	 * @see #setDefaultReplyDestination
	 * @see #setDefaultReplyQueueName
	 * @see #setDefaultReplyTopicName
	 * @see #setDestinationResolver
	 */
	private Destination resolveDefaultReplyDestination(Session session) throws JMSException {
		if (this.defaultReplyDestination instanceof Destination) {
			return (Destination) this.defaultReplyDestination;
		}
		if (this.defaultReplyDestination instanceof DestinationNameHolder nameHolder) {
			return this.destinationResolver.resolveDestinationName(session, nameHolder.name, nameHolder.isTopic);
		}
		return null;
	}

	private void sendReply(jakarta.jms.Message replyMessage, Destination destination, Session session)
			throws JMSException {

		MessageProducer producer = session.createProducer(destination);
		try {
			if (this.explicitQosEnabledForReplies) {
				producer.send(replyMessage, this.replyDeliveryMode, this.replyPriority, this.replyTimeToLive);
			}
			else {
				producer.send(replyMessage);
			}
		}
		finally {
			JmsUtils.closeMessageProducer(producer);
		}
	}

	/**
	 * Internal class combining a destination name
	 * and its target destination type (queue or topic).
	 */
	private record DestinationNameHolder(String name, boolean isTopic) {

	}

	private class GatewayDelegate extends MessagingGatewaySupport {

		GatewayDelegate() {
		}

		@Override
		protected void send(Object request) { // NOSONAR - not useless, increases visibility
			super.send(request);
		}

		@Override
		protected Message<?> sendAndReceiveMessage(Object request) { // NOSONAR - not useless, increases visibility
			return super.sendAndReceiveMessage(request);
		}

		protected ErrorMessage buildErrorMessage(Throwable throwable) {
			return super.buildErrorMessage(null, throwable);
		}

		protected MessagingTemplate getMessagingTemplate() {
			return this.messagingTemplate;
		}

		@Override
		public String getComponentType() {
			if (ChannelPublishingJmsMessageListener.this.expectReply) {
				return "jms:inbound-gateway";
			}
			else {
				return "jms:message-driven-channel-adapter";
			}
		}

	}

}
