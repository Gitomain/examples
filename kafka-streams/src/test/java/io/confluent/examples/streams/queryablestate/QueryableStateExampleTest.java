package io.confluent.examples.streams.queryablestate;

import com.google.common.collect.Sets;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;

import io.confluent.examples.streams.IntegrationTestUtils;
import io.confluent.examples.streams.kafka.EmbeddedSingleNodeKafkaCluster;

import static junit.framework.TestCase.assertTrue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsCollectionContaining.hasItem;
import static org.hamcrest.core.IsCollectionContaining.hasItems;
import static org.hamcrest.core.IsEqual.equalTo;

public class QueryableStateExampleTest {

  @ClassRule
  public static final EmbeddedSingleNodeKafkaCluster CLUSTER = new EmbeddedSingleNodeKafkaCluster();
  public static final String WORD_COUNT = "wordcount-lambda-example-word-count-repartition";
  public static final String
      WINDOWED_WORD_COUNT =
      "wordcount-lambda-example-windowed-word-count-repartition";
  public static final String BASE_URL = "http://localhost:7070/state";

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();
  private KafkaStreams kafkaStreams;
  private QueryableStateProxy proxy;

  @BeforeClass
  public static void createTopic() {
    CLUSTER.createTopic(QueryableStateExample.TEXT_LINES_TOPIC, 2, 1);
    // The next two topics don't need to be created as they would be auto-created
    // by KafkaStreams, but it just makes the test more reliable if they already exist
    // as creating the topics causes a rebalance which closes the stores etc. So it makes
    // the timing quite difficult...
    CLUSTER.createTopic(WORD_COUNT, 2, 1);
    CLUSTER.createTopic(WINDOWED_WORD_COUNT, 2, 1);
  }

  @After
  public void shutdown() throws Exception {
    if (kafkaStreams != null) {
      kafkaStreams.close();
    }

    if (proxy != null) {
      proxy.stop();
    }

  }

  @Test
  public void shouldDemonstrateQueryableState() throws Exception {
    final List<String> inputValues = Arrays.asList("hello",
                                                   "world",
                                                   "world",
                                                   "hello world",
                                                   "all streams lead to kafka",
                                                   "streams",
                                                   "kafka streams");

    Properties producerConfig = new Properties();
    producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
    producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
    producerConfig.put(ProducerConfig.RETRIES_CONFIG, 0);
    producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    IntegrationTestUtils
        .produceValuesSynchronously(QueryableStateExample.TEXT_LINES_TOPIC, inputValues,
                                    producerConfig);

    final int port = 7070;
    kafkaStreams = QueryableStateExample.createStreams(
        createStreamConfig(CLUSTER.bootstrapServers(), port, "one"));
    kafkaStreams.start();
    proxy = QueryableStateExample.startRestProxy(kafkaStreams, port);

    final Client client = ClientBuilder.newClient();
    List<HostStoreInfo> hostStoreInfo = client.target(BASE_URL + "/instances")
        .request(MediaType.APPLICATION_JSON_TYPE).get(new GenericType<List<HostStoreInfo>>() {
        });

    final long start = System.currentTimeMillis();

    // Fetch all instances. Done in a loop to wait for initialization
    // of the stores
    while (hostStoreInfo.isEmpty()
           || hostStoreInfo.get(0).getStoreNames().size() != 2
              && System.currentTimeMillis() - start < 60000L) {
      Thread.sleep(10);
      hostStoreInfo = client.target(BASE_URL + "/instances")
          .request(MediaType.APPLICATION_JSON_TYPE).get(new GenericType<List<HostStoreInfo>>() {
          });
    }

    assertThat(hostStoreInfo, hasItem(
        new HostStoreInfo("localhost", 7070, Sets.newHashSet("word-count", "windowed-word-count"))
    ));

    // Fetch instances with word-count
    final List<HostStoreInfo>
        wordCountInstances =
        client.target(BASE_URL + "/instances/word-count")
            .request(MediaType.APPLICATION_JSON_TYPE).get(new GenericType<List<HostStoreInfo>>() {
        });

    assertThat(wordCountInstances, hasItem(
        new HostStoreInfo("localhost", 7070, Sets.newHashSet("word-count", "windowed-word-count"))
    ));

    // Fetch all from the word-count store
    final Invocation.Builder
        allRequest =
        client.target(BASE_URL + "/keyvalues/word-count/all")
            .request(MediaType.APPLICATION_JSON_TYPE);

    final List<KeyValueBean>
        allValues =
        Arrays.asList(new KeyValueBean("all", 1L),
                      new KeyValueBean("hello", 2L),
                      new KeyValueBean("kafka", 2L),
                      new KeyValueBean("lead", 1L),
                      new KeyValueBean("streams", 3L),
                      new KeyValueBean("to", 1L),
                      new KeyValueBean("world", 3L));
    final List<KeyValueBean>
        all = fetchRangeOfValues(allRequest,
                                 allValues);
    assertThat(all, equalTo(allValues));

    // Fetch a range of values from the word-count store
    final List<KeyValueBean> expectedRange = Arrays.asList(
        new KeyValueBean("all", 1L),
        new KeyValueBean("hello", 2L),
        new KeyValueBean("kafka", 2L));

    final Invocation.Builder
        request =
        client.target(BASE_URL + "/keyvalues/word-count/range/all/kafka")
            .request(MediaType.APPLICATION_JSON_TYPE);
    final List<KeyValueBean>
        range = fetchRangeOfValues(request, expectedRange);

    assertThat(range, equalTo(expectedRange));

    // Find the streams instance that would have the key hello
    final HostStoreInfo
        hostWithHelloKey =
        client.target(BASE_URL + "/instance/word-count/hello")
            .request(MediaType.APPLICATION_JSON_TYPE).get(HostStoreInfo.class);

    // Fetch the value for hello from the instance.
    final KeyValueBean result = client.target("http://" + hostWithHelloKey.getHost() +
                                              ":" + hostWithHelloKey.getPort() +
                                              "/state/keyvalue/word-count/hello")
        .request(MediaType.APPLICATION_JSON_TYPE).get(new GenericType<KeyValueBean>() {
        });

    assertThat(result, equalTo(new KeyValueBean("hello", 2L)));

    // fetch windowed values for a key
    final List<KeyValueBean>
        windowedResult =
        client.target(BASE_URL + "/windowed/windowed-word-count/streams/0/" + System
            .currentTimeMillis())
            .request(MediaType.APPLICATION_JSON_TYPE)
            .get(new GenericType<List<KeyValueBean>>() {
            });
    assertThat(windowedResult.size(), equalTo(1));
    final KeyValueBean keyValueBean = windowedResult.get(0);
    assertTrue(keyValueBean.getKey().startsWith("streams"));
    assertThat(keyValueBean.getValue(), equalTo(3L));
  }

  private List<KeyValueBean> fetchRangeOfValues(final Invocation.Builder request,
                                                final List<KeyValueBean>
                                                    expectedResults) {
    List<KeyValueBean> results = new ArrayList<>();
    final long timeout = System.currentTimeMillis() + 10000L;
    while (!results.containsAll(expectedResults) && System.currentTimeMillis() < timeout) {
      try {
        results = request.get(new GenericType<List<KeyValueBean>>() {
        });
      } catch (NotFoundException e) {
        //
      }
    }
    Collections.sort(results, (o1, o2) -> o1.getKey().compareTo(o2.getKey()));
    return results;
  }

  private Properties createStreamConfig(final String bootStrap, final int port, String stateDir)
      throws
      IOException {
    Properties streamsConfiguration = new Properties();
    // Give the Streams application a unique name.  The name must be unique in the Kafka cluster
    // against which the application is run.
    streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG, "wordcount-lambda-example");
    // Where to find Kafka broker(s).
    streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootStrap);
    // Where to find the corresponding ZooKeeper ensemble.
    streamsConfiguration.put(StreamsConfig.ZOOKEEPER_CONNECT_CONFIG, CLUSTER.zookeeperConnect());
    streamsConfiguration.put(StreamsConfig.APPLICATION_SERVER_CONFIG, "localhost:" + port);
    streamsConfiguration.put(StreamsConfig.STATE_DIR_CONFIG, temp.newFolder(stateDir).getPath());
    return streamsConfiguration;
  }
}