package kraft.demos

import java.sql.{Connection, DriverManager, PreparedStatement}
import java.io.{ByteArrayOutputStream, PrintStream}

/**
 * Captures all demo outputs and stores them in SQLite for book rendering.
 */
object CaptureOutputs:
  val dbPath = "docs/dsl/demo_outputs.db"

  def main(args: Array[String]): Unit =
    // Initialize database
    Class.forName("org.sqlite.JDBC")
    val conn = DriverManager.getConnection(s"jdbc:sqlite:$dbPath")
    createSchema(conn)

    println("Capturing demo outputs...")
    println("")

    // Run and capture each demo
    captureDemo(conn, 1, "Ping-Pong", "Two workflows exchange messages back and forth", Demo01.main)
    captureDemo(conn, 2, "Chat Session", "10 messages in a conversation", Demo02.main)
    captureDemo(conn, 3, "Remote Commands", "Execute commands remotely", Demo03.main)
    captureDemo(conn, 4, "Durable Counter", "Counter with durable operations", Demo04.main)
    captureDemo(conn, 5, "Saga Pattern", "Multi-step transaction with compensation", Demo05.main)
    captureDemo(conn, 6, "Durable Timer", "Timed events that survive restarts", Demo06.main)
    captureDemo(conn, 7, "State Machine", "Order processing state transitions", Demo07.main)
    captureDemo(conn, 8, "Parallel Fan-Out", "Distribute tasks to workers", Demo08.main)
    captureDemo(conn, 9, "Automatic Retry", "Retry on transient failures", Demo09.main)
    captureDemo(conn, 10, "Checkpoint Recovery", "Resume from crash", Demo10.main)

    conn.close()
    println("")
    println(s"All outputs saved to $dbPath")

  def createSchema(conn: Connection): Unit =
    val stmt = conn.createStatement()
    stmt.execute("""
      CREATE TABLE IF NOT EXISTS demo_outputs (
        demo_number INTEGER PRIMARY KEY,
        title TEXT NOT NULL,
        description TEXT NOT NULL,
        output TEXT NOT NULL,
        captured_at TEXT DEFAULT CURRENT_TIMESTAMP
      )
    """)
    stmt.execute("DELETE FROM demo_outputs") // Clear old data
    stmt.close()

  def captureDemo(conn: Connection, num: Int, title: String, desc: String, run: Array[String] => Unit): Unit =
    print(s"  Demo $num: $title... ")

    // Capture stdout
    val baos = new ByteArrayOutputStream()
    val ps = new PrintStream(baos)
    val oldOut = System.out

    try
      System.setOut(ps)
      run(Array.empty)
      System.setOut(oldOut)

      val output = baos.toString("UTF-8")

      // Store in database
      val pstmt = conn.prepareStatement(
        "INSERT INTO demo_outputs (demo_number, title, description, output) VALUES (?, ?, ?, ?)"
      )
      pstmt.setInt(1, num)
      pstmt.setString(2, title)
      pstmt.setString(3, desc)
      pstmt.setString(4, output)
      pstmt.executeUpdate()
      pstmt.close()

      println(s"✓ (${output.split("\n").length} lines)")
    catch
      case e: Exception =>
        System.setOut(oldOut)
        println(s"✗ ${e.getMessage}")
