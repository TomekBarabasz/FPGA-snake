package newhope.snake.board

// =====================================================================
//  InlineReadmem: zamienia `$readmemb("<plik>", mem);` w wygenerowanym
//  Verilogu na jawne `mem[i] = W'b...;`, zeby zawartosc RAM-u (program)
//  siedziala w samym .v. XST (ISE) i yosys wnioskuja z tego BRAM z
//  inicjalizacja, a pliki .bin nie musza lezec obok projektu syntezy.
//  (Ta sama funkcja co j1sc.boards.InlineReadmem w cpu/j1.)
// =====================================================================
object InlineReadmem {
  def apply(verilogPath : String) : Unit = {
    import java.nio.file.{Files, Paths}
    import java.nio.charset.StandardCharsets.UTF_8
    val path = Paths.get(verilogPath)
    val dir  = path.getParent
    val re   = """\$readmemb\("([^"]+)",\s*(\w+)\);""".r
    val src  = new String(Files.readAllBytes(path), UTF_8)
    var n    = 0
    val out  = re.replaceAllIn(src, m => {
      val words = new String(Files.readAllBytes(dir.resolve(m.group(1))), UTF_8).split("\\s+").filter(_.nonEmpty)
      n += 1
      java.util.regex.Matcher.quoteReplacement(
        words.zipWithIndex.map { case (w, i) => s"${m.group(2)}[$i] = ${w.length}'b$w;" }.mkString("\n    "))
    })
    Files.write(path, out.getBytes(UTF_8))
    println(s"[InlineReadmem] $n memory image(s) inlined into $verilogPath")
  }
}
