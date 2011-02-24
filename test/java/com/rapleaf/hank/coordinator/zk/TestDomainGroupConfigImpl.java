package com.rapleaf.hank.coordinator.zk;


import java.util.HashMap;

import org.apache.zookeeper.KeeperException;

import com.rapleaf.hank.ZkTestCase;
import com.rapleaf.hank.coordinator.DomainConfig;
import com.rapleaf.hank.coordinator.DomainGroupChangeListener;
import com.rapleaf.hank.coordinator.DomainGroupConfig;
import com.rapleaf.hank.coordinator.DomainGroupConfigVersion;
import com.rapleaf.hank.exception.DataNotFoundException;
import com.rapleaf.hank.partitioner.ConstantPartitioner;
import com.rapleaf.hank.storage.constant.ConstantStorageEngine;

public class TestDomainGroupConfigImpl extends ZkTestCase {
  public class MockDomainGroupChangeListener implements DomainGroupChangeListener {
    public DomainGroupConfig calledWith;

    @Override
    public void onDomainGroupChange(DomainGroupConfig newDomainGroup) {
      this.calledWith = newDomainGroup;
      synchronized (this) {
        notifyAll();
      }
    }
  }

  private final String dg_root = getRoot() + "/myDomainGroup";
  private final String domains_root = getRoot() + "/domains";

  

  public void testLoad() throws Exception {
    create(domains_root + "/domain0");
    create(domains_root + "/domain0/num_parts", "1");
    create(domains_root + "/domain0/version", "1");
    create(domains_root + "/domain0/storage_engine_options", "---");
    create(domains_root + "/domain0/storage_engine_factory_class", ConstantStorageEngine.Factory.class.getName());
    create(domains_root + "/domain0/partitioner_class", ConstantPartitioner.class.getName());
    create(domains_root + "/domain1");
    create(domains_root + "/domain1/num_parts", "1");
    create(domains_root + "/domain1/version", "1");
    create(domains_root + "/domain1/storage_engine_options", "---");
    create(domains_root + "/domain1/storage_engine_factory_class", ConstantStorageEngine.Factory.class.getName());
    create(domains_root + "/domain1/partitioner_class", ConstantPartitioner.class.getName());
    create(dg_root + "/domains");
    create(dg_root + "/domains/0", domains_root + "/domain0");
    create(dg_root + "/domains/1", domains_root + "/domain1");
    create(dg_root + "/versions");
    create(dg_root + "/versions/1");
    create(dg_root + "/versions/1/domain0", "1");
    create(dg_root + "/versions/1/domain1", "1");
    create(dg_root + "/versions/1/.complete", "1");
    create(dg_root + "/versions/2");
    create(dg_root + "/versions/2/domain0", "1");
    create(dg_root + "/versions/2/domain1", "1");

    DomainGroupConfigImpl dgc = new DomainGroupConfigImpl(getZk(), dg_root);

    assertEquals(1, dgc.getVersions().size());
    assertEquals(1, ((DomainGroupConfigVersion)dgc.getVersions().toArray()[0]).getVersionNumber());
    assertEquals(1, dgc.getLatestVersion().getVersionNumber());
    assertEquals(0, dgc.getDomainId("domain0"));
    assertEquals(1, dgc.getDomainId("domain1"));
    assertEquals("domain0", dgc.getDomainConfig(0).getName());
    assertEquals("domain1", dgc.getDomainConfig(1).getName());
  }

  public void testDomainsAndListener() throws Exception {
    DomainGroupConfig dgc = DomainGroupConfigImpl.create(getZk(), dg_root, "myDomainGroup");
    MockDomainGroupChangeListener listener = new MockDomainGroupChangeListener();
    dgc.setListener(listener);
    assertNull(listener.calledWith);

    DomainConfig d0 = createDomain("domain0", 1);
    DomainConfig d1 = createDomain("domain1", 3);

    dgc.addDomain(d0, 0);
    assertEquals(0, dgc.getDomainId("domain0"));
    dgc.addDomain(d1, 1);
    assertEquals(1, dgc.getDomainId("domain1"));

    assertNull(listener.calledWith);

    HashMap<Integer, Integer> versionMap = new HashMap<Integer, Integer>() {{
      put(0, 1);
      put(1, 3);
    }};
    dgc.createNewVersion(versionMap);

    synchronized (listener) {
      listener.wait(1000);
    }
    assertNotNull(listener.calledWith);
    assertEquals(dgc.getName(), listener.calledWith.getName());
  }

  private DomainConfig createDomain(String domainName, int initVersion) throws KeeperException, InterruptedException, DataNotFoundException {
    return DomainConfigImpl.create(getZk(),
        domains_root,
        domainName,
        1,
        ConstantStorageEngine.Factory.class.getName(),
        "---\n",
        ConstantPartitioner.class.getName(),
        initVersion);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    create(domains_root);
    create(dg_root);
  }
}
