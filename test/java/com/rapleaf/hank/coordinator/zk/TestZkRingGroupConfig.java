package com.rapleaf.hank.coordinator.zk;

import java.util.Collections;

import com.rapleaf.hank.ZkTestCase;
import com.rapleaf.hank.coordinator.DomainGroupConfigVersion;
import com.rapleaf.hank.coordinator.MockDomainGroupConfig;
import com.rapleaf.hank.coordinator.PartDaemonAddress;
import com.rapleaf.hank.coordinator.RingConfig;
import com.rapleaf.hank.coordinator.RingGroupConfig;

public class TestZkRingGroupConfig extends ZkTestCase {

  public TestZkRingGroupConfig() throws Exception {
    super();
  }

  private final String ring_groups = getRoot() + "/ring_groups";
  private final String ring_group = ring_groups + "/myRingGroup";
  private final String dg_root = getRoot() + "/domain_groups";

  public void testLoad() throws Exception {
    create(ring_group, dg_root + "/myDomainGroup");
    createRing(1);
    createRing(2);
    createRing(3);

    MockDomainGroupConfig dgc = new MockDomainGroupConfig("myDomainGroup");
    ZkRingGroupConfig ringGroupConf = new ZkRingGroupConfig(getZk(), ring_group, dgc);

    assertEquals("ring group name", "myRingGroup", ringGroupConf.getName());
    assertEquals("num rings", 3, ringGroupConf.getRingConfigs().size());
    assertEquals("domain group config", dgc, ringGroupConf.getDomainGroupConfig());

    assertEquals("ring group for localhost:2", 2, ringGroupConf.getRingConfigForHost(new PartDaemonAddress("localhost", 2)).getRingNumber());
    assertEquals("ring group by number", 3, ringGroupConf.getRingConfig(3).getRingNumber());
  }

  public void testVersionStuff() throws Exception {
    ZkDomainGroupConfig dgc = (ZkDomainGroupConfig) ZkDomainGroupConfig.create(getZk(), getRoot() + "/domain_groups", "blah");
    DomainGroupConfigVersion version = dgc.createNewVersion(Collections.EMPTY_MAP);
    RingGroupConfig rgc = ZkRingGroupConfig.create(getZk(), getRoot() + "/my_ring_group", dgc);
    assertNull(rgc.getCurrentVersion());
    assertEquals(Integer.valueOf(version.getVersionNumber()), rgc.getUpdatingToVersion());
    rgc.updateComplete();
    assertEquals(Integer.valueOf(version.getVersionNumber()), rgc.getCurrentVersion());
    assertNull(rgc.getUpdatingToVersion());
  }

  public void testListener() throws Exception {
    fail();
  }

  public void testClaimDataDeployer() throws Exception {
    fail();
  }

  private void createRing(int ringNum) throws Exception {
    RingConfig rc = ZkRingConfig.create(getZk(), ring_group, ringNum, null, 1);
    rc.addHost(new PartDaemonAddress("localhost", ringNum));
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    create(dg_root);
    create(ring_groups);
  }
}
