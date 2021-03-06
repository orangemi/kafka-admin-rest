package com.teambition.kafka.admin.model;

import kafka.admin.AdminClient;
import kafka.admin.AdminUtils;
import kafka.api.LeaderAndIsr;
import kafka.server.ConfigType;
import kafka.utils.Json;
import kafka.utils.ZkUtils;
import org.I0Itec.zkclient.exception.ZkNoNodeException;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.zookeeper.data.Stat;
import scala.Option;
import scala.collection.JavaConversions;
import scala.collection.Seq;

import java.util.*;
import java.util.Vector;

public class Model {
  private static final String ADMIN_CONSUMER_GROUP_NAME = "kafka-admin-consumer";
  private static final String ADMIN_CONSUMER_OFFSETS_GROUP_NAME = "kafka-admin-consumer-offset-reader";
  private String zkHost = "localhost:2181";
  private String kafkaHost = "localhost:9092";
  
  private static Model instance = null;
  private ZkUtils zkUtils;
  private AdminClient adminClient;
  private Consumer<String, String> adminConsumer;
  private ConsumerManager consumerManager;
  private Thread consumerThread;
  private int adminConsumerCount = 0;
  
  public static Model getInstance() {
    if (instance == null) throw new RuntimeException("instance not inited ...");
    return instance;
  }
  
  public static Model getInstance(Properties properties) {
    if (instance != null) throw new RuntimeException("Can not re-init model with properties");
    instance = new Model();
    instance.zkHost = properties.getProperty("zookeeper");
    instance.kafkaHost = properties.getProperty("kafka");
    instance.init();
    return instance;
  }
  
  private Consumer<String, String> getAdminConsumer() {
    if (adminConsumer == null) {
      adminConsumer = createConsumer(ADMIN_CONSUMER_GROUP_NAME);
    }
    return adminConsumer;
  }
  
  public Map<String, Object> getBrokerInfo(int id) {
    String data = getZookeeperData("/brokers/ids/" + id);
    Map<String, Object> map =
      JavaConversions.mapAsJavaMap((scala.collection.immutable.HashMap)Json.parseFull(data).get());
    return map;
  }
  
  public KafkaBrokerJmxClient getKafkaBrokerJmxClient(int id) {
    Map<String, Object> map = getBrokerInfo(id);
    String host = (String)map.get("host");
    int port = (int)map.get("jmx_port");
    if (port == -1) {
      throw new RuntimeException("broker jmx not enabled");
    }
    String jmxUrl = "service:jmx:rmi:///jndi/rmi://" + host + ":" + port + "/jmxrmi";
    return new KafkaBrokerJmxClient(jmxUrl);
  }
  
  public Collection<Integer> getBrokerCollections() {
    Collection<Integer> brokers = new Vector<>();
    JavaConversions.asJavaCollection(zkUtils.getAllBrokersInCluster()).forEach(broker -> brokers.add(broker.id()));
    return brokers;
  }
  
  public Collection<String> getTopicCollections() {
    return JavaConversions.asJavaCollection(zkUtils.getAllTopics());
  }
  
  public Properties getTopicConfig(String topic) {
    Properties configs = AdminUtils.fetchEntityConfig(zkUtils, ConfigType.Topic(), topic);
    return configs;
  }
  public TopicPartitionModel getTopicPartition(String topic, int partition) {
    long beginOffset = getTopicPartitionOffset(topic, partition, true);
    long endOffset = getTopicPartitionOffset(topic, partition);
  
    Collection<PartitionReplica> replicas = new Vector<>();
    TopicPartitionModel topicPartitionModel = new TopicPartitionModel(topic, partition, beginOffset, endOffset, replicas);
//    topicPartitionModels.add(topicPartitionModel);
  
    Option<LeaderAndIsr> leaderAndIsrOpt = zkUtils.getLeaderAndIsrForPartition(topic, partition);
    if (!leaderAndIsrOpt.isEmpty()) {
  
      LeaderAndIsr leaderAndIsr = leaderAndIsrOpt.get();
      topicPartitionModel.setLeader(leaderAndIsr.leader());
  
      JavaConversions.asJavaCollection(zkUtils.getReplicasForPartition(topic, partition)).forEach(brokerObj -> {
        int broker = (Integer) brokerObj;
        PartitionReplica replica = new PartitionReplica(
          broker,
          broker == leaderAndIsr.leader(),
          leaderAndIsr.isr().toSet().contains(broker)
        );
        replicas.add(replica);
      });
    }
    
    return topicPartitionModel;
    
  }
  
  public int getTopicPartitions(String topic) {
    return JavaConversions.mapAsJavaMap(
      zkUtils.getPartitionAssignmentForTopics(JavaConversions.asScalaBuffer(Arrays.asList(topic)))
        .get(topic)
        .get()).size();
  }
  public Collection<TopicPartitionModel> getTopicPartitionDetails(String topic) {
    Collection<TopicPartitionModel> topicPartitionModels = new Vector<>();
    Map<Object, Seq<Object>> partitionMap
      = JavaConversions.mapAsJavaMap(
        zkUtils.getPartitionAssignmentForTopics(JavaConversions.asScalaBuffer(Arrays.asList(topic)))
          .get(topic)
          .get());
    partitionMap.entrySet().forEach((entry) -> {
      int partition = (Integer)entry.getKey();
      topicPartitionModels.add(getTopicPartition(topic, partition));
    });

    return topicPartitionModels;
  }
  
  public Collection<String> getConsumerGroups() {
    return JavaConversions.asJavaCollection(zkUtils.getConsumerGroups());
  }
  
  public Collection<String> getZkConsumerGroupsByTopic(String topic) {
    Collection<String> consumers = new Vector<>();
    getZookeeperChildren("/consumers").stream()
      .filter(consumer -> getZookeeperChildren("/consumers/" + consumer + "/offsets").contains(topic))
      .forEach(consumer -> consumers.add(consumer));
    return consumers;
  }

  @Deprecated
  public Collection<String> getZkConsumerGroupsByTopicOrigin(String topic) {
    return JavaConversions.asJavaCollection(zkUtils.getAllConsumerGroupsForTopic(topic));
  }
  
  public Collection<String> getZkTopicsByConsumerGroup(String group) {
    return getZookeeperChildren("/consumers/" + group + "/offsets");
  }
  
  @Deprecated
  public Collection<String> getZkTopicsByConsumerGroupOrigin(String group) {
    return JavaConversions.asJavaCollection(zkUtils.getTopicsByConsumerGroup(group));
  }
  
  public long getTopicPartitionOffset(String topic, int parititon) {
    return getTopicPartitionOffset(topic, parititon, false);
  }
  
  public long getTopicPartitionOffset(String topic, int partition, boolean seekBeginning) {
    TopicPartition topicPartition = new TopicPartition(topic, partition);
    Collection<TopicPartition> topicPartitions = new Vector<>();
    topicPartitions.add(topicPartition);
    Consumer consumer = createConsumer(ADMIN_CONSUMER_GROUP_NAME + "-" + adminConsumerCount++);
    consumer.assign(topicPartitions);
    if (seekBeginning) {
      consumer.seekToBeginning(topicPartitions);
    } else {
      consumer.seekToEnd(topicPartitions);
    }
    long offset = consumer.position(topicPartition);
    consumer.close();
    return offset;
  }
  
  public ConsumerModel getZkConsumerGroup(String group) {
    ConsumerModel consumerModelModel = new ConsumerModel(group);
    Collection<String> topics = getZookeeperChildren("/consumers/" + group + "/offsets");
    topics.forEach(topic -> {
      Collection<String> partitions = getZookeeperChildren("/consumers/" + group + "/offsets/" + topic);
      partitions.forEach(partitionString -> {
        int partition = Integer.valueOf(partitionString);
        String offsetString = getZookeeperData("/consumers/" + group + "/offsets/" + topic + "/" + partitionString);
        long offset = Long.valueOf(offsetString);
        consumerModelModel.addTopicPartition(new TopicPartition(topic, partition), offset);
      });
    });
    return consumerModelModel;
  }
  
  public Map<String, ConsumerModel> getAllConsumerV2s() {
    return consumerManager.getConsumerList();
  }
 
  public Collection<String> getConsumerV2s() {
    Collection<String> consumers = new Vector<>();
    JavaConversions.asJavaCollection(adminClient.listAllConsumerGroupsFlattened()).forEach(consumerGroup -> {
      consumers.add(consumerGroup.groupId());
    });
    return consumers;
  }
  
  public ConsumerModel getConsumerV2(String group) {
    ConsumerModel consumerModelModel = new ConsumerModel(group);
    Consumer<String, String> consumer = createConsumer(group);
    JavaConversions.asJavaCollection(adminClient.describeConsumerGroup(group).get())
      .forEach(consumerSummary -> {
        JavaConversions.asJavaCollection(consumerSummary.assignment()).forEach(topicPartition -> {
          OffsetAndMetadata offsetMeta = consumer.committed(new TopicPartition(topicPartition.topic(), topicPartition.partition()));
          if (offsetMeta == null) return;
          long offset = offsetMeta.offset();
          consumerModelModel.addTopicPartition(topicPartition, offset);
        });
        
      });
    consumer.close();
    return consumerModelModel;
  }
  
  public boolean setConsumerV2(String group, String topic, int partition, long offset) {
    Consumer<String, String> consumer = createConsumer(group);
    consumer.seek(new TopicPartition(topic, partition), offset);
    consumer.commitSync();
    return false;
  }
  
  public Collection<String> getZookeeperChildren(String path) {
    try {
      return JavaConversions.asJavaCollection(zkUtils.getChildren(path));
    } catch (ZkNoNodeException e) {
      return new Vector<>();
    }
  }
  
  public Stat getZookeeperStat(String path) {
    return zkUtils.readDataMaybeNull(path)._2();
  }
  public String getZookeeperData(String path) {
    return zkUtils.readDataMaybeNull(path)._1().get();
  }
  
  private Model() {
  }

  private void init() {
    zkUtils = ZkUtils.apply(zkHost, 3000, 3000, false);

    // Create adminClient
    Properties adminProps = new Properties();
    adminProps.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, kafkaHost);
    adminClient = AdminClient.create(adminProps);

    // Create a thread getting all consumer history
    consumerManager = new ConsumerManager(kafkaHost);
    consumerThread = new Thread(consumerManager);
    consumerThread.setDaemon(true);
    consumerThread.start();
  }
  
  private Consumer<String, String> createConsumer(String group) {
    String deserializer = StringDeserializer.class.getName();
  
    // Create ConsumerModel
    Properties consumerProps = new Properties();
    consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaHost);
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, group);
    consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    consumerProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "30000");
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, deserializer);
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializer);
  
    return new KafkaConsumer<>(consumerProps);
  }
  
  public ConsumerManager getConsumerManager() {
    return consumerManager;
  }
}
