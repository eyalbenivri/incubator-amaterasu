/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.amaterasu.leader.yarn

import java.io.{File, FileInputStream, InputStream}
import java.net.{InetAddress, ServerSocket}
import java.nio.ByteBuffer
import java.util
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, LinkedBlockingQueue}
import javax.jms.Session

import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.broker.BrokerService
import org.apache.amaterasu.common.configuration.ClusterConfig
import org.apache.amaterasu.common.dataobjects.ActionData
import org.apache.amaterasu.common.logging.Logging
import org.apache.amaterasu.leader.execution.frameworks.FrameworkProvidersFactory
import org.apache.amaterasu.leader.execution.{JobLoader, JobManager}
import org.apache.amaterasu.leader.utilities.{ActiveReportListener, Args}
import org.apache.curator.framework.recipes.barriers.DistributedBarrier
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.io.DataOutputBuffer
import org.apache.hadoop.security.UserGroupInformation
import org.apache.hadoop.yarn.api.records._
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest
import org.apache.hadoop.yarn.client.api.async.impl.NMClientAsyncImpl
import org.apache.hadoop.yarn.client.api.async.{AMRMClientAsync, NMClientAsync}
import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.apache.hadoop.yarn.security.AMRMTokenIdentifier
import org.apache.hadoop.yarn.util.{ConverterUtils, Records}
import org.apache.zookeeper.CreateMode

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.{concurrent, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

class ApplicationMaster extends AMRMClientAsync.CallbackHandler with Logging {



  log.info("ApplicationMaster start")

  private var jobManager: JobManager = _
  private var client: CuratorFramework = _
  private var config: ClusterConfig = _
  private var env: String = _
  private var branch: String = _
  private var fs: FileSystem = _
  private var conf: YarnConfiguration = _
  private var propPath: String = ""
  private var props: InputStream = _
  private var jarPath: Path = _
  private var executorPath: Path = _
  private var executorJar: LocalResource = _
  private var propFile: LocalResource = _
  private var log4jPropFile: LocalResource = _
  private var nmClient: NMClientAsync = _
  private var allocListener: YarnRMCallbackHandler = _
  private var rmClient: AMRMClientAsync[ContainerRequest] = _
  private var address: String = _
  private var frameworkFactory: FrameworkProvidersFactory = _
  private var maxMem:Int  = _
  private var maxVCores:Int  = _

  private val containerResourcesToTasks = new ConcurrentHashMap[Resource, ConcurrentLinkedQueue[ActionData]].asScala
  private val containersIdsToTask: concurrent.Map[Long, ActionData] = new ConcurrentHashMap[Long, ActionData].asScala
  private val completedContainersAndTaskIds: concurrent.Map[Long, String] = new ConcurrentHashMap[Long, String].asScala
  private val host: String = InetAddress.getLocalHost.getHostName
  private val broker: BrokerService = new BrokerService()

  def setLocalResourceFromPath(path: Path): LocalResource = {

    val stat = fs.getFileStatus(path)
    val fileResource = Records.newRecord(classOf[LocalResource])

    fileResource.setResource(ConverterUtils.getYarnUrlFromPath(path))
    fileResource.setSize(stat.getLen)
    fileResource.setTimestamp(stat.getModificationTime)
    fileResource.setType(LocalResourceType.FILE)
    fileResource.setVisibility(LocalResourceVisibility.PUBLIC)
    fileResource

  }

  def execute(arguments: Args): Unit = {

    log.info(s"started AM with args $arguments")

    propPath = System.getenv("PWD") + "/amaterasu.properties"
    props = new FileInputStream(new File(propPath))

    // no need for hdfs double check (nod to Aaron Rodgers)
    // jars on HDFS should have been verified by the YARN client
    conf = new YarnConfiguration()
    fs = FileSystem.get(conf)

    config = ClusterConfig(props)

    try {
      initJob(arguments)
    } catch {
      case e: Exception => log.error("error initializing ", e.getMessage)
    }

    // now that the job was initiated, the curator client is started and we can
    // register the broker's address
    client.create().withMode(CreateMode.PERSISTENT).forPath(s"/${jobManager.jobId}/broker")
    client.setData().forPath(s"/${jobManager.jobId}/broker", address.getBytes)

    // once the broker is registered, we can remove the barrier so clients can connect
    log.info(s"/${jobManager.jobId}-report-barrier")
    val barrier = new DistributedBarrier(client, s"/${jobManager.jobId}-report-barrier")
    barrier.removeBarrier()

    setupMessaging(jobManager.jobId)

    log.info(s"Job ${jobManager.jobId} initiated with ${jobManager.registeredActions.size} actions")

    jarPath = new Path(config.YARN.hdfsJarsPath)

    // TODO: change this to read all dist folder and add to exec path
    executorPath = Path.mergePaths(jarPath, new Path(s"/dist/executor-${config.version}-all.jar"))
    log.info("Executor jar path is {}", executorPath)
    executorJar = setLocalResourceFromPath(executorPath)
    propFile = setLocalResourceFromPath(Path.mergePaths(jarPath, new Path("/amaterasu.properties")))
    log4jPropFile = setLocalResourceFromPath(Path.mergePaths(jarPath, new Path("/log4j.properties")))

    log.info("Started execute")

    nmClient = new NMClientAsyncImpl(new YarnNMCallbackHandler())

    // Initialize clients to ResourceManager and NodeManagers
    nmClient.init(conf)
    nmClient.start()

    // TODO: awsEnv currently set to empty string. should be changed to read values from (where?).
    allocListener = new YarnRMCallbackHandler(nmClient, jobManager, env, awsEnv = "", config, executorJar)

    rmClient = startRMClient()
    val registrationResponse = registerAppMaster("", 0, "")
    maxMem = registrationResponse.getMaximumResourceCapability.getMemory
    log.info("Max mem capability of resources in this cluster " + maxMem)
    maxVCores = registrationResponse.getMaximumResourceCapability.getVirtualCores
    log.info("Max vcores capability of resources in this cluster " + maxVCores)
    log.info(s"Created jobManager. jobManager.registeredActions.size: ${jobManager.registeredActions.size}")

    // Resource requirements for worker containers
    frameworkFactory = FrameworkProvidersFactory.apply(env, config)

    while (!jobManager.outOfActions) {
      val actionData = jobManager.getNextActionData
      if (actionData != null) {

        val capability: Resource = getCapabilityFromAction(actionData)

        askContainer(actionData, capability)
      }
    }

    log.info("Finished asking for containers")
  }

  private def getCapabilityFromAction(actionData: ActionData) = {
    val frameworkProvider = frameworkFactory.providers(actionData.groupId)
    val driverConfiguration = frameworkProvider.getDriverConfiguration

    val capability = Records.newRecord(classOf[Resource])
    var mem: Int = driverConfiguration.getMemory
    mem = Math.min(mem, maxMem)
    capability.setMemory(mem)

    var cpu = driverConfiguration.getCPUs
    cpu = Math.min(cpu, maxVCores)
    capability.setVirtualCores(cpu)
    capability
  }

  private def startRMClient(): AMRMClientAsync[ContainerRequest] = {
    val client = AMRMClientAsync.createAMRMClientAsync[ContainerRequest](1000, this)
    client.init(conf)
    client.start()
    client
  }

  private def registerAppMaster(host: String, port: Int, url: String) = {
    // Register with ResourceManager
    log.info("Registering application")
    val registrationResponse = rmClient.registerApplicationMaster(host, port, url)
    log.info("Registered application")
    registrationResponse
  }

  private def setupMessaging(jobId: String): Unit = {

    val cf = new ActiveMQConnectionFactory(address)
    val conn = cf.createConnection()
    conn.start()

    val session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)
    //TODO: move to a const in common
    val destination = session.createTopic("JOB.REPORT")

    val consumer = session.createConsumer(destination)
    consumer.setMessageListener(new ActiveReportListener)

  }

  private def askContainer(actionData: ActionData, capability: Resource): Unit = {

    containerResourcesToTasks.getOrElseUpdate(capability, new ConcurrentLinkedQueue[ActionData]()).add(actionData)
    log.info(s"About to ask container for action ${actionData.id}. Action buffer size is: ${containerResourcesToTasks.values.map(q => q.size()).sum}")

    // we have an action to schedule, let's request a container
    val priority: Priority = Records.newRecord(classOf[Priority])
    priority.setPriority(1)
    val containerReq = new ContainerRequest(capability, null, null, priority)
    rmClient.addContainerRequest(containerReq)
    log.info(s"Asked container for action ${actionData.id}")

  }

  def resourceToString(resource: Resource): String = {
    s"Resource<${resource.getVirtualCores} vcores, ${resource.getMemory} mem>"
  }

  override def onContainersAllocated(containers: util.List[Container]): Unit = {

    log.info(s"${containers.size()} Containers allocated")
    if (containerResourcesToTasks.values.forall(_.isEmpty)) {
      log.warn(s"Why actionBuffer empty and i was called?. Container ids: ${containers.map(c => c.getId.getContainerId)}")
      return
    }
    for (container <- containers.asScala) { // Launch container by create ContainerLaunchContext
      val actionDataQueue: Option[ConcurrentLinkedQueue[ActionData]] = containerResourcesToTasks.get(container.getResource)
      if(actionDataQueue.isEmpty) {
        log.warn(s"Found new container with id ${container.getId}, and capability ${resourceToString(container.getResource)}, " +
          s"but no tasks were queued for that resource. " +
          s"Requested resources: ${containerResourcesToTasks.keys.map(resourceToString(_))}.")
      } else {
        val actionData = actionDataQueue.get.poll()
        if (actionData == null) {
          log.warn(s"Found new container with id ${container.getId}, and capability ${resourceToString(container.getResource)}, " +
            s"but no tasks were queued for that resource, although exists on map. " +
            s"Requested resources: ${containerResourcesToTasks.keys.map(resourceToString(_))}.")
        } else {
          val containerTask = Future[ActionData] {

        val frameworkFactory = FrameworkProvidersFactory(env, config)
        val framework = frameworkFactory.getFramework(actionData.groupId)
        val runnerProvider = framework.getRunnerProvider(actionData.typeId)
        val ctx = Records.newRecord(classOf[ContainerLaunchContext])
        val commands: List[String] = List(runnerProvider.getCommand(jobManager.jobId, actionData, env, s"${actionData.id}-${container.getId.getContainerId}", address))

            log.info("Running container id {}.", container.getId.getContainerId)
            log.info("Running container id {} with command '{}'", container.getId.getContainerId, commands.last)

            ctx.setCommands(commands)
            ctx.setTokens(allTokens)

        val yarnJarPath = new Path(config.YARN.hdfsJarsPath)

        //TODO Arun - Remove the hardcoding of the dist path
        /*  val resources = mutable.Map[String, LocalResource]()
          val binaryFileIter = fs.listFiles(new Path(s"${config.YARN.hdfsJarsPath}/dist"), false)
          while (binaryFileIter.hasNext) {
            val eachFile = binaryFileIter.next().getPath
            resources (eachFile.getName) = setLocalResourceFromPath(fs.makeQualified(eachFile))
          }
          resources("log4j.properties") = setLocalResourceFromPath(fs.makeQualified(new Path(s"${config.YARN.hdfsJarsPath}/log4j.properties")))
          resources ("amaterasu.properties") = setLocalResourceFromPath(fs.makeQualified(new Path(s"${config.YARN.hdfsJarsPath}/amaterasu.properties")))*/

        val resources = mutable.Map[String, LocalResource](
          "executor.jar" -> setLocalResourceFromPath(Path.mergePaths(yarnJarPath, new Path(s"/dist/executor-${config.version}-all.jar"))),
          "spark-runner.jar" -> setLocalResourceFromPath(Path.mergePaths(yarnJarPath, new Path(s"/dist/spark-runner-${config.version}-all.jar"))),
          "spark-runtime.jar" -> setLocalResourceFromPath(Path.mergePaths(yarnJarPath, new Path(s"/dist/spark-runtime-${config.version}.jar"))),
          "amaterasu.properties" -> setLocalResourceFromPath(Path.mergePaths(yarnJarPath, new Path("/amaterasu.properties"))),
          "log4j.properties" -> setLocalResourceFromPath(Path.mergePaths(yarnJarPath, new Path("/log4j.properties"))),
          // TODO: Nadav/Eyal all of these should move to the executor resource setup
          "miniconda.sh" -> setLocalResourceFromPath(Path.mergePaths(yarnJarPath, new Path("/dist/miniconda.sh"))),
          "codegen.py" -> setLocalResourceFromPath(Path.mergePaths(yarnJarPath, new Path("/dist/codegen.py"))),
          "runtime.py" -> setLocalResourceFromPath(Path.mergePaths(yarnJarPath, new Path("/dist/runtime.py"))),
          "spark-version-info.properties" -> setLocalResourceFromPath(Path.mergePaths(yarnJarPath, new Path("/dist/spark-version-info.properties"))),
          "spark_intp.py" -> setLocalResourceFromPath(Path.mergePaths(yarnJarPath, new Path("/dist/spark_intp.py"))))

            //adding the framework and executor resources
            setupResources(yarnJarPath, framework.getGroupIdentifier, resources, framework.getGroupIdentifier)
            setupResources(yarnJarPath, s"${framework.getGroupIdentifier}/${actionData.typeId}", resources, s"${framework.getGroupIdentifier}-${actionData.typeId}")

            ctx.setLocalResources(resources)

            ctx.setEnvironment(Map[String, String](
              "HADOOP_CONF_DIR" -> s"${config.YARN.hadoopHomeDir}/conf/",
              "YARN_CONF_DIR" -> s"${config.YARN.hadoopHomeDir}/conf/",
              "AMA_NODE" -> sys.env("AMA_NODE"),
              "HADOOP_USER_NAME" -> UserGroupInformation.getCurrentUser.getUserName
            ))

            log.info(s"hadoop conf dir is ${config.YARN.hadoopHomeDir}/conf/")
            nmClient.startContainerAsync(container, ctx)
            actionData
          }

          containerTask onComplete {
            case Failure(t) =>
              log.error(s"launching container failed", t)
              val capability: Resource = getCapabilityFromAction(actionData)
              askContainer(actionData, capability)

            case Success(requestedActionData) =>
              jobManager.actionStarted(requestedActionData.id)
              containersIdsToTask.put(container.getId.getContainerId, requestedActionData)
              log.info(s"launching container succeeded: ${container.getId.getContainerId}; task: ${requestedActionData.id}")

          }
        }
      }
    }
  }

  private def allTokens: ByteBuffer = {
    // creating the credentials for container execution
    val credentials = UserGroupInformation.getCurrentUser.getCredentials
    val dob = new DataOutputBuffer
    credentials.writeTokenStorageToStream(dob)

    // removing the AM->RM token so that containers cannot access it.
    val iter = credentials.getAllTokens.iterator
    log.info("Executing with tokens:")
    for (token <- iter) {
      log.info(token.toString)
      if (token.getKind == AMRMTokenIdentifier.KIND_NAME) iter.remove()
    }
    ByteBuffer.wrap(dob.getData, 0, dob.getLength)
  }

  private def setupResources(yarnJarPath: Path, frameworkPath: String, countainerResources: mutable.Map[String, LocalResource], resourcesPath: String): Unit = {

    val sourcePath = Path.mergePaths(yarnJarPath, new Path(s"/$resourcesPath"))

    if (fs.exists(sourcePath)) {

      val files = fs.listFiles(sourcePath, true)

      while (files.hasNext) {
        val res = files.next()
        val containerPath = res.getPath.toUri.getPath.replace("/apps/amaterasu/", "")
        countainerResources.put(containerPath, setLocalResourceFromPath(res.getPath))
      }
    }
  }

  def stopApplication(finalApplicationStatus: FinalApplicationStatus, appMessage: String): Unit = {
    import java.io.IOException

    import org.apache.hadoop.yarn.exceptions.YarnException
    try
      rmClient.unregisterApplicationMaster(finalApplicationStatus, appMessage, null)
    catch {
      case ex: YarnException =>
        log.error("Failed to unregister application", ex)
      case e: IOException =>
        log.error("Failed to unregister application", e)
    }
    rmClient.stop()
    nmClient.stop()
  }

  override def onContainersCompleted(statuses: util.List[ContainerStatus]): Unit = {

    for (status <- statuses.asScala) {

      if (status.getState == ContainerState.COMPLETE) {

        val containerId = status.getContainerId.getContainerId
        val task = containersIdsToTask(containerId)
        rmClient.releaseAssignedContainer(status.getContainerId)

        if (status.getExitStatus == 0) {

          //completedContainersAndTaskIds.put(containerId, task.id)
          jobManager.actionComplete(task.id)
          log.info(s"Container $containerId completed with task ${task.id} with success.")
        } else {
          // TODO: Check the getDiagnostics value and see if appropriate
          jobManager.actionFailed(task.id, status.getDiagnostics)
          log.warn(s"Container $containerId completed with task ${task.id} with failed status code (${status.getExitStatus})")
        }
      }
    }

    if (jobManager.outOfActions) {
      log.info("Finished all tasks successfully! Wow!")
      jobManager.actionsCount()
      stopApplication(FinalApplicationStatus.SUCCEEDED, "SUCCESS")
    } else {
      log.info(s"jobManager.registeredActions.size: ${jobManager.registeredActions.size}; completedContainersAndTaskIds.size: ${completedContainersAndTaskIds.size}")
    }
  }

  override def getProgress: Float = {
    jobManager.registeredActions.size.toFloat / completedContainersAndTaskIds.size
  }

  override def onNodesUpdated(updatedNodes: util.List[NodeReport]): Unit = {
    log.info("Nodes change. Nothing to report.")
  }

  override def onShutdownRequest(): Unit = {
    log.error("Shutdown requested.")
    stopApplication(FinalApplicationStatus.KILLED, "Shutdown requested")
  }

  override def onError(e: Throwable): Unit = {
    log.error("Error on AM", e)
    stopApplication(FinalApplicationStatus.FAILED, "Error on AM")
  }

  def initJob(args: Args): Unit = {

    this.env = args.env
    this.branch = args.branch
    try {
      val retryPolicy = new ExponentialBackoffRetry(1000, 3)
      client = CuratorFrameworkFactory.newClient(config.zk, retryPolicy)
      client.start()
    } catch {
      case e: Exception =>
        log.error("Error connecting to zookeeper", e)
        throw e
    }
    if (args.jobId != null && !args.jobId.isEmpty) {
      log.info("resuming job" + args.jobId)
      jobManager = JobLoader.reloadJob(
        args.jobId,
        client,
        config.Jobs.Tasks.attempts,
        new LinkedBlockingQueue[ActionData])

    } else {
      log.info("new job is being created")
      try {

        jobManager = JobLoader.loadJob(
          args.repo,
          args.branch,
          args.newJobId,
          client,
          config.Jobs.Tasks.attempts,
          new LinkedBlockingQueue[ActionData])
      } catch {
        case e: Exception =>
          log.error("Error creating JobManager.", e)
          throw e
      }

    }

    jobManager.start()
    log.info("started jobManager")
  }
}

object ApplicationMaster extends App with Logging {


  val parser = Args.getParser
  parser.parse(args, Args()) match {

    case Some(arguments: Args) =>
      val appMaster = new ApplicationMaster()

      appMaster.address = s"tcp://${appMaster.host}:$generatePort"
      appMaster.broker.addConnector(appMaster.address)
      appMaster.broker.start()

      log.info(s"broker started with address ${appMaster.address}")
      appMaster.execute(arguments)

    case None =>
  }

  private def generatePort: Int = {
    val socket = new ServerSocket(0)
    val port = socket.getLocalPort
    socket.close()
    port
  }
}
