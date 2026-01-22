package kraft.demos

import kraft.dsl.*
import kraft.server.HttpServer
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import java.io.File
import java.nio.file.{Files, Paths, StandardOpenOption, FileVisitOption}
import scala.jdk.CollectionConverters.*
import scala.jdk.StreamConverters.*

/**
 * Smart Filesystem Node - Enhanced node for distributed file storage.
 *
 * Features:
 * - Disk space reporting for smart placement
 * - File search across the node's filesystem
 * - File metadata with checksums for replication verification
 *
 * Usage:
 *   sbt "runMain kraft.demos.SmartFSNode seed 7800"
 *   sbt "runMain kraft.demos.SmartFSNode seed 7800 /data"
 */
object SmartFSNode:

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
    path: String,
    isDirectory: Boolean,
    size: Long,
    modified: Long
  )

  case class WriteRequest(path: String, content: String)
  case class WriteBinaryRequest(path: String, base64Content: String)
  case class WriteResponse(
    nodeId: String,
    hostname: String,
    path: String,
    bytesWritten: Long,
    checksum: String,
    error: Option[String] = None
  )

  case class ReadRequest(path: String)
  case class ReadResponse(
    nodeId: String,
    hostname: String,
    path: String,
    content: String,
    size: Long,
    checksum: String,
    error: Option[String] = None
  )

  case class DeleteRequest(path: String)
  case class DeleteResponse(
    nodeId: String,
    hostname: String,
    path: String,
    deleted: Boolean,
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

  case class DiskInfoResponse(
    nodeId: String,
    hostname: String,
    totalSpace: Long,
    freeSpace: Long,
    usableSpace: Long,
    usedSpace: Long,
    usagePercent: Double,
    storagePath: String
  )

  case class SearchRequest(pattern: String, path: String, maxResults: Int = 100)
  case class SearchResponse(
    nodeId: String,
    hostname: String,
    pattern: String,
    results: List[FileInfo],
    totalFound: Int,
    error: Option[String] = None
  )

  case class ExistsRequest(path: String)
  case class ExistsResponse(
    nodeId: String,
    hostname: String,
    path: String,
    exists: Boolean,
    isFile: Boolean,
    size: Long,
    checksum: Option[String]
  )

  // JSON codecs
  given JsonValueCodec[LsRequest] = JsonCodecMaker.make
  given JsonValueCodec[LsResponse] = JsonCodecMaker.make
  given JsonValueCodec[FileInfo] = JsonCodecMaker.make
  given JsonValueCodec[WriteRequest] = JsonCodecMaker.make
  given JsonValueCodec[WriteBinaryRequest] = JsonCodecMaker.make
  given JsonValueCodec[WriteResponse] = JsonCodecMaker.make
  given JsonValueCodec[ReadRequest] = JsonCodecMaker.make
  given JsonValueCodec[ReadResponse] = JsonCodecMaker.make
  given JsonValueCodec[DeleteRequest] = JsonCodecMaker.make
  given JsonValueCodec[DeleteResponse] = JsonCodecMaker.make
  given JsonValueCodec[ExecRequest] = JsonCodecMaker.make
  given JsonValueCodec[ExecResponse] = JsonCodecMaker.make
  given JsonValueCodec[DiskInfoResponse] = JsonCodecMaker.make
  given JsonValueCodec[SearchRequest] = JsonCodecMaker.make
  given JsonValueCodec[SearchResponse] = JsonCodecMaker.make
  given JsonValueCodec[ExistsRequest] = JsonCodecMaker.make
  given JsonValueCodec[ExistsResponse] = JsonCodecMaker.make

  // ============================================================================
  // Utilities
  // ============================================================================

  def computeChecksum(content: Array[Byte]): String =
    val md = java.security.MessageDigest.getInstance("MD5")
    val digest = md.digest(content)
    digest.map("%02x".format(_)).mkString

  def computeFileChecksum(path: java.nio.file.Path): String =
    if Files.exists(path) && Files.isRegularFile(path) then
      computeChecksum(Files.readAllBytes(path))
    else
      ""

  // ============================================================================
  // HTTP Routes for Smart Filesystem Operations
  // ============================================================================

  def filesystemRoutes(nodeId: String, storagePath: String): HttpRoutes =
    val hostname = java.net.InetAddress.getLocalHost.getHostName
    val storageDir = new File(storagePath)
    if !storageDir.exists() then storageDir.mkdirs()

    HttpRoutes(
      // Disk info - for smart placement decisions
      GET("/fs/disk-info") { _ =>
        val total = storageDir.getTotalSpace
        val free = storageDir.getFreeSpace
        val usable = storageDir.getUsableSpace
        val used = total - free
        val usagePercent = if total > 0 then (used.toDouble / total) * 100 else 0.0

        val response = DiskInfoResponse(
          nodeId, hostname, total, free, usable, used,
          Math.round(usagePercent * 100) / 100.0, storagePath
        )
        Ok(writeToArray(response), "application/json")
      },

      // Search for files
      POST("/fs/search") { req =>
        try
          val request = readFromArray[SearchRequest](req.body)
          val searchPath = if request.path.isEmpty then storagePath else request.path
          val searchDir = Paths.get(searchPath)

          if !Files.exists(searchDir) then
            val response = SearchResponse(nodeId, hostname, request.pattern, Nil, 0,
              Some(s"Path does not exist: $searchPath"))
            Ok(writeToArray(response), "application/json")
          else
            val pattern = request.pattern.toLowerCase
            val results = Files.walk(searchDir, 10, FileVisitOption.FOLLOW_LINKS)
              .toScala(LazyList)
              .filter(p => Files.isRegularFile(p) && p.getFileName.toString.toLowerCase.contains(pattern))
              .take(request.maxResults)
              .map { p =>
                val f = p.toFile
                FileInfo(f.getName, p.toString, f.isDirectory, f.length(), f.lastModified())
              }
              .toList

            val response = SearchResponse(nodeId, hostname, request.pattern, results, results.length)
            Ok(writeToArray(response), "application/json")
        catch
          case e: Exception =>
            val response = SearchResponse(nodeId, hostname, "", Nil, 0, Some(e.getMessage))
            Ok(writeToArray(response), "application/json")
      },

      // Check if file exists (with checksum for replication verification)
      POST("/fs/exists") { req =>
        try
          val request = readFromArray[ExistsRequest](req.body)
          val path = Paths.get(request.path)
          val exists = Files.exists(path)
          val isFile = exists && Files.isRegularFile(path)
          val size = if isFile then Files.size(path) else 0L
          val checksum = if isFile then Some(computeFileChecksum(path)) else None

          val response = ExistsResponse(nodeId, hostname, request.path, exists, isFile, size, checksum)
          Ok(writeToArray(response), "application/json")
        catch
          case e: Exception =>
            val response = ExistsResponse(nodeId, hostname, "", false, false, 0, None)
            Ok(writeToArray(response), "application/json")
      },

      // List directory
      POST("/fs/ls") { req =>
        try
          val request = readFromArray[LsRequest](req.body)
          val dirPath = if request.path == "." || request.path.isEmpty then storagePath else request.path
          val dir = new File(dirPath)

          if !dir.exists() then
            val response = LsResponse(nodeId, hostname, dirPath, Nil, Some("Path does not exist"))
            Ok(writeToArray(response), "application/json")
          else if !dir.isDirectory then
            val response = LsResponse(nodeId, hostname, dirPath, Nil, Some("Path is not a directory"))
            Ok(writeToArray(response), "application/json")
          else
            val files = Option(dir.listFiles()).map(_.toList).getOrElse(Nil).map { f =>
              FileInfo(f.getName, f.getAbsolutePath, f.isDirectory, f.length(), f.lastModified())
            }
            val response = LsResponse(nodeId, hostname, dirPath, files)
            Ok(writeToArray(response), "application/json")
        catch
          case e: Exception =>
            val response = LsResponse(nodeId, hostname, "", Nil, Some(e.getMessage))
            Ok(writeToArray(response), "application/json")
      },

      // Write file (with checksum)
      POST("/fs/write") { req =>
        try
          val request = readFromArray[WriteRequest](req.body)
          val path = Paths.get(request.path)

          // Create parent directories if needed
          Option(path.getParent).foreach(Files.createDirectories(_))

          val bytes = request.content.getBytes("UTF-8")
          Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
          val checksum = computeChecksum(bytes)

          val response = WriteResponse(nodeId, hostname, request.path, bytes.length, checksum)
          Ok(writeToArray(response), "application/json")
        catch
          case e: Exception =>
            val response = WriteResponse(nodeId, hostname, "", 0, "", Some(e.getMessage))
            Ok(writeToArray(response), "application/json")
      },

      // Write binary file (base64 encoded)
      POST("/fs/write-binary") { req =>
        try
          val request = readFromArray[WriteBinaryRequest](req.body)
          val path = Paths.get(request.path)

          Option(path.getParent).foreach(Files.createDirectories(_))

          val bytes = java.util.Base64.getDecoder.decode(request.base64Content)
          Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
          val checksum = computeChecksum(bytes)

          val response = WriteResponse(nodeId, hostname, request.path, bytes.length, checksum)
          Ok(writeToArray(response), "application/json")
        catch
          case e: Exception =>
            val response = WriteResponse(nodeId, hostname, "", 0, "", Some(e.getMessage))
            Ok(writeToArray(response), "application/json")
      },

      // Read file (with checksum)
      POST("/fs/read") { req =>
        try
          val request = readFromArray[ReadRequest](req.body)
          val path = Paths.get(request.path)
          val bytes = Files.readAllBytes(path)
          val content = new String(bytes, "UTF-8")
          val checksum = computeChecksum(bytes)

          val response = ReadResponse(nodeId, hostname, request.path, content, bytes.length, checksum)
          Ok(writeToArray(response), "application/json")
        catch
          case e: Exception =>
            val response = ReadResponse(nodeId, hostname, "", "", 0, "", Some(e.getMessage))
            Ok(writeToArray(response), "application/json")
      },

      // Delete file
      POST("/fs/delete") { req =>
        try
          val request = readFromArray[DeleteRequest](req.body)
          val path = Paths.get(request.path)
          val deleted = Files.deleteIfExists(path)

          val response = DeleteResponse(nodeId, hostname, request.path, deleted)
          Ok(writeToArray(response), "application/json")
        catch
          case e: Exception =>
            val response = DeleteResponse(nodeId, hostname, "", false, Some(e.getMessage))
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
        val disk = storageDir
        val info = s"""{
          |"nodeId":"$nodeId",
          |"hostname":"$hostname",
          |"storagePath":"$storagePath",
          |"totalSpace":${disk.getTotalSpace},
          |"freeSpace":${disk.getFreeSpace},
          |"usableSpace":${disk.getUsableSpace}
          |}""".stripMargin.replaceAll("\n", "")
        Ok(info, "application/json")
      },

      // Health endpoint
      GET("/health") { _ =>
        val json = s"""{"status":"healthy","nodeId":"$nodeId","hostname":"$hostname"}"""
        Ok(json, "application/json")
      }
    )

  // ============================================================================
  // Main
  // ============================================================================

  def main(args: Array[String]): Unit =
    if args.length < 2 then
      println("Usage:")
      println("  SmartFSNode seed <port> [storage-path]")
      println()
      println("Example:")
      println("  sbt \"runMain kraft.demos.SmartFSNode seed 7800\"")
      println("  sbt \"runMain kraft.demos.SmartFSNode seed 7800 /data/storage\"")
      sys.exit(1)

    val mode = args(0)
    val port = args(1).toInt
    val storagePath = if args.length > 2 then args(2) else "/data"

    mode match
      case "seed" => runNode(port, storagePath)
      case _ =>
        println(s"Unknown mode: $mode (use 'seed')")
        sys.exit(1)

  def runNode(port: Int, storagePath: String): Unit =
    val nodeId = s"smart-fs-$port"
    val hostname = java.net.InetAddress.getLocalHost.getHostName

    println("=" * 60)
    println(s"Smart Filesystem Node")
    println("=" * 60)
    println()
    println(s"  Node ID:      $nodeId")
    println(s"  Hostname:     $hostname")
    println(s"  Port:         $port")
    println(s"  Storage Path: $storagePath")
    println()

    val allRoutes = filesystemRoutes(nodeId, storagePath)

    // Start HTTP server
    val server = HttpServer(allRoutes)
    val handle = server.start(port)

    println(s"  Server listening on port $port")
    println()
    println("Endpoints:")
    println(s"  GET  http://localhost:$port/health       - Node health")
    println(s"  GET  http://localhost:$port/fs/info      - Node info + disk space")
    println(s"  GET  http://localhost:$port/fs/disk-info - Detailed disk info")
    println(s"  POST http://localhost:$port/fs/ls        - List directory")
    println(s"  POST http://localhost:$port/fs/read      - Read file")
    println(s"  POST http://localhost:$port/fs/write     - Write file")
    println(s"  POST http://localhost:$port/fs/delete    - Delete file")
    println(s"  POST http://localhost:$port/fs/search    - Search files")
    println(s"  POST http://localhost:$port/fs/exists    - Check file exists")
    println()
    println("Press Ctrl+C to stop")

    // Shutdown hook
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      println("\nShutting down...")
      handle.close()
    }))

    // Keep running
    handle.awaitTermination()
