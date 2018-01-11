/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.replication;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.CompoundConfiguration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.replication.ReplicationPeer.PeerState;
import org.apache.hadoop.hbase.zookeeper.ZKWatcher;
import org.apache.yetus.audience.InterfaceAudience;

import org.apache.hbase.thirdparty.com.google.common.annotations.VisibleForTesting;

/**
 * This provides an class for maintaining a set of peer clusters. These peers are remote slave
 * clusters that data is replicated to.
 */
@InterfaceAudience.Private
public class ReplicationPeers {

  private final Configuration conf;

  // Map of peer clusters keyed by their id
  private final ConcurrentMap<String, ReplicationPeerImpl> peerCache;
  private final ReplicationPeerStorage peerStorage;

  ReplicationPeers(ZKWatcher zookeeper, Configuration conf) {
    this.conf = conf;
    this.peerCache = new ConcurrentHashMap<>();
    this.peerStorage = ReplicationStorageFactory.getReplicationPeerStorage(zookeeper, conf);
  }

  public Configuration getConf() {
    return conf;
  }

  public void init() throws ReplicationException {
    // Loading all existing peerIds into peer cache.
    for (String peerId : this.peerStorage.listPeerIds()) {
      addPeer(peerId);
    }
  }

  @VisibleForTesting
  public ReplicationPeerStorage getPeerStorage() {
    return this.peerStorage;
  }

  /**
   * Method called after a peer has been connected. It will create a ReplicationPeer to track the
   * newly connected cluster.
   * @param peerId a short that identifies the cluster
   * @return whether a ReplicationPeer was successfully created
   * @throws ReplicationException
   */
  public boolean addPeer(String peerId) throws ReplicationException {
    if (this.peerCache.containsKey(peerId)) {
      return false;
    }

    peerCache.put(peerId, createPeer(peerId));
    return true;
  }

  public void removePeer(String peerId) {
    peerCache.remove(peerId);
  }

  /**
   * Get the peer state for the specified connected remote slave cluster. The value might be read
   * from cache, so it is recommended to use {@link #peerStorage } to read storage directly if
   * reading the state after enabling or disabling it.
   * @param peerId a short that identifies the cluster
   * @return true if replication is enabled, false otherwise.
   */
  public boolean isPeerEnabled(String peerId) {
    ReplicationPeer replicationPeer = this.peerCache.get(peerId);
    if (replicationPeer == null) {
      throw new IllegalArgumentException("Peer with id= " + peerId + " is not cached");
    }
    return replicationPeer.getPeerState() == PeerState.ENABLED;
  }

  /**
   * Returns the ReplicationPeerImpl for the specified cached peer. This ReplicationPeer will
   * continue to track changes to the Peer's state and config. This method returns null if no peer
   * has been cached with the given peerId.
   * @param peerId id for the peer
   * @return ReplicationPeer object
   */
  public ReplicationPeerImpl getPeer(String peerId) {
    return peerCache.get(peerId);
  }

  /**
   * Returns the set of peerIds of the clusters that have been connected and have an underlying
   * ReplicationPeer.
   * @return a Set of Strings for peerIds
   */
  public Set<String> getAllPeerIds() {
    return peerCache.keySet();
  }

  public static Configuration getPeerClusterConfiguration(ReplicationPeerConfig peerConfig,
      Configuration baseConf) throws ReplicationException {
    Configuration otherConf;
    try {
      otherConf = HBaseConfiguration.createClusterConf(baseConf, peerConfig.getClusterKey());
    } catch (IOException e) {
      throw new ReplicationException("Can't get peer configuration for peer " + peerConfig, e);
    }

    if (!peerConfig.getConfiguration().isEmpty()) {
      CompoundConfiguration compound = new CompoundConfiguration();
      compound.add(otherConf);
      compound.addStringMap(peerConfig.getConfiguration());
      return compound;
    }

    return otherConf;
  }

  public PeerState refreshPeerState(String peerId) throws ReplicationException {
    ReplicationPeerImpl peer = peerCache.get(peerId);
    if (peer == null) {
      throw new ReplicationException("Peer with id=" + peerId + " is not cached.");
    }
    peer.setPeerState(peerStorage.isPeerEnabled(peerId));
    return peer.getPeerState();
  }

  public ReplicationPeerConfig refreshPeerConfig(String peerId) throws ReplicationException {
    ReplicationPeerImpl peer = peerCache.get(peerId);
    if (peer == null) {
      throw new ReplicationException("Peer with id=" + peerId + " is not cached.");
    }
    peer.setPeerConfig(peerStorage.getPeerConfig(peerId));
    return peer.getPeerConfig();
  }

  /**
<<<<<<< 2bb2fd611d4b88c724a2b561f10433b56c6fd3dd
   * Update the peerConfig for the a given peer cluster
   * @param id a short that identifies the cluster
   * @param peerConfig new config for the peer cluster
   * @throws ReplicationException
=======
   * Helper method to connect to a peer
   * @param peerId peer's identifier
   * @return object representing the peer
>>>>>>> HBASE-19622 Reimplement ReplicationPeers with the new replication storage interface
   */
  private ReplicationPeerImpl createPeer(String peerId) throws ReplicationException {
    ReplicationPeerConfig peerConfig = peerStorage.getPeerConfig(peerId);
    boolean enabled = peerStorage.isPeerEnabled(peerId);
    return new ReplicationPeerImpl(getPeerClusterConfiguration(peerConfig, conf), peerId, enabled,
        peerConfig);
  }
}
