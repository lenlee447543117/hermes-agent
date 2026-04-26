package com.ailaohu.service.accessibility

import android.view.accessibility.AccessibilityNodeInfo

class NodeFinder(private val rootNode: AccessibilityNodeInfo) {

    fun findNode(targetText: String): AccessibilityNodeInfo? {
        findNodeByTextExact(targetText)?.let { return it }
        findNodeByTextContains(targetText)?.let { return it }
        findNodeByContentDescription(targetText)?.let { return it }
        return null
    }

    fun findNodeByTextExact(text: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            node.text?.toString()?.let { if (it == text) return node }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    fun findNodeByTextContains(text: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            node.text?.toString()?.let { if (it.contains(text)) return node }
            node.contentDescription?.toString()?.let { if (it.contains(text)) return node }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    fun findNodeByContentDescription(desc: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            node.contentDescription?.toString()?.let { if (it.contains(desc)) return node }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    fun getNodeCenter(node: AccessibilityNodeInfo): Pair<Float, Float>? {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        return Pair((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
    }
}
