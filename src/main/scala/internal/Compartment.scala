package internal


import internal.UnionTypes.RoleUnionTypes

import scala.annotation.tailrec
import scala.language.implicitConversions

import java.lang
import java.lang.reflect.Method
import reflect.runtime.universe._
import scala.collection.immutable.Queue
import annotations.Role
import graph.ScalaRoleGraph

trait Compartment extends QueryStrategies with RoleUnionTypes {

  val plays = new ScalaRoleGraph()

  private def isRole(value: Any): Boolean = {
    require(null != value)
    value.getClass.isAnnotationPresent(classOf[Role])
  }

  // declaring a is-part-of relation between compartments
  def partOf(other: Compartment) {
    require(null != other)
    plays.store ++= other.plays.store
  }

  // declaring a bidirectional is-part-of relation between compartment
  def union(other: Compartment): Compartment = {
    other.partOf(this)
    this.partOf(other)
    this
  }

  // removing is-part-of relation between compartments
  def notPartOf(other: Compartment) {
    require(null != other)
    other.plays.store.edges.toSeq.foreach(e => {
      plays.store -= e.value
    })
  }

  def all[T: WeakTypeTag](matcher: RoleQueryStrategy = *()): Seq[T] = {
    plays.store.nodes.toSeq.filter(_.value.is[T])
      .map(_.value.asInstanceOf[T]).filter(a => matcher.matches(getCoreFor(a)))
  }

  def all[T: WeakTypeTag](matcher: () => Boolean): Seq[T] = {
    plays.store.nodes.toSeq.filter(_.value.is[T])
      .map(_.value.asInstanceOf[T]).filter(_ => matcher())
  }

  private def safeReturn[T](seq: Seq[T], typeName: String): Seq[T] = seq.isEmpty match {
    case true => throw new RuntimeException(s"No player with type '$typeName' found!")
    case false => seq
  }

  def one[T: WeakTypeTag](matcher: RoleQueryStrategy = *()): T = safeReturn(all[T](matcher), weakTypeOf[T].toString).head

  def one[T: WeakTypeTag](matcher: () => Boolean): T = safeReturn(all[T](matcher), weakTypeOf[T].toString).head

  def addPlaysRelation(core: Any, role: Any) {
    require(isRole(role), "Argument for adding a role must be a role (you maybe want to add the @Role annotation).")
    plays.addBinding(core, role)
  }

  def removePlaysRelation(core: Any, role: Any) {
    require(isRole(role), "Argument for removing a role must be a role (you maybe want to add the @Role annotation).")
    plays.removeBinding(core, role)
  }

  def transferRole(coreFrom: Any, coreTo: Any, role: Any) {
    require(null != coreFrom)
    require(null != coreTo)
    require(coreFrom != coreTo, "You can not transfer a role from itself.")
    require(isRole(role), "Argument for transferring a role must be a role (you maybe want to add the @Role annotation).")

    removePlaysRelation(coreFrom, role)
    addPlaysRelation(coreTo, role)
  }

  def transferRoles(coreFrom: Any, coreTo: Any, roles: Set[Any]) {
    require(null != roles)
    roles.foreach(transferRole(coreFrom, coreTo, _))
  }

  @tailrec
  private def getCoreFor(role: Any): Any = {
    require(null != role)
    role match {
      case cur: Player[_] => getCoreFor(cur.wrapped)
      case cur: Any => plays.store.get(cur).diPredecessors.toList match {
        case p :: Nil => getCoreFor(p.value)
        case Nil => cur
        case _ =>
      }
    }
  }

  trait DynamicType extends Dynamic {
    /**
     * Allows to call a function with arguments.  
     *
     * @param name the function name
     * @param args the arguments handed over to the given function
     * @param dispatchQuery the dispatch rules that should be applied
     * @tparam E return type
     * @tparam A argument type
     * @return the result of the function call
     */
    def applyDynamic[E, A](name: String)(args: A*)(implicit dispatchQuery: DispatchQuery = DispatchQuery.empty): E

    /**
     * Allows to call a function with named arguments.
     *
     * @param name the function name
     * @param args tuple with the the name and argument handed over to the given function
     * @param dispatchQuery the dispatch rules that should be applied
     * @tparam E return type
     * @return the result of the function call
     */
    def applyDynamicNamed[E](name: String)(args: (String, Any)*)(implicit dispatchQuery: DispatchQuery = DispatchQuery.empty): E

    /**
     * Allows to write field accessors.
     *
     * @param name of the field
     * @param dispatchQuery the dispatch rules that should be applied
     * @tparam E return type
     * @return the result of the field access
     */
    def selectDynamic[E](name: String)(implicit dispatchQuery: DispatchQuery = DispatchQuery.empty): E

    /**
     * Allows to write field updates.
     *
     * @param name of the field
     * @param value the new value to write
     * @param dispatchQuery the dispatch rules that should be applied
     */
    def updateDynamic(name: String)(value: Any)(implicit dispatchQuery: DispatchQuery = DispatchQuery.empty)
  }

  trait DispatchType {
    // for single method dispatch
    def dispatch[E](on: Any, m: Method): E = {
      require(null != on)
      require(null != m)
      m.invoke(on, Array.empty[Object]: _*).asInstanceOf[E]
    }

    // for multi-method / multi-argument dispatch
    def dispatch[E, A](on: Any, m: Method, args: Seq[A]): E = {
      require(null != on)
      require(null != m)
      require(null != args)
      val argTypes: Array[Class[_]] = m.getParameterTypes
      val actualArgs: Seq[Any] = args.zip(argTypes).map {
        case (arg: Player[_], tpe: Class[_]) =>
          plays.getRoles(arg.wrapped).find(_.getClass == tpe) match {
            case Some(curRole) => curRole
            case None => throw new RuntimeException(s"No role for type '$tpe' found.")
          }
        case (arg: Int, tpe: Class[_]) => new lang.Integer(arg.toInt)
        case (arg: Double, tpe: Class[_]) => new lang.Double(arg.toDouble)
        case (arg: Float, tpe: Class[_]) => new lang.Float(arg.toFloat)
        case (arg: Long, tpe: Class[_]) => new lang.Long(arg.toLong)
        case (arg: Short, tpe: Class[_]) => new lang.Short(arg.toShort)
        case (arg: Byte, tpe: Class[_]) => new lang.Byte(arg.toByte)
        case (arg: Char, tpe: Class[_]) => new lang.Character(arg.toChar)
        case (arg: Boolean, tpe: Class[_]) => new lang.Boolean(arg)
        // Fallback:
        case (arg@unchecked, tpe: Class[_]) => tpe.cast(arg)
      }

      m.invoke(on, actualArgs.map {
        _.asInstanceOf[Object]
      }: _*).asInstanceOf[E]
    }

  }

  /**
   * Implicit wrapper class to add basic functionality to roles and its players as unified types.
   *
   * @param wrapped the player or role that is wrapped into this dynamic type
   * @tparam T type of wrapped
   */
  implicit class Player[T](val wrapped: T) extends DynamicType with DispatchType {
    /**
     * Applies lifting to Player
     *
     * @return an lifted Player instance with the calling object as wrapped.
     */
    def unary_+ : Player[T] = this

    /**
     * @return the player of this Player instance if this is a role, or this itself. Alias for [[Compartment.getCoreFor]].
     */
    def player: Any = getCoreFor(this)

    def play(role: Any): Player[T] = {
      wrapped match {
        case p: Player[_] => addPlaysRelation(p.wrapped, role)
        case p: Any => addPlaysRelation(p, role)
      }
      this
    }

    def drop(role: Any): Player[T] = {
      removePlaysRelation(wrapped, role)
      this
    }

    def transfer(role: Any) = new {
      def to(player: Any) {
        transferRole(this, player, role)
      }
    }

    def isPlaying[E: WeakTypeTag]: Boolean = plays.getRoles(wrapped).exists(r => r.getClass.getSimpleName == ReflectiveHelper.typeSimpleClassName(weakTypeOf[E]))

    override def applyDynamic[E, A](name: String)(args: A*)(implicit dispatchQuery: DispatchQuery = DispatchQuery.empty): E = {
      val core = getCoreFor(wrapped)
      val anys = dispatchQuery.reorder(Queue() ++ plays.getRoles(core) :+ wrapped :+ core)
      anys.foreach(r => {
        r.getClass.getDeclaredMethods.find(m => m.getName == name).foreach(fm => {
          args match {
            case Nil => return dispatch(r, fm)
            case _ => return dispatch(r, fm, args.toSeq)
          }
        })
      })
      // otherwise give up
      throw new RuntimeException(s"No role with method '$name' found! (core: '$wrapped')")
    }

    override def applyDynamicNamed[E](name: String)(args: (String, Any)*)(implicit dispatchQuery: DispatchQuery = DispatchQuery.empty): E =
      applyDynamic(name)(args.map(_._2): _*)(dispatchQuery)

    override def selectDynamic[E](name: String)(implicit dispatchQuery: DispatchQuery = DispatchQuery.empty): E = {
      val core = getCoreFor(wrapped)
      val anys = dispatchQuery.reorder(Queue() ++ plays.getRoles(core) :+ wrapped :+ core)
      anys.foreach(r => if (r.hasAttribute(name)) return r.propertyOf[E](name))

      // otherwise give up
      throw new RuntimeException(s"No role with value '$name' found! (core: '$wrapped')")
    }

    override def updateDynamic(name: String)(value: Any)(implicit dispatchQuery: DispatchQuery = DispatchQuery.empty) {
      val core = getCoreFor(wrapped)
      val anys = dispatchQuery.reorder(Queue() ++ plays.getRoles(core) :+ wrapped :+ core)
      anys.foreach(r => if (r.hasAttribute(name)) {
        r.setPropertyOf(name, value)
        return
      })
      // otherwise give up
      throw new RuntimeException(s"No role with value '$name' found! (core: '$wrapped')")
    }

    override def equals(o: Any) = o match {
      case other: Player[_] => getCoreFor(this.wrapped) == getCoreFor(other.wrapped)
      case other: Any => getCoreFor(this.wrapped) == other
    }

    override def hashCode(): Int = wrapped.hashCode()
  }

}
