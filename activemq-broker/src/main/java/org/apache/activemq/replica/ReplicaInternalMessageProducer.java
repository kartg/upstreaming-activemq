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
package org.apache.activemq.replica;

import org.apache.activemq.broker.Broker;
import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.broker.ProducerBrokerExchange;
import org.apache.activemq.command.ActiveMQMessage;
import org.apache.activemq.command.ProducerInfo;
import org.apache.activemq.state.ProducerState;

import static java.util.Objects.requireNonNull;

public class ReplicaInternalMessageProducer {

    private final Broker broker;
    private final ConnectionContext connectionContext;

    ReplicaInternalMessageProducer(final Broker broker, final ConnectionContext connectionContext) {
        this.broker = requireNonNull(broker);
        this.connectionContext = requireNonNull(connectionContext);
    }

    void produceToReplicaQueue(final ConnectionContext connectionContext, final ActiveMQMessage eventMessage) throws Exception {
        if (connectionContext != null) {
            sendIgnoringFlowControl(eventMessage, connectionContext);
            return;
        }
        sendIgnoringFlowControl(eventMessage, this.connectionContext);
    }

    void produceToReplicaQueue(final ActiveMQMessage eventMessage) throws Exception {
        produceToReplicaQueue(this.connectionContext, eventMessage);
    }

    private void sendIgnoringFlowControl(ActiveMQMessage eventMessage, ConnectionContext connectionContext) throws Exception {
        ProducerBrokerExchange producerExchange = new ProducerBrokerExchange();
        producerExchange.setConnectionContext(connectionContext);
        producerExchange.setMutable(true);
        producerExchange.setProducerState(new ProducerState(new ProducerInfo()));

        boolean originalFlowControl = connectionContext.isProducerFlowControl();
        try {
            connectionContext.setProducerFlowControl(false);
            broker.send(producerExchange, eventMessage);
        } finally {
            connectionContext.setProducerFlowControl(originalFlowControl);
        }
    }

}
