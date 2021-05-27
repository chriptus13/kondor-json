package com.ubertob.kondor.json.parser

import com.ubertob.kondor.json.JsonError
import com.ubertob.kondor.json.JsonOutcome
import com.ubertob.kondor.json.jsonnode.*
import com.ubertob.kondor.outcome.*
import com.ubertob.kondor.outcome.Outcome.Companion.tryOrFail
import java.math.BigDecimal

private inline fun <T> tryParse(
    expected: String,
    actual: KondorToken,
    position: Int,
    path: NodePath,
    f: () -> T
): Outcome<JsonError, T> =
    tryOrFail(f)
        .transformFailure {
            when (it.throwable) {
                is NumberFormatException ->
                    parsingError(expected, actual, position, path, it.msg)
                else ->
                    parsingError(expected, "${it.msg} after $actual", position, path, "Invalid Json")
            }
        }

data class TokensPath(val tokens: TokensStream, val path: NodePath)


fun <T> tryParseBind(
    expected: String,
    tokens: TokensStream,
    path: NodePath,
    f: TokensPath.() -> Outcome<JsonError, T>
): Outcome<JsonError, T> =
    try {
        f(TokensPath(tokens, path))
    } catch (t: NumberFormatException) {
        parsingError(expected, "'${tokens.last()}'", tokens.position(), path, t.message.orEmpty()).asFailure()
    } catch (t: Throwable) {
        parsingError(
            expected, "${t.message.orEmpty()} after ${tokens.last()}", tokens.position(),
            path, "Invalid Json"
        ).asFailure()
    }


private fun parsingError(expected: String, actual: String, position: Int, path: NodePath, details: String) = JsonError(
    path, "at position $position: expected $expected but found $actual - $details"
)

private fun parsingError(
    expected: String,
    actual: KondorToken?,
    position: Int,
    path: NodePath,
    details: String
): JsonError =
    parsingError(expected, "'$actual'", position, path, details)

fun parsingFailure(expected: String, actual: String, position: Int, path: NodePath, details: String) =
    parsingError(expected, actual, position, path, details).asFailure()

fun parsingFailure(expected: String, actual: KondorToken, position: Int, path: NodePath, details: String) =
    parsingError(expected, actual, position, path, details).asFailure()


//todo delete these and just use TokensPath.boolean in NodeKind directly. Same for the others
fun parseJsonNodeBoolean(
    tokens: TokensStream,
    path: NodePath
): JsonOutcome<JsonNodeBoolean> =
    tryParseBind(
        "a Boolean", tokens, path,
        TokensPath::boolean
    )

fun parseJsonNodeNum(
    tokens: TokensStream,
    path: NodePath
): Outcome<JsonError, JsonNodeNumber> =
    tryParseBind(
        "a Number", tokens, path,
        TokensPath::number
    )

fun parseJsonNodeNull(
    tokens: TokensStream,
    path: NodePath
): Outcome<JsonError, JsonNodeNull> =
    tryParseBind(
        "a Null", tokens, path,
        TokensPath::explicitNull
    )

fun parseJsonNodeString(
    tokens: TokensStream,
    path: NodePath
): JsonOutcome<JsonNodeString> =
    tryParseBind(
        "a String", tokens, path,
        surrounded(OpeningQuotes, TokensPath::string, ClosingQuotes)
    )

fun parseJsonNodeArray(
    tokens: TokensStream,
    path: NodePath
): JsonOutcome<JsonNodeArray> =
    tryParseBind(
        "an Array", tokens, path,
        surrounded(
            OpeningBracket, TokensPath::array, ClosingBracket
        )
    )

fun parseJsonNodeObject(
    tokens: TokensStream,
    path: NodePath
): JsonOutcome<JsonNodeObject> =
    tryParseBind(
        "an Object", tokens, path,
        surrounded(
            OpeningCurly, TokensPath::jsonobject, ClosingCurly
        )
    )


typealias JsonParser<T> = TokensPath.() -> JsonOutcome<T>


fun <T> surrounded(openingToken: KondorToken, takeContent: JsonParser<T>, closingToken: KondorToken): JsonParser<T> =
    {
        fun middle(left: KondorToken, middle: T, right: KondorToken) = middle

        ::middle `!` take(openingToken) `*` takeContent() `*` take(closingToken)
    }


fun <T> TokensPath.extractNodesIndexed(f: TokensPath.() -> JsonOutcome<T>?): JsonOutcome<List<T>> =
    naturals().map { f(subNodePath(it)) }
        .takeWhileNotNull()
        .extractList()

private fun TokensPath.subNodePath(nodeNumber: Int) =
    copy(path = NodePathSegment("[$nodeNumber]", path))

fun TokensPath.boolean(): JsonOutcome<JsonNodeBoolean> =
    when (val token = tokens.next()) {
        Value("true") -> true.asSuccess()
        Value("false") -> false.asSuccess()
        else -> parsingFailure("a Boolean", token, tokens.position(), path, "valid values: false, true")
    }.transform { JsonNodeBoolean(it, path) }


fun TokensPath.number(): JsonOutcome<JsonNodeNumber> =
    when (val token = tokens.next()) {
        is Value -> BigDecimal(token.text).asSuccess()
        else -> parsingFailure("a Number", token, tokens.position(), path, "not a valid number")
    }.transform { JsonNodeNumber(it, path) }


fun TokensPath.string(allowEmpty: Boolean = true): JsonOutcome<JsonNodeString> =
    when (val token = tokens.peek()) {
        is Value -> token.text.asSuccess().also { tokens.next() }
        else -> if (allowEmpty) "".asSuccess() else
            parsingFailure("a non empty String", token, tokens.position(), path, "invalid Json")
    }.transform { JsonNodeString(it, path) }


fun TokensPath.array(): JsonOutcome<JsonNodeArray> =
    list { parseNewNode(tokens, path) }
        .transform { JsonNodeArray(it, path) }


fun TokensPath.jsonobject(): JsonOutcome<JsonNodeObject> =
    list { keyValue { parseNewNode(tokens, path) ?: parsingFailure("sm", "nothing", tokens.position(), path, "!!!") } }
        .transform { JsonNodeObject(it.toMap(), path) }


fun <T> TokensPath.keyValue(contentParser: TokensPath.() -> JsonOutcome<T>): JsonOutcome<Pair<String, T>>? =
    parseNewNode(tokens, path.parent())
        ?.bind { takeKey(it) }
        ?.bind { key ->
            fun kv(sep: KondorToken, node: T): Pair<String, T> = key to node

            ::kv `!` take(Colon) `*` contentParser(copy(path = NodePathSegment(key, path.parent())))
        }

private fun TokensPath.takeKey(it: JsonNode): Outcome<JsonError, String> =
    when (it) {
        is JsonNodeString -> it.text.asSuccess()
        else -> parsingFailure("not a key", it.toString(), tokens.position(), path, "invalid Json")
    }

fun <T> TokensPath.list(contentParser: TokensPath.() -> JsonOutcome<T>?): JsonOutcome<List<T>> =
    extractNodesIndexed {
        contentParser()?.bindAndIgnore {
            takeOrNull(Comma) ?: null.asSuccess()
        }
    }

private fun TokensPath.explicitNull(): JsonOutcome<JsonNodeNull> =
    when (val token = tokens.next()) {
        Value("null") -> Unit.asSuccess()
        else -> parsingFailure("a Null", token, tokens.position(), path, "valid values: null")
    }.transform { JsonNodeNull(path) }


private fun TokensPath.take(kondorToken: KondorToken): JsonOutcome<KondorToken> =
    tokens.next().let { currToken ->
        if (currToken != kondorToken)
            parsingFailure("'$kondorToken'", currToken, tokens.position(), path, "invalid Json")
        else
            currToken.asSuccess()
    }

private fun TokensPath.takeOrNull(kondorToken: KondorToken): JsonOutcome<KondorToken>? =
    tokens.peek().let { currToken ->
        if (currToken != kondorToken)
            null
        else
            take(kondorToken)
    }

//---

fun parseNewNode(tokens: TokensStream, path: NodePath): JsonOutcome<JsonNode>? =
    when (val t = tokens.peek()) {
        Value("null") -> parseJsonNodeNull(tokens, path)
        Value("false"), Value("true") -> parseJsonNodeBoolean(tokens, path)
        is Value -> parseJsonNodeNum(tokens, path)
        OpeningQuotes -> parseJsonNodeString(tokens, path)
        OpeningBracket -> parseJsonNodeArray(tokens, path)
        OpeningCurly -> parseJsonNodeObject(tokens, path)
        ClosingBracket, ClosingCurly -> null //no more nodes
        ClosingQuotes, Comma, Colon -> parsingError(
            "a new node", tokens.last(),
            tokens.position(), path, "'$t' in wrong position"
        ).asFailure()
    }



inline fun <T, U, E : OutcomeError> Outcome<E, T>.bindAndIgnore(f: (T) -> Outcome<E, U>): Outcome<E, T> =
    when (this) {
        is Failure -> this
        is Success -> f(value).transform { value }
    }

@Suppress("UNCHECKED_CAST")
fun <T : Any> Sequence<T?>.takeWhileNotNull(): Sequence<T> = takeWhile { it != null } as Sequence<T>


fun naturals(): Sequence<Int> = generateSequence(0) { it + 1 }