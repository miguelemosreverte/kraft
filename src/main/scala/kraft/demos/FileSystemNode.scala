package kraft.demos

import kraft.dsl.*
import kraft.dsl.durable.storage.*
import kraft.dsl.durable.runtime.*
import kraft.dsl.durable.runtime.NodeRuntime.*
import kraft.dsl.durable.model.*
import kraft.dsl.durable.cluster.*
import kraft.dsl.durable.cluster.model.*
import kraft.server.HttpServer
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global
import java.io.File
import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.jdk.CollectionConverters.*

/**
 * Filesystem Node - A cluster node that can execute filesystem operations.
 *
 * This demonstrates which node handles a workflow by showing:
 * - The node's hostname
 * - The node's local filesystem
 *
 * Usage:
 *   sbt "runMain kraft.demos.FileSystemNode seed 7800"
 *   sbt "runMain kraft.demos.FileSystemNode join 7800 192.168.0.130:7800"
 */
object FileSystemNode:

  // ============================================================================
  // Request/Response Types
  // ============================================================================

  case class LsRequest(path: String)
  case class LsResponse(
    nodeId: String,
    hostname: String,
    path: String,
    files: List[FileInfo],
    error: Option[String] = None
  )

  case class FileInfo(
    name: String,
    isDirectory: Boolean,
    size: Long
  )

  case class WriteRequest(path: String, content: String)
  case class WriteResponse(
    nodeId: String,
    hostname: String,
    path: String,
    bytesWritten: Long,
    error: Option[String] = None
  )

  case class ReadRequest(path: String)
  case class ReadResponse(
    nodeId: String,
    hostname: String,
    path: String,
    content: String,
    error: Option[String] = None
  )

  case class ExecRequest(command: String)
  case class ExecResponse(
    nodeId: String,
    hostname: String,
    command: String,
    output: String,
    exitCode: Int,
    error: Option[String] = None
  )

  // JSON codecs
  given JsonValueCodec[LsRequest] = JsonCodecMaker.make
  given JsonValueCodec[LsResponse] = JsonCodecMaker.make
  given JsonValueCodec[FileInfo] = JsonCodecMaker.make
  given JsonValueCodec[WriteRequest] = JsonCodecMaker.make
  given JsonValueCodec[WriteResponse] = JsonCodecMaker.make
  given JsonValueCodec[ReadRequest] = JsonCodecMaker.make
  given JsonValueCodec[ReadResponse] = JsonCodecMaker.make
  given JsonValueCodec[ExecRequest] = JsonCodecMaker.make
  given JsonValueCodec[ExecResponse] = JsonCodecMaker.make

  // ============================================================================
  // HTTP Routes for Filesystem Operations
  // ============================================================================

  def filesystemRoutes(nodeId: String): HttpRoutes =
    val hostname = java.net.InetAddress.getLocalHost.getHostName

    HttpRoutes(
      // List directory
      POST("/fs/ls") { req =>
        try
          val request = readFromArray[LsRequest](req.body)
          val dir = new File(request.path)

          if !dir.exists() then
            val response = LsResponse(nodeId, hostname, request.path, Nil, Some("Path does not exist"))
            Ok(writeToArray(response), "application/json")
          else if !dir.isDirectory then
            val response = LsResponse(nodeId, hostname, request.path, Nil, Some("Path is not a directory"))
            Ok(writeToArray(response), "application/json")
          else
            val files = Option(dir.listFiles()).map(_.toList).getOrElse(Nil).map { f =>
              FileInfo(f.getName, f.isDirectory, f.length())
            }
            val response = LsResponse(nodeId, hostname, request.path, files)
            Ok(writeToArray(response), "application/json")
        catch
          case e: Exception =>
            val response = LsResponse(nodeId, hostname, "", Nil, Some(e.getMessage))
            Ok(writeToArray(response), "application/json")
      },

      // Write file
      POST("/fs/write") { req =>
        try
          val request = readFromArray[WriteRequest](req.body)
          val path = Paths.get(request.path)

          // Create parent directories if needed
          Option(path.getParent).foreach(Files.createDirectories(_))

          Files.writeString(path, request.content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
          val response = WriteResponse(nodeId, hostname, request.path, request.content.length)
          Ok(writeToArray(response), "application/json")
        catch
          case e: Exception =>
            val response = WriteResponse(nodeId, hostname, "", 0, Some(e.getMessage))
            Ok(writeToArray(response), "application/json")
      },

      // Read file
      POST("/fs/read") { req =>
        try
          val request = readFromArray[ReadRequest](req.body)
          val content = Files.readString(Paths.get(request.path))
          val response = ReadResponse(nodeId, hostname, request.path, content)
          Ok(writeToArray(response), "application/json")
        catch
          case e: Exception =>
            val response = ReadResponse(nodeId, hostname, "", "", Some(e.getMessage))
            Ok(writeToArray(response), "application/json")
      },

      // Execute command
      POST("/fs/exec") { req =>
        try
          val request = readFromArray[ExecRequest](req.body)
          val process = Runtime.getRuntime.exec(Array("/bin/sh", "-c", request.command))
          val output = scala.io.Source.fromInputStream(process.getInputStream).mkString
          val exitCode = process.waitFor()
          val response = ExecResponse(nodeId, hostname, request.command, output, exitCode)
          Ok(writeToArray(response), "application/json")
        catch
          case e: Exception =>
            val response = ExecResponse(nodeId, hostname, "", "", -1, Some(e.getMessage))
            Ok(writeToArray(response), "application/json")
      },

      // Info endpoint
      GET("/fs/info") { _ =>
        val info = s"""{"nodeId":"$nodeId","hostname":"$hostname","cwd":"${System.getProperty("user.dir")}","home":"${System.getProperty("user.home")}"}"""
        Ok(info, "application/json")
      }
    )

  // ============================================================================
  // Main
  // ============================================================================

  def main(args: Array[String]): Unit =
    if args.length < 2 then
      println("Usage:")
      println("  FileSystemNode seed <port>           - Start seed node")
      println("  FileSystemNode join <port> <seed>    - Join existing cluster")
      println()
      println("Example:")
      println("  sbt \"runMain kraft.demos.FileSystemNode seed 7800\"")
      println("  sbt \"runMain kraft.demos.FileSystemNode join 7800 192.168.0.48:7800\"")
      sys.exit(1)

    val mode = args(0)
    val port = args(1).toInt

    mode match
      case "seed" => runNode(port, None)
      case "join" =>
        if args.length < 3 then
          println("Error: join mode requires seed address (host:port)")
          sys.exit(1)
        val seedAddr = args(2)
        val parts = seedAddr.split(":")
        runNode(port, Some(NodeAddress(parts(0), parts(1).toInt)))
      case _ =>
        println(s"Unknown mode: $mode (use 'seed' or 'join')")
        sys.exit(1)

  def runNode(port: Int, seed: Option[NodeAddress]): Unit =
    val nodeId = s"fs-node-$port"
    val hostname = java.net.InetAddress.getLocalHost.getHostName

    println("=" * 60)
    println(s"Kraft Filesystem Node")
    println("=" * 60)
    println()
    println(s"  Node ID:  $nodeId")
    println(s"  Hostname: $hostname")
    println(s"  Port:     $port")
    println(s"  Mode:     ${if seed.isEmpty then "seed" else s"join -> ${seed.get}"}")
    println()

    // Create combined routes: filesystem + health
    val healthRoutes = HttpRoutes(
      GET("/health") { _ =>
        val json = s"""{"status":"healthy","nodeId":"$nodeId","hostname":"$hostname","nodes":1,"activeWorkflows":0}"""
        Ok(json, "application/json")
      }
    )

    val allRoutes = filesystemRoutes(nodeId) <+> healthRoutes

    // Start HTTP server
    val server = HttpServer(allRoutes)
    val handle = server.start(port)

    println(s"  Server listening on port $port")
    println()
    println("Endpoints:")
    println(s"  GET  http://localhost:$port/health    - Node health")
    println(s"  GET  http://localhost:$port/fs/info   - Node info")
    println(s"  POST http://localhost:$port/fs/ls     - List directory")
    println(s"  POST http://localhost:$port/fs/read   - Read file")
    println(s"  POST http://localhost:$port/fs/write  - Write file")
    println(s"  POST http://localhost:$port/fs/exec   - Execute command")
    println()
    println("Press Ctrl+C to stop")

    // Shutdown hook
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      println("\nShutting down...")
      handle.close()
    }))

    // Keep running
    handle.awaitTermination()
