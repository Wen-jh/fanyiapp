package com.wenjh.fanyiapp

import java.util.Locale

object OfflineEnglishChineseTranslator {
    data class Result(
        val text: String,
        val usedBuiltinPhrase: Boolean,
        val usedWordFallback: Boolean
    )

    private val phraseDictionary = linkedMapOf(
        "sign in with google" to "使用 Google 登录",
        "sign in" to "登录",
        "log in" to "登录",
        "open settings" to "打开设置",
        "network error" to "网络 错误",
        "try again" to "重试",
        "terms of service" to "服务条款",
        "privacy policy" to "隐私政策",
        "create account" to "创建账号",
        "forgot password" to "忘记密码",
        "pull to refresh" to "下拉刷新",
        "loading" to "加载中",
        "continue" to "继续",
        "cancel" to "取消",
        "confirm" to "确认",
        "next" to "下一步",
        "back" to "返回",
        "done" to "完成",
        "submit" to "提交",
        "save" to "保存",
        "share" to "分享",
        "delete" to "删除",
        "edit" to "编辑",
        "home" to "首页",
        "profile" to "个人资料",
        "search" to "搜索",
        "settings" to "设置"
    )

    private val wordDictionary = mapOf(
        "sign" to "登录",
        "in" to "",
        "with" to "使用",
        "google" to "Google",
        "network" to "网络",
        "error" to "错误",
        "episode" to "第",
        "settings" to "设置",
        "setting" to "设置",
        "open" to "打开",
        "close" to "关闭",
        "menu" to "菜单",
        "refresh" to "刷新",
        "retry" to "重试",
        "loading" to "加载中",
        "login" to "登录",
        "logout" to "退出登录",
        "username" to "用户名",
        "password" to "密码",
        "email" to "邮箱",
        "phone" to "手机",
        "profile" to "个人资料",
        "photo" to "图片",
        "camera" to "相机",
        "allow" to "允许",
        "deny" to "拒绝",
        "notification" to "通知",
        "notifications" to "通知",
        "update" to "更新",
        "install" to "安装",
        "download" to "下载",
        "upload" to "上传",
        "successful" to "成功",
        "success" to "成功",
        "failed" to "失败",
        "failure" to "失败",
        "please" to "请",
        "tap" to "点击",
        "continue" to "继续",
        "cancel" to "取消",
        "confirm" to "确认",
        "next" to "下一步",
        "back" to "返回",
        "done" to "完成",
        "submit" to "提交",
        "save" to "保存",
        "share" to "分享",
        "delete" to "删除",
        "edit" to "编辑",
        "home" to "首页",
        "search" to "搜索",
        "page" to "页",
        "version" to "版本"
    )

    private val tokenRegex = Regex("[A-Za-z']+|\\d+|[^A-Za-z\\d\\s]")

    fun translate(input: String): Result {
        val normalizedInput = input.trim()
        if (normalizedInput.isBlank()) {
            return Result(text = "", usedBuiltinPhrase = false, usedWordFallback = false)
        }

        val phraseKey = normalizedInput
            .lowercase(Locale.US)
            .replace(Regex("\\s+"), " ")
        phraseDictionary[phraseKey]?.let { phrase ->
            return Result(text = phrase, usedBuiltinPhrase = true, usedWordFallback = false)
        }

        val translatedLines = normalizedInput.lines().map { translateLine(it) }
        val merged = translatedLines.joinToString("\n").trim()
        return Result(
            text = merged,
            usedBuiltinPhrase = false,
            usedWordFallback = merged.isNotBlank()
        )
    }

    private fun translateLine(line: String): String {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return ""

        phraseDictionary[trimmed.lowercase(Locale.US)]?.let { return it }

        val tokens = tokenRegex.findAll(trimmed).map { it.value }.toList()
        val output = mutableListOf<String>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            val lower = token.lowercase(Locale.US)

            if (lower == "episode" && index + 1 < tokens.size && tokens[index + 1].all(Char::isDigit)) {
                output += "第"
                output += tokens[index + 1]
                output += "集"
                index += 2
                continue
            }

            val mapped = wordDictionary[lower]
            when {
                mapped != null && mapped.isNotBlank() -> output += mapped
                mapped == "" -> Unit
                token.all(Char::isDigit) -> output += token
                token.length == 1 && !token[0].isLetterOrDigit() -> output += token
                else -> output += token
            }
            index += 1
        }

        return output.joinToString(" ")
            .replace(" ,", ",")
            .replace(" .", ".")
            .replace(" !", "!")
            .replace(" ?", "?")
            .replace(" :", ":")
            .replace(" ;", ";")
            .replace(" ( ", "(")
            .replace(" )", ")")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
