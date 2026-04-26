package com.ailaohu.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 自动驾驶无障碍服务
 * 负责执行层的核心功能，通过模拟用户操作实现跨应用自动化控制
 */
class AutoPilotService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoPilot"
        
        @Volatile
        var instance: AutoPilotService? = null
            private set

        /**
         * 检查服务是否正在运行
         * @return 服务运行状态
         */
        fun isRunning(): Boolean = instance != null

        /**
         * 执行点击操作（静态方法）
         * @param x 归一化X坐标（0-1）
         * @param y 归一化Y坐标（0-1）
         * @return 操作是否成功
         */
        fun performClick(x: Float, y: Float): Boolean {
            return instance?.clickAt(x, y) ?: false
        }

        /**
         * 执行滑动操作（静态方法）
         * @param startX 起点归一化X坐标
         * @param startY 起点归一化Y坐标
         * @param endX 终点归一化X坐标
         * @param endY 终点归一化Y坐标
         * @param duration 滑动时长（毫秒），默认500ms
         * @return 操作是否成功
         */
        fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 500): Boolean {
            return instance?.swipe(startX, startY, endX, endY, duration) ?: false
        }

        /**
         * 执行长按操作（静态方法）
         * @param x 归一化X坐标
         * @param y 归一化Y坐标
         * @param duration 长按时长（毫秒），默认500ms
         * @return 操作是否成功
         */
        fun performLongClick(x: Float, y: Float, duration: Long = 500): Boolean {
            return instance?.longClickAt(x, y, duration) ?: false
        }

        /**
         * 执行滚动操作（静态方法）
         * @param direction 滚动方向：up/down/left/right
         * @return 操作是否成功
         */
        fun performScroll(direction: String): Boolean {
            return instance?.scroll(direction) ?: false
        }

        /**
         * 执行文本输入操作（静态方法）
         * @param text 要输入的文本内容
         * @return 操作是否成功
         */
        fun performTypeText(text: String): Boolean {
            return instance?.typeText(text) ?: false
        }

        /**
         * 执行返回操作（静态方法）
         * @return 操作是否成功
         */
        fun performBack(): Boolean {
            return instance?.goBack() ?: false
        }

        /**
         * 执行返回主页操作（静态方法）
         * @return 操作是否成功
         */
        fun performHome(): Boolean {
            return instance?.goHome() ?: false
        }

        /**
         * 执行打开通知栏操作（静态方法）
         * @return 操作是否成功
         */
        fun performNotifications(): Boolean {
            return instance?.openNotifications() ?: false
        }

        /**
         * 获取当前屏幕信息（静态方法）
         * @return 屏幕节点信息的文本描述
         */
        fun getScreenInfo(): String? {
            return instance?.captureScreenInfo()
        }
    }

    /**
     * 服务连接回调
     * 当无障碍服务成功连接时调用
     */
    override fun onServiceConnected() {
        instance = this
        Log.d(TAG, "无障碍服务已连接")
    }

    /**
     * 无障碍事件回调
     * 监听系统的无障碍事件，如窗口变化、点击等
     * @param event 无障碍事件对象
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            when (it.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    Log.d(TAG, "窗口变化: ${it.packageName}")
                }
            }
        }
    }

    /**
     * 服务中断回调
     * 当无障碍服务被系统中断时调用
     */
    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
        instance = null
    }

    /**
     * 服务解绑回调
     * 当无障碍服务被解绑时调用
     * @param intent 解绑意图
     * @return 是否允许重新绑定
     */
    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "无障碍服务已卸载/断开")
        instance = null
        return super.onUnbind(intent)
    }

    /**
     * 执行点击操作
     * @param relativeX 归一化X坐标（0-1）
     * @param relativeY 归一化Y坐标（0-1）
     * @return 操作是否成功
     */
    private fun clickAt(relativeX: Float, relativeY: Float): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()
        val absoluteX = relativeX * screenWidth
        val absoluteY = relativeY * screenHeight

        val path = Path().apply { moveTo(absoluteX, absoluteY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        val result = dispatchGesture(gesture, null, null)
        Log.d(TAG, "点击: ($relativeX, $relativeY) → ($absoluteX, $absoluteY) 结果=$result")
        return result
    }

    /**
     * 执行长按操作
     * @param relativeX 归一化X坐标
     * @param relativeY 归一化Y坐标
     * @param duration 长按时长（毫秒）
     * @return 操作是否成功
     */
    private fun longClickAt(relativeX: Float, relativeY: Float, duration: Long): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()
        val absoluteX = relativeX * screenWidth
        val absoluteY = relativeY * screenHeight

        val path = Path().apply { moveTo(absoluteX, absoluteY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        val result = dispatchGesture(gesture, null, null)
        Log.d(TAG, "长按: ($relativeX, $relativeY) 时长=${duration}ms 结果=$result")
        return result
    }

    /**
     * 执行滑动操作
     * @param startX 起点归一化X坐标
     * @param startY 起点归一化Y坐标
     * @param endX 终点归一化X坐标
     * @param endY 终点归一化Y坐标
     * @param duration 滑动时长（毫秒）
     * @return 操作是否成功
     */
    private fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        val path = Path().apply {
            moveTo(startX * screenWidth, startY * screenHeight)
            lineTo(endX * screenWidth, endY * screenHeight)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        val result = dispatchGesture(gesture, null, null)
        Log.d(TAG, "滑动: ($startX,$startY)→($endX,$endY) 时长=${duration}ms 结果=$result")
        return result
    }

    /**
     * 执行滚动操作
     * @param direction 滚动方向：up/down/left/right
     * @return 操作是否成功
     */
    private fun scroll(direction: String): Boolean {
        val centerX = 0.5f
        return when (direction) {
            "up" -> swipe(centerX, 0.7f, centerX, 0.3f, 500)
            "down" -> swipe(centerX, 0.3f, centerX, 0.7f, 500)
            "left" -> swipe(0.8f, 0.5f, 0.2f, 0.5f, 500)
            "right" -> swipe(0.2f, 0.5f, 0.8f, 0.5f, 500)
            else -> false
        }
    }

    /**
     * 执行文本输入操作
     * 通过查找可编辑节点并设置文本实现输入
     * @param text 要输入的文本内容
     * @return 操作是否成功
     */
    private fun typeText(text: String): Boolean {
        try {
            val rootNode = rootInActiveWindow ?: return false
            val focusNode = findFocusNode(rootNode)
            if (focusNode != null) {
                val arguments = android.os.Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                val result = focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                Log.d(TAG, "输入文本: '$text' 结果=$result")
                focusNode.recycle()
                return result
            } else {
                Log.w(TAG, "未找到可输入的焦点节点")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "输入文本失败", e)
            return false
        }
    }

    /**
     * 递归查找可编辑的焦点节点
     * @param node 当前遍历的节点
     * @return 找到的可编辑节点，如果未找到则返回null
     */
    private fun findFocusNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusNode(child)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    /**
     * 执行返回操作
     * @return 操作是否成功
     */
    private fun goBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * 执行返回主页操作
     * @return 操作是否成功
     */
    private fun goHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * 执行打开通知栏操作
     * @return 操作是否成功
     */
    private fun openNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        } else false
    }

    /**
     * 捕获并格式化当前屏幕信息
     * 遍历整个视图节点树，提取有用的UI信息
     * @return 屏幕信息的文本描述
     */
    private fun captureScreenInfo(): String {
        try {
            val rootNode = rootInActiveWindow ?: return "无法获取屏幕信息"
            val info = StringBuilder()
            traverseNode(rootNode, info, 0)
            return info.toString()
        } catch (e: Exception) {
            Log.e(TAG, "获取屏幕信息失败", e)
            return "获取屏幕信息失败"
        }
    }

    /**
     * 递归遍历视图节点树
     * 提取节点的文本、描述、点击状态和边界信息
     * @param node 当前遍历的节点
     * @param info 用于存储信息的StringBuilder
     * @param depth 当前遍历的深度（用于缩进）
     */
    private fun traverseNode(node: AccessibilityNodeInfo, info: StringBuilder, depth: Int) {
        if (depth > 8) return
        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString()?.substringAfterLast(".") ?: ""
        val clickable = node.isClickable
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        if (text.isNotEmpty() || contentDesc.isNotEmpty() || clickable) {
            info.append("$indent$className")
            if (text.isNotEmpty()) info.append(" text=\"$text\"")
            if (contentDesc.isNotEmpty()) info.append(" desc=\"$contentDesc\"")
            if (clickable) info.append(" [clickable]")
            info.append(" bounds=$bounds\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, info, depth + 1)
            child.recycle()
        }
    }
}
