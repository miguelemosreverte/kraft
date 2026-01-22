val scala3Version = "3.4.2"

lazy val root = project
  .in(file("."))
  .settings(
    name := "kraft-scala",
    version := "0.1.0",
    scalaVersion := scala3Version,

    // Compiler options for performance
    scalacOptions ++= Seq(
      "-Xmax-inlines", "128",
      "-release", "21"
    ),

    // JVM options for io_uring
    fork := true,
    javaOptions ++= Seq(
      "--enable-native-access=ALL-UNNAMED",
      "--add-opens", "java.base/java.nio=ALL-UNNAMED",
      "-Dio.netty.transport.noNative=false"
    ),

    libraryDependencies ++= Seq(
      // Netty core
      "io.netty" % "netty-all" % "4.1.114.Final",

      // io_uring transport (classes + natives bundle)
      // The classes artifact includes Java code, native artifacts load at runtime
      "io.netty.incubator" % "netty-incubator-transport-classes-io_uring" % "0.0.25.Final",
      "io.netty.incubator" % "netty-incubator-transport-native-io_uring" % "0.0.25.Final" classifier "linux-x86_64",
      "io.netty.incubator" % "netty-incubator-transport-native-io_uring" % "0.0.25.Final" classifier "linux-aarch_64",

      // JSON - using jsoniter for speed (faster than circe)
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.30.1",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.30.1" % Provided,

      // XML parsing for sync
      "org.scala-lang.modules" %% "scala-xml" % "2.3.0",

      // SQLite for persistence
      "org.xerial" % "sqlite-jdbc" % "3.45.1.0",

      // Embedded key-value stores for durable execution (per-node storage)
      "org.rocksdb" % "rocksdbjni" % "9.0.0",
      "org.iq80.leveldb" % "leveldb" % "0.12",

      // HTTP client for sync polling
      "com.softwaremill.sttp.client3" %% "core" % "3.9.7",

      // Markdown processing for BookGen
      "com.vladsch.flexmark" % "flexmark-all" % "0.64.8",

      // Testing
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),

    // Default main class for 'sbt run'
    Compile / run / mainClass := Some("kraft.Main"),

    // Assembly settings for fat JAR
    assembly / mainClass := Some("kraft.Main"),
    assembly / assemblyMergeStrategy := {
      // Keep native libraries (.so files)
      case PathList(ps @ _*) if ps.last.endsWith(".so") => MergeStrategy.first
      // Keep META-INF/native folder (Netty native libs)
      case PathList("META-INF", "native", _ @_*) => MergeStrategy.first
      case PathList("META-INF", "native-image", _ @_*) => MergeStrategy.discard
      case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.first
      case PathList("META-INF", _ @_*) => MergeStrategy.discard
      case "module-info.class" => MergeStrategy.discard
      case x => MergeStrategy.first
    }
  )
