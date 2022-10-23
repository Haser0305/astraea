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
package org.astraea.common.balancer.executor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.astraea.common.Utils;
import org.astraea.common.admin.AsyncAdmin;
import org.astraea.common.admin.Replica;
import org.astraea.common.admin.TopicPartition;
import org.astraea.common.admin.TopicPartitionReplica;
import org.astraea.common.balancer.log.ClusterLogAllocation;

/** Execute every possible migration immediately. */
public class StraightPlanExecutor implements RebalancePlanExecutor {

  public StraightPlanExecutor() {}

  @Override
  public void run(RebalanceAdmin rebalanceAdmin, ClusterLogAllocation logAllocation) {
    final var clusterInfo = rebalanceAdmin.clusterInfo();
    final var currentLogAllocation = ClusterLogAllocation.of(clusterInfo);
    final var migrationTargets =
        ClusterLogAllocation.findNonFulfilledAllocation(currentLogAllocation, logAllocation);

    var executeReplicaMigration =
        (Function<TopicPartition, List<ReplicaMigrationTask>>)
            (topicPartition) ->
                rebalanceAdmin.alterReplicaPlacements(
                    topicPartition,
                    logAllocation.logPlacements(topicPartition).stream()
                        .sorted(
                            Comparator.comparing(Replica::isPreferredLeader).<Replica>reversed())
                        .collect(
                            Collectors.toMap(
                                e -> e.nodeInfo().id(),
                                Replica::path,
                                (e1, e2) -> e1,
                                LinkedHashMap::new)));

    // do log migration
    migrationTargets.stream()
        .map(executeReplicaMigration)
        .flatMap(Collection::stream)
        .map(ReplicaMigrationTask::completableFuture)
        .collect(Collectors.toUnmodifiableSet())
        .forEach(CompletableFuture::join);

    // do leader election
    migrationTargets.stream()
        .map(rebalanceAdmin::leaderElection)
        .map(LeaderElectionTask::completableFuture)
        .collect(Collectors.toUnmodifiableSet())
        .forEach(CompletableFuture::join);
  }

  @Override
  public CompletionStage<Void> run(AsyncAdmin admin, ClusterLogAllocation logAllocation) {
    return admin
        .topicNames(true)
        .thenCompose(
            topicNames ->
                admin
                    .clusterInfo(topicNames)
                    .thenApply(ClusterLogAllocation::of)
                    .thenApply(
                        currentLogAllocation ->
                            ClusterLogAllocation.findNonFulfilledAllocation(
                                currentLogAllocation, logAllocation)))
        .thenCompose(
            topicPartitions -> {
              Map<TopicPartition, List<Integer>> move2BrokerItems =
                  new java.util.HashMap<>(Collections.emptyMap());
              Map<TopicPartitionReplica, String> move2FolderItems =
                  new java.util.HashMap<>(Collections.emptyMap());

              topicPartitions.forEach(
                  topicPartition -> {
                    var expectedPlacement =
                        logAllocation.logPlacements(topicPartition).stream()
                            .sorted(
                                Comparator.comparing(Replica::isPreferredLeader)
                                    .<Replica>reversed())
                            .collect(
                                Collectors.toMap(
                                    e -> e.nodeInfo().id(),
                                    Replica::path,
                                    (e1, e2) -> e1,
                                    LinkedHashMap::new));

                    var currentReplicaBrokerPath =
                        admin
                            .replicas(Set.of(topicPartition.topic()))
                            .thenApply(
                                replicas ->
                                    replicas.stream()
                                        .filter(
                                            replica ->
                                                replica.partition() == topicPartition.partition())
                                        .collect(
                                            Collectors.toMap(
                                                replica -> replica.nodeInfo().id(),
                                                Replica::path)));

                    // to find out which replica needs to move data folder
                    var forCrossDirMigration =
                        currentReplicaBrokerPath
                            .thenApply(
                                currentBrokerPath ->
                                    expectedPlacement.entrySet().stream()
                                        .filter(
                                            entry ->
                                                !(currentBrokerPath.containsKey(entry.getKey())
                                                    && currentBrokerPath.containsValue(
                                                        entry.getValue())))
                                        .collect(
                                            Collectors.toMap(
                                                idAndPath ->
                                                    TopicPartitionReplica.of(
                                                        topicPartition.topic(),
                                                        topicPartition.partition(),
                                                        idAndPath.getKey()),
                                                Map.Entry::getValue)))
                            .toCompletableFuture()
                            .join();

                    move2BrokerItems.put(
                        TopicPartition.of(topicPartition.topic(), topicPartition.partition()),
                        new ArrayList<>(expectedPlacement.keySet()));
                    move2FolderItems.putAll(forCrossDirMigration);
                  });
              return admin
                  .moveToBrokers(move2BrokerItems)
                  .thenRun(
                      () -> {
                        // wait until the whole cluster knows the replica list just changed
                        Utils.sleep(Duration.ofMillis(500));

                        admin.moveToFolders(move2FolderItems).toCompletableFuture().join();
                        admin.preferredLeaderElection(topicPartitions).toCompletableFuture().join();
                      });
            });
  }
}
