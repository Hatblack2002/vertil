package com.vertil.tokenizer

/**
 * Renderizador del chat template de Hugging Face a partir del campo
 * `chat_template` de tokenizer_config.json.
 *
 * Implementa un SUBCONJUNTO de Jinja2 suficiente para los templates de chat
 * de las familias más comunes (ChatML/SmolLM/Qwen, Llama-2/3, Mistral,
 * Zephyr, Phi, Gemma…):
 *  - {% for x in seq %} / {% endfor %} con variable `loop` (index, first, last)
 *  - {% if %} / {% elif %} / {% else %} / {% endif %}
 *  - {% set x = expr %}
 *  - {{ expr }} con concatenación, subscripts m.role / m['role'], filtros
 *    (tojson, trim, length, lower, upper, join, replace, first, last, string,
 *    int, float, list, startswith, endswith), raise_exception()
 *  - control de espacios {%- -%} {{- -}}
 *  - literales de cadena multilínea con escapes \\n, \\t, \\', \\", \\\\
 *
 * Si el template falla o no existe, [renderOrFallback] recurre a un ChatML
 * genérico usando los tokens del tokenizer_config cuando existen.
 */
object ChatTemplate {

    class TemplateException(message: String) : Exception(message)

    // ============================== API ==============================

    fun render(template: String, context: Map<String, Any?>): String {
        val nodes = Parser(Lexer(template).lex()).parseProgram(endTags = emptySet())
        val ctx = HashMap<String, Any?>(context.size * 2).apply { putAll(context) }
        val out = StringBuilder()
        exec(nodes, ctx, out)
        return out.toString()
    }

    fun renderOrFallback(
        template: String?,
        context: Map<String, Any?>,
        fallbackTokens: FallbackTokens
    ): Pair<String, String> {
        if (!template.isNullOrBlank()) {
            try {
                return render(template, context) to "template"
            } catch (e: TemplateException) {
                // cae al fallback con mensaje registrado por el llamador
            }
        }
        return renderFallback(context, fallbackTokens) to "fallback"
    }

    /** Tokens para el fallback ChatML. */
    data class FallbackTokens(
        val imStart: String = "<|im_start|>",
        val imEnd: String = "<|im_end|>",
        val hasImTokens: Boolean = true
    )

    /** Fallback genérico: ChatML si el tokenizador tiene <|im_start|>/<|im_end|>; si no, formato plano. */
    fun renderFallback(context: Map<String, Any?>, fb: FallbackTokens): String {
        @Suppress("UNCHECKED_CAST")
        val messages = context["messages"] as? List<Map<String, Any?>> ?: emptyList()
        val addGen = context["add_generation_prompt"] == true
        val sb = StringBuilder()
        if (fb.hasImTokens) {
            for (m in messages) {
                sb.append(fb.imStart).append(m["role"] ?: "user").append('\n')
                    .append(m["content"] ?: "").append(fb.imEnd).append('\n')
            }
            if (addGen) sb.append(fb.imStart).append("assistant\n")
        } else {
            for (m in messages) {
                sb.append((m["role"] ?: "user")).append(": ").append(m["content"] ?: "").append('\n')
            }
            if (addGen) sb.append("assistant: ")
        }
        return sb.toString()
    }

    // ============================ LEXER ============================

    private data class Tok(val kind: Kind, val text: String) {
        enum class Kind { TEXT, OUTPUT, TAG }
    }

    private fun Lexer(template: String): LexerImpl = LexerImpl(template)

    private class LexerImpl(private val src: String) {
        fun lex(): List<Tok> {
            val toks = ArrayList<Tok>()
            var i = 0
            val sb = StringBuilder()
            fun flushText(trimRight: Boolean) {
                if (sb.isNotEmpty()) {
                    if (trimRight) while (sb.isNotEmpty() && sb.last().isWhitespace()) sb.setLength(sb.length - 1)
                    toks.add(Tok(Tok.Kind.TEXT, sb.toString()))
                    sb.clear()
                }
            }
            while (i < src.length) {
                val brace1 = src.indexOf("{{", i)
                val brace2 = src.indexOf("{%", i)
                val next = if (brace1 == -1) brace2 else if (brace2 == -1) brace1 else minOf(brace1, brace2)
                if (next == -1) {
                    sb.append(src.substring(i)); break
                }
                sb.append(src.substring(i, next))
                val isOutput = next == brace1
                val openTag = if (isOutput) "{{" else "{%"
                val closeTag = if (isOutput) "}}" else "%}"
                var j = next + 2
                var stripRight = false
                val body = StringBuilder()
                while (j < src.length) {
                    val ch = src[j]
                    if (ch == '\'' || ch == '"') {
                        val end = skipString(src, j)
                        body.append(src.substring(j, end))
                        j = end
                        continue
                    }
                    if (isOutput) {
                        if (src.startsWith("}}", j)) break
                        if (src.startsWith("-}}", j)) break
                    } else {
                        if (src.startsWith("%}", j)) break
                        if (src.startsWith("-%}", j)) break
                    }
                    body.append(ch); j++
                }
                if (j >= src.length) throw TemplateException("Tag sin cerrar: $openTag")
                var expr = body.toString()
                val stripLeft = expr.startsWith("-")
                if (stripLeft) expr = expr.substring(1)
                if (src.startsWith("-%}", j) || src.startsWith("-}}", j)) {
                    stripRight = true
                }
                flushText(stripLeft)
                toks.add(Tok(if (isOutput) Tok.Kind.OUTPUT else Tok.Kind.TAG, expr.trim()))
                j += if (stripRight) 3 else 2
                i = j
                if (stripRight) {
                    while (i < src.length && src[i].isWhitespace()) i++
                }
            }
            flushText(false)
            return toks
        }

        /** Devuelve el índice DESPUÉS de la cadena que empieza en [start]. */
        fun skipString(s: String, start: Int): Int {
            val quote = s[start]
            var i = start + 1
            while (i < s.length) {
                val ch = s[i]
                if (ch == '\\' && i + 1 < s.length) { i += 2; continue }
                if (ch == quote) return i + 1
                i++
            }
            throw TemplateException("Cadena sin cerrar en el template")
        }
    }

    // ============================ AST ============================

    private interface Node
    private data class TextN(val text: String) : Node
    private data class OutputN(val expr: Expr) : Node
    private data class IfN(val branches: List<Pair<Expr?, List<Node>>>) : Node
    private data class ForN(val varName: String, val iterExpr: Expr, val body: List<Node>) : Node
    private data class SetN(val target: String, val expr: Expr) : Node

    private class Parser(private val toks: List<Tok>) {
        var pos = 0
        fun parseProgram(endTags: Set<String>): List<Node> {
            val out = ArrayList<Node>()
            while (pos < toks.size) {
                val t = toks[pos]
                when (t.kind) {
                    Tok.Kind.TEXT -> { out.add(TextN(t.text)); pos++ }
                    Tok.Kind.OUTPUT -> { out.add(OutputN(parseExpr(t.text))); pos++ }
                    Tok.Kind.TAG -> {
                        val keyword = t.text.split(Regex("\\s"), limit = 2)[0]
                        if (keyword in endTags) return out
                        when (keyword) {
                            "if" -> { out.add(parseIf()); }
                            "for" -> out.add(parseFor())
                            "set" -> {
                                val m = Regex("^set\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)
                                    .find(t.text) ?: throw TemplateException("set inválido: ${t.text}")
                                out.add(SetN(m.groupValues[1], parseExpr(m.groupValues[2].trim())))
                                pos++
                            }
                            "generation" -> {
                                // {% generation %}...{% endgeneration %}: contenido transparente
                                pos++
                                val inner = parseProgram(setOf("endgeneration"))
                                if (pos < toks.size) pos++ // consume endgeneration
                                out.addAll(inner)
                            }
                            "else", "elif", "endif", "endfor", "endgeneration" ->
                                throw TemplateException("Tag inesperado: $keyword")
                            else -> throw TemplateException("Tag no soportado: $keyword (template no soportado)")
                        }
                    }
                }
            }
            return out
        }

        private fun parseIf(): Node {
            val cond = parseExpr(consumeTagText().removePrefix("if").trim())
            pos++ // consume 'if'
            val branches = ArrayList<Pair<Expr?, List<Node>>>()
            branches.add(cond to parseProgram(setOf("elif", "else", "endif")))
            while (pos < toks.size) {
                val t = toks[pos]
                val kw = t.text.split(Regex("\\s"), limit = 2)[0]
                when (kw) {
                    "elif" -> {
                        val c = parseExpr(t.text.removePrefix("elif").trim())
                        pos++
                        branches.add(c to parseProgram(setOf("elif", "else", "endif")))
                    }
                    "else" -> {
                        pos++
                        branches.add(null to parseProgram(setOf("endif")))
                        if (pos < toks.size) pos++ // endif
                        return IfN(branches)
                    }
                    "endif" -> { pos++; return IfN(branches) }
                    else -> throw TemplateException("Se esperaba elif/else/endif")
                }
            }
            throw TemplateException("if sin endif")
        }

        private fun parseFor(): Node {
            val text = consumeTagText()
            val m = Regex("^for\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+in\\s+(.+)$", RegexOption.DOT_MATCHES_ALL)
                .find(text) ?: throw TemplateException("for inválido: $text")
            val iterExpr = parseExpr(m.groupValues[2].trim())
            pos++
            val body = parseProgram(setOf("endfor"))
            if (pos < toks.size) pos++
            return ForN(m.groupValues[1], iterExpr, body)
        }

        private fun consumeTagText(): String = toks[pos].text
    }

    // ========================= EXPRESIONES =========================

    private sealed interface Expr
    private data class LitE(val value: Any?) : Expr
    private data class VarE(val name: String) : Expr
    private data class BinE(val op: String, val l: Expr, val r: Expr) : Expr
    private data class NotE(val e: Expr) : Expr
    private data class NegE(val e: Expr) : Expr
    private data class IndexE(val obj: Expr, val idx: Expr) : Expr
    private data class AttrE(val obj: Expr, val name: String) : Expr
    private data class FilterE(val obj: Expr, val name: String, val args: List<Expr>) : Expr
    private data class CallE(val name: String, val args: List<Expr>) : Expr
    private data class TernaryE(val cond: Expr, val then: Expr, val elseE: Expr) : Expr

    private class ExprParser(private val src: String) {
        var i = 0
        fun parse(): Expr {
            val e = parseOr()
            skipWs()
            if (i < src.length && src.startsWith("if", i)) { // ternario a if b else c
                i += 2
                val cond = parseOr()
                skipWs()
                expect("else")
                val elseE = parseOr()
                return TernaryE(cond, e, elseE)
            }
            return e
        }

        private fun skipWs() { while (i < src.length && src[i].isWhitespace()) i++ }
        private fun expect(s: String) {
            skipWs()
            if (!src.startsWith(s, i)) throw TemplateException("Se esperaba '$s' en: ${src.substring(i.coerceAtMost(src.length))}")
            i += s.length
        }

        private fun parseOr(): Expr {
            var l = parseAnd()
            while (true) {
                skipWs()
                if (src.startsWith("or", i) && !isWordChar(src.getOrNull(i + 2))) {
                    i += 2
                    l = BinE("or", l, parseAnd())
                } else return l
            }
        }

        private fun parseAnd(): Expr {
            var l = parseNot()
            while (true) {
                skipWs()
                if (src.startsWith("and", i) && !isWordChar(src.getOrNull(i + 3))) {
                    i += 3
                    l = BinE("and", l, parseNot())
                } else return l
            }
        }

        private fun parseNot(): Expr {
            skipWs()
            if (src.startsWith("not ", i) || src.startsWith("not(", i)) {
                i += 3
                return NotE(parseNot())
            }
            return parseCompare()
        }

        private fun parseCompare(): Expr {
            var l = parseAdd()
            while (true) {
                skipWs()
                val two = src.substring(i, minOf(i + 2, src.length))
                val op = when {
                    src.startsWith("not in", i) -> { i += 6; "not in" }
                    src.startsWith(" in ", i) -> { i += 4; "in" }
                    two == "==" || two == "!=" || two == "<=" || two == ">=" -> { i += 2; two }
                    i < src.length && (src[i] == '<' || src[i] == '>') -> { val c = src[i].toString(); i += 1; c }
                    else -> return l
                }
                l = BinE(op, l, parseAdd())
            }
        }

        private fun parseAdd(): Expr {
            var l = parseMul()
            while (true) {
                skipWs()
                if (i < src.length && (src[i] == '+' || src[i] == '-') && src.getOrNull(i + 1) != '=') {
                    val op = src[i].toString(); i++
                    l = BinE(op, l, parseMul())
                } else return l
            }
        }

        private fun parseMul(): Expr {
            var l = parseUnary()
            while (true) {
                skipWs()
                if (i < src.length && (src[i] == '*' || src[i] == '/') && src.getOrNull(i + 1) != '=') {
                    val op = src[i].toString(); i++
                    l = BinE(op, l, parseUnary())
                } else return l
            }
        }

        private fun parseUnary(): Expr {
            skipWs()
            if (i < src.length && src[i] == '-') { i++; return NegE(parseUnary()) }
            return parsePostfix()
        }

        private fun parsePostfix(): Expr {
            var e = parsePrimary()
            while (true) {
                skipWs()
                if (i < src.length && src[i] == '.') {
                    i++
                    val name = readIdent() ?: throw TemplateException("Atributo inválido")
                    e = AttrE(e, name)
                } else if (i < src.length && src[i] == '[') {
                    i++
                    val idx = parse()
                    expect("]")
                    e = IndexE(e, idx)
                } else if (i < src.length && src[i] == '|') {
                    i++
                    val name = readIdent() ?: throw TemplateException("Filtro inválido")
                    val args = ArrayList<Expr>()
                    skipWs()
                    if (i < src.length && src[i] == '(') {
                        i++
                        while (true) {
                            skipWs()
                            if (i < src.length && src[i] == ')') { i++; break }
                            args.add(parse())
                            skipWs()
                            if (i < src.length && src[i] == ',') i++
                        }
                    }
                    e = FilterE(e, name, args)
                } else return e
            }
        }

        private fun parsePrimary(): Expr {
            skipWs()
            if (i >= src.length) throw TemplateException("Expresión vacía")
            val c = src[i]
            return when {
                c == '(' -> { i++; val e = parse(); expect(")"); e }
                c == '\'' || c == '"' -> LitE(readString())
                c.isDigit() -> readNumber()
                src.startsWith("true", i) -> { i += 4; LitE(true) }
                src.startsWith("false", i) -> { i += 5; LitE(false) }
                src.startsWith("none", i) -> { i += 4; LitE(null) }
                else -> {
                    val name = readIdent() ?: throw TemplateException("Token inesperado: ${src.substring(i, minOf(i + 12, src.length))}")
                    skipWs()
                    if (i < src.length && src[i] == '(') {
                        // llamada: raise_exception(...) u otras funciones
                        i++
                        val args = ArrayList<Expr>()
                        while (true) {
                            skipWs()
                            if (i < src.length && src[i] == ')') { i++; break }
                            args.add(parse())
                            skipWs()
                            if (i < src.length && src[i] == ',') i++
                        }
                        CallE(name, args)
                    } else VarE(name)
                }
            }
        }

        private fun readIdent(): String? {
            skipWs()
            var j = i
            if (j >= src.length || (!src[j].isLetterOrDigit() && src[j] != '_')) return null
            while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_')) j++
            val s = src.substring(i, j); i = j
            return s
        }

        private fun readNumber(): Expr {
            var j = i
            var isFloat = false
            while (j < src.length && (src[j].isDigit() || src[j] == '.')) {
                if (src[j] == '.') isFloat = true
                j++
            }
            val s = src.substring(i, j); i = j
            return if (isFloat) LitE(s.toDouble()) else LitE(s.toLong())
        }

        private fun readString(): String {
            val quote = src[i]
            i++
            val sb = StringBuilder()
            while (i < src.length) {
                val ch = src[i]
                if (ch == '\\' && i + 1 < src.length) {
                    when (val next = src[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '\\' -> sb.append('\\')
                        '\'' -> sb.append('\'')
                        '"' -> sb.append('"')
                        else -> { sb.append('\\'); sb.append(next) }
                    }
                    i += 2
                    continue
                }
                if (ch == quote) { i++; return sb.toString() }
                sb.append(ch); i++
            }
            throw TemplateException("Cadena sin cerrar en expresión")
        }

        private fun isWordChar(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_')
    }

    private fun parseExpr(src: String): Expr = ExprParser(src).parse()

    // ========================= EJECUCIÓN =========================

    private fun exec(nodes: List<Node>, ctx: MutableMap<String, Any?>, out: StringBuilder) {
        for (n in nodes) {
            when (n) {
                is TextN -> out.append(n.text)
                is OutputN -> out.append(toOutputString(eval(n.expr, ctx)))
                is IfN -> {
                    for ((cond, body) in n.branches) {
                        if (cond == null || truthy(eval(cond, ctx))) {
                            exec(body, ctx, out); break
                        }
                    }
                }
                is ForN -> {
                    val iterable = eval(n.iterExpr, ctx)
                    val items = when (iterable) {
                        is List<*> -> iterable
                        is Map<*, *> -> iterable.keys.toList()
                        else -> throw TemplateException("for sobre tipo no iterable: ${iterable?.javaClass?.simpleName}")
                    }
                    val savedVar = ctx[n.varName]
                    val savedLoop = ctx["loop"]
                    for ((idx, item) in items.withIndex()) {
                        ctx[n.varName] = item
                        ctx["loop"] = mapOf(
                            "index" to (idx + 1).toLong(),
                            "index0" to idx.toLong(),
                            "first" to (idx == 0),
                            "last" to (idx == items.size - 1),
                            "length" to items.size.toLong()
                        )
                        exec(n.body, ctx, out)
                    }
                    if (savedVar !== UNSET) ctx[n.varName] = savedVar else ctx.remove(n.varName)
                    if (savedLoop !== UNSET) ctx["loop"] = savedLoop else ctx.remove("loop")
                }
                is SetN -> ctx[n.target] = eval(n.expr, ctx)
            }
        }
    }

    private val UNSET = Any()

    private fun toOutputString(v: Any?): String = when (v) {
        null -> "None"
        is String -> v
        is Boolean -> v.toString()
        is Long, is Int -> v.toString()
        is Double -> if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is List<*> -> "[" + v.joinToString(", ") { toOutputString(it) } + "]"
        is Map<*, *> -> "{" + v.entries.joinToString(", ") { "${it.key}: ${toOutputString(it.value)}" } + "}"
        else -> v.toString()
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        null, false -> false
        is String -> v.isNotEmpty()
        is List<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        is Long -> v != 0L
        is Int -> v != 0
        is Double -> v != 0.0
        else -> true
    }

    private fun eval(expr: Expr, ctx: Map<String, Any?>): Any? = when (expr) {
        is LitE -> expr.value
        is VarE -> if (ctx.containsKey(expr.name)) ctx[expr.name] else throw TemplateException("Variable no definida: ${expr.name}")
        is BinE -> binOp(expr.op, eval(expr.l, ctx), eval(expr.r, ctx))
        is NotE -> !truthy(eval(expr.e, ctx))
        is NegE -> when (val v = eval(expr.e, ctx)) {
            is Long -> -v
            is Int -> -v
            is Double -> -v
            else -> throw TemplateException("Negación sobre tipo no numérico")
        }
        is IndexE -> indexGet(eval(expr.obj, ctx), eval(expr.idx, ctx))
        is AttrE -> attrGet(eval(expr.obj, ctx), expr.name)
        is TernaryE -> if (truthy(eval(expr.cond, ctx))) eval(expr.then, ctx) else eval(expr.elseE, ctx)
        is CallE -> when (expr.name) {
            "raise_exception" -> throw TemplateException(
                "raise_exception: " + (eval(expr.args.firstOrNull() ?: LitE(""), ctx)?.toString() ?: "")
            )
            "namespace" -> HashMap<String, Any?>()
            else -> throw TemplateException("Función no soportada: ${expr.name}")
        }
        is FilterE -> applyFilter(expr.name, eval(expr.obj, ctx), expr.args.map { eval(it, ctx) }, ctx)
    }

    private fun binOp(op: String, l: Any?, r: Any?): Any? = when (op) {
        "+" -> when {
            l is String -> l + toOutputString(r)
            r is String -> toOutputString(l) + r
            l is Long && r is Long -> l + r
            l is Double || r is Double -> toNum(l) + toNum(r)
            l is Long && r is Int -> l + r
            l is Int && r is Long -> l + r
            l is List<*> && r is List<*> -> l + r
            else -> throw TemplateException("+ sobre tipos: ${l?.javaClass?.simpleName} / ${r?.javaClass?.simpleName}")
        }
        "-" -> toNum(l) - toNum(r)
        "*" -> when {
            l is String && r is Long -> l.repeat(r.toInt().coerceAtLeast(0))
            else -> toNum(l) * toNum(r)
        }
        "/" -> toNum(l) / toNum(r)
        "==" -> valuesEqual(l, r)
        "!=" -> !valuesEqual(l, r)
        "<" -> toNum(l) < toNum(r)
        ">" -> toNum(l) > toNum(r)
        "<=" -> toNum(l) <= toNum(r)
        ">=" -> toNum(l) >= toNum(r)
        "and" -> truthy(l) && truthy(r)
        "or" -> truthy(l) || truthy(r)
        "in" -> contains(r, l)
        "not in" -> !contains(r, l)
        else -> throw TemplateException("Operador no soportado: $op")
    }

    private fun valuesEqual(l: Any?, r: Any?): Boolean = when {
        l is Number && r is Number -> toNum(l) == toNum(r)
        else -> l == r
    }

    private fun toNum(v: Any?): Double = when (v) {
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        is Double -> v
        is String -> v.toDoubleOrNull() ?: throw TemplateException("No numérico: '$v'")
        else -> throw TemplateException("No numérico: ${v?.javaClass?.simpleName}")
    }

    private fun contains(container: Any?, item: Any?): Boolean = when (container) {
        is List<*> -> container.any { valuesEqual(it, item) }
        is String -> container.contains(toOutputString(item))
        is Map<*, *> -> container.containsKey(item)
        else -> throw TemplateException("'in' sobre tipo no contenedor")
    }

    private fun indexGet(obj: Any?, idx: Any?): Any? = when (obj) {
        is List<*> -> {
            val i = (idx as? Long ?: (idx as? Int)?.toLong())
                ?: throw TemplateException("Índice no numérico")
            obj.getOrElse(i.toInt()) { throw TemplateException("Índice fuera de rango: $i") }
        }
        is Map<*, *> -> obj[idx]
        is String -> obj[(idx as Long).toInt()].toString()
        else -> throw TemplateException("Indexación sobre tipo: ${obj?.javaClass?.simpleName}")
    }

    private fun attrGet(obj: Any?, name: String): Any? = when (obj) {
        is Map<*, *> -> obj[name]
        is Map<*, *>? -> null
        else -> when (name) {
            "length" -> (obj as? List<*>)?.size?.toLong() ?: (obj as? String)?.length?.toLong()
            "size" -> (obj as? List<*>)?.size?.toLong()
            else -> throw TemplateException("Atributo no soportado: .$name")
        }
    }

    private fun applyFilter(name: String, value: Any?, args: List<Any?>, ctx: Map<String, Any?>): Any? = when (name) {
        "tojson" -> toJson(value)
        "trim" -> toOutputString(value).trim()
        "lower" -> toOutputString(value).lowercase()
        "upper" -> toOutputString(value).uppercase()
        "length" -> when (value) {
            is List<*> -> value.size.toLong()
            is Map<*, *> -> value.size.toLong()
            is String -> value.length.toLong()
            else -> throw TemplateException("length sobre tipo no contenedor")
        }
        "first" -> (value as? List<*>)?.firstOrNull()
        "last" -> (value as? List<*>)?.lastOrNull()
        "list" -> value
        "string" -> toOutputString(value)
        "int" -> toNum(value).toLong()
        "float" -> toNum(value)
        "join" -> (value as? List<*>)?.joinToString(args.firstOrNull()?.let { toOutputString(it) } ?: "") {
            toOutputString(it)
        } ?: throw TemplateException("join sobre tipo no lista")
        "replace" -> {
            val s = toOutputString(value)
            s.replace(toOutputString(args.getOrNull(0) ?: ""), toOutputString(args.getOrNull(1) ?: ""))
        }
        "startswith" -> toOutputString(value).startsWith(toOutputString(args.getOrNull(0)))
        "endswith" -> toOutputString(value).endsWith(toOutputString(args.getOrNull(0)))
        "selectattr" -> throw TemplateException("Filtro no soportado: selectattr")
        "map" -> throw TemplateException("Filtro no soportado: map")
        else -> throw TemplateException("Filtro no soportado: $name")
    }

    /** JSON compacto con escapes de control (equivalente razonable a tojson). */
    private fun toJson(v: Any?): String = when (v) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (ch in v) when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
            append('"')
        }
        is Boolean -> v.toString()
        is Long, is Int -> v.toString()
        is Double -> v.toString()
        is List<*> -> v.joinToString(",", "[", "]") { toJson(it) }
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { "${toJson(it.key.toString())}:${toJson(it.value)}" }
        else -> toJson(toOutputString(v))
    }
}
