package svm

import collection.mutable
import model._
import model.Attached.LineNumber



class VirtualMachine(classLoader: String => Array[Byte]){

  implicit object classTable extends (String => Class){
    private[this] val classes = mutable.Map.empty[String, Class]
    def apply(name: String): Class = {
      require(!name.contains("."))

      classes.get(name) match{
        case Some(cls) => cls
        case None =>
          classes(name) = new Class(ClassData.parse(classLoader(name)))
          classes(name).method("<clinit>", "()V").foreach( m =>
            threads(0).invoke(classes(name), m, Nil)
          )
          classes(name)
      }
    }
  }

  val threads = List(new VmThread())

  def invoke(bootClass: String, mainMethod: String, args: Seq[Any]) = {

    Object.fromVirtual[Any](
      threads(0).invoke(
        bootClass,
        bootClass
          .classFile
          .methods
          .find(x => x.name == mainMethod)
          .getOrElse(throw new IllegalArgumentException("Can't find method: " + mainMethod)),
        args.map(Object.toVirtual[Any])
      )
    )
  }
}

class VmThread(val threadStack: mutable.Stack[Frame] = mutable.Stack())(implicit val classes: String => svm.Class){

  val nativeX = Natives.nativeX(getStackTrace _)
  def getStackTrace =
    threadStack.map { f =>
      new StackTraceElement(
        f.runningClass.name,
        f.method.name,
        f.runningClass.classFile.misc.sourceFile.getOrElse("[no source]"),
        f.method.code.attachments.flatten.reverse.collectFirst{
          case LineNumber(line, startPc) if startPc < f.pc => line
        }.getOrElse(-1)
      )
    }.toList
  def indent = "\t" * threadStack.filter(_.method.name != "Dummy").length
  def step() = {
    val topFrame = threadStack.head

    val node = topFrame.method.code.instructions(topFrame.pc)

    //println(indent + topFrame.pc + "\t---------------------- " + node )
    topFrame.pc += 1
    node.op(Context(this))

    //println(indent + topFrame.method.name + ": " + topFrame.stack)
  }
  def returnVal(x: Option[Any]) = {
    //println(indent + "Returning!")
    threadStack.pop()
    x.foreach(value => threadStack.head.stack = value :: threadStack.head.stack)
  }
  def throwException(ex: svm.Object): Unit = {
    val frame = threadStack.head

    val handler =
      frame.method.misc.tryCatchBlocks
           .filter(x => x.start <= frame.pc && x.end >= frame.pc)
           .filter(x => !x.blockType.isDefined || ex.cls.isInstanceOf(x.blockType.get))
           .headOption


    handler match{
      case None =>
        threadStack.pop()
        throwException(ex)
      case Some(TryCatchBlock(start, end, handler, blockType)) =>
        frame.pc = handler
        frame.stack ::= ex
    }
  }
  def prepInvoke(cls: Class, method: Method, args: Seq[Any]) = {
    //println("prepInvoke " + cls.name + " " + method.name)
    //println(method.code)
    method match{
      case m if m.code != Code.Empty =>
        val stretchedArgs = args.flatMap {
          case l: Long => Seq(l, l)
          case d: Double => Seq(d, d)
          case x => Seq(x)
        }
        val startFrame = new Frame(
          runningClass = cls,
          method = method,
          locals = mutable.Seq.tabulate(method.misc.maxLocals)(stretchedArgs.orElse{case x => null}),
          stack = Nil
        )

        threadStack.push(startFrame)
        println(indent + "Invoking " + method.name)
        println(indent + "Locals " + startFrame.locals)
      //method.code.instructions.zipWithIndex.foreach{case (x, i) => println(indent + i + "\t" + x) }
      case m if (m.access | Access.Native) != 0 =>
        val topFrame = threadStack.head
        //println(indent + "Native Method Call!")
        //println(indent + args)

        val result = nativeX.lookup(cls.name + "/" + method.name + method.desc) match{
          case None => throw new Exception("Can't find Native Method: " + cls.name + " " + method.name + " " + method.desc)
          case Some(n) => n.apply(args)
        }

        topFrame.stack = result match{
          case () => topFrame.stack
          case nonUnit => nonUnit :: topFrame.stack
        }
      case _ =>
        println(indent + "Empty Method!")
    }

  }
  def invoke(cls: Class, method: Method, args: Seq[Any]) = {
    val dummyFrame = new Frame(
      runningClass = cls,
      method = method.copy(name = "Dummy"),
      locals = mutable.Seq.empty,
      stack = Nil
    )

    threadStack.push(dummyFrame)
    prepInvoke(cls, method, args)

    while(threadStack.head != dummyFrame) step()

    threadStack.pop().stack.headOption.getOrElse(())
  }
}

class Frame(
  var pc: Int = 0,
  val runningClass: Class,
  val method: Method,
  val locals: mutable.Seq[Any] = mutable.Seq.empty,
  var stack: List[Any] = Nil){

}


