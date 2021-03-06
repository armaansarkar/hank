/**
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
package com.rapleaf.hank.coordinator.zk;

import org.apache.zookeeper.KeeperException;

import com.rapleaf.hank.coordinator.Domain;
import com.rapleaf.hank.coordinator.DomainGroupVersionDomainVersion;
import com.rapleaf.hank.zookeeper.ZooKeeperPlus;

public class ZkDomainGroupVersionDomainVersion implements DomainGroupVersionDomainVersion {
  private final Domain domain;
  private final int versionNumber;
  public ZkDomainGroupVersionDomainVersion(ZooKeeperPlus zk, String path, Domain domain) throws KeeperException, InterruptedException {
    this.domain = domain;
    versionNumber = zk.getInt(path);
  }

  @Override
  public Domain getDomain() {
    return domain;
  }

  @Override
  public int getVersionNumber() {
    return versionNumber;
  }

  @Override
  public String toString() {
    return "ZkDomainConfigVersion [domain=" + domain
        + ", versionNumber=" + versionNumber + "]";
  }
}
