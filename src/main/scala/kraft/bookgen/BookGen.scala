package kraft.bookgen

import java.sql.{Connection, DriverManager, ResultSet}
import scala.util.matching.Regex
import scala.collection.mutable
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.anchorlink.AnchorLinkExtension
import com.vladsch.flexmark.util.data.MutableDataSet
import java.util.Arrays

/**
 * BookGen - Documentation generator that processes Markdown with embedded directives.
 * Produces HTML with dynamic content from SQLite database.
 */
case class Version(
  tag: String,
  chapterNumber: Double,
  title: String,
  description: String,
  baselineRPS: Double,
  improvementPercent: Option[Double],
  technique: String
)

case class Directive(
  directiveType: String,
  params: Map[String, String],
  raw: String
)

class Interpreter(dbPath: Option[String]) extends AutoCloseable:

  private val connection: Option[Connection] = dbPath.map { path =>
    Class.forName("org.sqlite.JDBC")
    val conn = DriverManager.getConnection(s"jdbc:sqlite:$path")
    conn
  }

  // Match directives: <!-- @type:params --> where params is everything up to -->
  private val directiveRe: Regex = """(?s)<!--\s*@(\w+):(.*?)-->""".r
  private var versions: Seq[Version] = Seq.empty
  private var chartCounter: Int = 0

  // Load versions from database (if table exists)
  connection.foreach { conn =>
    versions = try loadVersions(conn) catch case _: Exception => Seq.empty
  }

  private def loadVersions(conn: Connection): Seq[Version] =
    val stmt = conn.createStatement()
    val rs = stmt.executeQuery("""
      SELECT tag, chapter_number, title, COALESCE(description, ''),
             baseline_rps, improvement_percent, COALESCE(technique, '')
      FROM versions ORDER BY chapter_number
    """)
    val result = mutable.ArrayBuffer[Version]()
    while rs.next() do
      val impPct = rs.getDouble("improvement_percent")
      result += Version(
        tag = rs.getString("tag"),
        chapterNumber = rs.getDouble("chapter_number"),
        title = rs.getString("title"),
        description = rs.getString(4),
        baselineRPS = rs.getDouble("baseline_rps"),
        improvementPercent = if rs.wasNull() then None else Some(impPct),
        technique = rs.getString(7)
      )
    rs.close()
    stmt.close()
    result.toSeq

  def resetChartCounter(): Unit = chartCounter = 0

  private def parseDirective(matchResult: Regex.Match): Directive =
    val directiveType = matchResult.group(1)
    val content = matchResult.group(2)
    val params = parseParams(content)
    Directive(directiveType, params, matchResult.matched)

  private def parseParams(content: String): Map[String, String] =
    val params = mutable.Map[String, String]()
    var remaining = content

    while remaining.nonEmpty do
      remaining = remaining.dropWhile(c => c == ' ' || c == '\t' || c == '\n' || c == '\r')
      if remaining.nonEmpty then
        val eqIdx = remaining.indexOf('=')
        if eqIdx == -1 then
          remaining = ""
        else
          val key = remaining.take(eqIdx).trim
          remaining = remaining.drop(eqIdx + 1)

          if remaining.nonEmpty && (remaining.head == '"' || remaining.head == '\'') then
            val quote = remaining.head
            remaining = remaining.tail
            val sb = new StringBuilder
            var escaped = false
            var done = false
            var idx = 0
            while idx < remaining.length && !done do
              val c = remaining(idx)
              if escaped then
                sb.append(c)
                escaped = false
              else if c == '\\' then
                escaped = true
              else if c == quote then
                params(key) = sb.toString
                remaining = remaining.drop(idx + 1)
                done = true
              else
                sb.append(c)
              idx += 1
          else
            val endIdx = remaining.indexWhere(c => c == ' ' || c == '\t' || c == '\n' || c == '\r')
            if endIdx == -1 then
              params(key) = remaining
              remaining = ""
            else
              params(key) = remaining.take(endIdx)
              remaining = remaining.drop(endIdx)

    params.toMap

  def process(content: String): String =
    processWithTemplate(content, "article")

  def processWithTemplate(content: String, templateType: String): String =
    // First, protect markdown code blocks from directive processing
    val codeBlockPlaceholders = mutable.Map[String, String]()
    var codeBlockIdx = 0

    // Protect fenced code blocks (```...```)
    val codeBlockRe = """(?s)```(\w*)\n(.*?)```""".r
    var protectedContent = codeBlockRe.replaceAllIn(content, m => {
      val placeholder = s"__CODEBLOCK_${codeBlockIdx}__"
      codeBlockIdx += 1
      codeBlockPlaceholders(placeholder) = m.matched
      placeholder
    })

    // Now process directives (code block content is protected)
    val directivePlaceholders = mutable.Map[String, String]()
    var directiveIdx = 0

    val withDirectivePlaceholders = directiveRe.replaceAllIn(protectedContent, m => {
      val directive = parseDirective(m)
      val rendered = renderDirective(directive)
      // If the rendered content contains HTML tags, protect it
      // Use BOOKGENDIRECTIVE instead of __DIRECTIVE__ to avoid markdown bold interpretation
      if rendered.contains("<") then
        val placeholder = s"BOOKGENDIRECTIVE${directiveIdx}PLACEHOLDER"
        directiveIdx += 1
        directivePlaceholders(placeholder) = rendered
        placeholder
      else
        rendered.replace("$", "\\$")
    })

    // Restore code blocks before markdown processing
    var restoredContent = withDirectivePlaceholders
    codeBlockPlaceholders.foreach { case (placeholder, original) =>
      restoredContent = restoredContent.replace(placeholder, original)
    }

    // Process markdown (directives are now placeholders)
    var mdContent = markdownToHTML(restoredContent)

    // Restore directive outputs
    directivePlaceholders.foreach { case (placeholder, content) =>
      mdContent = mdContent.replace(placeholder, content)
      // Also handle case where placeholder got wrapped in <p> tags
      mdContent = mdContent.replace(s"<p>$placeholder</p>", content)
    }

    templateType match
      case "landing" => Templates.wrapLandingHTML(mdContent, extractMetricValue, connection)
      case _         => Templates.wrapHTML(mdContent)

  private def renderDirective(d: Directive): String =
    d.directiveType match
      case "meta"    => ""
      case "metric"  => if connection.isEmpty then "N/A" else renderMetric(d)
      case "chart"   => if connection.isEmpty then "<!-- Chart requires database -->" else renderChart(d)
      case "table"   => if connection.isEmpty then "<!-- Table requires database -->" else renderTable(d)
      case "git"     => if connection.isEmpty then "<!-- Git requires database -->" else renderGit(d)
      case "demo"    => if connection.isEmpty then "<!-- Demo requires database -->" else renderDemo(d)
      case "c4"      => renderC4(d)
      case "mermaid" => renderMermaid(d)
      case "footer"  => renderFooter(d)
      case _         => s"<!-- Unknown directive: ${d.directiveType} -->"

  private def renderMetric(d: Directive): String =
    val tag = d.params.getOrElse("tag", "")
    if tag.isEmpty then return "Error: metric requires tag"

    val format = d.params.getOrElse("format", "")
    val field = d.params.getOrElse("field", "")

    if field.nonEmpty && connection.isDefined then
      return queryMetricField(tag, field, format)

    versions.find(_.tag == tag).map { v =>
      format match
        case "short" =>
          if v.baselineRPS >= 1000000 then f"${v.baselineRPS / 1000000}%.1fM"
          else f"${v.baselineRPS / 1000}%.0fK"
        case "comma" => formatWithCommas(v.baselineRPS.toLong)
        case _       => f"${v.baselineRPS}%.0f"
    }.getOrElse("N/A")

  private def queryMetricField(tag: String, field: String, format: String): String =
    val query = field match
      case "improvement_percent" =>
        "SELECT improvement_percent FROM versions WHERE tag = ?"
      case f if Seq("p99_latency_us", "p50_latency_us", "avg_latency_us", "max_latency_us").contains(f) =>
        s"""SELECT br.$f FROM benchmark_results br
           |JOIN benchmark_runs r ON br.run_id = r.id
           |JOIN commits c ON r.commit_hash = c.hash
           |WHERE c.tag = ?
           |ORDER BY r.id DESC LIMIT 1""".stripMargin
      case _ => return "N/A"

    connection.flatMap { conn =>
      val stmt = conn.prepareStatement(query)
      stmt.setString(1, tag)
      val rs = stmt.executeQuery()
      val result = if rs.next() then
        val value = rs.getDouble(1)
        if rs.wasNull() then None
        else Some(formatValue(value, format))
      else None
      rs.close()
      stmt.close()
      result
    }.getOrElse("N/A")

  private def formatValue(value: Double, format: String): String =
    format match
      case "short" =>
        if value >= 1000000 then f"${value / 1000000}%.1fM"
        else if value >= 1000 then f"${value / 1000}%.0fK"
        else f"$value%.0f"
      case "comma"   => formatWithCommas(value.toLong)
      case "decimal" => f"$value%.1f"
      case _         => f"$value%.0f"

  private def formatWithCommas(n: Long): String =
    val s = n.toString
    if s.length <= 3 then s
    else s.reverse.grouped(3).mkString(",").reverse

  private def renderChart(d: Directive): String =
    chartCounter += 1
    val chartID = s"chart_$chartCounter"
    val title = d.params.getOrElse("title", "Performance Chart")
    val query = d.params.getOrElse("query", "")

    if query.isEmpty then return "<!-- Error: chart requires query -->"

    renderDeclarativeChart(chartID, title, query, d.params)

  private def renderDeclarativeChart(chartID: String, title: String, query: String, params: Map[String, String]): String =
    connection.map { conn =>
      val stmt = conn.createStatement()
      val rs = stmt.executeQuery(query)
      val meta = rs.getMetaData
      val cols = (1 to meta.getColumnCount).map(meta.getColumnName).toSeq

      val xCol = params.getOrElse("x", cols.headOption.getOrElse(""))
      val yCol = params.getOrElse("y", cols.lift(1).getOrElse(""))
      val seriesCol = params.getOrElse("series", cols.lift(2).getOrElse(""))

      val xIdx = cols.indexOf(xCol)
      val yIdx = cols.indexOf(yCol)
      val seriesIdx = cols.indexOf(seriesCol)

      val seriesData = mutable.LinkedHashMap[String, mutable.ArrayBuffer[(Any, Any)]]()

      while rs.next() do
        val x = if xIdx >= 0 then rs.getObject(xIdx + 1) else null
        val y = if yIdx >= 0 then rs.getObject(yIdx + 1) else null
        val series = if seriesIdx >= 0 then Option(rs.getString(seriesIdx + 1)).getOrElse("default") else "default"

        if !seriesData.contains(series) then
          seriesData(series) = mutable.ArrayBuffer()
        seriesData(series) += ((x, y))

      rs.close()
      stmt.close()

      val defaultColors = Seq("#3498db", "#e74c3c", "#27ae60", "#f39c12", "#9b59b6")

      // Parse custom dataset configs from params
      val customDatasets = params.get("datasets").map { json =>
        // Simple JSON parsing for dataset overrides
        val pattern = """"(\w+)":\s*\{([^}]+)\}""".r
        pattern.findAllMatchIn(json).map { m =>
          val seriesName = m.group(1)
          val props = m.group(2)
          seriesName -> props
        }.toMap
      }.getOrElse(Map.empty)

      val datasetsJSON = seriesData.toSeq.zipWithIndex.map { case ((series, data), idx) =>
        val yValues = data.map(_._2).map {
          case n: Number => n.toString
          case other => s""""$other""""
        }.mkString("[", ",", "]")

        // Check for custom config for this series
        customDatasets.get(series) match {
          case Some(customProps) =>
            // Extract properties from custom config
            val labelMatch = """"label":\s*"([^"]+)"""".r.findFirstMatchIn(customProps)
            val colorMatch = """"borderColor":\s*"([^"]+)"""".r.findFirstMatchIn(customProps)
            val yAxisMatch = """"yAxisID":\s*"([^"]+)"""".r.findFirstMatchIn(customProps)

            val label = labelMatch.map(_.group(1)).getOrElse(series)
            val borderColor = colorMatch.map(_.group(1)).getOrElse(defaultColors(idx % defaultColors.length))
            val yAxisID = yAxisMatch.map(m => s""", yAxisID: '${m.group(1)}'""").getOrElse("")

            s"""{
               |      label: '$label',
               |      data: $yValues,
               |      borderColor: '$borderColor',
               |      backgroundColor: '${borderColor}22',
               |      borderWidth: 2,
               |      fill: false,
               |      tension: 0.3$yAxisID
               |    }""".stripMargin
          case None =>
            val borderColor = defaultColors(idx % defaultColors.length)
            s"""{
               |      label: '$series',
               |      data: $yValues,
               |      borderColor: '$borderColor',
               |      backgroundColor: '${borderColor}22',
               |      borderWidth: 2,
               |      fill: false,
               |      tension: 0.3
               |    }""".stripMargin
        }
      }.mkString(",\n")

      val labels = seriesData.values.headOption.map { data =>
        data.map(_._1).map {
          case s: String => s""""$s""""
          case n: Number => n.toString
          case other => s""""$other""""
        }.mkString("[", ",", "]")
      }.getOrElse("[]")

      val chartType = params.getOrElse("type", "line")

      // Use custom options if provided, otherwise defaults
      val chartOptions = params.get("options").getOrElse("""{
          "responsive": true,
          "plugins": { "legend": { "position": "top" } },
          "scales": { "y": { "beginAtZero": true } }
        }""")

      s"""
<div class="chart-container">
  <h4>$title</h4>
  <canvas id="$chartID"></canvas>
  <script>
    (function() {
      const ctx = document.getElementById('$chartID').getContext('2d');
      new Chart(ctx, {
        type: '$chartType',
        data: {
          labels: $labels,
          datasets: [$datasetsJSON]
        },
        options: $chartOptions
      });
    })();
  </script>
</div>"""
    }.getOrElse("<!-- Error: no database connection -->")

  private def renderTable(d: Directive): String =
    val title = d.params.getOrElse("title", "Data Table")
    val query = d.params.getOrElse("query", "")

    if query.isEmpty then return "<!-- Error: table requires query -->"

    connection.map { conn =>
      val stmt = conn.createStatement()
      val rs = stmt.executeQuery(query)
      val meta = rs.getMetaData
      val cols = (1 to meta.getColumnCount).map(meta.getColumnName).toSeq

      val header = cols.map(c => s"<th>$c</th>").mkString
      val rows = mutable.ArrayBuffer[String]()

      while rs.next() do
        val cells = cols.indices.map { i =>
          val value = Option(rs.getObject(i + 1)).map(_.toString).getOrElse("")
          s"<td>$value</td>"
        }.mkString
        rows += s"<tr>$cells</tr>"

      rs.close()
      stmt.close()

      s"""
<div class="table-container">
  <h4>$title</h4>
  <table class="data-table">
    <thead><tr>$header</tr></thead>
    <tbody>${rows.mkString}</tbody>
  </table>
</div>"""
    }.getOrElse("<!-- Error: no database connection -->")

  private def renderDemo(d: Directive): String =
    val num = d.params.get("num").flatMap(_.toIntOption)
    if num.isEmpty then return "<!-- Error: demo requires num parameter -->"

    connection.flatMap { conn =>
      val stmt = conn.prepareStatement(
        "SELECT title, description, output FROM demo_outputs WHERE demo_number = ?"
      )
      stmt.setInt(1, num.get)
      val rs = stmt.executeQuery()
      val result = if rs.next() then
        val title = rs.getString("title")
        val desc = rs.getString("description")
        val output = rs.getString("output")
          .replace("&", "&amp;")
          .replace("<", "&lt;")
          .replace(">", "&gt;")
        Some(s"""
<h3>Demo ${num.get}: $title</h3>
<p><strong>$desc</strong></p>
<pre><code>$output</code></pre>
""")
      else None
      rs.close()
      stmt.close()
      result
    }.getOrElse(s"<!-- Demo ${num.get} not found in database -->")

  private def renderGit(d: Directive): String =
    val commits = versions.zipWithIndex.map { case (v, idx) =>
      val isLast = idx == versions.length - 1
      val nodeClass = if isLast then "node final" else "node"
      val rpsDisplay = f"""<span class="rps">${v.baselineRPS}%.0f RPS</span>"""

      s"""
      <div class="git-commit">
        <div class="$nodeClass"></div>
        <div class="commit-line"></div>
        <div class="commit-info">
          <code class="tag">${v.tag}</code>
          <span class="message">${v.title}</span>
          $rpsDisplay
        </div>
      </div>"""
    }.mkString

    s"""
<div class="git-visualization">
  <div class="git-branch main">
    <div class="branch-label">main</div>
    <div class="commits">$commits</div>
  </div>
</div>"""

  private def renderC4(d: Directive): String =
    chartCounter += 1
    val diagramID = s"c4_$chartCounter"
    val title = d.params.getOrElse("title", "")
    var diagram = d.params.getOrElse("diagram", "")

    if diagram.isEmpty then return "<!-- Error: c4 requires diagram -->"

    diagram = diagram.replace("\\\"", "\"")
    if title.nonEmpty && !diagram.contains("title ") then
      diagram = s"title $title\n$diagram"

    s"""
<div class="c4-diagram">
  <pre class="c4-custom" id="$diagramID" style="display:none;">${diagram.trim}</pre>
</div>"""

  private def renderMermaid(d: Directive): String =
    chartCounter += 1
    val diagramID = s"mermaid_$chartCounter"
    val title = d.params.getOrElse("title", "")
    val diagram = d.params.getOrElse("diagram", "")

    if diagram.isEmpty then return "<!-- Error: mermaid requires diagram -->"

    val titleHTML = if title.nonEmpty then s"<h4>$title</h4>" else ""

    s"""
<div class="mermaid-diagram">
  $titleHTML
  <pre class="mermaid" id="$diagramID">
$diagram
  </pre>
</div>"""

  private def renderFooter(d: Directive): String =
    """
<footer class="generated-footer">
  <p>Generated from narrative Markdown with live benchmark data</p>
  <p class="timestamp">Fever Code Challenge - Event Search API</p>
</footer>"""

  def extractMetricValue(tag: String, field: String, format: String): String =
    if field.nonEmpty && connection.isDefined then
      val result = queryMetricField(tag, field, format)
      if result != "N/A" then return result

    versions.find(_.tag == tag).map { v =>
      format match
        case "short" =>
          if v.baselineRPS >= 1000000 then f"${v.baselineRPS / 1000000}%.1fM"
          else f"${v.baselineRPS / 1000}%.0fK"
        case "comma" => formatWithCommas(v.baselineRPS.toLong)
        case "decimal" =>
          v.improvementPercent.map(p => f"$p%.1f").getOrElse("N/A")
        case _ => f"${v.baselineRPS}%.0f"
    }.getOrElse("N/A")

  private val mdOptions: MutableDataSet = {
    val opts = new MutableDataSet()
    opts.set(Parser.EXTENSIONS, Arrays.asList(
      TablesExtension.create(),
      StrikethroughExtension.create(),
      AnchorLinkExtension.create()
    ))
    opts.set(HtmlRenderer.GENERATE_HEADER_ID, java.lang.Boolean.TRUE)
    opts.set(AnchorLinkExtension.ANCHORLINKS_WRAP_TEXT, java.lang.Boolean.FALSE)
    opts.set(AnchorLinkExtension.ANCHORLINKS_ANCHOR_CLASS, "anchor")
    opts
  }

  private val mdParser: Parser = Parser.builder(mdOptions).build()
  private val mdRenderer: HtmlRenderer = HtmlRenderer.builder(mdOptions).build()

  private def markdownToHTML(content: String): String =
    // Pre-process: ensure blank line before markdown tables
    // Flexmark requires blank line before tables, gomarkdown doesn't
    val tableRowRe = """(?m)^(\|.+\|)\s*$""".r
    val lines = content.split("\n").toBuffer
    var i = 1
    while i < lines.length do
      val line = lines(i).trim
      val prevLine = lines(i - 1).trim
      // If current line starts a table (| ... |) and previous line is not empty and not a table row
      if line.startsWith("|") && line.endsWith("|") && prevLine.nonEmpty && !prevLine.startsWith("|") then
        lines.insert(i, "")
        i += 1
      i += 1

    val preprocessed = lines.mkString("\n")
    val document = mdParser.parse(preprocessed)
    mdRenderer.render(document)

  override def close(): Unit =
    connection.foreach(_.close())

object Interpreter:
  def apply(dbPath: String): Interpreter = new Interpreter(Some(dbPath))
  def withoutDB(): Interpreter = new Interpreter(None)
