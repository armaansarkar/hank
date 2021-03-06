package com.rapleaf.hank.ui.controllers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.rapleaf.hank.coordinator.Coordinator;
import com.rapleaf.hank.coordinator.Domain;
import com.rapleaf.hank.coordinator.Host;
import com.rapleaf.hank.coordinator.HostDomain;
import com.rapleaf.hank.coordinator.PartDaemonAddress;
import com.rapleaf.hank.coordinator.Ring;
import com.rapleaf.hank.coordinator.RingGroup;
import com.rapleaf.hank.ui.URLEnc;

public class RingController extends Controller {

  private final Coordinator coordinator;

  public RingController(String name, Coordinator coordinator) {
    super(name);
    this.coordinator = coordinator;
    actions.put("add_host", new Action() {
      @Override
      protected void action(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        doAddHost(req, resp);
      }
    });

    actions.put("delete_host", new Action() {
      @Override
      protected void action(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        doDeleteHost(req, resp);
      }
    });

    actions.put("assign_all", new Action() {
      @Override
      protected void action(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        doAssignAll(req, resp);
      }
    });
  }

  protected void doAssignAll(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    RingGroup rgc = coordinator.getRingGroupConfig(req.getParameter("g"));
    int ringNum = Integer.parseInt(req.getParameter("n"));
    Ring ringConfig = rgc.getRing(ringNum);

    for (Domain dc : rgc.getDomainGroup().getDomains()) {
      Set<Integer> unassignedParts = ringConfig.getUnassignedPartitions(dc);
      Integer domainId = rgc.getDomainGroup().getDomainId(dc.getName());

      for (Host hc : ringConfig.getHosts()) {
        HostDomain hdc = hc.getDomainById(domainId);
        if (hdc == null) {
          hdc = hc.addDomain(domainId);
        }
      }

      List<Integer> randomizedUnassigned = new ArrayList<Integer>();
      randomizedUnassigned.addAll(unassignedParts);
      Collections.shuffle(randomizedUnassigned);
      List<Host> hosts = new ArrayList<Host>(ringConfig.getHosts());
      for (int i = 0; i < unassignedParts.size(); i++) {
        hosts.get(i % hosts.size()).getDomainById(domainId).addPartition(randomizedUnassigned.get(i), rgc.getDomainGroup().getLatestVersion().getVersionNumber());
      }
    }

    resp.sendRedirect(String.format("/ring.jsp?g=%s&n=%d", req.getParameter("g"), ringNum));
  }

  protected void doDeleteHost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    RingGroup rgc = coordinator.getRingGroupConfig(req.getParameter("g"));
    Ring ringConfig = rgc.getRing(Integer.parseInt(req.getParameter("n")));
    ringConfig.removeHost(PartDaemonAddress.parse(URLEnc.decode(req.getParameter("h"))));

    resp.sendRedirect(String.format("/ring.jsp?g=%s&n=%d", rgc.getName(), ringConfig.getRingNumber()));
  }

  private void doAddHost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String rgName = req.getParameter("rgName");
    int ringNum = Integer.parseInt(req.getParameter("ringNum"));
    String hostname = req.getParameter("hostname");
    int portNum = Integer.parseInt(req.getParameter("port"));
    coordinator.getRingGroupConfig(rgName).getRing(ringNum).addHost(new PartDaemonAddress(hostname, portNum));
    resp.sendRedirect("/ring.jsp?g=" + rgName + "&n=" + ringNum);
  }
}
