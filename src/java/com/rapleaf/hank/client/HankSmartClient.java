/*
 *  Copyright 2011 Rapleaf
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.rapleaf.hank.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.apache.thrift.TException;

import com.rapleaf.hank.coordinator.Coordinator;
import com.rapleaf.hank.coordinator.Domain;
import com.rapleaf.hank.coordinator.DomainGroup;
import com.rapleaf.hank.coordinator.DomainGroupVersionDomainVersion;
import com.rapleaf.hank.coordinator.Host;
import com.rapleaf.hank.coordinator.HostDomain;
import com.rapleaf.hank.coordinator.HostDomainPartition;
import com.rapleaf.hank.coordinator.PartDaemonAddress;
import com.rapleaf.hank.coordinator.Ring;
import com.rapleaf.hank.coordinator.RingGroup;
import com.rapleaf.hank.coordinator.RingGroupChangeListener;
import com.rapleaf.hank.coordinator.RingStateChangeListener;
import com.rapleaf.hank.generated.HankExceptions;
import com.rapleaf.hank.generated.HankResponse;
import com.rapleaf.hank.generated.SmartClient.Iface;
import com.rapleaf.hank.util.Bytes;

/**
 * HankSmartClient implements the logic of determining which PartDaemon to
 * contact to fulfill requests for a given key, as well as managing a connection
 * pool and detecting PartDaemon failures.
 */
public class HankSmartClient implements Iface, RingGroupChangeListener, RingStateChangeListener {
  private static final HankResponse NO_SUCH_DOMAIN = HankResponse.xception(HankExceptions.no_such_domain(true));

  private static final Logger LOG = Logger.getLogger(HankSmartClient.class);

  private final DomainGroup domainGroup;
  private final RingGroup ringGroupConfig;

  private final Map<PartDaemonAddress, List<PartDaemonConnection>> connectionCache = new HashMap<PartDaemonAddress, List<PartDaemonConnection>>();

  private final Map<Integer, Map<Integer, PartDaemonConnectionSet>> domainToPartitionToConnectionSet = new HashMap<Integer, Map<Integer, PartDaemonConnectionSet>>();

  /**
   * Create a new HankSmartClient that uses the supplied coordinator and works
   * with the requested ring group. Note that a given HankSmartClient can only
   * contact one ring group.
   *
   * @param coord
   * @param ringGroupName
   * @param numConnectionsPerHost 
   * @throws IOException
   * @throws TException
   */
  public HankSmartClient(Coordinator coord, String ringGroupName, int numConnectionsPerHost) throws IOException, TException {
    ringGroupConfig = coord.getRingGroupConfig(ringGroupName);
    this.domainGroup = ringGroupConfig.getDomainGroup();

    loadCache(numConnectionsPerHost);
    ringGroupConfig.setListener(this);
    for (Ring ringConfig : ringGroupConfig.getRings()) {
      ringConfig.setStateChangeListener(this);
    }
  }

  private void loadCache(int numConnectionsPerHost) throws IOException, TException {
    // preprocess the config to create skeleton domain -> part -> [hosts] map
    DomainGroup domainGroup = ringGroupConfig.getDomainGroup();

    Map<Integer, Map<Integer, List<PartDaemonAddress>>> domainPartToHostList = new HashMap<Integer, Map<Integer, List<PartDaemonAddress>>>();
    for (DomainGroupVersionDomainVersion domainVersion : domainGroup.getLatestVersion().getDomainVersions()) {
      Domain domain = domainVersion.getDomain();
      HashMap<Integer, List<PartDaemonAddress>> partitionToAddress = new HashMap<Integer, List<PartDaemonAddress>>();
      domainPartToHostList.put(domainGroup.getDomainId(domain.getName()), partitionToAddress);

      for (int i = 0; i < domain.getNumParts(); i++) {
        partitionToAddress.put(i, new ArrayList<PartDaemonAddress>());
      }
    }

    // populate the skeleton, while also establishing connections to online hosts
    for (Ring ringConfig : ringGroupConfig.getRings()) {
      for (Host hostConfig : ringConfig.getHosts()) {
        for (HostDomain hdc : hostConfig.getAssignedDomains()) {
          Map<Integer, List<PartDaemonAddress>> domainMap = domainPartToHostList.get(hdc.getDomainId());
          for (HostDomainPartition hdcp : hdc.getPartitions()) {
            List<PartDaemonAddress> partList = domainMap.get(hdcp.getPartNum());
            partList.add(hostConfig.getAddress());
          }
        }

        // establish connection to hosts
        List<PartDaemonConnection> hostConnections = new ArrayList<PartDaemonConnection>(numConnectionsPerHost);
        for (int i = 0; i < numConnectionsPerHost; i++) {
          hostConnections.add(new PartDaemonConnection(hostConfig));
        }
        connectionCache.put(hostConfig.getAddress(), hostConnections);
      }
    }

    for (Map.Entry<Integer, Map<Integer, List<PartDaemonAddress>>> entry1 : domainPartToHostList.entrySet()) {
      Map<Integer, PartDaemonConnectionSet> domainMap = new HashMap<Integer, PartDaemonConnectionSet>();
      for (Map.Entry<Integer, List<PartDaemonAddress>> entry2 : entry1.getValue().entrySet()) {
        List<PartDaemonConnection> clientBundles = new ArrayList<PartDaemonConnection>();
        for (PartDaemonAddress address : entry2.getValue()) {
          for (PartDaemonConnection conn : connectionCache.get(address)) {
            clientBundles.add(conn);
          }
        }
        domainMap.put(entry2.getKey(), new PartDaemonConnectionSet(clientBundles));
      }
      domainToPartitionToConnectionSet.put(entry1.getKey(), domainMap);
    }
  }

  @Override
  public HankResponse get(String domainName, ByteBuffer key) throws TException {
    int partition = -1;
    Integer domainId = null;
    try {
      domainId = domainGroup.getDomainId(domainName);
    } catch (IOException e1) {
      // TODO: this might be bad
      LOG.error(e1);
    }

    if (domainId != null) {
      Domain domain;
      try {
        domain = domainGroup.getDomain(domainId);
      } catch (IOException e) {
        // TODO: this might be bad.
        LOG.error(e);
        return NO_SUCH_DOMAIN;
      }
      if (domain != null) {
        partition = domain.getPartitioner().partition(key, domain.getNumParts());
      } else {
        return NO_SUCH_DOMAIN;
      }
    } else {
      return NO_SUCH_DOMAIN;
    }

    Map<Integer, PartDaemonConnectionSet> partitionToConnectionSet = domainToPartitionToConnectionSet.get(domainId);
    if (partitionToConnectionSet == null) {
      String errMsg = String.format("Got a null domain->part map for domain %s (%d)!", domainName, domainId);
      LOG.error(errMsg);
      return HankResponse.xception(HankExceptions.internal_error(errMsg));
    }

    PartDaemonConnectionSet connectionSet = partitionToConnectionSet.get(partition);
    if (connectionSet == null) {
      // this is a problem, since the cache must not have been loaded correctly
      String errMsg = String.format("Got a null list of hosts for domain %s (%d) when looking for partition %d", domainName, domainId, partition);
      LOG.error(errMsg);
      return HankResponse.xception(HankExceptions.internal_error(errMsg));
    }
    LOG.trace("Looking in domain " + domainName + ", in partition " + partition + ", for key: " + Bytes.bytesToHexString(key));
    return connectionSet.get(domainId, key);
  }

  @Override
  public void onRingGroupChange(RingGroup newRingGroup) {
    LOG.debug("Smart Client notified of ring group change!");
  }

  @Override
  public void onRingStateChange(Ring ringConfig) {
  }
}
