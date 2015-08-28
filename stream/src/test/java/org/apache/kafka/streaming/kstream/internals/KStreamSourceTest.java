/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.streaming.kstream.internals;

import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.streaming.kstream.KStream;
import org.apache.kafka.streaming.kstream.KStreamBuilder;
import org.apache.kafka.streaming.kstream.internals.KStreamSource;
import org.apache.kafka.test.MockKStreamBuilder;
import org.apache.kafka.test.MockProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class KStreamSourceTest {

    private String topicName = "topic";

    private KStreamBuilder topology = new MockKStreamBuilder();
    private StringDeserializer keyDeserializer = new StringDeserializer();
    private StringDeserializer valDeserializer = new StringDeserializer();

    @Test
    public void testKStreamSource() {

<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
    KStreamTopology initializer = new MockKStreamTopology();
<<<<<<< HEAD
=======
    KStreamInitializer initializer = new KStreamInitializerImpl(null, null, null, null);
>>>>>>> new api model
=======
    KStreamInitializer initializer = new KStreamInitializerImpl();
>>>>>>> wip
=======
    KStreamTopology initializer = new MockKStreamTopology();
>>>>>>> wip
    TestProcessor<String, String> processor = new TestProcessor<>();
=======
    MockProcessor<String, String> processor = new MockProcessor<>();
>>>>>>> removing io.confluent imports: wip
=======
        KStreamTopology initializer = new MockKStreamTopology();
=======
>>>>>>> wip
        MockProcessor<String, String> processor = new MockProcessor<>();
>>>>>>> compile and test passed

        KStream<String, String> stream = topology.<String, String>from(keyDeserializer, valDeserializer, topicName);
        stream.process(processor);

        final String[] expectedKeys = new String[]{"k1", "k2", "k3"};
        final String[] expectedValues = new String[]{"v1", "v2", "v3"};

        for (int i = 0; i < expectedKeys.length; i++) {
            ((KStreamSource<String, String>) stream).source().process(expectedKeys[i], expectedValues[i]);
        }

        assertEquals(3, processor.processed.size());

        for (int i = 0; i < expectedKeys.length; i++) {
            assertEquals(expectedKeys[i] + ":" + expectedValues[i], processor.processed.get(i));
        }
    }
}