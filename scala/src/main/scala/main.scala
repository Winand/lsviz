import io.qt.widgets._

class MyMainWindow extends QWidget {
  val txt = QTextEdit()

  setWindowTitle("Scala Jambi Test")
  val btn = QPushButton("Hello")
  btn.clicked.connect(() => btnClicked())
  val btnOpen = QPushButton("Open")
  btnOpen.clicked.connect(() => btnOpenClicked())
  QVBoxLayout(this)
  layout.addWidget(txt)
  layout.addWidget(btn)
  layout.addWidget(btnOpen)

  resize(250, 250)
  move(300, 300)


  private def btnClicked(): Unit = {
    println(s"CLICK: ${txt.getPlainText}")
    QMessageBox.information(this, "QtJambi", "Hello World!")
  }

  private def btnOpenClicked(): Unit =
    Option(QFileDialog.getOpenFileName(
      this, "Open HDFS Log", "", "Text (*.txt)"
    )).map(_.result) match
      case Some(filepath) =>
        println(filepath)
      case None =>
        println("CANCELED")
}

@main
def main(args: String*): Unit = {
  // https://www.qtjambi.io/doc/md.jsp?md=Options
  System.setProperty("io.qt.library-path-override", "qt/bin") // -Djava.library.path
  System.setProperty("io.qt.pluginpath", "qt/bin/plugins") // QT_PLUGIN_PATH

  QApplication.initialize(args.toArray)
  MyMainWindow().show()
  QApplication.exec
}

