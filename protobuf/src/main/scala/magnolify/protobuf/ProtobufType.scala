/*
 * Copyright 2020 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package magnolify.protobuf

import java.lang.reflect.Method
import java.{util => ju}

import com.google.protobuf.Descriptors.FileDescriptor.Syntax
import com.google.protobuf.Descriptors.{Descriptor, FieldDescriptor}
import com.google.protobuf.{ByteString, Message}
import magnolia._
import magnolify.shared.Converter
import magnolify.shims.FactoryCompat
import magnolify.shims.JavaConverters._

import scala.annotation.{implicitNotFound, StaticAnnotation}
import scala.collection.concurrent
import scala.language.experimental.macros
import scala.reflect.ClassTag

class noneValue(value: Any) extends StaticAnnotation with Serializable {
  def get: Any = value
  override def toString: String = s"noneValue($value)"
}

sealed trait ProtobufType[T, MsgT <: Message] extends Converter[T, MsgT, MsgT] {
  def apply(r: MsgT): T = from(r)
  def apply(t: T): MsgT = to(t)
}

object ProtobufType {
  implicit def apply[T, MsgT <: Message](
    implicit f: ProtobufField.Record[T],
    ct: ClassTag[MsgT]
  ): ProtobufType[T, MsgT] =
    new ProtobufType[T, MsgT] {
      if (f.hasNoneValue) {
        val syntax = ct.runtimeClass
          .getMethod("getDescriptor")
          .invoke(null)
          .asInstanceOf[Descriptor]
          .getFile
          .getSyntax
        require(syntax == Syntax.PROTO3, "@noneValue annotation supports PROTO3 only")
      }

      @transient private var _newBuilder: Method = _
      private def newBuilder: Message.Builder = {
        if (_newBuilder == null) {
          _newBuilder = ct.runtimeClass.getMethod("newBuilder")
        }
        _newBuilder.invoke(null).asInstanceOf[Message.Builder]
      }

      override def from(v: MsgT): T = f.from(v)
      override def to(v: T): MsgT = f.to(v, newBuilder).asInstanceOf[MsgT]
    }
}

sealed trait ProtobufField[T] extends Serializable {
  type FromT
  type ToT

  def from(v: FromT): T
  def to(v: T, b: Message.Builder): ToT

  def fromAny(v: Any): T = from(v.asInstanceOf[FromT])
}

object ProtobufField {
  trait Aux[T, From, To] extends ProtobufField[T] {
    override type FromT = From
    override type ToT = To
  }

  trait Record[T] extends Aux[T, Message, Message] {
    def hasNoneValue: Boolean
  }

  //////////////////////////////////////////////////

  type Typeclass[T] = ProtobufField[T]

  def combine[T](caseClass: CaseClass[Typeclass, T]): Record[T] = new Record[T] {
    override def hasNoneValue: Boolean = noneValues.nonEmpty

    // One Record[T] instance may be used for multiple Message types
    private val fieldsCache: concurrent.Map[String, Array[FieldDescriptor]] =
      concurrent.TrieMap.empty
    private def getFields(descriptor: Descriptor): Array[FieldDescriptor] =
      fieldsCache.getOrElseUpdate(
        descriptor.getFullName, {
          val fields = new Array[FieldDescriptor](caseClass.parameters.size)
          caseClass.parameters.foreach(p => fields(p.index) = descriptor.findFieldByName(p.label))
          fields
        }
      )

    override def from(v: Message): T = {
      val descriptor = v.getDescriptorForType
      val syntax = descriptor.getFile.getSyntax
      val fields = getFields(descriptor)

      caseClass.construct { p =>
        val field = fields(p.index)
        val nv = noneValues.get(p.index)

        if (syntax == Syntax.PROTO2) {
          // PROTO2 supports `optional` and `hasField` behaves properly
          val value = if (field.isOptional && !v.hasField(field)) {
            null
          } else {
            v.getField(field)
          }
          p.typeclass.fromAny(value)
        } else {
          val value = p.typeclass.fromAny(v.getField(field))
          if (nv.isDefined) {
            // field is annotated with `@noneValue(x: T)` and `nv` is `Some(x: T)`
            // `p.typeclass` is `OptionProtobufField` and `p.PType` is `Option[T]`
            if (value == nv.asInstanceOf[p.PType]) None else value
          } else {
            value
          }
        }
      }
    }

    override def to(v: T, bu: Message.Builder): Message = {
      val fields = getFields(bu.getDescriptorForType)

      caseClass.parameters
        .foldLeft(bu) { (b, p) =>
          val field = fields(p.index)
          val value = if (field.getType == FieldDescriptor.Type.MESSAGE) {
            // nested records
            p.typeclass.to(p.dereference(v), b.newBuilderForField(field))
          } else {
            // non-nested
            p.typeclass.to(p.dereference(v), null)
          }
          if (value == null) b else b.setField(field, value)
        }
        .build()
    }

    private val noneValues: Map[Int, Any] = {
      caseClass.parameters.iterator.flatMap { p =>
        val nv = getNoneValue(p)
        if (nv.isDefined) {
          require(
            p.typeclass.isInstanceOf[OptionProtobufField[_]],
            s"@noneValue annotation supports Option[T] type only: ${caseClass.typeName.full}#${p.label}"
          )
          p.typeclass
            .asInstanceOf[OptionProtobufField[_]]
            .checkNoneValue(nv.get, s"${caseClass.typeName.full}#${p.label}")
        }
        nv.map((p.index, _))
      }.toMap
    }

    private def getNoneValue(p: Param[Typeclass, T]): Option[Any] = {
      val nvs = p.annotations.collect { case nv: noneValue => nv.get }
      require(
        nvs.size <= 1,
        s"More than one @noneValue annotation: ${caseClass.typeName.full}#${p.label}"
      )
      nvs.headOption
    }
  }

  @implicitNotFound("Cannot derive ProtobufField for sealed trait")
  private sealed trait Dispatchable[T]
  def dispatch[T: Dispatchable](sealedTrait: SealedTrait[Typeclass, T]): Record[T] = ???

  implicit def gen[T]: Record[T] = macro Magnolia.gen[T]

  //////////////////////////////////////////////////

  def apply[T](implicit f: ProtobufField[T]): ProtobufField[T] = f

  def from[T]: FromWord[T] = new FromWord[T]

  class FromWord[T] {
    def apply[U](f: T => U)(g: U => T)(implicit pf: ProtobufField[T]): ProtobufField[U] =
      new ProtobufField[U] {
        override type FromT = pf.FromT
        override type ToT = pf.ToT
        override def from(v: FromT): U = f(pf.from(v))
        override def to(v: U, b: Message.Builder): ToT = pf.to(g(v), null)
      }
  }

  private def aux[T, From, To](f: From => T)(g: T => To): ProtobufField[T] =
    new ProtobufField[T] {
      override type FromT = From
      override type ToT = To
      override def from(v: FromT): T = f(v)
      override def to(v: T, b: Message.Builder): ToT = g(v)
    }

  private def aux2[T, Repr](f: Repr => T)(g: T => Repr): ProtobufField[T] =
    aux[T, Repr, Repr](f)(g)

  private def id[T]: ProtobufField[T] = aux[T, T, T](identity)(identity)

  implicit val pfBoolean = id[Boolean]
  implicit val pfInt = id[Int]
  implicit val pfLong = id[Long]
  implicit val pfFloat = id[Float]
  implicit val pfDouble = id[Double]
  implicit val pfString = id[String]
  implicit val pfByteString = id[ByteString]
  implicit val pfByteArray = aux2[Array[Byte], ByteString](_.toByteArray)(ByteString.copyFrom)

  private class OptionProtobufField[T: ClassTag](val f: ProtobufField[T])
      extends ProtobufField[Option[T]] {
    override type FromT = f.FromT
    override type ToT = f.ToT
    override def from(v: f.FromT): Option[T] = if (v == null) None else Some(f.from(v))
    override def to(v: Option[T], b: Message.Builder): f.ToT = v match {
      case None    => null.asInstanceOf[f.ToT]
      case Some(x) => f.to(x, b)
    }

    // check whether `v: Any` in `@noneValue(v)` is compatible with field type `Option[T]`
    def checkNoneValue(v: Any, name: String): Unit = {
      val vc = v.getClass
      val cls = implicitly[ClassTag[T]].runtimeClass
      val compatible = cls match {
        // special handling for primitive mismatches, e.g. `scala.Int` vs `java.lang.Integer`
        case c if c == classOf[Boolean] => vc == classOf[java.lang.Boolean]
        case c if c == classOf[Byte]    => vc == classOf[java.lang.Byte]
        case c if c == classOf[Char]    => vc == classOf[java.lang.Character]
        case c if c == classOf[Short]   => vc == classOf[java.lang.Short]
        case c if c == classOf[Int]     => vc == classOf[java.lang.Integer]
        case c if c == classOf[Long]    => vc == classOf[java.lang.Long]
        case c if c == classOf[Float]   => vc == classOf[java.lang.Float]
        case c if c == classOf[Double]  => vc == classOf[java.lang.Double]
        case _                          => vc == cls
      }
      require(
        compatible,
        s"@noneValue annotation with incompatible type: $name ${vc.getCanonicalName} is not ${cls.getCanonicalName}"
      )
    }
  }

  implicit def pfOption[T: ClassTag](implicit f: ProtobufField[T]): ProtobufField[Option[T]] =
    new OptionProtobufField[T](f)

  implicit def pfIterable[T, C[_]](
    implicit f: ProtobufField[T],
    ti: C[T] => Iterable[T],
    fc: FactoryCompat[T, C[T]]
  ): ProtobufField[C[T]] =
    new Aux[C[T], ju.List[f.FromT], ju.List[f.ToT]] {
      override def from(v: ju.List[f.FromT]): C[T] =
        if (v == null) {
          fc.newBuilder.result()
        } else {
          fc.build(v.asScala.iterator.map(f.from))
        }
      override def to(v: C[T], b: Message.Builder): ju.List[f.ToT] =
        if (v.isEmpty) null else v.iterator.map(f.to(_, b)).toList.asJava
    }
}
