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
package org.apache.hadoop.hbase.master.replication;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.master.MasterCoprocessorHost;
import org.apache.hadoop.hbase.master.procedure.MasterProcedureEnv;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * The procedure for removing a replication peer.
 */
@InterfaceAudience.Private
public class RemovePeerProcedure extends ModifyPeerProcedure {

  private static final Log LOG = LogFactory.getLog(RemovePeerProcedure.class);

  public RemovePeerProcedure() {
  }

  public RemovePeerProcedure(String peerId) {
    super(peerId);
  }

  @Override
  public PeerOperationType getPeerOperationType() {
    return PeerOperationType.REMOVE;
  }

  @Override
  protected void prePeerModification(MasterProcedureEnv env) throws IOException {
    MasterCoprocessorHost cpHost = env.getMasterCoprocessorHost();
    if (cpHost != null) {
      cpHost.preRemoveReplicationPeer(peerId);
    }
  }

  @Override
  protected void updatePeerStorage(MasterProcedureEnv env) throws Exception {
    env.getReplicationManager().removeReplicationPeer(peerId);
  }

  @Override
  protected void postPeerModification(MasterProcedureEnv env) throws IOException {
    LOG.info("Successfully removed peer " + peerId);
    MasterCoprocessorHost cpHost = env.getMasterCoprocessorHost();
    if (cpHost != null) {
      cpHost.postRemoveReplicationPeer(peerId);
    }
  }
}
