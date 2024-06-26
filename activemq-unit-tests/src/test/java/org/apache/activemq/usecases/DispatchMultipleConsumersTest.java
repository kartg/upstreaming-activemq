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
package org.apache.activemq.usecases;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.jms.Connection;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import junit.framework.TestCase;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.command.ActiveMQQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Rajani Chennamaneni
 */
public class DispatchMultipleConsumersTest extends TestCase {
    private final static Logger logger = LoggerFactory.getLogger(DispatchMultipleConsumersTest.class);
    BrokerService broker;
    Destination dest;
    String destinationName = "TEST.Q";
    String msgStr = "Test text message";
    int messagesPerThread = 20;
    int producerThreads = 50;
    int consumerCount = 2;
    AtomicInteger sentCount;
    AtomicInteger consumedCount;
    CountDownLatch producerLatch;
    CountDownLatch consumerLatch;
    String brokerURL;
    String userName = "";
    String password = "";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        broker = new BrokerService();
        broker.setPersistent(true);
        broker.setUseJmx(true);
        broker.deleteAllMessages();
        broker.addConnector("tcp://localhost:0");
        broker.start();
        broker.waitUntilStarted();
        dest = new ActiveMQQueue(destinationName);
        resetCounters();
        brokerURL = broker.getTransportConnectors().get(0).getPublishableConnectString();
    }

    @Override
    protected void tearDown() throws Exception {
        broker.stop();
        broker.waitUntilStopped();
        super.tearDown();
    }

    private void resetCounters() {
        sentCount = new AtomicInteger(0);
        consumedCount = new AtomicInteger(0);
        producerLatch = new CountDownLatch(producerThreads);
        consumerLatch = new CountDownLatch(consumerCount);
    }

    public void testDispatch1() {
        for (int i = 1; i <= 5; i++) {
            resetCounters();
            dispatch();
            assertEquals("Incorrect messages in Iteration " + i, sentCount.get(), consumedCount.get());
        }
    }

    private void dispatch() {
        startConsumers();
        startProducers();
        try {
            producerLatch.await();
            consumerLatch.await();
        } catch (InterruptedException e) {
            fail("test interrupted!");
        }
    }

    private void startConsumers() {
        ActiveMQConnectionFactory connFactory = new ActiveMQConnectionFactory(userName, password, brokerURL);
        Connection conn;
        try {
            conn = connFactory.createConnection();
            conn.start();
            for (int i = 0; i < consumerCount; i++) {
                new ConsumerThread(conn, "ConsumerThread"+i);
            }
        } catch (JMSException e) {
            logger.error("Failed to start consumers", e);
        }
    }

    private void startProducers() {
        ActiveMQConnectionFactory connFactory = new ActiveMQConnectionFactory(userName, password, brokerURL);
        for (int i = 0; i < producerThreads; i++) {
            new ProducerThread(connFactory, messagesPerThread, "ProducerThread"+i);
        }
    }

    private class ConsumerThread extends Thread {
        Session session;
        MessageConsumer consumer;

        public ConsumerThread(Connection conn, String name) {
            super();
            this.setName(name);
            logger.trace("Created new consumer thread:" + name);
            try {
                session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
                consumer = session.createConsumer(dest);
                start();
            } catch (JMSException e) {
                logger.error("Failed to start consumer thread:" + name, e);
            }
        }

        @Override
        public void run() {
            int msgCount = 0;
            int nullCount = 0;
            while (true) {
                try {
                    Message msg = consumer.receive(1000);
                    if (msg == null) {
                        if (producerLatch.getCount() > 0) {
                            continue;
                        }
                        nullCount++;
                        if (nullCount > 10) {
                            //assume that we are not getting any more messages
                            break;
                        } else {
                            continue;
                        }
                    } else {
                        nullCount = 0;
                    }
                    Thread.sleep(100);
                    if (logger.isTraceEnabled()) {
                        logger.trace("Message received:" + msg.getJMSMessageID());
                    }
                    msgCount++;
                } catch (JMSException e) {
                    logger.error("Failed to consume:", e);
                } catch (InterruptedException e) {
                    logger.error("Interrupted!", e);
                }
            }
            try {
                consumer.close();
            } catch (JMSException e) {
                logger.error("Failed to close consumer " + getName(), e);
            }
            consumedCount.addAndGet(msgCount);
            consumerLatch.countDown();
            logger.trace("Consumed " + msgCount + " messages using thread " + getName());
        }
    }

    private class ProducerThread extends Thread {
        int count;
        Connection conn;
        Session session;
        MessageProducer producer;

        public ProducerThread(ActiveMQConnectionFactory connFactory, int count, String name) {
            super();
            this.count = count;
            this.setName(name);
            logger.trace("Created new producer thread:" + name);
            try {
                conn = connFactory.createConnection();
                conn.start();
                session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
                producer = session.createProducer(dest);
                start();
            } catch (JMSException e) {
                logger.error("Failed to start producer thread:" + name, e);
            }
        }

        @Override
        public void run() {
            int i = 0;
            try {
                for (; i < count; i++) {
                    producer.send(session.createTextMessage(msgStr));
                    Thread.sleep(500);
                }
                conn.close();
            } catch (JMSException e) {
                logger.error(e.getMessage(), e);
            } catch (InterruptedException e) {
                logger.error("Interrupted!", e);
            }
            sentCount.addAndGet(i);
            producerLatch.countDown();
            if (logger.isTraceEnabled()) {
                logger.trace("Sent " + i + " messages from thread " + getName());
            }
        }
    }
}
