/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.astraea.common.admin;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.kafka.clients.CommonClientConfigs;
import org.astraea.common.DataRate;
import org.astraea.common.Utils;

public interface AsyncAdmin extends AutoCloseable {

  static AsyncAdmin of(String bootstrap) {
    return of(Map.of(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrap));
  }

  static AsyncAdmin of(Map<String, Object> configs) {
    return new AsyncAdminImpl(configs);
  }

  // ---------------------------------[internal]---------------------------------//
  String clientId();

  /** @return the number of pending requests. */
  int pendingRequests();

  // ---------------------------------[readonly]---------------------------------//

  /**
   * @param listInternal should list internal topics or not
   * @return names of topics
   */
  CompletionStage<Set<String>> topicNames(boolean listInternal);

  CompletionStage<List<Topic>> topics(Set<String> topics);

  /**
   * @param topics target
   * @return the partitions belong to input topics
   */
  CompletionStage<Set<TopicPartition>> topicPartitions(Set<String> topics);

  /**
   * list all partition replicas belongs to input brokers
   *
   * @param brokers to search
   * @return all partition belongs to brokers
   */
  CompletionStage<Set<TopicPartitionReplica>> topicPartitionReplicas(Set<Integer> brokers);

  CompletionStage<List<Partition>> partitions(Set<String> topics);

  /** @return online node information */
  CompletionStage<Set<NodeInfo>> nodeInfos();

  /** @return online broker information */
  CompletionStage<List<Broker>> brokers();

  default CompletionStage<Map<Integer, Set<String>>> brokerFolders() {
    return brokers()
        .thenApply(
            brokers ->
                brokers.stream()
                    .collect(
                        Collectors.toMap(
                            NodeInfo::id,
                            n ->
                                n.folders().stream()
                                    .map(Broker.DataFolder::path)
                                    .collect(Collectors.toSet()))));
  }

  CompletionStage<Set<String>> consumerGroupIds();

  CompletionStage<List<ConsumerGroup>> consumerGroups(Set<String> consumerGroupIds);

  CompletionStage<List<ProducerState>> producerStates(Set<TopicPartition> partitions);

  CompletionStage<List<AddingReplica>> addingReplicas(Set<String> topics);

  CompletionStage<Set<String>> transactionIds();

  CompletionStage<List<Transaction>> transactions(Set<String> transactionIds);

  CompletionStage<List<Replica>> replicas(Set<String> topics);

  default CompletionStage<ClusterInfo<Replica>> clusterInfo(Set<String> topics) {
    return nodeInfos().thenCombine(replicas(topics), ClusterInfo::of);
  }

  /**
   * get the quotas associated to given target. {@link QuotaConfigs#IP}, {@link
   * QuotaConfigs#CLIENT_ID}, and {@link QuotaConfigs#USER}
   *
   * @param targetKey to search
   * @return quotas matched to given target
   */
  CompletionStage<List<Quota>> quotas(String targetKey);

  CompletionStage<List<Quota>> quotas();

  // ---------------------------------[write]---------------------------------//

  /**
   * set the connection rate for given ip address.
   *
   * @param ipAndRate ip address and its connection rate
   */
  CompletionStage<Void> setConnectionQuotas(Map<String, Integer> ipAndRate);

  /**
   * remove the connection quotas for given ip addresses
   *
   * @param ips to delete connection quotas
   */
  CompletionStage<Void> unsetConnectionQuotas(Set<String> ips);

  /**
   * set the producer rate for given client id
   *
   * @param clientAndRate client id and its producer rate
   */
  CompletionStage<Void> setProducerQuotas(Map<String, DataRate> clientAndRate);

  /**
   * remove the producer rate quotas for given client ids
   *
   * @param clientIds to delete producer rate quotas
   */
  CompletionStage<Void> unsetProducerQuotas(Set<String> clientIds);

  /**
   * set the consumer rate for given client id
   *
   * @param clientAndRate client id and its consumer rate
   */
  CompletionStage<Void> setConsumerQuotas(Map<String, DataRate> clientAndRate);

  /**
   * remove the consumer rate quotas for given client ids
   *
   * @param clientIds to delete consumer rate quotas
   */
  CompletionStage<Void> unsetConsumerQuotas(Set<String> clientIds);

  /** @return a topic creator to set all topic configs and then run the procedure. */
  TopicCreator creator();

  CompletionStage<Void> moveToBrokers(Map<TopicPartition, List<Integer>> assignments);

  CompletionStage<Void> moveToFolders(Map<TopicPartitionReplica, String> assignments);

  /**
   * Perform preferred leader election for the specified topic/partitions. Let the first replica(the
   * preferred leader) in the partition replica list becomes the leader of its corresponding
   * topic/partition. Noted that the first replica(the preferred leader) must be in-sync state.
   * Otherwise, an exception might be raised.
   *
   * @param topicPartition to perform preferred leader election
   */
  CompletionStage<Void> preferredLeaderElection(TopicPartition topicPartition);

  /**
   * @param total the final number of partitions. Noted that reducing number of partitions is
   *     illegal
   */
  CompletionStage<Void> addPartitions(String topic, int total);

  /** @param override defines the key and new value. The other undefined keys won't get changed. */
  CompletionStage<Void> setConfigs(String topic, Map<String, String> override);

  /**
   * unset the value associated to given keys. The unset config will become either null of default
   * value. Normally, the default value is defined by server.properties or hardcode in source code.
   */
  CompletionStage<Void> unsetConfigs(String topic, Set<String> keys);

  /** @param override defines the key and new value. The other undefined keys won't get changed. */
  CompletionStage<Void> setConfigs(int brokerId, Map<String, String> override);

  /**
   * unset the value associated to given keys. The unset config will become either null of default
   * value. Normally, the default value is defined by server.properties or hardcode in source code.
   */
  CompletionStage<Void> unsetConfigs(int brokerId, Set<String> keys);

  /** delete topics by topic names */
  CompletionStage<Void> deleteTopics(Set<String> topics);

  /**
   * Remove the records when their offsets are smaller than given offsets.
   *
   * @param offsets to truncate topic partition
   * @return topic partition and low watermark (it means the minimum logStartOffset of all alive
   *     replicas)
   */
  CompletionStage<Map<TopicPartition, Long>> deleteRecords(Map<TopicPartition, Long> offsets);

  // ---------------------------------[wait]---------------------------------//

  /**
   * wait the async operations to be done on server-side. You have to define the predicate to
   * terminate loop. Or the loop get breaks when timeout is reached.
   *
   * @param topics to trace
   * @param predicate to break loop
   * @param timeout to break loop
   * @param debounce to double-check the status. Some brokers may return out-of-date cluster state,
   *     so you can set a positive value to keep the loop until to debounce is completed
   * @return a background running loop
   */
  default CompletionStage<Boolean> waitCluster(
      Set<String> topics,
      Predicate<ClusterInfo<Replica>> predicate,
      Duration timeout,
      int debounce) {
    return loop(
        () ->
            clusterInfo(topics)
                .thenApply(predicate::test)
                .exceptionally(
                    e -> {
                      System.out.println("e: " + e.getClass().getName());
                      if (e instanceof CompletionException
                          && e.getCause()
                              instanceof org.apache.kafka.common.errors.RetriableException)
                        return false;
                      throw (RuntimeException) e;
                    }),
        timeout.toMillis(),
        debounce,
        debounce);
  }

  static CompletionStage<Boolean> loop(
      Supplier<CompletionStage<Boolean>> supplier,
      long remainingMs,
      final int debounce,
      int remainingDebounce) {
    if (remainingMs <= 0) return CompletableFuture.completedFuture(false);
    var start = System.currentTimeMillis();
    return supplier
        .get()
        .thenCompose(
            match -> {
              // everything is good!!!
              if (match && remainingDebounce <= 0) return CompletableFuture.completedFuture(true);

              // take a break before retry/debounce
              Utils.sleep(Duration.ofMillis(300));

              var remaining = remainingMs - (System.currentTimeMillis() - start);

              // keep debounce
              if (match) return loop(supplier, remaining, debounce, remainingDebounce - 1);

              // reset debounce for retry
              return loop(supplier, remaining, debounce, debounce);
            });
  }

  @Override
  void close();
}