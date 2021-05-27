package com.ubertob.kondor.json.jsonnode

sealed class NodePath()
object NodePathRoot : NodePath()
data class NodePathSegment(val nodeName: String, val parent: NodePath) : NodePath()

private val ROOT_NODE = "[root]"

fun NodePath.getPath(): String =
    when (this) {
        NodePathRoot -> ROOT_NODE
        is NodePathSegment -> parent.getPath() append nodeName
    }


fun NodePath.parent(): NodePath =
    when (this) {
        NodePathRoot -> this
        is NodePathSegment -> parent
    }

private infix fun String.append(next: String): String = if (this == ROOT_NODE) "/$next" else "$this/$next"
