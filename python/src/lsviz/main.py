import re
import sys
from collections.abc import Generator
from dataclasses import dataclass
from enum import IntEnum
from pathlib import Path
from typing import Iterable, NamedTuple, TypedDict, cast, override

from PySide6.QtCore import (
    QAbstractItemModel,
    QFileInfo,
    QModelIndex,
    QPersistentModelIndex,
    Qt,
    QTimer,
)
from PySide6.QtGui import QAction, QIcon
from PySide6.QtWidgets import (
    QApplication,
    QFileDialog,
    QFileIconProvider,
    QLineEdit,
    QMainWindow,
    QTreeView,
    QVBoxLayout,
    QWidget,
)

InvalidIndex = QModelIndex()


class ExpandState:
    Collapsed = False
    Expanded = True


class Column(IntEnum):
    Name = 0
    Size = 1
    Modified = 2
    Permissions = 3
    Owner = 4


@dataclass
class Sorting:
    column: int
    order: Qt.SortOrder


class FSItem(TypedDict):
    perms: str
    replicas: str
    owner: str
    group: str
    size: str
    dt_mod: str
    path: str


class FSNode:
    def __init__(
        self,
        path: Path,
        size: int = 0,
        modified: str = "",
        permissions: str = "",
        owner: str = "",
        parent: "FSNode | None" = None,
    ) -> None:
        self.path = path
        self.name = path.name
        self.name_lower = self.name.lower()
        self.is_dir = not permissions or permissions.startswith("d")
        self.parent = parent

        # Shadow lists for performance
        self.all_children: list[FSNode] = []
        self.visible_children: list[FSNode] = []
        self.BATCH = 10000
        self.fetched_items = self.BATCH

        # Metadata
        self.size = size
        self.modified = modified
        self.permissions = permissions
        self.owner = owner

        self.current_sort_state: Sorting | None = None

    def append_child(self, child: "FSNode") -> None:
        self.all_children.append(child)
        self.visible_children.append(child)
        self.current_sort_state = None  # reset sorting

    def apply_filter(self, filter_text: str) -> None:
        """Recursively filters children based on name match."""
        if not filter_text:
            self.visible_children = self.all_children[:]
        else:
            # List comprehension is significantly faster for 1M rows
            self.visible_children = [
                child
                for child in self.all_children
                if filter_text in child.name_lower or child.is_dir
            ]
            # Optional: Remove empty folders if you want a strict search
            # This implementation keeps folders visible so you can explore paths

        for child in self.all_children:
            if child.is_dir:
                child.apply_filter(filter_text)

    def sort(self, sorting: Sorting) -> None:
        """Use Python's Timsort (C-speed) for high-performance sorting."""
        if self.current_sort_state == sorting:
            print(self.parent.name if self.parent else "", "/", self.name, "sorted")
            return  # Already sorted this way

        reverse = sorting.order == Qt.SortOrder.DescendingOrder

        def sort_key(node: FSNode) -> tuple[bool, str | int]:
            # Rule 1 & 2: Folders always first
            dir_first = node.is_dir == reverse
            if sorting.column == Column.Name:
                return (dir_first, node.name_lower)
            if sorting.column == Column.Size:
                return (dir_first, node.name_lower if node.is_dir else node.size)
            if sorting.column == Column.Modified:
                return (False, node.modified)
            if sorting.column == Column.Permissions:
                return (False, node.permissions)
            if sorting.column == Column.Owner:
                return (False, node.owner)
            msg = f"Unknown column index {sorting.column}"
            raise ValueError(msg)

        self.visible_children.sort(key=sort_key, reverse=reverse)
        self.current_sort_state = sorting


class FSModel(QAbstractItemModel):
    def __init__(self, root_node: FSNode):
        super().__init__()
        self.root_node = root_node
        self.headers = ["Name", "Size", "Modified", "Permissions", "Owner"]

        # Cache icons to avoid OS calls during paint
        self.icon_provider = QFileIconProvider()
        self.dir_icon = self.icon_provider.icon(QFileIconProvider.IconType.Folder)
        self.file_icon = self.icon_provider.icon(QFileIconProvider.IconType.File)
        self.ext_icons: dict[str, QIcon] = {}

    def index(
        self,
        row: int,
        column: int,
        parent: QModelIndex | QPersistentModelIndex = InvalidIndex,
    ):
        # if not self.hasIndex(row, column, parent):
        #     return QModelIndex()
        p_item = parent.internalPointer() if parent.isValid() else self.root_node
        return self.createIndex(row, column, p_item.visible_children[row])

    @override
    def parent(self, index: QModelIndex) -> QModelIndex:  # pyright: ignore[reportIncompatibleMethodOverride]
        if not index.isValid():
            return QModelIndex()
        child_item = index.internalPointer()
        p_item = child_item.parent
        if p_item == self.root_node or p_item is None:
            return QModelIndex()
        # Find the row of the parent in its own parent's visible list
        grandparent = p_item.parent if p_item.parent else self.root_node
        return self.createIndex(grandparent.visible_children.index(p_item), 0, p_item)

    def rowCount(self, parent: QModelIndex | QPersistentModelIndex = QModelIndex()):
        p_item = parent.internalPointer() if parent.isValid() else self.root_node
        return min(len(p_item.visible_children), p_item.fetched_items)

    def canFetchMore(self, parent: QModelIndex | QPersistentModelIndex) -> bool:
        if not parent.isValid():
            return False
        p_item = parent.internalPointer()
        return p_item.fetched_items < len(p_item.visible_children)

    def fetchMore(self, parent: QModelIndex | QPersistentModelIndex) -> None:
        p_item = parent.internalPointer() if parent.isValid() else self.root_node
        prev_fetched_items = p_item.fetched_items
        p_item.fetched_items = min(
            p_item.fetched_items + p_item.BATCH,
            len(p_item.visible_children),
        )
        self.beginInsertRows(parent, prev_fetched_items, p_item.fetched_items - 1)
        self.endInsertRows()
        print(p_item.path, p_item.fetched_items)

    def columnCount(self, parent: QModelIndex | QPersistentModelIndex = QModelIndex()):
        return len(self.headers)

    @override
    def data(
        self,
        index: QModelIndex | QPersistentModelIndex,
        role: int = Qt.ItemDataRole.DisplayRole,
    ) -> str | QIcon | None:
        if not index.isValid():
            return None
        item: FSNode = index.internalPointer()
        col = index.column()

        if role == Qt.ItemDataRole.DisplayRole:
            if col == Column.Name:
                return item.name
            if col == Column.Size:
                return f"{item.size:,}" if not item.is_dir else ""
            if col == Column.Modified:
                return item.modified
            if col == Column.Permissions:
                return item.permissions
            if col == Column.Owner:
                return item.owner

        if role == Qt.ItemDataRole.DecorationRole and col == 0:
            if item.is_dir:
                return self.dir_icon
            if ext := Path(item.name).suffix:
                if ext in self.ext_icons:
                    return self.ext_icons[ext]
                icon = self.icon_provider.icon(QFileInfo(item.name))
                self.ext_icons[ext] = icon
                return icon
            return self.file_icon

        return None

    @override
    def headerData(
        self,
        section: int,
        orientation: Qt.Orientation,
        role: int = Qt.ItemDataRole.DisplayRole,
    ) -> str | None:
        if (
            orientation == Qt.Orientation.Horizontal
            and role == Qt.ItemDataRole.DisplayRole
        ):
            return self.headers[section]
        return None

    def sort(self, column: int, order: Qt.SortOrder = Qt.SortOrder.AscendingOrder):
        self.sorting = Sorting(column, order)

    def update_filter(self, text):
        self.layoutAboutToBeChanged.emit()
        self.root_node.apply_filter(text.lower())
        self.layoutChanged.emit()


class HdfsExplorer(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("HDFS Explorer")
        self.resize(1000, 700)

        # UI Setup
        self.setup_ui()

        self.model: FSModel | None = None

        # Debounce timer
        self.filter_timer = QTimer()
        self.filter_timer.setSingleShot(True)
        self.filter_timer.timeout.connect(self.run_filter)

    def setup_ui(self):
        toolbar = self.addToolBar("Main")
        open_act = QAction(
            "Open/append file list",
            self,
            icon=self.style().standardIcon(
                self.style().StandardPixmap.SP_FileDialogNewFolder,
            ),
        )
        open_act.triggered.connect(self.load_hdfs_file)
        toolbar.addAction(open_act)
        act_close = QAction(
            "Clear file tree",
            self,
            icon=self.style().standardIcon(
                self.style().StandardPixmap.SP_LineEditClearButton,
            ),
        )
        act_close.triggered.connect(self.clear_tree)
        toolbar.addAction(act_close)

        central = QWidget()
        self.setCentralWidget(central)
        self._layout = QVBoxLayout(central)

        self.search_bar = QLineEdit()
        self.search_bar.setPlaceholderText("Filter")
        self.search_bar.textChanged.connect(lambda: self.filter_timer.start(300))
        self._layout.addWidget(self.search_bar)

        self.tree_view = QTreeView()
        self.tree_view.setSortingEnabled(True)
        self.tree_view.setUniformRowHeights(True)  # Optimization for large lists
        self.tree_view.header().setSortIndicator(0, Qt.SortOrder.AscendingOrder)
        self.tree_view.expanded.connect(self.on_node_expanded)
        self.tree_view.header().sectionClicked.connect(self.on_section_clicked)
        self._layout.addWidget(self.tree_view)

    def run_filter(self):
        self.model.update_filter(self.search_bar.text())

    def _parse_filelist_file(self, filepath: str) -> Generator[FSItem]:
        pattern = re.compile(
            r"^(?P<perms>[drwx-]+)\s+"
            r"[\d-]+\s+"  # replicas
            r"(?P<owner>\S+)\s+(?P<group>\S+)\s+"
            r"(?P<size>\d+)\s+"
            r"(?P<dt_mod>\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2})\s+"
            r"(?P<path>.+)$",
        )
        with Path(filepath).open() as f:
            for line in f:
                match = pattern.match(line.strip())
                if not match:
                    continue
                yield cast(FSItem, match.groupdict())

    # def _find_fsnode(self, path: Path, indices: Iterable[QModelIndex]) -> FSNode | None:
    #     for i in indices:

    def load_hdfs_file(self, filepath: str | None = None) -> None:
        "Load a new file list from file."
        if not filepath:
            filepath, _ = QFileDialog.getOpenFileName(
                self, "Open HDFS Log", "", "Text (*.txt)"
            )
        if not filepath:
            return

        # Regex parsing
        root = self.model.root_node if self.model else FSNode(Path())
        node_map = {Path("/"): root}

        print("TRAVERSING")
        for idx, i in enumerate(self._parse_filelist_file(filepath)):
            if idx == 300000:
                break
            path = Path(i["path"])
            cur = root
            cur_children = self._children_indices(InvalidIndex)
            for parent in path.parents[::-1]:
                if parent not in node_map:
                    if found := next(
                        (i for i in cur_children if i.internalPointer().path == parent),
                        None,
                    ):
                        print("Found", found.internalPointer().path)
                        node = found.internalPointer()
                        cur_children = self._children_indices(found)
                    else:
                        node = FSNode(parent, parent=cur)
                        cur.append_child(node)
                    node_map[parent] = node
                cur = node_map[parent]

            # if found := next(
            #     (i for i in cur_children if i.internalPointer().path == path),
            #     None,
            # ):
            #     print("Found!!!", found.internalPointer().path)
            node = FSNode(
                path,
                int(i["size"]),
                i["dt_mod"],
                i["perms"],
                f"{i['owner']}:{i['group']}",
                parent=cur,
            )
            node_map[path] = node
            cur.append_child(node)
            # insert(i, node_map, root)
        print("NODES CREATED")

        if not self.model:
            self.model = FSModel(root)
            self.tree_view.setModel(self.model)
        else:  # is it the signal required after adding data?
            self.model.layoutChanged.emit()
        self.tree_view.setColumnWidth(0, 350)

        print("EXPAND")
        current = InvalidIndex
        while children := list(self._children_indices(current)):
            self.tree_view.setExpanded(current, ExpandState.Expanded)
            if len(children) != 1:
                break
            current = children[0]

        print("SORT")
        for child_index in self._children_indices(InvalidIndex):
            if self.tree_view.isExpanded(child_index):
                self.sort_expanded(child_index)
        print("LOADED")

    def clear_tree(self) -> None:
        self.tree_view.setModel(None)
        self.model = None

    def _children_indices(self, index: QModelIndex) -> Generator[QModelIndex]:
        if not self.model:
            return
        for i in range(self.model.rowCount(index)):
            yield self.model.index(i, 0, index)

    def sort_expanded(self, index: QModelIndex = InvalidIndex) -> None:
        assert self.model
        if index.isValid():
            node: FSNode = index.internalPointer()
            if not node.is_dir:
                return
            if self.model.rowCount(index) > 1:
                node.sort(self.model.sorting)
        for child_index in self._children_indices(index):
            if self.tree_view.isExpanded(child_index):
                self.sort_expanded(child_index)

    def on_node_expanded(self, index: QModelIndex) -> None:
        "Sort folder and its descendants when it is expanded."
        # if not index.isValid():
        #     return
        assert self.model
        self.model.layoutAboutToBeChanged.emit()
        self.sort_expanded(index)
        self.model.layoutChanged.emit()

    def on_section_clicked(self, _column: int) -> None:
        assert self.model
        self.model.layoutAboutToBeChanged.emit()
        from time import perf_counter

        a = perf_counter()
        print("start")
        expansion_state = self.save_expansion_state()
        print(perf_counter() - a)
        a = perf_counter()
        self.sort_expanded()
        print(perf_counter() - a)
        a = perf_counter()
        self.restore_expansion_state(expansion_state)
        print(perf_counter() - a)
        self.model.layoutChanged.emit()

    def save_expansion_state(self, index: QModelIndex = InvalidIndex) -> set[FSNode]:
        """Recursively finds all expanded nodes."""
        expanded_nodes: set[FSNode] = set()
        for child_idx in self._children_indices(index):
            if not self.tree_view.isExpanded(child_idx):
                continue
            # We store the memory address of the FSNode object
            expanded_nodes.add(child_idx.internalPointer())
            expanded_nodes.update(self.save_expansion_state(child_idx))
        return expanded_nodes

    def restore_expansion_state(
        self,
        expanded_nodes: set[FSNode],
        index: QModelIndex = InvalidIndex,
    ) -> None:
        """Recursively re-expands nodes that were previously expanded."""
        # self.tree_view.collapseAll()
        # FIXME: why correct expansion state is preserved inside collapsed folders?
        for child_idx in self._children_indices(index):
            node = child_idx.internalPointer()
            if node in expanded_nodes:
                print(
                    "expand",
                    child_idx.internalPointer().name,
                    "and children",
                    [i.name for i in expanded_nodes],
                )
                self.tree_view.setExpanded(child_idx, ExpandState.Expanded)
                expanded_nodes.remove(node)
                if expanded_nodes:
                    self.restore_expansion_state(expanded_nodes, child_idx)
            elif self.tree_view.isExpanded(child_idx):
                self.tree_view.setExpanded(child_idx, ExpandState.Collapsed)
                print(child_idx.internalPointer().path, "collapsed")


def main() -> None:
    app = QApplication(sys.argv)
    window = HdfsExplorer()
    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
