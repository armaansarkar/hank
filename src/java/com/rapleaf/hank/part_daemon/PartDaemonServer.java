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
package com.rapleaf.hank.part_daemon;

import java.io.IOException;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.server.THsHaServer;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.THsHaServer.Args;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TTransportException;

import com.rapleaf.hank.config.PartservConfigurator;
import com.rapleaf.hank.config.yaml.YamlPartservConfigurator;
import com.rapleaf.hank.coordinator.Coordinator;
import com.rapleaf.hank.coordinator.Host;
import com.rapleaf.hank.coordinator.HostCommand;
import com.rapleaf.hank.coordinator.HostCommandQueueChangeListener;
import com.rapleaf.hank.coordinator.HostState;
import com.rapleaf.hank.coordinator.PartDaemonAddress;
import com.rapleaf.hank.coordinator.Ring;
import com.rapleaf.hank.coordinator.RingGroup;
import com.rapleaf.hank.generated.PartDaemon;
import com.rapleaf.hank.util.CommandLineChecker;
import com.rapleaf.hank.util.HostUtils;

/**
 * The main class of the Part Daemon.
 */
public class PartDaemonServer implements HostCommandQueueChangeListener {
  private static final Logger LOG = Logger.getLogger(PartDaemonServer.class);

  private final PartservConfigurator configurator;
  private final Coordinator coord;
  private Thread serverThread;
  private TServer server;
  private boolean goingDown = false;
  private final PartDaemonAddress hostAddress;
  private final Host hostConfig;

  private Thread updateThread;

  private final RingGroup ringGroupConfig;

  private final Ring ringConfig;

  public PartDaemonServer(PartservConfigurator configurator, String hostName) throws IOException {
    this.configurator = configurator;
    this.coord = configurator.getCoordinator();
    hostAddress = new PartDaemonAddress(hostName, configurator.getServicePort());
    ringGroupConfig = coord.getRingGroupConfig(configurator.getRingGroupName());
    ringConfig = ringGroupConfig.getRingForHost(hostAddress);
    if (ringConfig == null) {
      throw new RuntimeException("Could not get ring configuration for host: " + hostAddress);
    }
    hostConfig = ringConfig.getHostByAddress(hostAddress);
    if (hostConfig == null) {
      throw new RuntimeException("Could not get host configuration for host: " + hostAddress);
    }
    hostConfig.setCommandQueueChangeListener(this);
  }

  public void run() throws IOException {
    setState(HostState.IDLE);

    processCommands();
    while (!goingDown) {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        LOG.debug("Interrupted in run loop. Exiting.");
        break;
      }
    }
    setState(HostState.OFFLINE);
  }

  public void stop() throws IOException {
    // don't wait to be started again.
    goingDown = true;
    stopServingData();
    setState(HostState.IDLE);
  }

  @Override
  public void onCommandQueueChange(Host hostConfig) {
    processCommands();
  }

  protected IfaceWithShutdown getHandler() throws IOException {
    return new PartDaemonHandler(hostAddress, configurator);
  }

  protected IUpdateManager getUpdateManager() throws IOException {
    return new UpdateManager(configurator, hostConfig, ringGroupConfig, ringConfig);
  }

  private synchronized void processCommands() {
    try {
      if (hostConfig.getCurrentCommand() != null) {
        processCurrentCommand(hostConfig, hostConfig.getCurrentCommand());
      }
      while (!hostConfig.getCommandQueue().isEmpty()) {
        HostCommand nextCommand = hostConfig.processNextCommand();
        processCurrentCommand(hostConfig, nextCommand);
      }
    } catch (IOException e) {
      // TODO
      LOG.error("Uh oh, failed to process all the commands in the queue, somehow...", e);
    }
  }

  /**
   * Start serving the thrift server. doesn't return.
   * @throws TTransportException
   * @throws IOException
   * @throws InterruptedException 
   */
  private void startThriftServer() throws TTransportException, IOException, InterruptedException {
    // set up the service handler
    IfaceWithShutdown handler = getHandler();

    // launch the thrift server
    TNonblockingServerSocket serverSocket = new TNonblockingServerSocket(configurator.getServicePort());
    Args options = new Args(serverSocket);
    options.processor(new PartDaemon.Processor(handler));
    options.workerThreads(configurator.getNumThreads());
    options.protocolFactory(new TCompactProtocol.Factory());
    server = new THsHaServer(options);
    LOG.debug("Launching Thrift server...");
    server.serve();
    LOG.debug("Thrift server exited.");
    handler.shutDown();
    LOG.debug("Handler shutdown.");
  }

  /**
   * Start serving the data. Returns when the server is up.
   */
  private void serveData() {
    if (server == null) {
      Runnable r = new Runnable(){
        @Override
        public void run() {
          try {
            startThriftServer();
          } catch (Exception e) {
            // TODO deal with exception. server is probably going down unexpectedly
            LOG.fatal("Server thread died with exception!", e);
          }
        }
      };
      serverThread = new Thread(r, "PartDaemon Thrift Server thread");
      LOG.info("Launching server thread...");
      serverThread.start();
      try {
        while (server == null || !server.isServing()) {
          LOG.debug("Server isn't online yet. Waiting...");
          Thread.sleep(1000);
        }
        LOG.info("Thrift server online and serving.");
      } catch (InterruptedException e) {
        throw new RuntimeException("Interrupted waiting for server thread to start", e);
      }
    } else {
      LOG.info("Told to start, but server was already running.");
    }
  }

  /**
   * Block until thrift server is down
   */
  private void stopServingData() {
    if (server == null) {
      return;
    }

    server.stop();
    try {
      serverThread.join();
    } catch (InterruptedException e) {
      LOG.debug("Interrupted while waiting for server thread to stop. Continuing.", e);
    }
    server = null;
    serverThread = null;
  }

  private synchronized void setState(HostState state) throws IOException {
    hostConfig.setState(state);
  }

  private void update() {
    if (updateThread != null) {
      throw new IllegalStateException("update got called again unexpectedly!");
    }
    Runnable updateRunnable = new Runnable() {
      @Override
      public void run() {
        try {
          IUpdateManager updateManager = getUpdateManager();
          updateManager.update();
          LOG.info("Update is complete! recording state changes...");
          setState(HostState.IDLE);
          hostConfig.completeCommand();
          updateThread = null;
        } catch (Throwable e) {
          // TODO: should this take the server down?
          LOG.fatal("updater encountered a fatal error!", e);
        }
      }
    };
    updateThread = new Thread(updateRunnable, "update manager thread");
    updateThread.start();
  }

  private void processCurrentCommand(Host hostConfig, HostCommand nextCommand) throws IOException {
    HostState state = hostConfig.getState();
    switch (nextCommand) {
    case EXECUTE_UPDATE:
      processExecuteUpdate(state);
      break;
    case GO_TO_IDLE:
      processGoToIdle(state);
      break;
    case SERVE_DATA:
      processServeData(state);
      break;
    }
  }

  private void processServeData(HostState state) throws IOException {
    switch (state) {
    case IDLE:
      serveData();
      setState(HostState.SERVING);
      hostConfig.completeCommand();
      break;
    default:
      LOG.debug("received command " + HostCommand.SERVE_DATA
          + " but not compatible with current state " + state
          + ". Ignoring.");
    }
  }

  private void processGoToIdle(HostState state) throws IOException {
    switch (state) {
    case SERVING:
      stopServingData();
      setState(HostState.IDLE);
      hostConfig.completeCommand();
      break;
    case UPDATING:
      LOG.debug("received command " + HostCommand.GO_TO_IDLE
          + " but current state is " + state
          + ", which cannot be stopped. Will wait until completion.");
      break;
    default:
      LOG.debug("received command " + HostCommand.GO_TO_IDLE
          + " but not compatible with current state " + state
          + ". Ignoring.");
    }
  }

  private void processExecuteUpdate(HostState state) throws IOException {
    switch (state) {
    case IDLE:
      setState(HostState.UPDATING);
      update();
      break;
    case SERVING:
      LOG.debug("Going directly from SERVING to UPDATING is not currently supported.");
    default:
      LOG.debug("have command " + HostCommand.EXECUTE_UPDATE
          + " but not compatible with current state " + state
          + ". Ignoring.");
    }
  }

  public static void main(String[] args) throws Throwable {
    try {
      CommandLineChecker.check(args, new String[] {"configuration_file_path", "log4j_properties_file_path"}, PartDaemonServer.class);
      String configPath = args[0];
      String log4jprops = args[1];

      PartservConfigurator configurator = new YamlPartservConfigurator(configPath);
      PropertyConfigurator.configure(log4jprops);

      new PartDaemonServer(configurator, HostUtils.getHostName()).run();
    } catch (Throwable t) {
      System.err.println("usage: bin/part_daemon.sh <path to config.yml> <path to log4j properties>");
      throw t;
    }
  }
}
