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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.replication.ReplicationPeer.PeerState;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.zookeeper.ZKConfig;
import org.apache.zookeeper.KeeperException;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * White box testing for replication state interfaces. Implementations should extend this class, and
 * initialize the interfaces properly.
 */
public abstract class TestReplicationStateBasic {

  private static final Logger LOG = LoggerFactory.getLogger(TestReplicationStateBasic.class);

  protected ReplicationQueueStorage rqs;
  protected ServerName server1 = ServerName.valueOf("hostname1.example.org", 1234, 12345);
  protected ServerName server2 = ServerName.valueOf("hostname2.example.org", 1234, 12345);
  protected ServerName server3 = ServerName.valueOf("hostname3.example.org", 1234, 12345);
  protected ReplicationPeers rp;
  protected static final String ID_ONE = "1";
  protected static final String ID_TWO = "2";
  protected static String KEY_ONE;
  protected static String KEY_TWO;

  // For testing when we try to replicate to ourself
  protected String OUR_ID = "3";
  protected String OUR_KEY;

  protected static int zkTimeoutCount;
  protected static final int ZK_MAX_COUNT = 300;
  protected static final int ZK_SLEEP_INTERVAL = 100; // millis

  @Test
  public void testReplicationQueueStorage() throws ReplicationException {
    // Test methods with empty state
    assertEquals(0, rqs.getListOfReplicators().size());
    assertTrue(rqs.getWALsInQueue(server1, "qId1").isEmpty());
    assertTrue(rqs.getAllQueues(server1).isEmpty());

    /*
     * Set up data Two replicators: -- server1: three queues with 0, 1 and 2 log files each --
     * server2: zero queues
     */
    rqs.addWAL(server1, "qId1", "trash");
    rqs.removeWAL(server1, "qId1", "trash");
    rqs.addWAL(server1,"qId2", "filename1");
    rqs.addWAL(server1,"qId3", "filename2");
    rqs.addWAL(server1,"qId3", "filename3");
    rqs.addWAL(server2,"trash", "trash");
    rqs.removeQueue(server2,"trash");

    List<ServerName> reps = rqs.getListOfReplicators();
    assertEquals(2, reps.size());
    assertTrue(server1.getServerName(), reps.contains(server1));
    assertTrue(server2.getServerName(), reps.contains(server2));

    assertTrue(rqs.getWALsInQueue(ServerName.valueOf("bogus", 12345, 12345), "bogus").isEmpty());
    assertTrue(rqs.getWALsInQueue(server1, "bogus").isEmpty());
    assertEquals(0, rqs.getWALsInQueue(server1, "qId1").size());
    assertEquals(1, rqs.getWALsInQueue(server1, "qId2").size());
    assertEquals("filename1", rqs.getWALsInQueue(server1, "qId2").get(0));

    assertTrue(rqs.getAllQueues(ServerName.valueOf("bogus", 12345, -1L)).isEmpty());
    assertEquals(0, rqs.getAllQueues(server2).size());
    List<String> list = rqs.getAllQueues(server1);
    assertEquals(3, list.size());
    assertTrue(list.contains("qId2"));
    assertTrue(list.contains("qId3"));
  }

  private void removeAllQueues(ServerName serverName) throws ReplicationException {
    for (String queue: rqs.getAllQueues(serverName)) {
      rqs.removeQueue(serverName, queue);
    }
  }
  @Test
  public void testReplicationQueues() throws ReplicationException {
    // Initialize ReplicationPeer so we can add peers (we don't transfer lone queues)
    rp.init();

    rqs.removeQueue(server1, "bogus");
    rqs.removeWAL(server1, "bogus", "bogus");
    removeAllQueues(server1);
    assertEquals(0, rqs.getAllQueues(server1).size());
    assertEquals(0, rqs.getWALPosition(server1, "bogus", "bogus"));
    assertTrue(rqs.getWALsInQueue(server1, "bogus").isEmpty());
    assertTrue(rqs.getAllQueues(ServerName.valueOf("bogus", 1234, 12345)).isEmpty());

    populateQueues();

    assertEquals(3, rqs.getListOfReplicators().size());
    assertEquals(0, rqs.getWALsInQueue(server2, "qId1").size());
    assertEquals(5, rqs.getWALsInQueue(server3, "qId5").size());
    assertEquals(0, rqs.getWALPosition(server3, "qId1", "filename0"));
    rqs.setWALPosition(server3, "qId5", "filename4", 354L);
    assertEquals(354L, rqs.getWALPosition(server3, "qId5", "filename4"));

    assertEquals(5, rqs.getWALsInQueue(server3, "qId5").size());
    assertEquals(0, rqs.getWALsInQueue(server2, "qId1").size());
    assertEquals(0, rqs.getAllQueues(server1).size());
    assertEquals(1, rqs.getAllQueues(server2).size());
    assertEquals(5, rqs.getAllQueues(server3).size());

    assertEquals(0, rqs.getAllQueues(server1).size());
    rqs.removeReplicatorIfQueueIsEmpty(server1);
    assertEquals(2, rqs.getListOfReplicators().size());

    List<String> queues = rqs.getAllQueues(server3);
    assertEquals(5, queues.size());
    for (String queue : queues) {
      rqs.claimQueue(server3, queue, server2);
    }
    rqs.removeReplicatorIfQueueIsEmpty(server3);
    assertEquals(1, rqs.getListOfReplicators().size());

    assertEquals(6, rqs.getAllQueues(server2).size());
    removeAllQueues(server2);
    rqs.removeReplicatorIfQueueIsEmpty(server2);
    assertEquals(0, rqs.getListOfReplicators().size());
  }

  @Test
  public void testInvalidClusterKeys() throws ReplicationException, KeeperException {
    rp.init();

    try {
      rp.registerPeer(ID_ONE,
        new ReplicationPeerConfig().setClusterKey("hostname1.example.org:1234:hbase"));
      fail("Should throw an IllegalArgumentException because " +
        "zookeeper.znode.parent is missing leading '/'.");
    } catch (IllegalArgumentException e) {
      // Expected.
    }

    try {
      rp.registerPeer(ID_ONE,
        new ReplicationPeerConfig().setClusterKey("hostname1.example.org:1234:/"));
      fail("Should throw an IllegalArgumentException because zookeeper.znode.parent is missing.");
    } catch (IllegalArgumentException e) {
      // Expected.
    }

    try {
      rp.registerPeer(ID_ONE,
        new ReplicationPeerConfig().setClusterKey("hostname1.example.org::/hbase"));
      fail("Should throw an IllegalArgumentException because " +
        "hbase.zookeeper.property.clientPort is missing.");
    } catch (IllegalArgumentException e) {
      // Expected.
    }
  }

  @Test
  public void testHfileRefsReplicationQueues() throws ReplicationException, KeeperException {
    rp.init();

    List<Pair<Path, Path>> files1 = new ArrayList<>(3);
    files1.add(new Pair<>(null, new Path("file_1")));
    files1.add(new Pair<>(null, new Path("file_2")));
    files1.add(new Pair<>(null, new Path("file_3")));
    assertTrue(rqs.getReplicableHFiles(ID_ONE).isEmpty());
    assertEquals(0, rqs.getAllPeersFromHFileRefsQueue().size());
    rp.registerPeer(ID_ONE, new ReplicationPeerConfig().setClusterKey(KEY_ONE));
    rqs.addPeerToHFileRefs(ID_ONE);
    rqs.addHFileRefs(ID_ONE, files1);
    assertEquals(1, rqs.getAllPeersFromHFileRefsQueue().size());
    assertEquals(3, rqs.getReplicableHFiles(ID_ONE).size());
    List<String> hfiles2 = new ArrayList<>(files1.size());
    for (Pair<Path, Path> p : files1) {
      hfiles2.add(p.getSecond().getName());
    }
    String removedString = hfiles2.remove(0);
    rqs.removeHFileRefs(ID_ONE, hfiles2);
    assertEquals(1, rqs.getReplicableHFiles(ID_ONE).size());
    hfiles2 = new ArrayList<>(1);
    hfiles2.add(removedString);
    rqs.removeHFileRefs(ID_ONE, hfiles2);
    assertEquals(0, rqs.getReplicableHFiles(ID_ONE).size());
    rp.unregisterPeer(ID_ONE);
  }

  @Test
  public void testRemovePeerForHFileRefs() throws ReplicationException, KeeperException {
    rp.init();
    rp.registerPeer(ID_ONE, new ReplicationPeerConfig().setClusterKey(KEY_ONE));
    rqs.addPeerToHFileRefs(ID_ONE);
    rp.registerPeer(ID_TWO, new ReplicationPeerConfig().setClusterKey(KEY_TWO));
    rqs.addPeerToHFileRefs(ID_TWO);

    List<Pair<Path, Path>> files1 = new ArrayList<>(3);
    files1.add(new Pair<>(null, new Path("file_1")));
    files1.add(new Pair<>(null, new Path("file_2")));
    files1.add(new Pair<>(null, new Path("file_3")));
    rqs.addHFileRefs(ID_ONE, files1);
    rqs.addHFileRefs(ID_TWO, files1);
    assertEquals(2, rqs.getAllPeersFromHFileRefsQueue().size());
    assertEquals(3, rqs.getReplicableHFiles(ID_ONE).size());
    assertEquals(3, rqs.getReplicableHFiles(ID_TWO).size());

    rp.unregisterPeer(ID_ONE);
    rqs.removePeerFromHFileRefs(ID_ONE);
    assertEquals(1, rqs.getAllPeersFromHFileRefsQueue().size());
    assertTrue(rqs.getReplicableHFiles(ID_ONE).isEmpty());
    assertEquals(3, rqs.getReplicableHFiles(ID_TWO).size());

    rp.unregisterPeer(ID_TWO);
    rqs.removePeerFromHFileRefs(ID_TWO);
    assertEquals(0, rqs.getAllPeersFromHFileRefsQueue().size());
    assertTrue(rqs.getReplicableHFiles(ID_TWO).isEmpty());
  }

  @Test
  public void testReplicationPeers() throws Exception {
    rp.init();

    // Test methods with non-existent peer ids
    try {
      rp.unregisterPeer("bogus");
      fail("Should have thrown an IllegalArgumentException when passed a bogus peerId");
    } catch (IllegalArgumentException e) {
    }
    try {
      rp.enablePeer("bogus");
      fail("Should have thrown an IllegalArgumentException when passed a bogus peerId");
    } catch (IllegalArgumentException e) {
    }
    try {
      rp.disablePeer("bogus");
      fail("Should have thrown an IllegalArgumentException when passed a bogus peerId");
    } catch (IllegalArgumentException e) {
    }
    try {
      rp.getStatusOfPeer("bogus");
      fail("Should have thrown an IllegalArgumentException when passed a bogus peerId");
    } catch (IllegalArgumentException e) {
    }
    assertFalse(rp.peerConnected("bogus"));
    rp.peerDisconnected("bogus");

    assertNull(rp.getPeerConf("bogus"));
    assertNumberOfPeers(0);

    // Add some peers
    rp.registerPeer(ID_ONE, new ReplicationPeerConfig().setClusterKey(KEY_ONE));
    assertNumberOfPeers(1);
    rp.registerPeer(ID_TWO, new ReplicationPeerConfig().setClusterKey(KEY_TWO));
    assertNumberOfPeers(2);

    // Test methods with a peer that is added but not connected
    try {
      rp.getStatusOfPeer(ID_ONE);
      fail("There are no connected peers, should have thrown an IllegalArgumentException");
    } catch (IllegalArgumentException e) {
    }
    assertEquals(KEY_ONE, ZKConfig.getZooKeeperClusterKey(rp.getPeerConf(ID_ONE).getSecond()));
    rp.unregisterPeer(ID_ONE);
    rp.peerDisconnected(ID_ONE);
    assertNumberOfPeers(1);

    // Add one peer
    rp.registerPeer(ID_ONE, new ReplicationPeerConfig().setClusterKey(KEY_ONE));
    rp.peerConnected(ID_ONE);
    assertNumberOfPeers(2);
    assertTrue(rp.getStatusOfPeer(ID_ONE));
    rp.disablePeer(ID_ONE);
    // now we do not rely on zk watcher to trigger the state change so we need to trigger it
    // manually...
    ReplicationPeerImpl peer = rp.getConnectedPeer(ID_ONE);
    peer.refreshPeerState();
    assertEquals(PeerState.DISABLED, peer.getPeerState());
    assertConnectedPeerStatus(false, ID_ONE);
    rp.enablePeer(ID_ONE);
    // now we do not rely on zk watcher to trigger the state change so we need to trigger it
    // manually...
    peer.refreshPeerState();
    assertEquals(PeerState.ENABLED, peer.getPeerState());
    assertConnectedPeerStatus(true, ID_ONE);

    // Disconnect peer
    rp.peerDisconnected(ID_ONE);
    assertNumberOfPeers(2);
    try {
      rp.getStatusOfPeer(ID_ONE);
      fail("There are no connected peers, should have thrown an IllegalArgumentException");
    } catch (IllegalArgumentException e) {
    }
  }

  protected void assertConnectedPeerStatus(boolean status, String peerId) throws Exception {
    // we can first check if the value was changed in the store, if it wasn't then fail right away
    if (status != rp.getStatusOfPeerFromBackingStore(peerId)) {
      fail("ConnectedPeerStatus was " + !status + " but expected " + status + " in ZK");
    }
    while (true) {
      if (status == rp.getStatusOfPeer(peerId)) {
        return;
      }
      if (zkTimeoutCount < ZK_MAX_COUNT) {
        LOG.debug("ConnectedPeerStatus was " + !status + " but expected " + status +
          ", sleeping and trying again.");
        Thread.sleep(ZK_SLEEP_INTERVAL);
      } else {
        fail("Timed out waiting for ConnectedPeerStatus to be " + status);
      }
    }
  }

  protected void assertNumberOfPeers(int total) {
    assertEquals(total, rp.getAllPeerConfigs().size());
    assertEquals(total, rp.getAllPeerIds().size());
    assertEquals(total, rp.getAllPeerIds().size());
  }

  /*
   * three replicators: rq1 has 0 queues, rq2 has 1 queue with no logs, rq3 has 5 queues with 1, 2,
   * 3, 4, 5 log files respectively
   */
  protected void populateQueues() throws ReplicationException {
    rqs.addWAL(server1, "trash", "trash");
    rqs.removeQueue(server1, "trash");

    rqs.addWAL(server2, "qId1", "trash");
    rqs.removeWAL(server2, "qId1", "trash");

    for (int i = 1; i < 6; i++) {
      for (int j = 0; j < i; j++) {
        rqs.addWAL(server3, "qId" + i, "filename" + j);
      }
      // Add peers for the corresponding queues so they are not orphans
      rp.registerPeer("qId" + i,
        new ReplicationPeerConfig().setClusterKey("localhost:2818:/bogus" + i));
    }
  }
}
