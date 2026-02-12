package org.winand

import io.qt.core.QAbstractItemModel
import io.qt.core.QFileInfo
import io.qt.core.QModelIndex
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.gui.QAbstractFileIconProvider
import io.qt.gui.QAction
import io.qt.gui.QIcon
import io.qt.widgets.QApplication
import io.qt.widgets.QFileDialog
import io.qt.widgets.QFileIconProvider
import io.qt.widgets.QLineEdit
import io.qt.widgets.QMainWindow
import io.qt.widgets.QStyle
import io.qt.widgets.QTreeView
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.system.exitProcess

val Path.parents: List<Path> // Path extension
    get() = generateSequence(this.parent) { it.parent }.toList()

val INVALID_INDEX by lazy { QModelIndex() } // cannot be created before Qt paths are set in main()

enum class Column(val id: Int) {
    Name(0), Size(1), Modified(2), Permissions(3), Owner(4)
}

private data class Sorting (
    val column: Int,
    val order: Qt.SortOrder,
)

private data class FSItem (
    val perms: String,
    val owner: String,
    val group: String,
    val size: Long,
    val dtMod: String,
    val path: Path,
)

private class FSNode(
    val path: Path,
    val size: Long = 0,
    val modified: String = "",
    val permissions: String = "",
    val owner: String = "",
    val parent: FSNode? = null
) {
    companion object {
        private val idPool = AtomicLong(0)
    }
    val id: Long = idPool.getAndIncrement()
    val name = path.name
    val nameLower = name.lowercase()
    val isDir = permissions.isEmpty() || permissions.startsWith("d")

    // Shadow lists for performance
    val allChildren = mutableListOf<FSNode>()
    val visibleChildren = mutableListOf<FSNode>()

    var currentSortState: Sorting? = null

    fun appendChild(child: FSNode) {
        allChildren.add(child)
        visibleChildren.add(child)
    }

    fun sort(sorting: Sorting) {
        if (currentSortState == sorting)
            return
        val reverse = sorting.order == Qt.SortOrder.DescendingOrder

        fun sortKey(node: FSNode): Pair<Boolean, Comparable<*>> {
            // Rule 1 & 2: Folders always first
            return when (sorting.column) {
                Column.Name.id -> Pair(!node.isDir, node.nameLower)
                Column.Size.id -> Pair(!node.isDir, if (node.isDir) node.nameLower else node.size)
                Column.Modified.id -> Pair(false, node.modified)
                Column.Permissions.id -> Pair(false, node.permissions)
                Column.Owner.id -> Pair(false, node.owner)
                else -> throw IllegalArgumentException("Unknown column index ${sorting.column}")
            }
        }
        visibleChildren.sortWith(if (reverse)
            compareBy<FSNode>{ sortKey(it).first }
                .thenByDescending { sortKey(it).second }
        else
            compareBy<FSNode>{ sortKey(it).first }
                .thenBy { sortKey(it).second }
        )
        currentSortState = sorting
    }
}

private class FSModel(val rootNode: FSNode) : QAbstractItemModel() {
    val headers = listOf("Name", "Size", "Modified", "Permissions", "Owner")
    val iconProvider = QFileIconProvider()
    val dirIcon: QIcon = iconProvider.icon(QAbstractFileIconProvider.IconType.Folder)
    val fileIcon: QIcon = iconProvider.icon(QAbstractFileIconProvider.IconType.File)
    val extIcons = mutableMapOf<String, QIcon>()
    val fsNodeIndex = mutableMapOf(Pair(rootNode.id, rootNode))
    var sorting: Sorting? = null

    override fun index(row: Int, column: Int, parent: QModelIndex): QModelIndex {
//        val pItemId = if (parent.isValid) parent.internalId() else 0
        val pItem = fsNodeIndex[parent.internalId()]!!
        return createIndex(row, column, pItem.visibleChildren[row].id)
    }

    override fun parent(index: QModelIndex): QModelIndex {
        if (!index.isValid)
            return INVALID_INDEX
        val childItemId = index.internalId()
        val pItem = fsNodeIndex[childItemId]!!.parent
        if (pItem == rootNode || pItem == null)
            return INVALID_INDEX
        // Find the row of the parent in its own parent's visible list
        val grandparent = pItem.parent ?: rootNode
        return createIndex(grandparent.visibleChildren.indexOf(pItem), 0, pItem.id)
    }

    override fun rowCount(parent: QModelIndex): Int {
        val pItem = fsNodeIndex[parent.internalId()]!!
        return pItem.visibleChildren.size
    }

    override fun columnCount(parent: QModelIndex): Int =
        headers.size

    override fun data(index: QModelIndex, role: Int): Any? {
        if (!index.isValid)
            return null
        val item = fsNodeIndex[index.internalId()]!!
        val col = index.column()

        if (role == Qt.ItemDataRole.DisplayRole) {
            if (col == Column.Name.id)
                return item.name
            if (col == Column.Size.id)
                return if (!item.isDir) item.size else ""
            if (col == Column.Modified.id)
                return item.modified
            if (col == Column.Permissions.id)
                return item.permissions
            if (col == Column.Owner.id)
                return item.owner
        }
        if (role == Qt.ItemDataRole.DecorationRole && col == Column.Name.id) {
            if (item.isDir)
                return dirIcon
            val ext = item.path.extension
            if (ext.isEmpty())
                return fileIcon
            if (ext in extIcons)
                return extIcons[ext]
            val icon = iconProvider.icon(QFileInfo(item.name))
            extIcons[ext] = icon
            return icon
        }
        return null
    }

    override fun headerData(section: Int, orientation: Qt.Orientation, role: Int): Any? {
        if (orientation == Qt.Orientation.Horizontal && role == Qt.ItemDataRole.DisplayRole)
            return headers[section]
        return null
    }

    override fun sort(column: Int, order: Qt.SortOrder) {
        sorting = Sorting(column, order)
    }
}

class HdfsExplorer : QMainWindow() {
    private val filterTimer = QTimer()
    private val searchBar = QLineEdit()
    private val treeView = QTreeView()
    private var model: FSModel? = null

    init {
        windowTitle = "HDFS Explorer"
        resize(1000, 700)

        // UI Setup
        setupUi()

        // Debounce timer
        filterTimer.singleShot = true
        filterTimer.timeout.connect(this::runFilter)
    }

    private fun setupUi() {
        val toolbar = addToolBar("Main")!!
        val openAct = QAction(
            style()!!.standardIcon(
                QStyle.StandardPixmap.SP_FileDialogNewFolder,
            ),
            "Open/append file list",
            this,
        )
        openAct.triggered.connect(this::loadHdfsFile)
        toolbar.addAction(openAct)
        val actClose = QAction(
            style()!!.standardIcon(
                QStyle.StandardPixmap.SP_LineEditClearButton,
            ),
            "Clear file tree",
            this,
        )
        actClose.triggered.connect(this::clearTree)
        toolbar.addAction(actClose)

        val central = QWidget()
        setCentralWidget(central)
        val layout = QVBoxLayout(central)

        searchBar.placeholderText = "Filter"
        searchBar.textChanged.connect({ -> this.filterTimer.start(300) })
        layout.addWidget(searchBar)

        treeView.sortingEnabled = true
        treeView.uniformRowHeights = true
        treeView.header()!!.setSortIndicator(0, Qt.SortOrder.AscendingOrder)
        treeView.expanded.connect(this::onNodeExpanded)
        treeView.header()!!.sectionClicked.connect(this::onSectionClicked)
        layout.addWidget(treeView)
    }

    private fun runFilter() {
        // model.update_filter(self.search_bar.text())
    }

    private fun parseFilelistFile(filepath: String): List<FSItem> {
        val pattern = (
                "^(?<perms>[drwx-]+)\\s+" +
                        "[\\d-]+\\s+" + // replicas
                        "(?<owner>\\S+)\\s+(?<group>\\S+)\\s+" +
                        "(?<size>\\d+)\\s+" +
                        "(?<dtMod>\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2})\\s+" +
                        "(?<path>.+)$"
                ).toRegex()
        return File(filepath).useLines { lines ->
            lines.mapNotNull {
                pattern.matchEntire(it)
            }.map {
                FSItem(
                    it.groups["perms"]!!.value, it.groups["owner"]!!.value, it.groups["group"]!!.value,
                    it.groups["size"]!!.value.toLong(), it.groups["dtMod"]!!.value,
                    Path.of(it.groups["path"]!!.value),
                )
            }.toList()
        }
    }

    /**
     * Load a new file list from file.
     */
    private fun loadHdfsFile(filepath: String? = null) {
        val filepath = filepath ?: QFileDialog.getOpenFileName(
            this, "Open HDFS Log", "", "Text (*.txt)"
        )?.result
        if (filepath == null)
            return


        val root = model?.rootNode ?: FSNode(Path.of(""))
        val nodeMap = mutableMapOf(Pair(Path.of("/"), root))

        if (model == null) {
            model = FSModel(root)
            treeView.setModel(model)
        }

        parseFilelistFile(filepath).forEach { i ->
            val path = i.path
            var cur = root
            var curChildren = childrenIndices(INVALID_INDEX)
            for (parent in path.parents.asReversed()) {
                if (parent !in nodeMap) {
                    val found = curChildren.find { model!!.fsNodeIndex[it.internalId()]!!.path == parent }
                    val node: FSNode
                    if (found != null) {
                        node = model!!.fsNodeIndex[found.internalId()]!!
                        curChildren = childrenIndices(found)
                    } else {
                        node = FSNode(parent, parent=cur)
                        model!!.fsNodeIndex[node.id] = node
                        cur.appendChild(node)
                    }
                    nodeMap[parent] = node
                }
                cur = nodeMap[parent]!!
            }
            val node = FSNode(path, i.size, i.dtMod, i.perms, "${i.owner}:${i.group}", parent=cur)
            model!!.fsNodeIndex[node.id] = node
            nodeMap[path] = node
            cur.appendChild(node)
        }
        println("NODES CREATED")

         // is it the signal required after adding data?
        model!!.layoutChanged.emit()
        treeView.setColumnWidth(0, 350)

        println("EXPAND")
        var current = INVALID_INDEX
        var children = childrenIndices(current).toList()
        while (children.isNotEmpty()) {
            treeView.setExpanded(current, true)
            if (children.size != 1)
                break
            current = children.first()
            children = childrenIndices(current).toList()
        }

        println("SORT")
        // TODO:

        println("LOADED")
    }

    private fun clearTree() {
        treeView.setModel(null)
        model = null
    }

    private fun childrenIndices(index: QModelIndex): Sequence<QModelIndex> = sequence {
        if (model == null)
            return@sequence
        for (i in 0 until model!!.rowCount(index))
            yield(model!!.index(i, 0, index))
    }

    private fun sortExpanded(index: QModelIndex = INVALID_INDEX) {
        if (index.isValid) {
            val node = model!!.fsNodeIndex[index.internalId()]!!
            if (!node.isDir)
                return
            if (model!!.rowCount(index) > 1) {
                println("SORT ${node.path}")
                node.sort(model!!.sorting!!)
            }
        }
        for (childIndex in childrenIndices(index))
            if (treeView.isExpanded(childIndex))
                sortExpanded(childIndex)
    }

    /**
     * Sort folder and its descendants when it is expanded.
     */
    private fun onNodeExpanded(index: QModelIndex) {
        model!!.layoutAboutToBeChanged.emit()
        sortExpanded(index)
        model!!.layoutChanged.emit()
    }

    private fun onSectionClicked() {
        model!!.layoutAboutToBeChanged.emit()
        val expansionState = saveExpansionState().toMutableSet()
        sortExpanded()
        restoreExpansionState(expansionState)
        model!!.layoutChanged.emit()
    }

    /**
     * Recursively finds all expanded nodes.
     */
    private fun saveExpansionState(index: QModelIndex = INVALID_INDEX): Sequence<FSNode> =
        childrenIndices(index)
            .filter(treeView::isExpanded)
            .flatMap {
                sequenceOf(model!!.fsNodeIndex[it.internalId()]!!) + saveExpansionState(it)
            }

    /**
     * Recursively re-expands nodes that were previously expanded.
     */
    private fun restoreExpansionState(expandedNodes: MutableSet<FSNode>, index: QModelIndex = INVALID_INDEX): Unit =
        childrenIndices(index).forEach {
            val node = model!!.fsNodeIndex[it.internalId()]!!
            if (node in expandedNodes) {
                treeView.setExpanded(it, true)
                expandedNodes.remove(node)
                if (expandedNodes.isNotEmpty())
                    restoreExpansionState(expandedNodes, it)
            } else if (treeView.isExpanded(it))
                treeView.setExpanded(it, false)
        }
}

fun main() {
    // https://www.qtjambi.io/doc/md.jsp?md=Options
    System.setProperty("io.qt.library-path-override", "qt/bin") // -Djava.library.path
    System.setProperty("io.qt.pluginpath", "qt/bin/plugins") // QT_PLUGIN_PATH

    QApplication.initialize(arrayOf())
    val window = HdfsExplorer()
    window.show()
    exitProcess(QApplication.exec())
}
