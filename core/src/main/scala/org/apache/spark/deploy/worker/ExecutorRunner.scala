/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy.worker

import java.io._
import java.nio.charset.StandardCharsets

import scala.collection.JavaConverters._

import com.google.common.io.Files

import org.apache.spark.{SecurityManager, SparkConf}
import org.apache.spark.deploy.{ApplicationDescription, ExecutorState}
import org.apache.spark.deploy.DeployMessages.ExecutorStateChanged
import org.apache.spark.internal.Logging
import org.apache.spark.rpc.RpcEndpointRef
import org.apache.spark.util.{ShutdownHookManager, Utils}
import org.apache.spark.util.logging.FileAppender

/**
 * 管理一个Executor的进程；现在仅用在standalone 模式；
 */
private[deploy] class ExecutorRunner(
                                      val appId: String,
                                      val execId: Int,
                                      val appDesc: ApplicationDescription,
                                      val cores: Int,
                                      val memory: Int,
                                      val worker: RpcEndpointRef,
                                      val workerId: String,
                                      val host: String,
                                      val webUiPort: Int,
                                      val publicAddress: String,
                                      val sparkHome: File,
                                      val executorDir: File,
                                      val workerUrl: String,
                                      conf: SparkConf,
                                      val appLocalDirs: Seq[String],
                                      @volatile var state: ExecutorState.Value)
  extends Logging {

  private val fullId = appId + "/" + execId
  private var workerThread: Thread = null
  private var process: Process = null
  private var stdoutAppender: FileAppender = null
  private var stderrAppender: FileAppender = null
  // 在尝试终止执行Executor的等待超时时间
  private val EXECUTOR_TERMINATE_TIMEOUT_MS = 10 * 1000

  private var shutdownHook: AnyRef = null

  /**
   * leen
   * start()方法
   */
  private[worker] def start() {
    // 创建一个Java的线程体
    workerThread = new Thread("ExecutorRunner for " + fullId) {
      // fetchAndRunExecutor() 创建并运行Executor
      override def run() {
        fetchAndRunExecutor()
      }
    }
    workerThread.start()
    shutdownHook = ShutdownHookManager.addShutdownHook { () =>
      // 在我们调用fetchAndRunExecutor方法之前，我们可能会到达这里，这种情况下，设置state = ExecutorState.FAILED
      if (state == ExecutorState.RUNNING) {
        state = ExecutorState.FAILED
      }
      // Kill executor进程，等待退出，并通知worker更新资源状态
      killProcess(Some("Worker shutting down"))
    }
  }

  /**
   * Kill executor进程，等待退出，并通知worker更新资源状态
   * @param message 引起Executor失败的异常消息
   */
  private def killProcess(message: Option[String]) {
    var exitCode: Option[Int] = None
    if (process != null) {
      logInfo("Killing process!")
      if (stdoutAppender != null) {
        stdoutAppender.stop()
      }
      if (stderrAppender != null) {
        stderrAppender.stop()
      }
      exitCode = Utils.terminateProcess(process, EXECUTOR_TERMINATE_TIMEOUT_MS)
      if (exitCode.isEmpty) {
        logWarning("Failed to terminate process: " + process +
          ". This process will likely be orphaned.")
      }
    }
    try {
      worker.send(ExecutorStateChanged(appId, execId, state, message, exitCode))
    } catch {
      case e: IllegalStateException => logWarning(e.getMessage(), e)
    }
  }

  /** Stop this executor runner, including killing the process it launched */
  private[worker] def kill() {
    if (workerThread != null) {
      // the workerThread will kill the child process when interrupted
      workerThread.interrupt()
      workerThread = null
      state = ExecutorState.KILLED
      try {
        ShutdownHookManager.removeShutdownHook(shutdownHook)
      } catch {
        case e: IllegalStateException => None
      }
    }
  }

  /** Replace variables such as {{EXECUTOR_ID}} and {{CORES}} in a command argument passed to us */
  private[worker] def substituteVariables(argument: String): String = argument match {
    case "{{WORKER_URL}}" => workerUrl
    case "{{EXECUTOR_ID}}" => execId.toString
    case "{{HOSTNAME}}" => host
    case "{{CORES}}" => cores.toString
    case "{{APP_ID}}" => appId
    case other => other
  }

  /**
   * 下载并运行我们ApplicationDescription中描述的Executor
   */
  private def fetchAndRunExecutor() {
    try {
      // 创建ProcessBuilder，启动进程
      val builder = CommandUtils.buildProcessBuilder(appDesc.command, new SecurityManager(conf),
        memory, sparkHome.getAbsolutePath, substituteVariables)
      // 启动命令
      val command = builder.command()
      // 格式化启动命令
      val formattedCommand = command.asScala.mkString("\"", "\" \"", "\"")
      logInfo(s"Launch command: $formattedCommand")

      builder.directory(executorDir)
      builder.environment.put("SPARK_EXECUTOR_DIRS", appLocalDirs.mkString(File.pathSeparator))
      builder.environment.put("SPARK_LAUNCH_WITH_SCALA", "0")

      // 添加webUI日志网址
      val baseUrl =
        if (conf.getBoolean("spark.ui.reverseProxy", false)) {
          s"/proxy/$workerId/logPage/?appId=$appId&executorId=$execId&logType="
        } else {
          s"http://$publicAddress:$webUiPort/logPage/?appId=$appId&executorId=$execId&logType="
        }
      builder.environment.put("SPARK_LOG_URL_STDERR", s"${baseUrl}stderr")
      builder.environment.put("SPARK_LOG_URL_STDOUT", s"${baseUrl}stdout")

      /** 启动builder */
      process = builder.start()
      val header = "Spark Executor Command: %s\n%s\n\n".format(
        formattedCommand, "=" * 40)

      // 将其stdout和stderr重定向到文件
      val stdout = new File(executorDir, "stdout")
      stdoutAppender = FileAppender(process.getInputStream, stdout, conf)

      val stderr = new File(executorDir, "stderr")
      Files.write(header, stderr, StandardCharsets.UTF_8)
      stderrAppender = FileAppender(process.getErrorStream, stderr, conf)

      //等待它退出;执行器可以使用代码0退出（当Driver命令它关闭），或者非0的退出状态值
      val exitCode = process.waitFor()
      state = ExecutorState.EXITED
      val message = "Command exited with code " + exitCode
      // 当Executor的状态值发生变化的时候，发送给Master进行处理  ExecutorStateChanged()
      worker.send(ExecutorStateChanged(appId, execId, state, Some(message), Some(exitCode)))
    } catch {
      case interrupted: InterruptedException =>
        logInfo("Runner thread for executor " + fullId + " interrupted")
        state = ExecutorState.KILLED
        killProcess(None)
      case e: Exception =>
        logError("Error running executor", e)
        state = ExecutorState.FAILED
        killProcess(Some(e.toString))
    }
  }
}
