import io.qt.core.{QAbstractItemModel, QFileInfo, QModelIndex, QTimer, Qt}
import io.qt.gui.{QAbstractFileIconProvider, QAction, QIcon}
import io.qt.widgets.*

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.io.Source
import scala.util.control.Breaks.break


extension (path: Path) {
  def parents: Iterator[Path] =
    Iterator.iterate(path.getParent)(_.getParent).takeWhile(_ != null)
}

lazy val INVALID_INDEX = QModelIndex() // cannot be created before Qt paths are set in main()

enum Column {
  case Name, Size, Modified, Permissions, Owner
}

private case class Sorting (
  column: Int,
  order: Qt.SortOrder,
)

private case class FSItem (
  perms: String,
  owner: String,
  group: String,
  size: Long,
  dtMod: String,
  path: Path,
)

private class FSNode(
  val path: Path,
  val size: Long = 0,
  val modified: String = "",
  val permissions: String = "",
  val owner: String = "",
  val parent: Option[FSNode] = None,
) {
  val id: Long = FSNode.idPool.getAndIncrement
  val name: String = path.getFileName.toString
  private val nameLower = name.toLowerCase
  val isDir: Boolean = permissions.isEmpty || permissions.startsWith("d")

  private val allChildren = mutable.Buffer[FSNode]()
  val visibleChildren = mutable.Buffer[FSNode]()

  var currentSortState: Option[Sorting] = None

  def appendChild(child: FSNode): Unit = {
    allChildren += child
    visibleChildren += child
  }
}

private object FSNode {
  private val idPool = AtomicLong(0)
}

private class FSModel(val rootNode: FSNode) extends QAbstractItemModel {
  private val headers = Seq("Name", "Size", "Modified", "Permissions", "Owner")
  private val iconProvider = QFileIconProvider()
  private val dirIcon = iconProvider.icon(QAbstractFileIconProvider.IconType.Folder)
  private val fileIcon = iconProvider.icon(QAbstractFileIconProvider.IconType.File)
  private val extIcons = mutable.Map[String, QIcon]()
  val fsNodeIndex = mutable.Map(rootNode.id -> rootNode)
  private var sorting: Option[Sorting] = None

  override def index(row: Int, column: Int, parent: QModelIndex): QModelIndex = {
    val pItem = fsNodeIndex(parent.internalId)
    createIndex(row, column, pItem.visibleChildren(row).id)
  }

  override def parent(child: QModelIndex): QModelIndex =
    if (!child.isValid)
      INVALID_INDEX
    else fsNodeIndex(child.internalId).parent match {
      case None | Some(`rootNode`) => INVALID_INDEX
      case Some(pItem) => // Find the row of the parent in its own parent's visible list
        val grandparent = pItem.parent.getOrElse(rootNode)
        createIndex(grandparent.visibleChildren.indexOf(pItem), 0, pItem.id)
    }

  override def rowCount(parent: QModelIndex): Int =
    fsNodeIndex(parent.internalId).visibleChildren.size

  override def columnCount(parent: QModelIndex): Int =
    headers.size

  override def data(index: QModelIndex, role: Int): AnyRef = {
    if (!index.isValid)
      return null
    val item = fsNodeIndex(index.internalId)
    val col = Column.fromOrdinal(index.column)

    role match {
      case Qt.ItemDataRole.DisplayRole =>
        col match {
          case Column.Name =>
            item.name
          case Column.Size =>
            if (!item.isDir) Long.box(item.size) else ""
          case Column.Modified =>
            item.modified
          case Column.Permissions =>
            item.permissions
          case Column.Owner =>
            item.owner
        }
      case Qt.ItemDataRole.DecorationRole if col == Column.Name =>
        if (item.isDir)
          dirIcon
        else {
          val ext = { // NOTE: ".bashrc" -> ""
            val extPos = item.name.lastIndexOf('.')
            if (extPos > 0) item.name.substring(extPos + 1) else ""
          }
          if (ext.isEmpty)
            fileIcon
          else
            extIcons.getOrElseUpdate(ext, iconProvider.icon(QFileInfo(item.name)))
        }
      case _ =>
        null
    }
  }

  override def headerData(section: Int, orientation: Qt.Orientation, role: Int): AnyRef =
    if (orientation == Qt.Orientation.Horizontal && role == Qt.ItemDataRole.DisplayRole)
      headers(section)
    else null

  override def sort(column: Int, order: Qt.SortOrder): Unit =
    sorting = Option(Sorting(column, order))
}

class HdfsExplorer extends QMainWindow {
  private val filterTimer = QTimer()
  private val searchBar = QLineEdit()
  private val treeView = QTreeView()
  private var model: Option[FSModel] = None

  setWindowTitle("HDFS Explorer")
  resize(1000, 700)

  // UI setup
  setupUi()
//  val txt = QTextEdit()
//
//  setWindowTitle("Scala Jambi Test")
//  val btn = QPushButton("Hello")
//  btn.clicked.connect(() => btnClicked())
//  val btnOpen = QPushButton("Open")
//  btnOpen.clicked.connect(() => btnOpenClicked())
//  QVBoxLayout(this)
//  layout.addWidget(txt)
//  layout.addWidget(btn)
//  layout.addWidget(btnOpen)
//
//  resize(250, 250)
//  move(300, 300)

  private def setupUi(): Unit = {
    val toolbar = addToolBar("Main")
    val openAct = QAction(
      style.standardIcon(QStyle.StandardPixmap.SP_FileDialogNewFolder), "Open/append file list", this,
    )
    openAct.triggered.connect(() => loadHdfsFile())
    toolbar.addAction(openAct)
    val actClose = QAction(
      style.standardIcon(QStyle.StandardPixmap.SP_LineEditClearButton), "Clear file tree", this,
    )
    actClose.triggered.connect(() => clearTree())
    toolbar.addAction(actClose)

    val central = QWidget()
    setCentralWidget(central)
    val layout = QVBoxLayout(central)

    searchBar.setPlaceholderText("Filter")
    searchBar.textChanged.connect(() => filterTimer.start(300))
    layout.addWidget(searchBar)

    treeView.setSortingEnabled(true)
    treeView.setUniformRowHeights(true)
    treeView.header.setSortIndicator(0, Qt.SortOrder.AscendingOrder)
    treeView.expanded.connect(onNodeExpanded)
    treeView.header.sectionClicked.connect(() => onSectionClicked())
    layout.addWidget(treeView)
  }

  private def parseFilelistFile(filepath: String): Seq[FSItem] = {
    val pattern = (
      "^(?<perms>[drwx-]+)\\s+" +
      "[\\d-]+\\s+" + // replicas
      "(?<owner>\\S+)\\s+(?<group>\\S+)\\s+" +
      "(?<size>\\d+)\\s+" +
      "(?<dtMod>\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2})\\s+" +
      "(?<path>.+)$"
    ).r
    val src = Source.fromFile(filepath)
    try {
      src.getLines.flatMap {
        case pattern(perms, owner, group, size, dtMod, path) => Option(FSItem(
          perms, owner, group, size.toLong, dtMod, Path.of(path),
        ))
        case _ => None
      }.toSeq
    } finally
      src.close
  }

  /**
   * Load a new file list from file.
   * @param filepath path to file-list file to load
   */
  private def loadHdfsFile(filepath: Option[String] = None): Unit =
    Option(QFileDialog.getOpenFileName(
      this, "Open HDFS Log", "", "Text (*.txt)"
    )).map(_.result).foreach { filepath =>
      val root = model.fold(FSNode(Path.of("")))(_.rootNode)
      val nodeMap = mutable.Map(Path.of("/") -> root)

      if (model.isEmpty) {
        model = Option(FSModel(root))
        treeView.setModel(model.get)
      }
      val _model = model.get

      for (i <- parseFilelistFile(filepath)) {
        val path = i.path
        var cur = root
        var curChildren = childrenIndices(INVALID_INDEX)
        for (parent <- path.parents) {
          if (!nodeMap.contains(parent)) {
            val foundOpt = curChildren.find(i => _model.fsNodeIndex(i.internalId).path == parent)
            nodeMap(parent) = foundOpt match {
              case Some(found) =>
                curChildren = childrenIndices(found)
                _model.fsNodeIndex(found.internalId)
              case None =>
                val node = FSNode(parent, parent=Option(cur))
                _model.fsNodeIndex(node.id) = node
                cur.appendChild(node)
                node
            }
          }
          cur = nodeMap(parent)
        }
        val node = FSNode(path, i.size, i.dtMod, i.perms, s"${i.owner}:${i.group}", parent=Option(cur))
        _model.fsNodeIndex(node.id) = node
        nodeMap(path) = node
        cur.appendChild(node)
      }
      println("NODES CREATED")

      // is it the signal required after adding data?
      _model.layoutChanged.emit()
      treeView.setColumnWidth(0, 350)

      println("EXPAND")
      var current = INVALID_INDEX
      var children = childrenIndices(current).toList
      while (children.nonEmpty) {
        treeView.setExpanded(current, true)
        if (children.size != 1)
          break()
        current = children.head
        children = childrenIndices(current).toList
      }

      println("SORT")
      // TODO:

      println("LOADED")
    }

  private def clearTree(): Unit= {
    treeView.setModel(null)
    model = None
  }

  private def childrenIndices(index: QModelIndex): Iterator[QModelIndex] =
    model.fold(Iterator.empty) (m =>
      Iterator.range(0, m.rowCount(index)).map(i => m.index(i, 0, index))
    )

  private def onNodeExpanded(index: QModelIndex): Unit = {

  }

  private def onSectionClicked(): Unit = {

  }

//  private def btnClicked(): Unit = {
//    println(s"CLICK: ${txt.getPlainText}")
//    QMessageBox.information(this, "QtJambi", "Hello World!")
//  }
//
//  private def btnOpenClicked(): Unit =
//    Option(QFileDialog.getOpenFileName(
//      this, "Open HDFS Log", "", "Text (*.txt)"
//    )).map(_.result) match
//      case Some(filepath) =>
//        println(filepath)
//      case None =>
//        println("CANCELED")
}

@main
def main(args: String*): Unit = {
  // https://www.qtjambi.io/doc/md.jsp?md=Options
  System.setProperty("io.qt.library-path-override", "qt/bin") // -Djava.library.path
  System.setProperty("io.qt.pluginpath", "qt/bin/plugins") // QT_PLUGIN_PATH

  QApplication.initialize(args.toArray)
  HdfsExplorer().show()
  QApplication.exec
}

