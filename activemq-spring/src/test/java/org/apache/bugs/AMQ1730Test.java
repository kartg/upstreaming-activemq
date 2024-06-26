/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.bugs;

import java.util.concurrent.CountDownLatch;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

import junit.framework.TestCase;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.listener.DefaultMessageListenerContainer;


public class AMQ1730Test extends TestCase {

    private static final Logger log = LoggerFactory.getLogger(AMQ1730Test.class);
    private static final String JMSX_DELIVERY_COUNT = "JMSXDeliveryCount";
    BrokerService brokerService;

    private static final int MESSAGE_COUNT = 250;

    public AMQ1730Test() {
        super();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        brokerService = new BrokerService();
        brokerService.addConnector("tcp://localhost:0");
        brokerService.setUseJmx(false);
        brokerService.start();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        brokerService.stop();
        brokerService.waitUntilStopped();
    }

    public void testRedelivery() throws Exception {

        ConnectionFactory connectionFactory = new ActiveMQConnectionFactory(
                brokerService.getTransportConnectors().get(0).getConnectUri().toString() + "?jms.prefetchPolicy.queuePrefetch=100");

        Connection connection = connectionFactory.createConnection();
        connection.start();

        Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
        Queue queue = session.createQueue("queue.test");

        MessageProducer producer = session.createProducer(queue);

        for (int i = 0; i < MESSAGE_COUNT; i++) {
            log.info("Sending message " + i);
            TextMessage message = session.createTextMessage("Message " + i);
            producer.send(message);
        }

        producer.close();
        session.close();
        connection.stop();
        connection.close();

        final CountDownLatch countDownLatch = new CountDownLatch(MESSAGE_COUNT);

        final ValueHolder<Boolean> messageRedelivered = new ValueHolder<Boolean>(false);

        DefaultMessageListenerContainer messageListenerContainer = new DefaultMessageListenerContainer();
        messageListenerContainer.setConnectionFactory(connectionFactory);
        messageListenerContainer.setDestination(queue);
        messageListenerContainer.setAutoStartup(false);
        messageListenerContainer.setConcurrentConsumers(1);
        messageListenerContainer.setMaxConcurrentConsumers(16);
        messageListenerContainer.setMaxMessagesPerTask(10);
        messageListenerContainer.setReceiveTimeout(10000);
        messageListenerContainer.setRecoveryInterval(5000);
        messageListenerContainer.setAcceptMessagesWhileStopping(false);
        messageListenerContainer.setCacheLevel(DefaultMessageListenerContainer.CACHE_NONE);
        messageListenerContainer.setSessionTransacted(false);
        messageListenerContainer.setMessageListener(new MessageListener() {

            @Override
            public void onMessage(Message message) {
                if (!(message instanceof TextMessage)) {
                    throw new RuntimeException();
                }
                try {
                    TextMessage textMessage = (TextMessage) message;
                    String text = textMessage.getText();
                    int messageDeliveryCount = message.getIntProperty(JMSX_DELIVERY_COUNT);
                    if (messageDeliveryCount > 1) {
                        messageRedelivered.set(true);
                    }
                    log.info("[Count down latch: " + countDownLatch.getCount() + "][delivery count: " + messageDeliveryCount + "] - " + "Received message with id: " + message.getJMSMessageID() + " with text: " + text);

                } catch (JMSException e) {
                    e.printStackTrace();
                }
                finally {
                    countDownLatch.countDown();
                }
            }

        });
        messageListenerContainer.afterPropertiesSet();

        messageListenerContainer.start();

        countDownLatch.await();
        messageListenerContainer.stop();
        messageListenerContainer.destroy();

        assertFalse("no message has redelivery > 1", messageRedelivered.get());
    }

    private class ValueHolder<T> {

        private T value;

        public ValueHolder(T value) {
            super();
            this.value = value;
        }

        void set(T value) {
            this.value = value;
        }

        T get() {
            return value;
        }
    }
}
