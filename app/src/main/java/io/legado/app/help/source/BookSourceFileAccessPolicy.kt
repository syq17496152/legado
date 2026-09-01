package io.legado.app.help.source

import java.io.File

/**
 * 沙箱内解析结果（P0-S1，对齐 NG BookSourceFileTarget 1:1）
 *
 * @param file canonical 化后的目标文件/目录
 * @param relativePath 相对沙箱根的路径（供 downloadFile 等返回相对路径语义使用）
 */
internal data class BookSourceFileTarget(val file: File, val relativePath: String)

/**
 * 书源文件沙箱访问策略（P0-S1，对齐 NG BookSourceFileAccessPolicy 1:1）
 *
 * 职责：将书源上下文的文件访问严格限制在沙箱根内，防路径穿越/符号链接逃逸。
 * 边界行为：
 * - 空路径或 "/" → SecurityException（防误指沙箱根本身）
 * - 绝对路径且位于沙箱根内 → 直用 canonical 路径放行
 * - 绝对路径位于根外 / 相对路径 ".." 穿越归一后越界 → SecurityException
 * - requireContainedTree 递归校验子树内每个目录（防环），防符号链接/嵌套逃逸
 *
 * 本类不做根选择；根由调用方传入（沙箱根 = externalCache/source/{ns}，
 * 与脚本文件缓存根 cacheDir/bookSourceCache/{ns} 互不相干）。
 * 纯 java.io 实现，JVM 可单测；越界统一抛 SecurityException。
 */
internal object BookSourceFileAccessPolicy {

    /** 沙箱二级目录：externalCache/source/{ns} */
    private const val SOURCE_ROOT_FOLDER = "source"

    /**
     * 解析书源沙箱根目录：cacheRoot/source/{ns}
     */
    fun resolveSourceRoot(cacheRoot: File, sourceUrl: String): File {
        val root = cacheRoot.canonicalFile
        val sourceRoot = File(
            root,
            SOURCE_ROOT_FOLDER + File.separator + BookSourceStorageScope.namespace(sourceUrl)
        ).canonicalFile
        requireStrictChild(root, sourceRoot)
        return sourceRoot
    }

    /**
     * 在沙箱根内解析访问路径，越界抛 SecurityException 不回退
     */
    fun resolvePath(sourceRoot: File, path: String): BookSourceFileTarget {
        val root = sourceRoot.canonicalFile
        if (path.isEmpty() || path == "/") {
            throw SecurityException("书源文件路径为空或指向沙箱根本身")
        }
        val target = if (isAbsolutePathInsideSourceRoot(root, path)) {
            File(path).canonicalFile
        } else {
            File(root, path).canonicalFile
        }
        requireStrictChild(root, target)
        val relativePath = target.path.substring(root.path.length)
        return BookSourceFileTarget(target, relativePath)
    }

    /**
     * 校验单文件位于沙箱根内，返回 canonical 化文件
     */
    fun requireContainedFile(sourceRoot: File, file: File): File {
        val root = sourceRoot.canonicalFile
        val target = file.canonicalFile
        requireStrictChild(root, target)
        return target
    }

    /**
     * 校验目标及其整个子树位于沙箱根内（递归防环），防符号链接/嵌套逃逸
     */
    fun requireContainedTree(sourceRoot: File, file: File) {
        val root = sourceRoot.canonicalFile
        val target = requireContainedFile(root, file)
        if (!target.isDirectory) {
            return
        }
        val visitedDirectories = hashSetOf<File>()
        requireContainedTreeInternal(root, target, visitedDirectories)
    }

    private fun requireContainedTreeInternal(
        root: File,
        directory: File,
        visitedDirectories: MutableSet<File>
    ) {
        if (!visitedDirectories.add(directory)) {
            return
        }
        requireStrictChild(root, directory)
        val children = directory.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                requireContainedTreeInternal(root, child.canonicalFile, visitedDirectories)
            }
        }
    }

    private fun isAbsolutePathInsideSourceRoot(canonicalRoot: File, path: String): Boolean {
        val file = File(path)
        if (!file.isAbsolute) {
            return false
        }
        return file.path.startsWith(canonicalRoot.path + File.separator)
    }

    private fun requireStrictChild(canonicalRoot: File, canonicalTarget: File) {
        val rootPrefix = canonicalRoot.path + File.separator
        if (!canonicalTarget.path.startsWith(rootPrefix)) {
            throw SecurityException("书源文件路径超出缓存目录")
        }
    }
}
