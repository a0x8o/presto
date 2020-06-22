/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.kafka.util;

import com.google.common.collect.ImmutableMap;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.LongSerializer;
import org.testcontainers.containers.KafkaContainer;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.prestosql.plugin.kafka.util.TestUtils.toProperties;
import static org.testcontainers.containers.KafkaContainer.KAFKA_PORT;

public class TestingKafka
        implements Closeable
{
    private final KafkaContainer container;

    public TestingKafka()
    {
        container = new KafkaContainer("5.2.1");
    }

    public void start()
    {
        container.start();
    }

    @Override
    public void close()
    {
        container.close();
    }

    public void createTopics(String topic)
    {
        createTopics(2, 1, topic);
    }

    private void createTopics(int partitions, int replication, String topic)
    {
        try {
            List<String> command = new ArrayList<>();
            command.add("kafka-topics");
            command.add("--partitions");
            command.add(Integer.toString(partitions));
            command.add("--replication-factor");
            command.add(Integer.toString(replication));
            command.add("--topic");
            command.add(topic);

            container.execInContainer(command.toArray(new String[0]));
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String getConnectString()
    {
        return container.getContainerIpAddress() + ":" + container.getMappedPort(KAFKA_PORT);
    }

    public KafkaProducer<Long, Object> createProducer()
    {
        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, getConnectString())
                .put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, LongSerializer.class.getName())
                .put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class.getName())
                .put(ProducerConfig.PARTITIONER_CLASS_CONFIG, NumberPartitioner.class.getName())
                .put(ProducerConfig.ACKS_CONFIG, "1")
                .build();

        return new KafkaProducer<>(toProperties(properties));
    }
}
