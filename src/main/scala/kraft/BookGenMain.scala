package kraft

import kraft.bookgen.Interpreter
import java.io.{File, PrintWriter}
import scala.io.Source
import java.sql.{Connection, DriverManager}

/**
 * BookGen CLI - Documentation generator that processes Markdown with embedded directives.
 *
 * Usage:
 *   sbt "runMain kraft.BookGenMain -input book.md -output index.html -db benchmarks.db"
 *   sbt "runMain kraft.BookGenMain -all -db docs/benchmarks.db"
 */
object BookGenMain:

  case class Config(
    input: String = "book.md",
    output: String = "../index.html",
    db: String = "benchmarks.db",
    noDb: Boolean = false,
    buildAll: Boolean = false
  )

  def main(args: Array[String]): Unit =
    val config = parseArgs(args.toList, Config())

    val interp = if config.noDb then
      Interpreter.withoutDB()
    else
      val dbFile = new File(config.db)
      if !dbFile.exists() then
        println(s"Database not found at ${config.db}, creating...")
        initializeDatabase(config.db)
      Interpreter(config.db)

    try
      if config.buildAll then
        // Build all documentation files
        val builds = Seq(
          ("index.md", "../index.html"),
          ("book.md", "../app-book.html")
        )

        builds.foreach { case (input, output) =>
          val inputFile = new File(input)
          if inputFile.exists() then
            buildFile(interp, input, output) match
              case Right(_) => println(s"Generated $output")
              case Left(err) => println(s"Error building $input: $err")
            interp.resetChartCounter()
          else
            println(s"Skipping $input (not found)")
        }
      else
        // Single file mode
        buildFile(interp, config.input, config.output) match
          case Right(_) => println(s"Generated ${config.output}")
          case Left(err) =>
            println(s"Failed to build: $err")
            System.exit(1)
    finally
      interp.close()

  private def parseArgs(args: List[String], config: Config): Config =
    args match
      case Nil => config
      case "-input" :: value :: rest => parseArgs(rest, config.copy(input = value))
      case "-output" :: value :: rest => parseArgs(rest, config.copy(output = value))
      case "-db" :: value :: rest => parseArgs(rest, config.copy(db = value))
      case "-no-db" :: rest => parseArgs(rest, config.copy(noDb = true))
      case "-all" :: rest => parseArgs(rest, config.copy(buildAll = true))
      case unknown :: rest =>
        println(s"Unknown argument: $unknown")
        parseArgs(rest, config)

  private def buildFile(interp: Interpreter, inputPath: String, outputPath: String): Either[String, Unit] =
    try
      val mdContent = Source.fromFile(inputPath).mkString

      // Detect template type based on input file
      val templateType = if inputPath.endsWith("index.md") then "landing" else "article"

      val html = interp.processWithTemplate(mdContent, templateType)

      val writer = new PrintWriter(new File(outputPath))
      try
        writer.write(html)
        Right(())
      finally
        writer.close()
    catch
      case e: Exception => Left(e.getMessage)

  private def initializeDatabase(dbPath: String): Unit =
    Class.forName("org.sqlite.JDBC")
    val conn = DriverManager.getConnection(s"jdbc:sqlite:$dbPath")

    try
      val schemaFile = new File("schema.sql")
      if !schemaFile.exists() then
        throw new RuntimeException("schema.sql not found")

      val schema = Source.fromFile(schemaFile).mkString
      val stmt = conn.createStatement()
      schema.split(";").filter(_.trim.nonEmpty).foreach { sql =>
        stmt.execute(sql.trim)
      }
      stmt.close()

      val seedFile = new File("seed_data.sql")
      if seedFile.exists() then
        val seed = Source.fromFile(seedFile).mkString
        val seedStmt = conn.createStatement()
        seed.split(";").filter(_.trim.nonEmpty).foreach { sql =>
          try seedStmt.execute(sql.trim)
          catch case _: Exception => () // Ignore seed errors
        }
        seedStmt.close()
    finally
      conn.close()
